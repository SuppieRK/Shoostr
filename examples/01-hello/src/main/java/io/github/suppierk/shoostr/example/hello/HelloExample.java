package io.github.suppierk.shoostr.example.hello;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;

/** Composes the hello application; Shoostr owns the listener and shutdown hook. */
public final class HelloExample {
  /** Prevents construction of the launcher. */
  private HelloExample() {}

  /**
   * Starts a server on all IPv4 interfaces using the default listener settings.
   *
   * @throws Exception if the listener cannot start
   */
  static void main() throws Exception {
    var app = application(Options.defaults());
    app.start();
    System.out.println("Hello: http://127.0.0.1:" + app.port() + "/hello");
  }

  /**
   * Returns an unstarted app; callers or TestServer own its eventual lifetime.
   *
   * @param options listener settings
   * @return configured application
   */
  public static Shoostr application(Options options) {
    return new Shoostr(options)
        .routes(routes -> routes.get("/hello", (_, response) -> response.text("Hello, Shoostr!")));
  }
}
