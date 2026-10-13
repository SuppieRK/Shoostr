package io.github.suppierk.shoostr.example.hello;

import io.github.suppierk.shoostr.Shoostr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Composes the hello application; Shoostr owns the listener and shutdown hook. */
public final class HelloExample {
  private static final Logger LOGGER = LoggerFactory.getLogger(HelloExample.class);

  /** Prevents construction of the launcher. */
  private HelloExample() {}

  /**
   * Starts a server on all IPv4 interfaces using the default listener settings.
   *
   * @throws Exception if the listener cannot start
   */
  static void main() throws Exception {
    var app = application();
    app.start();
    LOGGER.info("Hello: http://127.0.0.1:{}/hello", app.port());
  }

  /**
   * Returns an unstarted app; callers or TestServer own its eventual lifetime.
   *
   * @return configured application
   */
  public static Shoostr application() {
    return new Shoostr()
        .routes(routes -> routes.get("/hello", (_, response) -> response.text("Hello, Shoostr!")));
  }
}
