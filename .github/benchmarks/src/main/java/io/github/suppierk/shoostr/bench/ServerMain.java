package io.github.suppierk.shoostr.bench;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.Routes;
import io.github.suppierk.shoostr.Shoostr;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.jspecify.annotations.Nullable;

/** Shoostr fixtures for the HTTP, SSE and WebSocket diagnostic workloads. */
public final class ServerMain {
  private static final byte[] PLAIN = "Hello, World!".getBytes(StandardCharsets.UTF_8);
  private static final byte[] JSON =
      "{\"message\":\"Hello, World!\"}".getBytes(StandardCharsets.UTF_8);
  private static final byte[] CHUNK = ("x".repeat(1023) + "\n").getBytes(StandardCharsets.UTF_8);
  private static final int EVENT_COUNT = 16;
  private static final String EVENT_PAYLOAD = "x".repeat(128);
  private static final String LARGE_EVENT_PAYLOAD = "x".repeat(65536);

  /**
   * Starts the fixture until process termination.
   *
   * @param args optional port and number of route groups
   * @throws Exception if startup fails or the main thread is interrupted
   * @throws IllegalArgumentException if the number of route groups is not positive
   */
  static void main(String[] args) throws Exception {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 18080;
    int routeGroups = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
    if (routeGroups < 1) {
      throw new IllegalArgumentException("Route groups require a positive count");
    }

    var app = new Shoostr(Options.defaults().withPort(port));
    app.routes(
            routes -> {
              routes.get("/plaintext", (_, res) -> res.body("text/plain;charset=utf-8", PLAIN));
              routes.get("/json-bytes", (_, res) -> res.body("application/json", JSON));
              routes.post(
                  "/echo", (req, res) -> res.body("application/octet-stream", req.bodyBytes()));
              routes.get(
                  "/stream",
                  (_, res) -> {
                    var stream = res.startStream("application/octet-stream");
                    for (int index = 0; index < EVENT_COUNT; index++) {
                      stream.write(CHUNK);
                      stream.flush();
                    }
                  });
              routes.get(
                  "/virtual",
                  (_, res) -> res.text(Boolean.toString(Thread.currentThread().isVirtual())));
              routes.sse("/sse-burst", (_, res) -> sendEvents(res, 0, EVENT_PAYLOAD));
              routes.sse("/sse-paced", (_, res) -> sendEvents(res, 20, EVENT_PAYLOAD));
              routes.sse("/sse-slow", (_, res) -> sendEvents(res, 0, LARGE_EVENT_PAYLOAD));
              routes.websocket("/ws-text", (_, _) -> new EchoListener());
              routes.websocket("/ws-binary", (_, _) -> new EchoListener());
              registerRouteGroups(routes, routeGroups);
            })
        .start();

    System.out.println("READY " + port);
    new CountDownLatch(1).await();
  }

  /**
   * Sends the same indexed event sequence for each SSE workload.
   *
   * @param response fixture response
   * @param pauseMillis delay between events
   * @param payload data in each indexed event
   * @throws Exception if writing or waiting fails
   */
  private static void sendEvents(Response response, int pauseMillis, String payload)
      throws Exception {
    var stream = response.startEventStream();
    for (int index = 0; index < EVENT_COUNT; index++) {
      stream.send(index + ":" + payload);
      if (pauseMillis > 0) {
        Thread.sleep(pauseMillis);
      }
    }
  }

  /**
   * Registers composed resource groups, each with eight method/path endpoints.
   *
   * @param routes root registration scope
   * @param groups number of resource groups
   */
  private static void registerRouteGroups(Routes routes, int groups) {
    for (int group = 0; group < groups; group++) {
      routes.path(
          "/api/resources/" + group,
          resources ->
              resources.path(
                  "/orders",
                  orders -> {
                    orders.get("/latest", (_, res) -> res.body("text/plain;charset=utf-8", PLAIN));
                    orders.get("/{id}", (req, res) -> res.text(req.pathParam("id").orElseThrow()));
                    orders.post(
                        "/{orderId}",
                        (req, res) -> res.text(req.pathParam("orderId").orElseThrow()));
                    orders.get("/fixed/details", (_, res) -> res.text("details"));
                    orders.get(
                        "/{id}/events", (req, res) -> res.text(req.pathParam("id").orElseThrow()));
                    orders.get(
                        "/{id}/items/{itemId}",
                        (req, res) ->
                            res.text(
                                req.pathParam("id").orElseThrow()
                                    + "/"
                                    + req.pathParam("itemId").orElseThrow()));
                    orders.get("/shadowed", (_, res) -> res.text("literal"));
                    orders.post("/latest", (_, res) -> res.text("latest"));
                  }));
    }
  }

  /** Echoes text and binary frames through Jetty's application listener API. */
  public static final class EchoListener implements Session.Listener.AutoDemanding {
    private @Nullable Session session;

    /**
     * Stores the open session for subsequent callbacks.
     *
     * @param opened accepted session
     */
    @Override
    public void onWebSocketOpen(Session opened) {
      session = opened;
    }

    /**
     * Echoes one assembled text message.
     *
     * @param message assembled text
     */
    @Override
    public void onWebSocketText(String message) {
      Objects.requireNonNull(session).sendText(message, Callback.NOOP);
    }

    /**
     * Echoes one borrowed binary message before completing its inbound callback.
     *
     * @param message assembled binary data
     * @param callback inbound completion
     */
    @Override
    public void onWebSocketBinary(ByteBuffer message, Callback callback) {
      Objects.requireNonNull(session)
          .sendBinary(message, Callback.from(callback::succeed, callback::fail));
    }
  }
}
