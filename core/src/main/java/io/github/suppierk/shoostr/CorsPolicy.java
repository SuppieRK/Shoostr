package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpCharacters;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Immutable application-wide cross-origin response sharing. Origins use exact HTTP(S) tuple
 * spelling without userinfo, path, query or fragment; supply the browser's serialized ASCII form.
 * No URL repair or allowlist normalization occurs. The special origin {@code null} admits opaque
 * origins; {@code *} admits all origins only without credentials. Header wildcards are unsupported.
 * Requests without Origin and same-origin requests retain normal dispatch. Cross-origin permission
 * failures use 403; malformed CORS input uses 400 through the application's normal error handling.
 *
 * @param origins allowed serialized origins, {@code null}, or noncredentialed {@code *}
 * @param methods allowed cross-origin methods
 * @param headers explicitly allowed preflight request headers
 * @param credentials whether browsers may share credentialed responses
 * @param exposedHeaders additional response headers visible to browser scripts
 * @param maxAgeSeconds browser preflight cache lifetime, subject to browser limits
 */
public record CorsPolicy(
    Set<String> origins,
    Set<HttpMethods> methods,
    Set<HttpHeaders> headers,
    boolean credentials,
    Set<HttpHeaders> exposedHeaders,
    long maxAgeSeconds) {
  private static final Map<String, String> VARIATION =
      Map.of(HttpHeaders.VARY.value(), HttpHeaders.ORIGIN.value());
  private static final Map<String, String> OPTIONS_VARIATION =
      Map.of(
          HttpHeaders.VARY.value(),
          String.join(
              ", ",
              HttpHeaders.ORIGIN.value(),
              HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD.value(),
              HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS.value()));

  /**
   * Copies policy sets and rejects invalid origins, wildcard credentials, header wildcards and
   * negative cache lifetimes before publication.
   *
   * @throws IllegalArgumentException if the policy cannot be represented safely
   * @throws NullPointerException if any set or element is null
   */
  public CorsPolicy {
    origins = Set.copyOf(origins);
    methods = Set.copyOf(methods);
    headers = Set.copyOf(headers);
    exposedHeaders = Set.copyOf(exposedHeaders);
    for (var origin : origins) {
      validateOrigin(origin);
    }
    if (credentials && origins.contains("*")) {
      throw new IllegalArgumentException("Credentialed CORS requires explicit origins");
    }

    if (maxAgeSeconds < 0
        || headers.contains(HttpHeaders.of("*"))
        || exposedHeaders.contains(HttpHeaders.of("*"))) {
      throw new IllegalArgumentException(
          "CORS requires nonnegative max-age and explicit header names");
    }
  }

  /**
   * Allows GET and HEAD without credentials or additional headers, with a five-second preflight
   * cache.
   *
   * @param origins allowed serialized origins
   */
  public CorsPolicy(Set<String> origins) {
    this(origins, Set.of(HttpMethods.GET, HttpMethods.HEAD), Set.of(), false, Set.of(), 5);
  }

  /**
   * Selects immutable response fields before application processing.
   *
   * @param request invocation input
   * @param response output receiving required sharing fields
   * @return whether this is an admitted preflight
   */
  boolean prepare(Request request, Response response) {
    boolean options = HttpMethods.OPTIONS.value().equals(request.method());
    var variation = options ? OPTIONS_VARIATION : VARIATION;
    response.corsHeaders(variation);
    var incomingOrigins = request.headers(HttpHeaders.ORIGIN.value());
    if (incomingOrigins.isEmpty()) {
      return false;
    }

    if (incomingOrigins.size() != 1 || "*".equals(incomingOrigins.getFirst())) {
      throw new BadRequestException();
    }

    var origin = incomingOrigins.getFirst();

    try {
      validateOrigin(origin);
    } catch (IllegalArgumentException failure) {
      throw new BadRequestException(failure);
    }

    var requestedMethods = request.headers(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD.value());
    boolean preflight = options && !requestedMethods.isEmpty();
    if (preflight && requestedMethods.size() != 1) {
      throw new BadRequestException();
    }

    if (!preflight && sameOrigin(origin, request.effectiveUrl())) {
      return false;
    }

    var method = preflight ? requestedMethods.getFirst() : request.method();
    validateToken(method);
    if (!origins.contains(origin) && !origins.contains("*")) {
      throw new ForbiddenException();
    }

    if (!HttpMethods.httpMethod(method).map(methods::contains).orElse(false)) {
      throw new ForbiddenException();
    }

    Map<String, String> fields = new HashMap<>(variation);
    fields.put(
        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN.value(), origins.contains("*") ? "*" : origin);
    if (credentials) {
      fields.put(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS.value(), "true");
    }

    if (preflight) {
      var requestedHeaders = requestedHeaders(request);
      fields.put(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS.value(), method);
      if (!requestedHeaders.isEmpty()) {
        fields.put(
            HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS.value(), String.join(", ", requestedHeaders));
      }

      fields.put(HttpHeaders.ACCESS_CONTROL_MAX_AGE.value(), Long.toString(maxAgeSeconds));
    } else if (!exposedHeaders.isEmpty()) {
      fields.put(
          HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS.value(),
          String.join(", ", exposedHeaders.stream().map(HttpHeaders::value).sorted().toList()));
    }

    response.corsHeaders(Map.copyOf(fields));
    return preflight;
  }

  /**
   * Compares scheme, host and effective port using only already-established proxy trust.
   *
   * @param origin validated incoming origin
   * @param url effective request URL, possibly with an unparseable raw path or query
   * @return whether this is ordinary same-origin traffic
   */
  static boolean sameOrigin(String origin, String url) {
    if ("null".equals(origin)) {
      return false;
    }

    var source = URI.create(origin);
    var target = effectiveOrigin(url);
    if (target == null) {
      return false;
    }

    return source.getScheme().equalsIgnoreCase(target.getScheme())
        && source.getHost().equalsIgnoreCase(target.getHost())
        && effectivePort(source) == effectivePort(target);
  }

  /**
   * Parses only the effective scheme and authority, leaving preserved raw path/query untouched.
   *
   * @param url effective URL from the request or trusted proxy
   * @return origin tuple, or null if the URL has no absolute authority
   */
  static @Nullable URI effectiveOrigin(String url) {
    int schemeEnd = url.indexOf(':');
    if (schemeEnd < 0
        || url.length() < schemeEnd + 3
        || url.charAt(schemeEnd + 1) != HttpCharacters.PATH_SEPARATOR
        || url.charAt(schemeEnd + 2) != HttpCharacters.PATH_SEPARATOR) {
      return null;
    }

    int end = url.length();
    for (int index = schemeEnd + 3; index < url.length(); index++) {
      char value = url.charAt(index);
      if (value == HttpCharacters.PATH_SEPARATOR
          || value == HttpCharacters.QUERY_SEPARATOR
          || value == HttpCharacters.FRAGMENT_SEPARATOR) {
        end = index;
        break;
      }
    }

    return URI.create(url.substring(0, end));
  }

  /**
   * Expands an omitted HTTP(S) default port for origin comparison.
   *
   * @param uri origin or request URL
   * @return explicit port or scheme default
   */
  private static int effectivePort(URI uri) {
    if (uri.getPort() >= 0) {
      return uri.getPort();
    }

    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  /**
   * Combines requested-header list fields, validates tokens and applies explicit permissions.
   *
   * @param request invocation input
   * @return unique lowercase header names in stable order
   */
  private Set<String> requestedHeaders(Request request) {
    var values = request.headers(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS.value());
    var names = new TreeSet<String>();
    for (var value : values) {
      for (var token : value.split(",", -1)) {
        var name = token.trim();
        if (name.isEmpty()) {
          continue;
        }

        validateToken(name);
        if (!headers.contains(HttpHeaders.of(name))) {
          throw new ForbiddenException();
        }

        names.add(name.toLowerCase(Locale.ROOT));
      }
    }
    if (!values.isEmpty() && names.isEmpty()) {
      throw new BadRequestException();
    }

    return names;
  }

  /**
   * Uses the existing HTTP field-name primitive to check the shared HTTP token grammar.
   *
   * @param token untrusted method or header name
   * @throws BadRequestException if the value is not one token
   */
  private static void validateToken(String token) {
    try {
      HttpHeaders.of(token);
    } catch (IllegalArgumentException failure) {
      throw new BadRequestException(failure);
    }
  }

  /**
   * Checks origin tuple syntax without DNS lookups or URL repair; matching remains exact.
   *
   * @param origin configured origin or supported special value
   * @throws IllegalArgumentException if origin is not an HTTP(S) tuple, null or wildcard
   */
  static void validateOrigin(String origin) {
    if ("null".equals(origin) || "*".equals(origin)) {
      return;
    }

    var uri = URI.create(origin);
    if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || !uri.getRawPath().isEmpty()
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || uri.getPort() > 65535
        || origin.indexOf(HttpCharacters.PERCENT_SIGN) >= 0
        || !origin.equals(
            uri.getScheme()
                + "://"
                + uri.getHost()
                + (uri.getPort() < 0 ? "" : ":" + uri.getPort()))) {
      throw new IllegalArgumentException("Expected an exact HTTP(S) origin without a path");
    }
  }
}
