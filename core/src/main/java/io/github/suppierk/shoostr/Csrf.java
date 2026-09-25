package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.server.Session;

/**
 * Session-bound synchronizer tokens for explicitly protected browser routes. Create one instance
 * per application, enable {@link Shoostr#sessions()}, then register {@code csrf::verify} through
 * {@link Routes#protect(Handler, java.util.function.Consumer)}. Token values are never put in a
 * cookie or URL; an application embeds {@link #token(Request)} in a page or response body.
 */
public final class Csrf {
  /** A token is valid only while its issuing session keeps the same identifier. */
  private record Token(String sessionId, String value) implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    /**
     * Validates both parts of the session-bound token.
     *
     * @param sessionId issuing session identifier
     * @param value encoded token
     * @throws IllegalArgumentException if either part is empty
     */
    private Token {
      if (Objects.requireNonNull(sessionId).isEmpty() || Objects.requireNonNull(value).isEmpty()) {
        throw new IllegalArgumentException("CSRF token parts cannot be empty");
      }
    }
  }

  private static final String ATTRIBUTE = Csrf.class.getName() + ".token";
  private static final String HEADER = "X-CSRF-Token";

  private final SecureRandom random;
  private final Set<String> trustedOrigins;

  /** Creates a same-origin CSRF policy for a browser's cookie-authenticated routes. */
  public Csrf() {
    this(Set.of());
  }

  /**
   * Creates a CSRF policy allowing the request target origin and explicitly trusted browser
   * origins. Cross-origin permission also requires a separate CORS policy when used by scripts.
   *
   * @param trustedOrigins additional exact serialized HTTP(S) origins
   * @throws IllegalArgumentException if an origin is malformed or opaque
   */
  public Csrf(Set<String> trustedOrigins) {
    this.trustedOrigins = Set.copyOf(trustedOrigins);
    for (var origin : this.trustedOrigins) {
      if ("null".equals(origin) || "*".equals(origin)) {
        throw new IllegalArgumentException("CSRF requires explicit HTTP(S) origins");
      }

      CorsPolicy.validateOrigin(origin);
    }

    random = new SecureRandom();
  }

  /**
   * Returns a stable, unpredictable token for the current session, creating a session if needed.
   * Session ID renewal rotates the token on the next access.
   *
   * @param request current browser request
   * @return token for a response body or HTML form
   * @throws IllegalStateException if sessions are not enabled
   */
  public String token(Request request) {
    var session = Objects.requireNonNull(request).session(true);
    if (session == null) {
      throw new IllegalStateException("CSRF requires enabled sessions");
    }

    synchronized (session) {
      var current = session.getAttribute(ATTRIBUTE);
      if (current instanceof Token(String sessionId, String value)
          && session.getId().equals(sessionId)) {
        return value;
      }

      byte[] bytes = new byte[32];
      random.nextBytes(bytes);
      var value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      session.setAttribute(ATTRIBUTE, new Token(session.getId(), value));
      return value;
    }
  }

  /**
   * Rejects unsafe operations unless an existing session supplies its token in exactly one {@code
   * X-CSRF-Token} header or {@code _csrf} form field and the request comes from the same origin.
   * Safe methods pass. This method is a route policy, not a bearer-token policy.
   *
   * @param request current request
   * @param response output reserved for the protected route
   * @throws ForbiddenException if browser CSRF checks fail
   * @throws IOException if bounded form input cannot be read
   */
  public void verify(Request request, Response response) throws IOException {
    Objects.requireNonNull(response);
    if (safe(request.method())) {
      return;
    }

    if (!sameOrigin(request)) {
      throw new ForbiddenException();
    }

    Session session = request.session(false);
    if (session == null) {
      throw new ForbiddenException();
    }

    List<String> presented = presented(request);
    if (presented.size() != 1) {
      throw new ForbiddenException();
    }

    synchronized (session) {
      var stored = session.getAttribute(ATTRIBUTE);
      if (!(stored instanceof Token(String sessionId, String value))
          || !session.getId().equals(sessionId)
          || !MessageDigest.isEqual(
              value.getBytes(StandardCharsets.US_ASCII),
              presented.getFirst().getBytes(StandardCharsets.US_ASCII))) {
        throw new ForbiddenException();
      }
    }
  }

  /**
   * Reads exactly one supported token channel, detecting duplicate or conflicting values.
   *
   * @param request current request
   * @return presented values, or empty if none
   * @throws IOException if form input cannot be read
   * @throws ForbiddenException if header and form token channels conflict
   */
  private static List<String> presented(Request request) throws IOException {
    var headers = request.headers(HEADER);
    var type =
        HttpField.getValueParameters(
            request.header(HttpHeaders.CONTENT_TYPE.value()), new HashMap<>());
    if (!MediaType.APPLICATION_FORM_URLENCODED.value().equalsIgnoreCase(type)
        && !"multipart/form-data".equalsIgnoreCase(type)) {
      return headers;
    }

    var fields = request.formParams("_csrf");
    if (!headers.isEmpty() && !fields.isEmpty()) {
      throw new ForbiddenException();
    }

    return headers.isEmpty() ? fields : headers;
  }

  /**
   * Recognizes the methods defined as safe by RFC 9110.
   *
   * @param method incoming wire method
   * @return whether the method should have no requested state change
   */
  private static boolean safe(String method) {
    var known = HttpMethods.httpMethod(method).orElse(null);
    return known == HttpMethods.GET
        || known == HttpMethods.HEAD
        || known == HttpMethods.OPTIONS
        || known == HttpMethods.TRACE;
  }

  /**
   * Applies strict same-origin policy to one Origin field, or one Referer when Origin is absent.
   *
   * @param request current request
   * @return whether its source is exactly the effective request origin
   */
  private boolean sameOrigin(Request request) {
    var origins = request.headers(HttpHeaders.ORIGIN.value());
    if (origins.size() > 1) {
      return false;
    }

    try {
      String origin;
      if (origins.isEmpty()) {
        var referers = request.headers(HttpHeaders.REFERER.value());
        if (referers.size() != 1) {
          return false;
        }

        var referer = CorsPolicy.effectiveOrigin(referers.getFirst());
        if (referer == null) {
          return false;
        }

        origin = referer.toString();
      } else {
        origin = origins.getFirst();
      }

      if ("null".equals(origin) || "*".equals(origin)) {
        return false;
      }

      CorsPolicy.validateOrigin(origin);
      return trustedOrigins.contains(origin)
          || CorsPolicy.sameOrigin(origin, request.effectiveUrl());
    } catch (IllegalArgumentException failure) {
      return false;
    }
  }
}
