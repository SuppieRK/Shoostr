package io.github.suppierk.shoostr.example.requests;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import java.util.Objects;
import java.util.function.Function;
import org.eclipse.jetty.server.ServerConnector;

/** Wires explicit dependencies and starts the request-input demonstration. */
public final class RequestExample {
  /** Prevents construction of the launcher. */
  private RequestExample() {}

  /**
   * Starts the app; no DI container or user-managed shutdown hook is needed.
   *
   * @throws Exception if startup fails
   */
  static void main() throws Exception {
    var app = application(Options.defaults(), name -> "Hello, " + name + "!");
    app.modifyServer(
        server ->
            ((ServerConnector) server.getConnectors()[0])
                .setHost(System.getProperty("shoostr.host", "127.0.0.1")));
    app.start();
    System.out.println("Inputs: http://127.0.0.1:" + app.port() + "/greetings/Ada");
  }

  /**
   * Configures an unstarted app using a caller-owned greeting dependency.
   *
   * @param options listener settings
   * @param greeting concurrent-safe application function, replaceable in tests
   * @return app owned by its eventual caller
   */
  public static Shoostr application(Options options, Function<String, String> greeting) {
    Objects.requireNonNull(greeting);
    return new Shoostr(options).routes(routes -> RequestRoutes.register(routes, greeting));
  }
}
