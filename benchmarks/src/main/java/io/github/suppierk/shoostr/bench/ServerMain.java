package io.github.suppierk.shoostr.bench;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.Routes;
import io.github.suppierk.shoostr.Shoostr;
import io.javalin.Javalin;
import io.javalin.apibuilder.ApiBuilder;
import io.javalin.config.RoutesConfig;
import io.javalin.http.HandlerType;
import io.javalin.http.sse.SseClient;
import io.jooby.ExecutionMode;
import io.jooby.Jooby;
import io.jooby.ServerOptions;
import io.jooby.ServerSentEmitter;
import io.jooby.StatusCode;
import io.jooby.jetty.JettyServer;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.jspecify.annotations.Nullable;

/** Same payloads, Java, HTTP engine and virtual-thread execution; no annotation-based routing. */
public final class ServerMain {
  private static final byte[] PLAIN = "Hello, World!".getBytes(StandardCharsets.UTF_8);
  private static final byte[] JSON =
      "{\"message\":\"Hello, World!\"}".getBytes(StandardCharsets.UTF_8);
  private static final byte[] CHUNK = ("x".repeat(1023) + "\n").getBytes(StandardCharsets.UTF_8);
  private static final int EVENT_COUNT = 16;
  private static final String EVENT_PAYLOAD = "x".repeat(128);
  private static final String LARGE_EVENT_PAYLOAD = "x".repeat(65536);

