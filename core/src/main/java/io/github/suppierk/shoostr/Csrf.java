package io.github.suppierk.shoostr;

import static io.github.suppierk.shoostr.Locks.getUnderLock;
import static io.github.suppierk.shoostr.Locks.runUnderLock;

import io.github.suppierk.shoostr.http.HttpCharacters;
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
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.server.Session;
import org.jspecify.annotations.Nullable;

/**
 * Session-bound synchronizer tokens for explicitly protected browser routes. Create one instance
 * per application, enable {@link Shoostr#sessions()}, then select {@code
 * lifecycle.withExtension(csrf)} on browser routes. First selection establishes shared setup
 * automatically. Use {@code app.withExtension(csrf)} before route registration to protect endpoints
 * by default. Selection does not enable sessions; protected endpoints require explicit session
 * configuration. Token values are never put in a cookie or URL; an application embeds {@link
 * #token(Request)} in a page or response body.
 *
 * <p>Equality compares configured trusted origins independently of generator state and token
 * history. Installation and endpoint selection still identify the exact extension instance.
 */
public final class Csrf implements ApplicationExtension<Csrf>, RouteExtension<Csrf> {
  private static final String ATTRIBUTE = Csrf.class.getName() + ".token";
  private static final String HEADER = "X-CSRF-Token";
  private static final String FORM_PARAMS = "_csrf";

  private final SecureRandom random;
  private final @Nullable Lifecycle lifecycle;
  private Set<String> trustedOrigins;

  /** Creates a same-origin CSRF policy for a browser's cookie-authenticated routes. */
  public Csrf() {
    this.random = new SecureRandom();
    this.lifecycle = null;
    this.trustedOrigins = Set.of();
  }

  /**
   * Creates a detached registration-owned selection while sharing the token generator.
   *
   * @param source source settings or inherited selection
   * @param lifecycle contribution scope controlling configuration lifetime
   */
  private Csrf(Csrf source, Lifecycle lifecycle) {
    this.trustedOrigins = Set.copyOf(source.trustedOrigins);
    this.random = source.random;
    this.lifecycle = Objects.requireNonNull(lifecycle);
  }

  /**
   * Creates a builder for independent browser-origin permissions.
   *
   * @return independent builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Recognizes the methods defined as safe by RFC 9110.
   *
   * @param method incoming wire method
   * @return whether the method should have no requested state change
   */
  private static boolean safe(String method) {
    final var known = HttpMethods.httpMethod(method).orElse(null);

    return known == HttpMethods.GET
        || known == HttpMethods.HEAD
        || known == HttpMethods.OPTIONS
        || known == HttpMethods.TRACE;
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
    final var headers = request.headers(HEADER);
    final var type =
        HttpField.getValueParameters(
            request.header(HttpHeaders.CONTENT_TYPE.value()).orElse(null), new HashMap<>());
    if (!MediaType.APPLICATION_FORM_URLENCODED.value().equalsIgnoreCase(type)
        && !MediaType.MULTIPART_FORM_DATA.value().equalsIgnoreCase(type)) {
      return headers;
    }

    final var fields = request.formParams(FORM_PARAMS);
    if (!headers.isEmpty() && !fields.isEmpty()) {
      throw new ForbiddenException();
    }

    return headers.isEmpty() ? fields : headers;
  }

  /**
   * Returns additional trusted origins without exposing a mutable collection.
   *
   * @return exact HTTP(S) origins, defaulting to none
   */
  public Set<String> getTrustedOrigins() {
    return trustedOrigins;
  }

  /**
   * Replaces additional trusted origins. Source beans remain mutable; selected copies freeze when
   * their registration scope finishes.
   *
   * @param value exact HTTP(S) origins, or null to restore same-origin defaults
   * @throws IllegalStateException if the selected registration scope has finished
   * @throws IllegalArgumentException if an origin is malformed, opaque or a wildcard
   */
  public void setTrustedOrigins(@Nullable Set<String> value) {
    if (lifecycle != null) {
      lifecycle.requireMutable();
    }

    final var resolved = value == null ? Set.<String>of() : Set.copyOf(value);
    for (final var origin : resolved) {
      if ("null".equals(origin) || HttpCharacters.ASTERISK_STRING.equals(origin)) {
        throw new IllegalArgumentException("CSRF requires explicit HTTP(S) origins");
      }

      Origins.validateOrigin(origin);
    }
    trustedOrigins = resolved;
  }

