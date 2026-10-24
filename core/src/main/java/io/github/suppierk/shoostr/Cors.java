package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpCharacters;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/** Scoped browser response sharing, resolved before authentication and request admission. */
@SuppressWarnings("java:S6206") // Extension instances identify owners, even with the same policy.
public final class Cors implements Extension<Cors.Configuration> {
  private static final Map<String, String> VARIATION =
      Map.of(HttpHeaders.VARY.value(), HttpHeaders.ORIGIN.value());
  static final Map<String, String> OPTIONS_VARIATION =
      Map.of(
          HttpHeaders.VARY.value(),
          String.join(
              ", ",
              HttpHeaders.ORIGIN.value(),
              HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD.value(),
              HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS.value()));

  private final CorsPolicy policy;

  /**
   * Creates immutable sharing behavior from validated permissions.
   *
   * @param policy immutable permissions
   */
  public Cors(CorsPolicy policy) {
    this.policy = java.util.Objects.requireNonNull(policy);
  }

  /**
   * Returns the immutable configuration, including typed allowed/exposed headers.
   *
   * @return configured policy
   */
  public CorsPolicy policy() {
    return policy;
  }

  /** {@inheritDoc} */
  @Override
  public Configuration configure(Extensions endpoint, @Nullable Configuration inherited) {
    return new Configuration(endpoint, this, inherited);
  }

  /** Scope-local complete policy replacement for one installed owner. */
  public static final class Configuration {
    private final Extensions endpoint;
    private final Cors cors;
    private CorsPolicy policy;

    /**
     * Creates an independent selection.
     *
     * @param endpoint contribution scope
     * @param cors installed owner
     * @param inherited enclosing configuration, or null
     */
    private Configuration(Extensions endpoint, Cors cors, @Nullable Configuration inherited) {
      this.endpoint = java.util.Objects.requireNonNull(endpoint);
      this.cors = java.util.Objects.requireNonNull(cors);
      policy = inherited == null ? cors.policy : inherited.policy;
      endpoint.cors(cors, policy);
    }

    /**
     * Replaces the complete policy without changing the selected owner.
     *
     * @param contribution complete immutable replacement
     * @return this configuration
     * @throws IllegalStateException if registration has finished
     * @throws IllegalArgumentException if the resolved permissions are invalid
     */
    public Configuration policy(CorsPolicy contribution) {
      endpoint.requireMutable();
      var combined = java.util.Objects.requireNonNull(contribution);
      endpoint.cors(cors, combined);

      policy = combined;
      return this;
    }
  }

  /**
   * Validates preflight structure before target existence or policy grants.
   *
   * @param request incoming preflight
   * @throws BadRequestException if preflight fields are malformed
   */
  static void validatePreflight(Request request) {
    validatedOrigin(request.headers(HttpHeaders.ORIGIN.value()));
    var methods = request.headers(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD.value());
    if (methods.size() != 1) {
      throw new BadRequestException();
    }

    validateToken(methods.getFirst());
    validateRequestedHeaders(request);
  }

  /**
   * Selects immutable response fields before application processing.
   *
   * @param policy immutable effective permissions for this binding
   * @param request invocation input
   * @param response output receiving required sharing fields
   * @return whether this is an admitted preflight
   * @throws BadRequestException if CORS fields are malformed
   * @throws ForbiddenException if cross-origin permissions are denied
   */
  static boolean prepare(CorsPolicy policy, Request request, Response response) {
    boolean options = HttpMethods.OPTIONS.value().equals(request.method());
    var variation = options ? OPTIONS_VARIATION : VARIATION;
    response.corsHeaders(variation);
    var incomingOrigins = request.headers(HttpHeaders.ORIGIN.value());
    if (incomingOrigins.isEmpty()) {
      return false;
    }

    var origin = validatedOrigin(incomingOrigins);

    var requestedMethods = request.headers(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD.value());
    boolean preflight = options && !requestedMethods.isEmpty();
    if (preflight && requestedMethods.size() != 1) {
      throw new BadRequestException();
    }

    if (!preflight && Origins.sameOrigin(origin, request.effectiveUrl())) {
      return false;
    }

    var method = preflight ? requestedMethods.getFirst() : request.method();
    validateToken(method);
    if (!policy.origins().contains(origin)
        && !policy.origins().contains(HttpCharacters.ASTERISK_STRING)) {
      throw new ForbiddenException();
    }

    if (!policy.allMethods()
        && !HttpMethods.httpMethod(method).map(policy.methods()::contains).orElse(false)) {
      throw new ForbiddenException();
    }

    Map<String, String> fields = new HashMap<>(variation);
    fields.put(
        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN.value(),
        policy.origins().contains(HttpCharacters.ASTERISK_STRING)
            ? HttpCharacters.ASTERISK_STRING
            : origin);
    if (policy.credentials()) {
      fields.put(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS.value(), "true");
    }

    populateResponseHeaders(policy, fields, request, method, preflight);
    response.corsHeaders(Map.copyOf(fields));
    return preflight;
  }

