package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class BorrowedExecutorTest {
  private static final Handler ENDPOINT =
      (_, response) -> response.setHeader("X-Worker", Thread.currentThread().getName()).text("ok");
  private static final Handler SSE =
      (_, response) -> {
        response.setHeader("X-Worker", Thread.currentThread().getName());
        response.startEventStream().send("ok");
      };

  @Test
  void runsSelectedHandlerOnTheSuppliedExecutorWithoutClosingIt() throws Exception {
    try (var workers =
        Executors.newSingleThreadExecutor(Thread.ofPlatform().name("selected").factory())) {
      try (var app = new Shoostr(Options.defaults().withPort(0))) {
        app.routes()
            .get(
                "/selected",
                workers,
                (_, response) -> response.text(Thread.currentThread().getName()));

        try (var test = TestServer.start(app)) {
          var response =
              test.send(request -> request.path("/selected"), HttpResponse.BodyHandlers.ofString());
          assertEquals("selected", response.body());
        }
      }

      assertFalse(workers.isShutdown());
      assertEquals("usable", workers.submit(() -> "usable").get(5, TimeUnit.SECONDS));
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("registrations")
  void runsEachEndpointOverloadOnItsSelectedExecutor(
      String name, String method, boolean configured, BiConsumer<Routes, ExecutorService> register)
      throws Exception {
    try (var workers =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().name("selected").factory());
        var app = new Shoostr(Options.defaults().withPort(0))) {
      register.accept(app.routes(), workers);

      try (var test = TestServer.start(app)) {
        var response = test.send(request -> request.path("/selected").method(method));
        assertEquals(200, response.statusCode(), name);
        assertEquals("selected", response.headers().firstValue("X-Worker").orElseThrow(), name);
        assertEquals(configured, response.headers().firstValue("X-Configured").isPresent(), name);
      }
    }
  }

  @Test
  void selectsDifferentExecutorsForDifferentMethodsAtTheSamePath() throws Exception {
    try (var reads =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().name("reads").factory());
        var writes =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().name("writes").factory());
        var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes().get("/same", reads, ENDPOINT).post("/same", writes, ENDPOINT);

      try (var test = TestServer.start(app)) {
        assertEquals(
            "reads",
            test.send(request -> request.path("/same"))
                .headers()
                .firstValue("X-Worker")
                .orElseThrow());
        assertEquals(
            "writes",
            test.send(request -> request.path("/same").method("POST"))
                .headers()
                .firstValue("X-Worker")
                .orElseThrow());
      }
    }
  }

  @Test
  void readsAndStreamsHttp2RequestBodiesOnSelectedWorkers() throws Exception {
    try (var workers = Executors.newSingleThreadExecutor();
        var app = new Shoostr(Options.defaults().withPort(0))) {
      app.http2();
      app.routes().get("/ready", workers, (_, response) -> response.text("ready"));
      app.routes()
          .post(
              "/echo",
              workers,
              (request, response) -> {
                try (var input = request.input()) {
                  response.startStream("application/octet-stream").write(input.readAllBytes());
                }
              });

      try (var test = TestServer.start(app, client -> client.version(HttpClient.Version.HTTP_2))) {
        assertEquals(
            HttpClient.Version.HTTP_2, test.send(request -> request.path("/ready")).version());
        var first =
            test.send(
                request ->
                    request
                        .path("/echo")
                        .method("POST")
                        .body("first".getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofString());
        var second =
            test.send(
                request ->
                    request
                        .path("/echo")
                        .method("POST")
                        .body("second".getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(HttpClient.Version.HTTP_2, first.version());
        assertEquals(HttpClient.Version.HTTP_2, second.version());
        assertEquals("first", first.body());
        assertEquals("second", second.body());
      }
    }
  }

  @Test
  void leavesDefaultEndpointsVirtualBesideSelectedEndpoints() throws Exception {
    try (var workers = Executors.newSingleThreadExecutor();
        var app = new Shoostr(Options.defaults().withPort(0))) {
      Handler endpoint =
          (_, response) -> response.text(Boolean.toString(Thread.currentThread().isVirtual()));
      app.routes().get("/default", endpoint).get("/selected", workers, endpoint);

      try (var test = TestServer.start(app)) {
        assertEquals(
            "true",
            test.send(request -> request.path("/default"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "false",
            test.send(request -> request.path("/selected"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void acceptsVirtualThreadExecutors() throws Exception {
    var owned = ConcurrentHashMap.<Thread>newKeySet();

    try (var workers =
            Executors.newThreadPerTaskExecutor(
                task -> {
                  var thread = Thread.ofVirtual().unstarted(task);
                  owned.add(thread);
                  return thread;
                });
        var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes()
          .get(
              "/selected",
              workers,
              (_, response) ->
                  response.text(Boolean.toString(owned.contains(Thread.currentThread()))));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "true",
            test.send(request -> request.path("/selected"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void rejectsNullExecutorBeforeConfiguringOrReservingTheEndpoint() throws Exception {
    var configured = new AtomicInteger();

    try (var workers = Executors.newSingleThreadExecutor();
        var app = new Shoostr()) {
      var routes = app.routes();
      assertThrows(
          NullPointerException.class,
          () ->
              routes.get(
                  "/selected",
                  (ExecutorService) null,
                  ENDPOINT,
                  _ -> configured.incrementAndGet()));
      assertEquals(0, configured.get());
      assertSame(app.routes(), app.routes().get("/selected", workers, ENDPOINT));
    }
  }

  @Test
  void rejectsShutdownExecutorBeforeConfiguringOrReservingTheEndpoint() throws Exception {
    var configured = new AtomicInteger();

    try (var workers = Executors.newSingleThreadExecutor();
        var app = new Shoostr()) {
      workers.shutdown();
      var routes = app.routes();
      assertThrows(
          IllegalArgumentException.class,
          () -> routes.get("/selected", workers, ENDPOINT, _ -> configured.incrementAndGet()));
      assertEquals(0, configured.get());
      app.routes().get("/selected", ENDPOINT);
    }
  }

  @ParameterizedTest
  @MethodSource("unsupportedPolicies")
  void rejectsInspectableFallbackAndDiscardPolicies(RejectedExecutionHandler policy)
      throws Exception {
    try (var workers =
            new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), policy);
        var app = new Shoostr()) {
      var routes = app.routes();
      assertThrows(
          IllegalArgumentException.class, () -> routes.get("/selected", workers, ENDPOINT));
      assertFalse(workers.isShutdown());
      app.routes().get("/selected", ENDPOINT);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void acceptsCustomThrowingRejectionPolicy(boolean overridesBuiltin) throws Exception {
    var policy = throwingPolicy(overridesBuiltin);

    try (var workers =
            new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), policy);
        var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes().get("/selected", workers, ENDPOINT);

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/selected")).statusCode());
      }

      assertFalse(workers.isShutdown());
    }
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void rendersCustomPolicyRejectionWithoutFallback(boolean wrapped, boolean overridesBuiltin)
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var invoked = new AtomicInteger();
    var policy = throwingPolicy(overridesBuiltin);
    var pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<>(), policy);
    pool.execute(
        () -> {
          entered.countDown();

          try {
            release.await();
          } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
          }
        });

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var workers = wrapped ? Executors.unconfigurableExecutorService(pool) : pool;
      app.exception(
          RejectedExecutionException.class,
          (failure, _, response) ->
              response.status(503).text(Objects.requireNonNull(failure.getMessage())));
      app.routes().get("/selected", workers, (_, _) -> invoked.incrementAndGet());

      try (var test = TestServer.start(app)) {
        var response =
            test.send(request -> request.path("/selected"), HttpResponse.BodyHandlers.ofString());
        assertEquals(503, response.statusCode());
        assertEquals("custom rejection", response.body());
        assertEquals(0, invoked.get());
      }

      assertFalse(pool.isShutdown());
    } finally {
      release.countDown();
      pool.close();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preservesBorrowedPoolWhenClosedBeforeStartOrAfterFailedStart(boolean failStart)
      throws Exception {
    try (var workers = Executors.newSingleThreadExecutor();
        var app = new Shoostr(Options.defaults().withPort(0))) {
      app.routes().get("/selected", workers, ENDPOINT);
      if (failStart) {
        app.modifyServer(
            _ -> {
              throw new IllegalStateException("startup failed");
            });
        assertThrows(IllegalStateException.class, app::start);
      }

      app.close();
      app.close();
      assertFalse(workers.isShutdown());
      assertEquals("usable", workers.submit(() -> "usable").get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void sharesOneExecutorAcrossAppsWithoutClosingTheOtherApp() throws Exception {
    try (var workers =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().name("shared").factory());
        var first = new Shoostr(Options.defaults().withPort(0));
        var second = new Shoostr(Options.defaults().withPort(0))) {
      first.routes().get("/selected", workers, ENDPOINT);
      second.routes().get("/selected", workers, ENDPOINT);

      try (var firstTest = TestServer.start(first);
          var secondTest = TestServer.start(second)) {
        assertEquals(200, firstTest.send(request -> request.path("/selected")).statusCode());
        first.close();
        assertEquals(200, secondTest.send(request -> request.path("/selected")).statusCode());
        assertEquals("usable", workers.submit(() -> "usable").get(5, TimeUnit.SECONDS));
        assertFalse(workers.isShutdown());
      }
    }
  }

  private static RejectedExecutionHandler throwingPolicy(boolean overridesBuiltin) {
    if (overridesBuiltin) {
      return new ThreadPoolExecutor.CallerRunsPolicy() {
        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
          throw new RejectedExecutionException("custom rejection");
        }
      };
    }

    return (_, _) -> {
      throw new RejectedExecutionException("custom rejection");
    };
  }

  private static Stream<RejectedExecutionHandler> unsupportedPolicies() {
    return Stream.of(
        new ThreadPoolExecutor.CallerRunsPolicy(),
        new ThreadPoolExecutor.DiscardPolicy(),
        new ThreadPoolExecutor.DiscardOldestPolicy());
  }

  private static Stream<Arguments> registrations() {
    return Stream.of(
        Arguments.of(
            "get.path.plain",
            "GET",
            false,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) -> routes.get("/selected", workers, ENDPOINT)),
        Arguments.of(
            "get.path.configured",
            "GET",
            true,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.get(
                        "/selected",
                        workers,
                        ENDPOINT,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Configured", "yes")))),
        Arguments.of(
            "get.pathless.plain",
            "GET",
            false,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.path("/selected", group -> group.get(workers, ENDPOINT))),
        Arguments.of(
            "get.pathless.configured",
            "GET",
            true,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.path(
                        "/selected",
                        group ->
                            group.get(
                                workers,
                                ENDPOINT,
                                extensions ->
                                    extensions.beforeRouteHandler(
                                        (_, response) ->
                                            response.setHeader("X-Configured", "yes"))))),
        Arguments.of(
            "post.path.plain",
            "POST",
            false,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) -> routes.post("/selected", workers, ENDPOINT)),
        Arguments.of(
            "post.path.configured",
            "POST",
            true,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.post(
                        "/selected",
                        workers,
                        ENDPOINT,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Configured", "yes")))),
        Arguments.of(
            "post.pathless.plain",
            "POST",
            false,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.path("/selected", group -> group.post(workers, ENDPOINT))),
        Arguments.of(
            "post.pathless.configured",
            "POST",
            true,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.path(
                        "/selected",
                        group ->
                            group.post(
                                workers,
                                ENDPOINT,
                                extensions ->
                                    extensions.beforeRouteHandler(
                                        (_, response) ->
                                            response.setHeader("X-Configured", "yes"))))),
        Arguments.of(
            "put.path.plain",
            "PUT",
            false,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) -> routes.put("/selected", workers, ENDPOINT)),
        Arguments.of(
            "put.path.configured",
            "PUT",
            true,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.put(
                        "/selected",
                        workers,
                        ENDPOINT,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Configured", "yes")))),
        Arguments.of(
            "put.pathless.plain",
            "PUT",
            false,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.path("/selected", group -> group.put(workers, ENDPOINT))),
        Arguments.of(
            "put.pathless.configured",
            "PUT",
            true,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.path(
                        "/selected",
                        group ->
                            group.put(
                                workers,
                                ENDPOINT,
                                extensions ->
                                    extensions.beforeRouteHandler(
                                        (_, response) ->
                                            response.setHeader("X-Configured", "yes"))))),
        Arguments.of(
            "patch.path.plain",
            "PATCH",
            false,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) -> routes.patch("/selected", workers, ENDPOINT)),
        Arguments.of(
            "patch.path.configured",
            "PATCH",
            true,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.patch(
                        "/selected",
                        workers,
                        ENDPOINT,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Configured", "yes")))),
        Arguments.of(
            "patch.pathless.plain",
            "PATCH",
            false,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.path("/selected", group -> group.patch(workers, ENDPOINT))),
        Arguments.of(
            "patch.pathless.configured",
            "PATCH",
            true,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.path(
                        "/selected",
                        group ->
                            group.patch(
                                workers,
                                ENDPOINT,
                                extensions ->
                                    extensions.beforeRouteHandler(
                                        (_, response) ->
                                            response.setHeader("X-Configured", "yes"))))),
        Arguments.of(
            "delete.path.plain",
            "DELETE",
            false,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) -> routes.delete("/selected", workers, ENDPOINT)),
        Arguments.of(
            "delete.path.configured",
            "DELETE",
            true,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.delete(
                        "/selected",
                        workers,
                        ENDPOINT,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Configured", "yes")))),
        Arguments.of(
            "delete.pathless.plain",
            "DELETE",
            false,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.path("/selected", group -> group.delete(workers, ENDPOINT))),
        Arguments.of(
            "delete.pathless.configured",
            "DELETE",
            true,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.path(
                        "/selected",
                        group ->
                            group.delete(
                                workers,
                                ENDPOINT,
                                extensions ->
                                    extensions.beforeRouteHandler(
                                        (_, response) ->
                                            response.setHeader("X-Configured", "yes"))))),
        Arguments.of(
            "head.path.plain",
            "HEAD",
            false,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) -> routes.head("/selected", workers, ENDPOINT)),
        Arguments.of(
            "head.path.configured",
            "HEAD",
            true,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.head(
                        "/selected",
                        workers,
                        ENDPOINT,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Configured", "yes")))),
        Arguments.of(
            "head.pathless.plain",
            "HEAD",
            false,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.path("/selected", group -> group.head(workers, ENDPOINT))),
        Arguments.of(
            "head.pathless.configured",
            "HEAD",
            true,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.path(
                        "/selected",
                        group ->
                            group.head(
                                workers,
                                ENDPOINT,
                                extensions ->
                                    extensions.beforeRouteHandler(
                                        (_, response) ->
                                            response.setHeader("X-Configured", "yes"))))),
        Arguments.of(
            "options.path.plain",
            "OPTIONS",
            false,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) -> routes.options("/selected", workers, ENDPOINT)),
        Arguments.of(
            "options.path.configured",
            "OPTIONS",
            true,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.options(
                        "/selected",
                        workers,
                        ENDPOINT,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Configured", "yes")))),
        Arguments.of(
            "options.pathless.plain",
            "OPTIONS",
            false,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.path("/selected", group -> group.options(workers, ENDPOINT))),
        Arguments.of(
            "options.pathless.configured",
            "OPTIONS",
            true,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.path(
                        "/selected",
                        group ->
                            group.options(
                                workers,
                                ENDPOINT,
                                extensions ->
                                    extensions.beforeRouteHandler(
                                        (_, response) ->
                                            response.setHeader("X-Configured", "yes"))))),
        Arguments.of(
            "sse.path.plain",
            "GET",
            false,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) -> routes.sse("/selected", workers, SSE)),
        Arguments.of(
            "sse.path.configured",
            "GET",
            true,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.sse(
                        "/selected",
                        workers,
                        SSE,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Configured", "yes")))),
        Arguments.of(
            "sse.pathless.plain",
            "GET",
            false,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) -> routes.path("/selected", group -> group.sse(workers, SSE))),
        Arguments.of(
            "sse.pathless.configured",
            "GET",
            true,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.path(
                        "/selected",
                        group ->
                            group.sse(
                                workers,
                                SSE,
                                extensions ->
                                    extensions.beforeRouteHandler(
                                        (_, response) ->
                                            response.setHeader("X-Configured", "yes"))))),
        Arguments.of(
            "enum.path.plain",
            "GET",
            false,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) -> routes.route(HttpMethods.GET, "/selected", workers, ENDPOINT)),
        Arguments.of(
            "enum.path.configured",
            "GET",
            true,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.route(
                        HttpMethods.GET,
                        "/selected",
                        workers,
                        ENDPOINT,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Configured", "yes")))),
        Arguments.of(
            "enum.pathless.plain",
            "GET",
            false,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.path(
                        "/selected", group -> group.route(HttpMethods.GET, workers, ENDPOINT))),
        Arguments.of(
            "enum.pathless.configured",
            "GET",
            true,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.path(
                        "/selected",
                        group ->
                            group.route(
                                HttpMethods.GET,
                                workers,
                                ENDPOINT,
                                extensions ->
                                    extensions.beforeRouteHandler(
                                        (_, response) ->
                                            response.setHeader("X-Configured", "yes"))))),
        Arguments.of(
            "string.path.plain",
            "GET",
            false,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) -> routes.route("GET", "/selected", workers, ENDPOINT)),
        Arguments.of(
            "string.path.configured",
            "GET",
            true,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.route(
                        "GET",
                        "/selected",
                        workers,
                        ENDPOINT,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Configured", "yes")))),
        Arguments.of(
            "string.pathless.plain",
            "GET",
            false,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.path("/selected", group -> group.route("GET", workers, ENDPOINT))),
        Arguments.of(
            "string.pathless.configured",
            "GET",
            true,
            (BiConsumer<Routes, ExecutorService>)
                (routes, workers) ->
                    routes.path(
                        "/selected",
                        group ->
                            group.route(
                                "GET",
                                workers,
                                ENDPOINT,
                                extensions ->
                                    extensions.beforeRouteHandler(
                                        (_, response) ->
                                            response.setHeader("X-Configured", "yes"))))));
  }
}
