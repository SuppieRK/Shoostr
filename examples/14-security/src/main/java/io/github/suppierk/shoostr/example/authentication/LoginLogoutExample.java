package io.github.suppierk.shoostr.example.authentication;

import io.github.suppierk.shoostr.AuthenticationExtension;
import io.github.suppierk.shoostr.Csrf;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.Cookie;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.http.exceptions.ConflictException;
import io.github.suppierk.shoostr.http.exceptions.UnauthorizedException;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Session;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;

/**
 * Application-owned login and logout using native sessions
 * and session-bound CSRF tokens.
 */
public final class LoginLogoutExample {
    private static final String NO_STORE = "no-store";
    private static final String THEME_ATTRIBUTE = "theme";

    private static final String AUTH_COOKIE = "__Host-SHOOSTR-AUTH";
    private static final String ACCOUNT = "account";

    /**
     * Creates an application to configure with HTTPS before startup.
     *
     * @param authenticate caller-owned, thread-safe credential verifier
     *                     returning a canonical account
     * @return unstarted, caller-owned application
     */
    public static Shoostr application(BiFunction<String, String, Optional<String>> authenticate) {
        Objects.requireNonNull(authenticate);
        var csrf = new Csrf();
        var sessionCookie = Cookie.secure(AUTH_COOKIE, "")
            .withHttpOnly(true)
            .withSameSite(Cookie.SameSite.LAX);

        var sessionLocks = new SessionLocks();
        var authentication = authentication(sessionLocks);

        return new Shoostr().sessions(
            handler -> {
                handler.setSessionCookie(AUTH_COOKIE);
                handler.setSessionPath("/");
                handler.setSecureCookies(true);
                handler.setHttpOnly(true);
                handler.setSameSite(HttpCookie.SameSite.LAX);
            })
            .withExtension(authentication)
            .withExtension(csrf)
            .routes(routes -> routes
                .get("/login", (request, response) -> {
                    var proof = csrf.token(request);
                    var session = request.getOrCreateSession();
                    var lock = sessionLocks.forSession(session);
                    lock.lock();

                    try {
                        if (session.getAttribute(THEME_ATTRIBUTE) == null) {
                            session.setAttribute(THEME_ATTRIBUTE, "light");
                        }
                    } finally {
                        lock.unlock();
                    }

                    response
                        .setHeader(HttpHeaders.CACHE_CONTROL.value(), NO_STORE)
                        .text(proof);
                },
                lifecycle -> lifecycle.withoutExtension(authentication))
                .post("/login", (request, response) -> {
                    var username = credential(request, "username");
                    var password = credential(request, "password");
                    var session = request.getSession().orElseThrow(BadRequestException::new);
                    var lock = sessionLocks.forSession(session);
                    lock.lock();

                    try {
                        requireAnonymous(session);
                    } finally {
                        lock.unlock();
                    }

                    // Credential verification follows CSRF admission and
                    // runs outside the transition lock.
                    var account = Objects
                        .requireNonNull(authenticate.apply(username, password))
                        .orElseThrow(UnauthorizedException::new);
                    if (account.isBlank()) {
                        throw new IllegalArgumentException(
                                "The verifier returned a blank account");
                    }

                    String proof;
                    lock.lock();

                    try {
                        // Recheck after verification so concurrent logins
                        // cannot replace an identity.
                        requireAnonymous(session);
                        request.renewSessionId();
                        proof = csrf.token(request);
                        session.setAttribute(ACCOUNT, account);
                    } finally {
                        lock.unlock();
                    }

                    response
                        .setHeader(HttpHeaders.CACHE_CONTROL.value(), NO_STORE)
                        .text(proof);
                },
                lifecycle -> lifecycle.withoutExtension(authentication))
                .get("/account/me", (request, response) -> {
                    var session = request.getSession()
                        .orElseThrow(UnauthorizedException::new);

                    response.text(
                        request.principal().orElseThrow().getName()
                            + ":"
                            + session.getAttribute(THEME_ATTRIBUTE));
                })
                .post("/account/theme", (request, response) -> {
                    var theme = request
                        .formParam(THEME_ATTRIBUTE)
                        .filter(value -> "light".equals(value) || "dark".equals(value))
                        .orElseThrow(BadRequestException::new);

                    request.getSession()
                        .orElseThrow(UnauthorizedException::new)
                        .setAttribute(THEME_ATTRIBUTE, theme);

                    response.text(theme);
                })
                .post("/account/logout", (request, response) -> {
                    var session = request.getSession().orElseThrow(UnauthorizedException::new);
                    var lock = sessionLocks.forSession(session);
                    lock.lock();

                    try {
                        // Server-side invalidation remains effective
                        // even if cookie deletion is not delivered.
                        session.invalidate();
                    } finally {
                        lock.unlock();
                    }

                    response
                        .removeCookie(sessionCookie)
                        .setHeader(HttpHeaders.CACHE_CONTROL.value(), NO_STORE)
                        .text("logged out");
                }));
    }

    /**
     * Configures the identity checks used by the protected example endpoints.
     *
     * @param sessionLocks locks shared with the login and logout handlers
     * @return authentication selected by the application
     */
    private static AuthenticationExtension authentication(SessionLocks sessionLocks) {
        return new AuthenticationExtension() {
            /** {@inheritDoc} */
            @Override
            public void handle(Request request, Response response) {
                request.principal(null);
                var session = request.getSession().orElseThrow(UnauthorizedException::new);
                var lock = sessionLocks.forSession(session);
                lock.lock();

                try {
                    if (!session.isValid() || !(session.getAttribute(ACCOUNT) instanceof String account)) {
                        throw new UnauthorizedException();
                    }

                    request.principal(() -> account);
                } finally {
                    lock.unlock();
                }


                // Account responses contain private state and
                // must not enter shared caches.
                response.setHeader(HttpHeaders.CACHE_CONTROL.value(), NO_STORE);
            }
        };
    }

    /**
     * Reads one body field without query fallback or password normalization.
     *
     * @param request current login request
     * @param name    credential field
     * @return submitted value
     * @throws IOException         if the form cannot be read
     * @throws BadRequestException if the credential field is missing or repeated
     */
    private static String credential(Request request, String name) throws IOException {
        var values = request.formParams(name);
        if (values.size() != 1) {
            throw new BadRequestException();
        }

        return values.getFirst();
    }

    /**
     * Rejects expired state and account switching before publishing a new login.
     *
     * @param session session held under the application's transition lock
     * @throws UnauthorizedException if the session has expired
     * @throws ConflictException     if the session already identifies an account
     */
    private static void requireAnonymous(Session session) {
        if (!session.isValid()) {
            throw new UnauthorizedException();
        }

        if (session.getAttribute(ACCOUNT) != null) {
            throw new ConflictException();
        }
    }

    /**
     * Shares one lock per native session without keeping
     * discarded sessions alive.
     */
    private static final class SessionLocks {
        private final Map<Session, ReentrantLock> locks;
        private final ReentrantLock guard;

        /**
         * Creates a registry owned by one application.
         */
        private SessionLocks() {
            this.locks = new WeakHashMap<>();
            this.guard = new ReentrantLock();
        }

        /**
         * Finds the lock for this example's native Jetty session,
         * including after ID renewal.
         *
         * @param session native session object
         * @return lock shared by requests using the same session
         */
        private ReentrantLock forSession(Session session) {
            guard.lock();

            try {
                return locks.computeIfAbsent(session, key -> new ReentrantLock());
            } finally {
                guard.unlock();
            }
        }
    }
}