  /**
   * Requires exactly one concrete, syntactically valid Origin field.
   *
   * @param incomingOrigins raw Origin fields
   * @return validated serialized origin
   * @throws BadRequestException if Origin is missing, repeated or malformed
   */
  private static String validatedOrigin(List<String> incomingOrigins) {
    if (incomingOrigins.size() != 1
        || HttpCharacters.ASTERISK_STRING.equals(incomingOrigins.getFirst())) {
      throw new BadRequestException();
    }

    var origin = incomingOrigins.getFirst();

    try {
      Origins.validateOrigin(origin);
    } catch (IllegalArgumentException failure) {
      throw new BadRequestException(failure);
    }

    return origin;
  }

  /**
   * Adds response headers that differ between preflight and ordinary CORS requests.
   *
   * @param policy immutable effective permissions
   * @param fields mutable response fields
   * @param request incoming request
   * @param method validated requested method
   * @param preflight whether the request is a preflight
   */
  private static void populateResponseHeaders(
      CorsPolicy policy,
      Map<String, String> fields,
      Request request,
      String method,
      boolean preflight) {
    if (preflight) {
      var requestedHeaders = requestedHeaders(policy, request);
      fields.put(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS.value(), method);
      if (!requestedHeaders.isEmpty()) {
        fields.put(
            HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS.value(), String.join(", ", requestedHeaders));
      }

      fields.put(HttpHeaders.ACCESS_CONTROL_MAX_AGE.value(), Long.toString(policy.maxAgeSeconds()));
    } else if (!policy.exposedHeaders().isEmpty()) {
      fields.put(
          HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS.value(),
          String.join(
              ", ", policy.exposedHeaders().stream().map(HttpHeaders::value).sorted().toList()));
    }
  }

  /**
   * Combines requested-header list fields, validates tokens and applies explicit permissions.
   *
   * @param policy immutable effective permissions
   * @param request invocation input
   * @return unique lowercase header names in stable order
   * @throws ForbiddenException if a requested header is not allowed
   */
  private static Set<String> requestedHeaders(CorsPolicy policy, Request request) {
    var names = validateRequestedHeaders(request);
    for (var name : names) {
      if (!policy.allowedHeaders().contains(HttpHeaders.of(HttpCharacters.ASTERISK_STRING))
          && !policy.allowedHeaders().contains(HttpHeaders.of(name))) {
        throw new ForbiddenException();
      }
    }
    return names;
  }

  /**
   * Parses header tokens without granting permissions.
   *
   * @param request incoming preflight
   * @return canonical names
   * @throws BadRequestException if preflight fields are malformed
   */
  private static Set<String> validateRequestedHeaders(Request request) {
    var values = request.headers(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS.value());
    var names = new TreeSet<String>();
    for (var value : values) {
      for (var token : value.split(HttpCharacters.COMMA_SIGN_STRING, -1)) {
        var name = token.trim();
        if (name.isEmpty()) {
          continue;
        }

        validateToken(name);
        names.add(name.toLowerCase(Locale.ROOT));
      }
    }
    if (!values.isEmpty() && names.isEmpty()) {
      throw new BadRequestException();
    }

    return names;
  }

  /**
   * Checks the shared HTTP token grammar without constructing a field-name object.
   *
   * @param token untrusted method or header name
   * @throws BadRequestException if the value is not one token
   */
  private static void validateToken(String token) {
    if (!HttpCharacters.isValidHttpToken(token)) {
      throw new BadRequestException(
          new IllegalArgumentException("HTTP field name must be an RFC 9110 token"));
    }
  }
}
