package io.github.suppierk.shoostr.example.authentication;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import org.eclipse.jetty.server.ServerConnector;

/** Wires authentication separately from its credential policy and request handlers. */
public final class AuthenticationExample {
  /** Prevents construction of the launcher. */
  private AuthenticationExample() {}

  /**
   * Starts the local fixture; these demonstration passwords are not production identities.
   *
   * @throws Exception if startup fails
   */
  static void main() throws Exception {
    var app = application(Options.defaults());
    app.modifyServer(
        server ->
            ((ServerConnector) server.getConnectors()[0])
                .setHost(System.getProperty("shoostr.host", "127.0.0.1")));
    app.start();
    System.out.println("Auth: http://127.0.0.1:" + app.port() + "/private/me");
    System.out.println("Demo logins: demo / local-only; admin / local-only");
  }

  /**
   * Returns an unstarted app with explicit group protection.
   *
   * @param options listener settings
   * @return app owned by its eventual caller
   */
  public static Shoostr application(Options options) {
    var auth = DemoAuthentication.create();
    return new Shoostr(options)
        .authentication(auth)
        .routes(routes -> PrivateRoutes.register(routes, auth));
  }
}
