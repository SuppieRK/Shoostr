package io.github.suppierk.shoostr.example.openapi;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import org.eclipse.jetty.server.ServerConnector;

/** Composes the explicit OpenAPI, codec and authentication demonstration. */
public final class OpenApiExample {
  /** Prevents instantiation of the example factory. */
  private OpenApiExample() {}

  /**
   * Starts the example; core owns its JVM shutdown lifecycle.
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
    System.out.println("Open http://127.0.0.1:" + app.port() + "/docs/");
    System.out.println("Demo username: demo");
    System.out.println("Demo password: local-only");
  }

  /**
   * Builds an unstarted app with independently configured dependencies.
   *
   * @param options listener settings
   * @return app owned by its caller
   */
  public static Shoostr application(Options options) {
    var configuration = new OpenApiConfiguration();
    var security = OpenApiAuthentication.create();
    var app =
        new Shoostr(options)
            .authentication(security)
            .extensions(configuration.docs(), configuration.internal())
            .routes(routes -> OpenApiRoutes.register(routes, configuration, security));
    app.codec(new UserCodec())
        .routes(routes -> OpenApiRoutes.registerTyped(routes, configuration, security));
    return app;
  }
}