  /**
   * Starts one benchmark server until process termination.
   *
   * @param args implementation (candidate, jooby, or javalin), optional port, and optional route
   *     groups
   * @throws Exception if startup fails or the main thread is interrupted
   * @throws IllegalArgumentException if the implementation is unknown
   */
  static void main(String[] args) throws Exception {
    var implementation = args.length > 0 ? args[0] : "candidate";
    int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
    int routeGroups = args.length > 2 ? Integer.parseInt(args[2]) : 0;
    if (routeGroups < 0) {
      throw new IllegalArgumentException("Route groups require a nonnegative count");
    }

    AutoCloseable close;
    if ("candidate".equals(implementation)) {
      var app = new Shoostr(Options.defaults().withPort(port));
      app.routes().get("/plaintext", (req, res) -> res.body("text/plain;charset=utf-8", PLAIN));
      app.routes().get("/json-bytes", (req, res) -> res.body("application/json", JSON));
      app.routes()
          .post("/echo", (req, res) -> res.body("application/octet-stream", req.bodyBytes()));
      app.routes()
          .get(
              "/stream",
              (req, res) -> {
                var stream = res.startStream("application/octet-stream");
                for (int i = 0; i < 16; i++) {
                  stream.write(CHUNK);
                  stream.flush();
                }
              });
      app.routes()
          .get(
              "/virtual",
              (req, res) -> res.text(Boolean.toString(Thread.currentThread().isVirtual())));
      app.routes().sse("/sse-burst", (req, res) -> sendCandidateEvents(res, 0, EVENT_PAYLOAD));
      app.routes().sse("/sse-paced", (req, res) -> sendCandidateEvents(res, 20, EVENT_PAYLOAD));
      app.routes().sse("/sse-slow", (req, res) -> sendCandidateEvents(res, 0, LARGE_EVENT_PAYLOAD));
      app.routes().websocket("/ws-text", (req, upgrade) -> new EchoListener());
      app.routes().websocket("/ws-binary", (req, upgrade) -> new EchoListener());
      registerRouteGroups(app.routes(), routeGroups);
      app.start();
      close = null;
    } else if ("jooby".equals(implementation)) {
      var virtual = Executors.newVirtualThreadPerTaskExecutor();
      var pool = new QueuedThreadPool();
      pool.setReservedThreads(0);
      pool.setVirtualThreadsExecutor(virtual);
      var options =
          new ServerOptions()
              .setHost("127.0.0.1")
              .setPort(port)
              .setHttp2(false)
              // Use Jetty's selector-count default, as the candidate connector does.
              .setIoThreads(-1)
              .setMaxRequestSize(Options.defaults().maxRequestBytes())
              .setDefaultHeaders(false);
      var server = new JettyServer(options, pool);
      var httpDefaults = new HttpConfiguration();
      // Match the candidate's HTTP/1.1 defaults without enabling Jooby's Server header.
      server.configure(
          http -> {
            http.setSendDateHeader(true);
            http.setUriCompliance(httpDefaults.getUriCompliance());
            http.setOutputBufferSize(httpDefaults.getOutputBufferSize());
            http.setOutputAggregationSize(httpDefaults.getOutputAggregationSize());
            http.setRequestHeaderSize(httpDefaults.getRequestHeaderSize());
          });
      Jooby.runApp(
          new String[0],
          server,
          ExecutionMode.WORKER,
          app -> {
            app.get(
                "/plaintext", ctx -> ctx.setResponseType("text/plain;charset=utf-8").send(PLAIN));
            app.get("/json-bytes", ctx -> ctx.setResponseType("application/json").send(JSON));
            app.post(
                "/echo",
                ctx -> ctx.setResponseType("application/octet-stream").send(ctx.body().bytes()));
            app.get(
                "/stream",
                ctx -> {
                  ctx.setResponseType("application/octet-stream");
                  return ctx.responseStream(
                      output -> {
                        output.flush();
                        for (int i = 0; i < 16; i++) {
                          output.write(CHUNK);
                          output.flush();
                        }
                      });
                });
            app.get("/virtual", ctx -> Boolean.toString(Thread.currentThread().isVirtual()));
            app.sse("/sse-burst", sse -> sendJoobyEvents(sse, 0, EVENT_PAYLOAD));
            app.sse("/sse-paced", sse -> sendJoobyEvents(sse, 20, EVENT_PAYLOAD));
            app.sse("/sse-slow", sse -> sendJoobyEvents(sse, 0, LARGE_EVENT_PAYLOAD));
            app.ws(
                "/ws-text",
                (ctx, configurer) ->
                    configurer.onMessage((socket, message) -> socket.send(message.value())));
            app.ws(
                "/ws-binary",
                (ctx, configurer) ->
                    configurer.onMessage((socket, message) -> socket.sendBinary(message.bytes())));
            registerJoobyRouteGroups(app, routeGroups);
          });
      close =
          () -> {
            try {
              server.stop();
            } finally {
              virtual.close();
            }
          };
    } else if ("javalin".equals(implementation)) {
      var app =
          Javalin.create(
                  config -> {
                    config.jetty.host = "127.0.0.1";
                    config.jetty.port = port;
                    config.concurrency.useVirtualThreads = true;
                    config.jetty.modifyHttpConfiguration(http -> http.setSendDateHeader(true));
                    config.routes.get(
                        "/plaintext",
                        ctx -> ctx.contentType("text/plain;charset=utf-8").result(PLAIN));
                    config.routes.get(
                        "/json-bytes", ctx -> ctx.contentType("application/json").result(JSON));
                    config.routes.post(
                        "/echo",
                        ctx ->
                            ctx.contentType("application/octet-stream").result(ctx.bodyAsBytes()));
                    config.routes.get(
                        "/stream",
                        ctx -> {
                          ctx.contentType("application/octet-stream");
                          var output = ctx.outputStream();
                          ctx.res().flushBuffer();
                          for (int i = 0; i < 16; i++) {
                            output.write(CHUNK);
                            output.flush();
                          }
                        });
                    config.routes.get(
                        "/virtual",
                        ctx -> ctx.result(Boolean.toString(Thread.currentThread().isVirtual())));
                    config.routes.sse(
                        "/sse-burst", client -> sendJavalinEvents(client, 0, EVENT_PAYLOAD));
                    config.routes.sse(
                        "/sse-paced", client -> sendJavalinEvents(client, 20, EVENT_PAYLOAD));
                    config.routes.sse(
                        "/sse-slow", client -> sendJavalinEvents(client, 0, LARGE_EVENT_PAYLOAD));
                    config.routes.ws(
                        "/ws-text", ws -> ws.onMessage(ctx -> ctx.send(ctx.message())));
                    config.routes.ws(
                        "/ws-binary", ws -> ws.onBinaryMessage(ctx -> ctx.send(ctx.data())));
                    registerJavalinRouteGroups(config.routes, routeGroups);
                  })
              .start();
      close = app::stop;
    } else {
      throw new IllegalArgumentException("Expected candidate, jooby, or javalin");
    }

    if (close != null) {
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      close.close();
                    } catch (Exception failure) {
                      System.getLogger(ServerMain.class.getName())
                          .log(System.Logger.Level.ERROR, "Benchmark shutdown failed", failure);
                    }
                  }));
    }

    System.out.println("READY " + implementation + " " + port);
    new CountDownLatch(1).await();
  }

  /**
   * Sends the same event sequence from the candidate for burst and paced workloads.
   *
   * @param response candidate response
   * @param pauseMillis delay between events
   * @param payload data repeated within each indexed event
   * @throws Exception if writing or waiting fails
   */
  private static void sendCandidateEvents(Response response, int pauseMillis, String payload)
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
   * Sends the same event sequence from Jooby and closes its independently owned emitter.
   *
   * @param emitter Jooby event emitter
   * @param pauseMillis delay between events
   * @param payload data repeated within each indexed event
   * @throws Exception if writing or waiting fails
   */
  private static void sendJoobyEvents(ServerSentEmitter emitter, int pauseMillis, String payload)
      throws Exception {
    try {
      for (int index = 0; index < EVENT_COUNT; index++) {
        emitter.send(index + ":" + payload);
        if (pauseMillis > 0) {
          Thread.sleep(pauseMillis);
        }
      }
    } finally {
      emitter.close();
    }
  }

  /**
   * Sends raw UTF-8 SSE data through Javalin without JSON serialization.
   *
   * @param client Javalin SSE client
   * @param pauseMillis delay between events
   * @param payload data repeated within each indexed event
   * @throws IllegalStateException if the sender is interrupted
   */
  private static void sendJavalinEvents(SseClient client, int pauseMillis, String payload) {
    try {
      for (int index = 0; index < EVENT_COUNT; index++) {
        var data =
            new ByteArrayInputStream((index + ":" + payload).getBytes(StandardCharsets.UTF_8));
        client.sendData(data);
        if (pauseMillis > 0) {
          Thread.sleep(pauseMillis);
        }
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while sending SSE events", failure);
    } finally {
      client.close();
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
     * Echoes one text message after Jetty has assembled it.
     *
     * @param message assembled text
     */
    @Override
    public void onWebSocketText(String message) {
      Objects.requireNonNull(session).sendText(message, Callback.NOOP);
    }

    /**
     * Echoes one binary message and completes the inbound callback.
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

  /**
   * Registers the Jooby counterpart of the composed-route workload with matching response payloads.
   *
   * @param app Jooby fixture application
   * @param groups number of resource groups, each with eight endpoints
   */
  private static void registerJoobyRouteGroups(Jooby app, int groups) {
    if (groups == 0) {
      return;
    }

    app.error(
        StatusCode.NOT_FOUND,
        (ctx, cause, code) ->
            ctx.setResponseCode(code)
                .setResponseType("text/plain; charset=utf-8")
                .send("Not found"));
    app.error(
        StatusCode.METHOD_NOT_ALLOWED,
        (ctx, cause, code) ->
            ctx.setResponseCode(code)
                .setResponseType("text/plain; charset=utf-8")
                .send("Method not allowed"));
    for (int group = 0; group < groups; group++) {
      app.path(
          "/api/resources/" + group,
          () ->
              app.path(
                  "/orders",
                  () -> {
                    app.get(
                        "/latest",
                        ctx -> ctx.setResponseType("text/plain;charset=utf-8").send(PLAIN));
                    app.get(
                        "/{id}",
                        ctx ->
                            ctx.setResponseType("text/plain; charset=utf-8")
                                .send(ctx.path("id").value()));
                    app.post("/{orderId}", ctx -> ctx.path("orderId").value());
                    app.get("/fixed/details", ctx -> "details");
                    app.get("/{id}/events", ctx -> ctx.path("id").value());
                    app.get(
                        "/{id}/items/{itemId}",
                        ctx -> ctx.path("id").value() + "/" + ctx.path("itemId").value());
                    app.get("/shadowed", ctx -> "literal");
                    app.post("/latest", ctx -> "latest");
                  }));
    }
  }

  /**
   * Registers Javalin's composed-route counterpart with matching response payloads.
   *
   * @param routes Javalin route configuration
   * @param groups number of resource groups, each with eight endpoints
   */
  private static void registerJavalinRouteGroups(RoutesConfig routes, int groups) {
    if (groups == 0) {
      return;
    }

    routes.error(
        404,
        ctx -> {
          if (HandlerType.DELETE.equals(ctx.method())
              && ctx.path().startsWith("/api/resources/")
              && ctx.path().endsWith("/orders/latest")) {
            ctx.status(405)
                .header("Allow", "GET, POST")
                .contentType("text/plain; charset=utf-8")
                .result("Method not allowed");
            return;
          }

          ctx.contentType("text/plain; charset=utf-8").result("Not found");
        });
    routes.error(
        405, ctx -> ctx.contentType("text/plain; charset=utf-8").result("Method not allowed"));
    routes.apiBuilder(
        () -> {
          for (int group = 0; group < groups; group++) {
            ApiBuilder.path(
                "/api/resources/" + group,
                () ->
                    ApiBuilder.path(
                        "/orders",
                        () -> {
                          ApiBuilder.get(
                              "/latest",
                              ctx -> ctx.contentType("text/plain;charset=utf-8").result(PLAIN));
                          ApiBuilder.get(
                              "/{id}",
                              ctx ->
                                  ctx.contentType("text/plain; charset=utf-8")
                                      .result(ctx.pathParam("id")));
                          ApiBuilder.post(
                              "/{orderId}", ctx -> ctx.result(ctx.pathParam("orderId")));
                          ApiBuilder.get("/fixed/details", ctx -> ctx.result("details"));
                          ApiBuilder.get("/{id}/events", ctx -> ctx.result(ctx.pathParam("id")));
                          ApiBuilder.get(
                              "/{id}/items/{itemId}",
                              ctx ->
                                  ctx.result(ctx.pathParam("id") + "/" + ctx.pathParam("itemId")));
                          ApiBuilder.get("/shadowed", ctx -> ctx.result("literal"));
                          ApiBuilder.post("/latest", ctx -> ctx.result("latest"));
                        }));
          }
        });
  }

  /**
   * Registers the candidate composed-route workload using the same endpoint shapes and payloads as
   * Jooby.
   *
   * @param routes root registration scope
   * @param groups number of resource groups, each with eight endpoints
   */
  private static void registerRouteGroups(Routes routes, int groups) {
    for (int group = 0; group < groups; group++) {
      routes.path(
          "/api/resources/" + group,
          resources ->
              resources.path(
                  "/orders",
                  orders -> {
                    orders.get(
                        "/latest", (req, res) -> res.body("text/plain;charset=utf-8", PLAIN));
                    orders.get("/{id}", (req, res) -> res.text(req.pathParam("id")));
                    orders.post("/{orderId}", (req, res) -> res.text(req.pathParam("orderId")));
                    orders.get("/fixed/details", (req, res) -> res.text("details"));
                    orders.get("/{id}/events", (req, res) -> res.text(req.pathParam("id")));
                    orders.get(
                        "/{id}/items/{itemId}",
                        (req, res) ->
                            res.text(req.pathParam("id") + "/" + req.pathParam("itemId")));
                    orders.get("/shadowed", (req, res) -> res.text("literal"));
                    orders.post("/latest", (req, res) -> res.text("latest"));
                  }));
    }
  }
}
