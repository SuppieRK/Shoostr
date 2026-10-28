package io.github.suppierk.shoostr.example.authentication;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.exceptions.NotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.session.DefaultSessionCache;
import org.eclipse.jetty.session.FileSessionDataStore;

/** Server-side preference sessions with an explicitly configured private file store. */
public final class SessionExample {
  private static final String THEME_ATTRIBUTE = "theme";

  /** Prevents utility-class construction. */
  private SessionExample() {}

  /**
   * Creates an application whose session files remain in caller-owned storage after closure.
   *
   * @param directory existing private session-store directory
   * @return unstarted, caller-owned application
   * @throws IOException if the directory cannot be resolved
   * @throws IllegalArgumentException if the resolved path is not a directory
   */
  public static Shoostr application(Path directory) throws IOException {
    // Session files belong in private storage, never in a directory served as public resources.
    var storeDirectory = Objects.requireNonNull(directory).toRealPath();
    if (!Files.isDirectory(storeDirectory)) {
      throw new IllegalArgumentException("Session storage must be a directory");
    }

    return new Shoostr()
        .sessions(
            handler -> {
              handler.setSessionCookie("PREFERENCES");
              handler.setHttpOnly(true);
              handler.setSecureCookies(true);
              handler.setSameSite(HttpCookie.SameSite.STRICT);
              handler.setMaxCookieAge(900);
              handler.setMaxInactiveInterval(900);
              var store = new FileSessionDataStore();
              store.setStoreDir(storeDirectory.toFile());
              var cache = new DefaultSessionCache(handler);
              cache.setSessionDataStore(store);
              handler.setSessionCache(cache);
            })
        .routes(
            routes ->
                routes
                    .get(
                        "/preferences/session",
                        (request, response) -> {
                          // Only this endpoint creates a missing session and initializes its
                          // preference.
                          var session = request.getOrCreateSession();
                          if (session.getAttribute(THEME_ATTRIBUTE) == null) {
                            session.setAttribute(THEME_ATTRIBUTE, "dark");
                          }

                          response.text((String) session.getAttribute(THEME_ATTRIBUTE));
                        })
                    // Inspection uses getSession(), so a read does not create server-side state.
                    .get(
                        "/preferences/state",
                        (request, response) ->
                            response.text(
                                request
                                    .getSession()
                                    .map(session -> (String) session.getAttribute(THEME_ATTRIBUTE))
                                    .orElse("absent")))
                    .post(
                        "/preferences/renew",
                        (request, response) -> {
                          if (request.getSession().isEmpty()) {
                            throw new NotFoundException();
                          }

                          // Rotate the identifier while preserving the existing session attributes.
                          request.renewSessionId();
                          response.text("renewed");
                        })
                    .post(
                        "/preferences/reset",
                        (request, response) -> {
                          // Invalidation clears server-side session state, not just the browser
                          // cookie.
                          request.getSession().ifPresent(Session::invalidate);
                          response.text("cleared");
                        }));
  }
}
