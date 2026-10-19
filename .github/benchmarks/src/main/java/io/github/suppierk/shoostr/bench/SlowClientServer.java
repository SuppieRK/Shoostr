package io.github.suppierk.shoostr.bench;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.Shoostr;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.server.Connector;

/** Loopback-only fixture for finite slow-client resource-safety diagnostics. */
public final class SlowClientServer implements AutoCloseable {
  private final Object phase;
  private int activeBodies;
  private int activeBlocked;
  private int activeExchanges;
  private int peakWork;
  private long admitted;
  private long completed;
  private CountDownLatch release;
  private List<Connector> connectors;
  private String nativeConfiguration;
  private final Shoostr app;

  /**
   * Registers the fixture without changing native request limits or timeouts.
   *
   * @param port requested loopback port, or zero for an ephemeral test listener
   */
  SlowClientServer(int port) {
    phase = new Object();
    release = new CountDownLatch(1);
    connectors = List.of();
    nativeConfiguration = "";
    var defaults = Options.defaults();
    app =
        new Shoostr(
            new Options(
                "127.0.0.1",
                port,
                defaults.maxRequestBytes(),
                defaults.maxResponseBytes(),
                defaults.streamBufferBytes(),
                defaults.idleTimeoutMillis(),
                defaults.maxParameters(),
                defaults.multipart()));
    app.modifyServer(server -> connectors = List.of(server.getConnectors()));
    app.modifyHttpConfiguration(configuration -> nativeConfiguration = configuration.dump());
    app.onRouteMatched(
        (request, _) -> {
          synchronized (phase) {
            if (("POST".equals(request.method()) && "/slow-body".equals(request.path()))
                || ("GET".equals(request.method()) && "/blocked".equals(request.path()))) {
              activeExchanges++;
              admitted++;
            }
          }
        });
    app.afterRequest(
        outcome -> {
          synchronized (phase) {
            if (("POST".equals(outcome.method()) && "/slow-body".equals(outcome.routePattern()))
                || ("GET".equals(outcome.method()) && "/blocked".equals(outcome.routePattern()))) {
              activeExchanges--;
              completed++;
            }
          }
        });
    app.routes().post("/slow-body", this::consumeBody);
    app.routes().get("/blocked", (_, response) -> awaitRelease(response));
    app.routes().post("/arm", (_, response) -> arm(response));
    app.routes()
        .post(
            "/release",
            (_, response) -> {
              releaseWork();
              response.text("released");
            });
    app.routes().get("/healthy", (_, response) -> response.text("healthy"));
    app.routes()
        .get(
            "/configuration",
            (_, response) ->
                response.text(
                    "max_request_bytes="
                        + defaults.maxRequestBytes()
                        + "\nconnector_idle_timeout_ms="
                        + connectors.stream().map(Connector::getIdleTimeout).toList()
                        + "\n"
                        + nativeConfiguration));
    app.routes()
        .get(
            "/state",
            (_, response) ->
                response.body("application/json", state().getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * Runs one owned fixture until the diagnostic launcher terminates the process.
   *
   * @param args optional loopback port
   * @throws IllegalArgumentException if more than one argument is supplied
   * @throws Exception if configuration, startup or the process wait fails
   */
  public static void main(String[] args) throws Exception {
    if (args.length > 1) {
      throw new IllegalArgumentException("Supply at most one loopback port");
    }

    try (var server = new SlowClientServer(args.length == 0 ? 18080 : Integer.parseInt(args[0]))) {
      server.start();
      Runtime.getRuntime()
          .addShutdownHook(
              Thread.ofPlatform()
                  .name("slow-client-shutdown")
                  .unstarted(
                      () -> {
                        try {
                          server.close();
                        } catch (IOException failure) {
                          System.err.println("Fixture shutdown failed: " + failure);
                        }
                      }));
      System.out.println("READY " + server.port());
      new CountDownLatch(1).await();
    }
  }

  /**
   * Starts the owned application.
   *
   * @throws Exception if native startup fails
   */
  void start() throws Exception {
    app.start();
  }

  /**
   * Returns the effective listener port after startup.
   *
   * @return effective loopback port
   */
  int port() {
    return app.port();
  }

  /**
   * Closes the owned application and transport resources.
   *
   * @throws IOException if native shutdown fails
   */
  @Override
  public void close() throws IOException {
    releaseWork();
    app.close();
  }

  /**
   * Consumes bounded content on the handler thread, leaving stream closure to Shoostr.
   *
   * @param request live request
   * @param response paired response
   * @throws IOException if content ends prematurely or the peer disconnects
   */
  private void consumeBody(Request request, Response response) throws IOException {
    synchronized (phase) {
      activeBodies++;
      peakWork = Math.max(peakWork, activeBodies + activeBlocked);
    }

    try {
      request.input().transferTo(OutputStream.nullOutputStream());
      response.text("consumed");
    } finally {
      synchronized (phase) {
        activeBodies--;
      }
    }
  }

  /**
   * Holds cooperative handler work for at most five seconds without changing native idle policy.
   *
   * @param response paired response
   * @throws InterruptedException if application shutdown interrupts the wait
   */
  private void awaitRelease(Response response) throws InterruptedException {
    CountDownLatch waiting;
    synchronized (phase) {
      activeBlocked++;
      peakWork = Math.max(peakWork, activeBodies + activeBlocked);
      waiting = release;
    }

    try {
      response.text(waiting.await(5, TimeUnit.SECONDS) ? "released" : "elapsed");
    } finally {
      synchronized (phase) {
        activeBlocked--;
      }
    }
  }

  /**
   * Starts another phase only after handlers and their terminal exchanges have finished.
   *
   * @param response paired phase-control response
   */
  private void arm(Response response) {
    synchronized (phase) {
      if (activeBodies != 0 || activeBlocked != 0 || activeExchanges != 0) {
        response.status(409).text("busy");
        return;
      }

      release = new CountDownLatch(1);
      peakWork = 0;
      response.text("armed");
    }
  }

  /** Releases current cooperative work without holding the phase lock during native shutdown. */
  private void releaseWork() {
    synchronized (phase) {
      release.countDown();
    }
  }

  /**
   * Exposes bounded work counts separately from terminal-exchange accounting.
   *
   * @return fixture state as JSON
   */
  private String state() {
    synchronized (phase) {
      return "{\"active_bodies\":"
          + activeBodies
          + ",\"active_blocked\":"
          + activeBlocked
          + ",\"active_exchanges\":"
          + activeExchanges
          + ",\"peak_work\":"
          + peakWork
          + ",\"admitted\":"
          + admitted
          + ",\"completed\":"
          + completed
          + ",\"connections\":"
          + connectors.stream()
              .mapToInt(connector -> connector.getConnectedEndPoints().size())
              .sum()
          + "}";
    }
  }
}
