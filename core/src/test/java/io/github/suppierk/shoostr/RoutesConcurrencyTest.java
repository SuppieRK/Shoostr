package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class RoutesConcurrencyTest {
  private static List<Routes> scopes(Shoostr app) {
    var scopes = new ArrayList<Routes>();
    scopes.add(app.routes());
    app.routes().path("/api", scopes::add);
    app.routes().path("/api", scopes::add);

    return scopes;
  }

  private static String prefix(int index) {
    return index % 3 == 0 ? "/api/" : "/";
  }

  private static void await(CountDownLatch latch) {
    assertDoesNotThrow(
        () -> assertTrue(latch.await(5, TimeUnit.SECONDS), "Coordination timed out"));
  }

  @Test
  void preservesWireMethodValidationPrecedenceAfterRegistrationCloses() throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close;
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var routes = app.routes();
      app.close();
      Handler handler = (request, response) -> {};

      assertThrows(
          IllegalStateException.class, () -> routes.route("INVALID", "/selected", handler));
      assertThrows(
          IllegalStateException.class, () -> routes.route((String) null, "/selected", handler));
      assertThrows(
          NullPointerException.class,
          () -> routes.route((String) null, "/selected", handler, ignored -> {}));
      assertEquals(
          "Unrecognized HTTP method: INVALID",
          assertThrows(
                  IllegalArgumentException.class,
                  () -> routes.route("INVALID", "/selected", handler, ignored -> {}))
              .getMessage());
      assertThrows(
          IllegalArgumentException.class,
          () -> routes.route("INVALID", "/selected", executor, handler));
      assertThrows(
          IllegalArgumentException.class, () -> routes.route("INVALID", executor, handler));
      assertThrows(
          IllegalStateException.class, () -> routes.route("GET", "/selected", executor, handler));
    }
  }

  @Test
  void preservesRegistrationsAcrossRootAndSharedPathScopes() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close;
          var workers = Executors.newVirtualThreadPerTaskExecutor()) {
        var scopes = scopes(app);
        var ready = new CountDownLatch(1);
        var tasks = new ArrayList<Future<?>>();
        for (int worker = 0; worker < 8; worker++) {
          int index = worker;
          tasks.add(
              workers.submit(() -> {
                await(ready);
                for (int route = 0; route < 8; route++) {
                  String path = index + "/" + route;
                  var scope = scopes.get(index % scopes.size());
                  scope.route(
                      "GET", prefix(index) + path, (ignored, response) -> response.text(path));
                }
              }));
        }
        ready.countDown();
        for (var task : tasks) {
          task.get(5, TimeUnit.SECONDS);
        }

        try (var test = TestServer.start(app)) {
          for (int worker = 0; worker < 8; worker++) {
            for (int route = 0; route < 8; route++) {
              String path = worker + "/" + route;

              assertEquals(
                  path,
                  test.send(
                          request -> request.path("/api/" + path).timeout(Duration.ofSeconds(3)),
                          HttpResponse.BodyHandlers.ofString())
                      .body());
            }
          }
        }
      }
    }
  }

  @Test
  void rejectsConcurrentDuplicateShapesAcrossDifferentScopes() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close;
          var workers = Executors.newVirtualThreadPerTaskExecutor()) {
        var scopes = scopes(app);
        var ready = new CountDownLatch(1);
        var tasks = new ArrayList<Future<Boolean>>();
        for (int worker = 0; worker < 24; worker++) {
          int index = worker;
          tasks.add(
              workers.submit(() -> {
                await(ready);

                try {
                  scopes
                      .get(index % scopes.size())
                      .get(
                          prefix(index) + "{id" + index + "}",
                          (ignoredRequest, response) -> response.text(Integer.toString(index)));

                  return true;
                } catch (IllegalArgumentException ignoredFailure) {
                  return false;
                }
              }));
        }
        ready.countDown();
        int winners = 0;
        int winner = -1;
        for (int index = 0; index < tasks.size(); index++) {
          if (tasks.get(index).get(5, TimeUnit.SECONDS)) {
            winners++;
            winner = index;
          }
        }

        assertEquals(1, winners);

        try (var test = TestServer.start(app)) {
          assertEquals(
              Integer.toString(winner),
              test.send(
                      request -> request.path("/api/42").timeout(Duration.ofSeconds(3)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  @Test
  void serializesCallbacksAcrossTheRootAndSharedScopes() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var attempted = new CountDownLatch(1);
    var app = new Shoostr();

    try (Closeable appCleanup = app::close;
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          submitCallbackForRootCallbackRejectsStartupUntilItFinishes(
              workers, app, entered, release);
      await(entered);
      var second = submitOtherScopeRegistration(workers, app, attempted);

      try {
        await(attempted);
        assertThrows(TimeoutException.class, () -> second.get(150, TimeUnit.MILLISECONDS));
        assertThrows(IllegalStateException.class, app::start);
      } finally {
        release.countDown();
      }

      first.get(3, TimeUnit.SECONDS);
      second.get(3, TimeUnit.SECONDS);

      try (var test = TestServer.start(app)) {
        assertEquals(
            "ready",
            test.send(request -> request.path("/ready"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "other",
            test.send(request -> request.path("/other"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void rootCallbackRejectsStartupUntilItFinishes() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);

    {
      var app = new Shoostr();

      try (Closeable appCleanup3 = app::close;
          var workers = Executors.newVirtualThreadPerTaskExecutor()) {
        var callback =
            submitCallbackForRootCallbackRejectsStartupUntilItFinishes(
                workers, app, entered, release);

        try {
          assertTrue(entered.await(3, TimeUnit.SECONDS));
          assertThrows(IllegalStateException.class, app::start);
        } finally {
          release.countDown();
        }

        assertEquals(app, callback.get(3, TimeUnit.SECONDS));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "ready",
              test.send(
                      request -> request.path("/ready").timeout(Duration.ofSeconds(3)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  private Future<Shoostr> submitCallbackForRootCallbackRejectsStartupUntilItFinishes(
      ExecutorService workers, Shoostr app, CountDownLatch entered, CountDownLatch release) {
    return workers.submit(() -> {
      var caller = Thread.currentThread();
      return app.routes(routes -> {
        assertSame(caller, Thread.currentThread());
        entered.countDown();
        await(release);
        routes.get("/ready", (request, response) -> response.text("ready"));
      });
    });
  }

  @Test
  void closeWaitsForRegistrationCallbackWithoutBlockingItsExtensionSetup() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var closing = new CountDownLatch(1);
    var app = new Shoostr();

    try (Closeable appCleanup = app::close;
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var callback = submitCallbackForCloseWaitsForExtensionSetup(workers, app, entered, release);
      await(entered);
      var close = submitClose(workers, app, closing);

      try {
        await(closing);
        assertThrows(TimeoutException.class, () -> close.get(150, TimeUnit.MILLISECONDS));
      } finally {
        release.countDown();
      }

      callback.get(3, TimeUnit.SECONDS);
      close.get(3, TimeUnit.SECONDS);
      assertThrows(IllegalStateException.class, app::start);
    }
  }

  private Future<Routes> submitCallbackForCloseWaitsForExtensionSetup(
      ExecutorService workers, Shoostr app, CountDownLatch entered, CountDownLatch release) {
    return workers.submit(() ->
        app.routes()
            .path(
                "/api",
                routes -> {
                  entered.countDown();
                  await(release);
                  var cors = new Cors(CorsPolicy.builder().origins("https://example.com").build());
                  routes.get((request, response) -> {}, lifecycle -> lifecycle.withExtension(cors));
                }));
  }

  @Test
  void releasesTheSharedLockAfterRegistrationCallbackFails() throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close;
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var failure = submitFailedRegistration(workers, app);
      assertEquals("registration failed", failure.get(3, TimeUnit.SECONDS).getMessage());
      var next = submitNextRegistration(workers, app);
      next.get(3, TimeUnit.SECONDS);

      try (var test = TestServer.start(app)) {
        assertEquals(
            "retained",
            test.send(request -> request.path("/retained"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "next",
            test.send(request -> request.path("/next"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @RepeatedTest(5)
  void racingStartupEitherIncludesOrRejectsEachRegistration() throws Exception {
    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup6 = app::close;
          var workers = Executors.newVirtualThreadPerTaskExecutor();
          var client = HttpClient.newHttpClient()) {
        var scopes = scopes(app);
        var ready = new CountDownLatch(1);
        var tasks = new ArrayList<Future<Boolean>>();
        for (int worker = 0; worker < 16; worker++) {
          int index = worker;
          tasks.add(
              workers.submit(() -> {
                await(ready);

                try {
                  scopes
                      .get(index % scopes.size())
                      .get(
                          prefix(index) + index,
                          (ignoredRequest, response) -> response.text("registered"));

                  return true;
                } catch (IllegalStateException ignoredFailure) {
                  return false;
                }
              }));
        }
        var startup =
            submitStartupForRacingStartupEitherIncludesOrRejectsEachRegistration(
                workers, ready, app);
        ready.countDown();
        boolean started = startup.get(5, TimeUnit.SECONDS);
        for (var task : tasks) {
          task.get(3, TimeUnit.SECONDS);
        }
        if (!started) {
          app.start();
        }

        for (int index = 0; index < tasks.size(); index++) {
          boolean registered = tasks.get(index).get(3, TimeUnit.SECONDS);

          assertEquals(
              registered ? 200 : 404,
              client
                  .send(
                      HttpRequest.newBuilder(
                              URI.create("http://127.0.0.1:" + app.port() + "/api/" + index))
                          .timeout(Duration.ofSeconds(3))
                          .build(),
                      HttpResponse.BodyHandlers.ofString())
                  .statusCode());
        }
      }
    }
  }

  private Future<Boolean> submitStartupForRacingStartupEitherIncludesOrRejectsEachRegistration(
      ExecutorService workers, CountDownLatch ready, Shoostr app) {
    return workers.submit(() -> {
      await(ready);

      try {
        app.start();
        return true;
      } catch (IllegalStateException registrationInProgress) {
        return false;
      }
    });
  }

  @Test
  void concurrentCloseAllowsStartupFailureCleanupToFinish() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var closing = new CountDownLatch(1);
    var app = startupFailureApplication(entered, release);

    try (Closeable appCleanup = app::close;
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var startup = workers.submit(() -> assertThrows(IllegalStateException.class, app::start));
      await(entered);
      var close = submitClose(workers, app, closing);

      try {
        await(closing);
        assertThrows(TimeoutException.class, () -> close.get(150, TimeUnit.MILLISECONDS));
      } finally {
        release.countDown();
      }

      assertEquals("startup failed", startup.get(3, TimeUnit.SECONDS).getMessage());
      close.get(3, TimeUnit.SECONDS);
      assertThrows(IllegalStateException.class, app::start);
    }
  }

  private Shoostr startupFailureApplication(CountDownLatch entered, CountDownLatch release) {
    return new Shoostr()
        .modifyServer(server -> {
          entered.countDown();
          await(release);
          throw new IllegalStateException("startup failed");
        });
  }

  private Future<Routes> submitOtherScopeRegistration(
      ExecutorService workers, Shoostr app, CountDownLatch attempted) {
    return workers.submit(() -> {
      attempted.countDown();
      return app.routes()
          .path("/other", routes -> routes.get((request, response) -> response.text("other")));
    });
  }

  private Future<?> submitClose(ExecutorService workers, Shoostr app, CountDownLatch closing) {
    return workers.submit(() -> {
      closing.countDown();
      app.close();
      return null;
    });
  }

  private Future<IllegalArgumentException> submitFailedRegistration(
      ExecutorService workers, Shoostr app) {
    return workers.submit(() ->
        assertThrows(
            IllegalArgumentException.class,
            () ->
                app.routes(routes -> {
                  routes.get("/retained", (request, response) -> response.text("retained"));
                  throw new IllegalArgumentException("registration failed");
                })));
  }

  private Future<Shoostr> submitNextRegistration(ExecutorService workers, Shoostr app) {
    return workers.submit(() ->
        app.routes(routes -> routes.get("/next", (request, response) -> response.text("next"))));
  }
}