  /**
   * Compares the configured additional browser-origin permissions.
   *
   * @param other object to compare
   * @return whether both instances trust the same additional origins
   */
  @Override
  public boolean equals(@Nullable Object other) {
    return other instanceof Csrf csrf && trustedOrigins.equals(csrf.trustedOrigins);
  }

  /**
   * Computes a hash from the current trusted origins.
   *
   * @return trusted-origin set hash
   */
  @Override
  public int hashCode() {
    return trustedOrigins.hashCode();
  }

  /** {@inheritDoc} */
  @Override
  public Csrf configure(Lifecycle endpoint, @Nullable Csrf inherited) {
    endpoint.csrf(this);
    final var selected = new Csrf(inherited == null ? this : inherited, endpoint);
    endpoint.beforeRouteHandler(selected::verify);

    return selected;
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
    final var session = Objects.requireNonNull(request).getOrCreateSession();

    final var sessionLock = SessionLock.acquire(session);
    Throwable primaryFailure = null;

    try {
      final var current = session.getAttribute(ATTRIBUTE);
      if (current instanceof Token(String sessionId, String value)
          && session.getId().equals(sessionId)) {
        return value;
      }

      final var bytes = new byte[32];
      random.nextBytes(bytes);
      final var value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      session.setAttribute(ATTRIBUTE, new Token(session.getId(), value));

      return value;
    } catch (Exception failure) {
      primaryFailure = failure;
      throw failure;
    } finally {
      sessionLock.unlock(primaryFailure);
    }
  }

  /**
   * Rejects unsafe operations unless an existing session supplies its token in exactly one {@code
   * X-CSRF-Token} header or {@code _csrf} form field and the request comes from the same origin.
   * Safe methods and recognized WebSocket bindings pass. WebSocket Origin admission belongs in an
   * explicit pre-upgrade check.
   *
   * @param request current request
   * @param response output reserved for the protected route
   * @throws ForbiddenException if browser CSRF checks fail
   * @throws IOException if bounded form input cannot be read
   */
  public void verify(Request request, Response response) throws IOException {
    Objects.requireNonNull(response);
    if (request.webSocket() || safe(request.method())) {
      return;
    }

    if (!sameOrigin(request)) {
      throw new ForbiddenException();
    }

    Session session = request.getSession().orElseThrow(ForbiddenException::new);

    List<String> presented = presented(request);
    if (presented.size() != 1) {
      throw new ForbiddenException();
    }

    final var sessionLock = SessionLock.acquire(session);
    Throwable primaryFailure = null;

    try {
      final var stored = session.getAttribute(ATTRIBUTE);
      if (!(stored instanceof Token(String sessionId, String value))
          || !session.getId().equals(sessionId)
          || !MessageDigest.isEqual(
              value.getBytes(StandardCharsets.US_ASCII),
              presented.getFirst().getBytes(StandardCharsets.US_ASCII))) {
        throw new ForbiddenException();
      }
    } catch (Exception failure) {
      primaryFailure = failure;
      throw failure;
    } finally {
      sessionLock.unlock(primaryFailure);
    }
  }

