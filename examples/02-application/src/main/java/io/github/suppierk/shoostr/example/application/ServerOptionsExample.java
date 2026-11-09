package io.github.suppierk.shoostr.example.application;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;

/** Configures the listener and resource limits with application settings. */
public final class ServerOptionsExample {
  /** Prevents construction of this example factory. */
  private ServerOptionsExample() {}

  /**
   * Creates an unstarted application owned by its caller.
   *
   * @param host listener bind address or host name
   * @param port listener port, or zero to select an available port
   * @return configured application
   */
  public static Shoostr application(String host, int port) {
    // Configure listener choices and request limits before the application captures its settings.
    var options = new Options();
    options.setHost(host);
    options.setPort(port);
    options.setMaxRequestHeaderBytes(1024);
    options.setMaxRequestBytes(1024);
    options.setMaxResponseBytes(2048);
    options.setStreamBufferBytes(4096);

    return new Shoostr(options)
        .routes(
            routes ->
                routes.post("/echo", (request, response) -> response.text(request.bodyText())));
  }
}
