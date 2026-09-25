package io.github.suppierk.shoostr.example;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.util.component.LifeCycle;

/** Exercises locally published artifacts from a separate Gradle consumer build. */
public final class ConsumerSmoke {
  /** Prevents construction of this command-line smoke fixture. */
  private ConsumerSmoke() {}

  /**
   * Verifies test fixture isolation, native configuration, lifecycle and startup errors.
   *
   * @param args unused command-line arguments
   * @throws Exception if a smoke scenario fails
   */
  static void main(String[] args) throws Exception {
    var started = new AtomicInteger();
    var stopped = new AtomicInteger();
    URI firstUri;

    try (var client = HttpClient.newHttpClient();
        var first =
            TestServer.start(
                app -> {
                  app.modifyHttpConfiguration(http -> http.setRequestHeaderSize(512));
                  app.modifyServer(
                      server ->
                          server.addEventListener(
                              new LifeCycle.Listener() {
                                /**
                                 * Records that the published app completed native startup.
                                 *
                                 * @param event started Jetty component
                                 */
                                @Override
                                public void lifeCycleStarted(LifeCycle event) {
                                  started.incrementAndGet();
                                }

                                /**
                                 * Records that fixture closure stopped the native server.
                                 *
                                 * @param event stopped Jetty component
                                 */
                                @Override
                                public void lifeCycleStopped(LifeCycle event) {
                                  stopped.incrementAndGet();
                                }
                              }));
                  app.routes().get("/value", (request, response) -> response.text("first"));
                });
        var second =
            TestServer.start(
                app ->
                    app.routes().get("/value", (request, response) -> response.text("second")))) {
      firstUri = first.baseUri();
      require(firstUri.getPort() != second.baseUri().getPort(), "fixtures share a port");
      require("first".equals(body(client, firstUri.resolve("value"))), "first route failed");
      require(
          "second".equals(body(client, second.baseUri().resolve("value"))), "second route failed");
      require(started.get() == 1, "native start event missing");
      var oversized =
          client.send(
              HttpRequest.newBuilder(firstUri.resolve("value"))
                  .header("X-Large", "x".repeat(1024))
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      require(oversized.statusCode() == 431, "native HTTP override was not applied");
    }

    require(stopped.get() == 1, "native stop event missing");

    try (var ignored = new Socket(InetAddress.getAllByName("127.0.0.1")[0], firstUri.getPort())) {
      throw new AssertionError("fixture still accepts connections after close");
    } catch (IOException expected) {
      // The listener is no longer bound.
    }

    try (var ignored =
        TestServer.start(
            app ->
                app.modifyServer(
                    server -> {
                      throw new IllegalStateException("invalid native configuration");
                    }))) {
      throw new AssertionError("invalid native configuration unexpectedly started");
    } catch (IllegalStateException expected) {
      require(
          "invalid native configuration".equals(expected.getMessage()),
          "startup failure lost its cause");
    }
  }

  /**
   * Reads a response body from a published framework route.
   *
   * @param client shared JDK client
   * @param uri route URI
   * @return response body
   * @throws Exception if the request fails
   */
  private static String body(HttpClient client, URI uri) throws Exception {
    var response =
        client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
    require(response.statusCode() == 200, "unexpected route status");
    return response.body();
  }

  /**
   * Fails the smoke build if an externally visible contract does not hold.
   *
   * @param condition result to validate
   * @param message failure description
   * @throws AssertionError if the condition is false
   */
  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