  /**
   * Applies strict same-origin policy to one Origin field, or one Referer when Origin is absent.
   *
   * @param request current request
   * @return whether its source is exactly the effective request origin
   */
  private boolean sameOrigin(Request request) {
    final var origins = request.headers(HttpHeaders.ORIGIN.value());
    if (origins.size() > 1) {
      return false;
    }

    try {
      String origin;
      if (origins.isEmpty()) {
        final var referrers = request.headers(HttpHeaders.REFERER.value());
        if (referrers.size() != 1) {
          return false;
        }

        final var referer = Origins.effectiveOrigin(referrers.getFirst());
        if (referer == null) {
          return false;
        }

        origin = referer.toString();
      } else {
        origin = origins.getFirst();
      }

      if ("null".equals(origin) || HttpCharacters.ASTERISK_STRING.equals(origin)) {
        return false;
      }

      Origins.validateOrigin(origin);

      return trustedOrigins.contains(origin) || Origins.sameOrigin(origin, request.effectiveUrl());
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  /** Session-owned coordination with a registry containing only active holders and waiters. */
  private static final class SessionLock {
    private static final IdentityHashMap<Session, SessionLock> SESSION_LOCKS =
        new IdentityHashMap<>();
    private static final ReentrantLock SESSION_LOCKS_GUARD = new ReentrantLock();

    private final Session session;
    private final ReentrantLock lock;
    private int reservations;

    /**
     * Creates an entry whose reservations are recorded under the registry guard.
     *
     * @param session exact session object
     */
    private SessionLock(Session session) {
      this.session = Objects.requireNonNull(session);
      this.lock = new ReentrantLock();
      this.reservations = 0;
    }

    /**
     * Acquires one session's CSRF lock without extending native locking into listeners.
     *
     * @param session exact session object
     * @return acquired lock, which the caller must unlock in a finally block
     */
    private static SessionLock acquire(Session session) {
      final var sessionLock = reserve(session);

      try {
        sessionLock.lock.lock();
        return sessionLock;
      } catch (Exception failure) {
        try {
          sessionLock.releaseReservation();
        } catch (Exception cleanupFailure) {
          suppressCleanupFailure(failure, cleanupFailure);
        }

        throw failure;
      }
    }

    /**
     * Counts each holder and waiter before it can acquire the shared session lock.
     *
     * @param session exact session object
     * @return reserved session lock
     */
    private static SessionLock reserve(Session session) {
      return Objects.requireNonNull(
          getUnderLock(
              SESSION_LOCKS_GUARD,
              () -> {
                final var sessionLock = SESSION_LOCKS.computeIfAbsent(session, SessionLock::new);
                sessionLock.reservations++;

                return sessionLock;
              }));
    }

    /**
     * Unlocks before retiring this reservation while preserving the operation's original failure.
     *
     * @param primaryFailure original failure, or null when the operation succeeded
     */
    private void unlock(@Nullable Throwable primaryFailure) {
      try {
        lock.unlock();
        releaseReservation();
      } catch (Exception cleanupFailure) {
        if (primaryFailure == null) {
          throw cleanupFailure;
        }

        suppressCleanupFailure(primaryFailure, cleanupFailure);
      }
    }

    /** Retires this entry only when no acquired scope or waiter still reserves it. */
    private void releaseReservation() {
      runUnderLock(
          SESSION_LOCKS_GUARD,
          () -> {
            reservations--;
            if (reservations == 0) {
              SESSION_LOCKS.remove(session);
            }
          });
    }

    /**
     * Attaches cleanup failure without replacing the original failure or suppressing it onto
     * itself.
     *
     * @param primaryFailure original failure
     * @param cleanupFailure cleanup failure to attach
     */
    @SuppressWarnings("ReferenceEquality") // Throwable self-suppression uses reference identity.
    private static void suppressCleanupFailure(Throwable primaryFailure, Throwable cleanupFailure) {
      if (cleanupFailure != primaryFailure) {
        primaryFailure.addSuppressed(cleanupFailure);
      }
    }
  }

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
      if (Objects.requireNonNull(sessionId).isBlank() || Objects.requireNonNull(value).isBlank()) {
        throw new IllegalArgumentException("CSRF token parts cannot be blank");
      }
    }
  }

  /** Builds independent CSRF protection without enabling native sessions or selecting endpoints. */
  public static final class Builder {
    private Set<String> trustedOrigins;

    /** Creates same-origin permissions. */
    private Builder() {
      this.trustedOrigins = Set.of();
    }

    /**
     * Sets additional trusted origins with a required first value.
     *
     * @param first first additional exact HTTP(S) origin
     * @param additional remaining origins; null means none
     * @return this builder
     */
    public Builder trustedOrigins(String first, String @Nullable ... additional) {
      final var origins = new HashSet<String>();
      origins.add(Objects.requireNonNull(first));
      if (additional != null) {
        for (final var additionalOrigin : additional) {
          origins.add(Objects.requireNonNull(additionalOrigin));
        }
      }

      return trustedOrigins(origins);
    }

    /**
     * Sets additional exact browser origins, copying the supplied collection.
     *
     * @param origins additional trusted HTTP(S) origins
     * @return this builder
     */
    public Builder trustedOrigins(Set<String> origins) {
      trustedOrigins = Set.copyOf(origins);
      return this;
    }

    /**
     * Constructs protection and validates all configured origins.
     *
     * @return independent mutable origin policy and token generator
     */
    public Csrf build() {
      final var csrf = new Csrf();
      csrf.setTrustedOrigins(trustedOrigins);
      return csrf;
    }
  }
}
