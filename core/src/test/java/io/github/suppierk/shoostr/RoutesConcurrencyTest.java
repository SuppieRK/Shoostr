package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
              workers.submit(
                  () -> {
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
              workers.submit(
                  () -> {
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
  void callbacksCanWaitForRegistrationsFromOtherThreads() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup2 = app::close;
          var workers = Executors.newVirtualThreadPerTaskExecutor()) {
        app.routes()
            .path(
                "/api",
                routes -> {
                  var task =
                      submitTaskForCallbacksCanWaitForRegistrationsFromOtherThreads(
                          workers, routes);

                  assertDoesNotThrow(() -> task.get(3, TimeUnit.SECONDS));

                  var startup =
                      workers.submit(() -> assertThrows(IllegalStateException.class, app::start));

                  assertDoesNotThrow(() -> startup.get(3, TimeUnit.SECONDS));
                });

        try (var test = TestServer.start(app)) {
          assertEquals(
              "ready",
              test.send(
                      request -> request.path("/api/nested").timeout(Duration.ofSeconds(3)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  private Future<Routes> submitTaskForCallbacksCanWaitForRegistrationsFromOtherThreads(
      ExecutorService workers, Routes routes) {
    return workers.submit(
        () ->
            routes.path(
                "/nested",
                nested -> nested.get((ignoredRequest, response) -> response.text("ready"))));
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
    return workers.submit(
        () ->
            app.routes(
                routes -> {
                  entered.countDown();
                  await(release);
                  routes.get("/ready", (ignoredRequest, response) -> response.text("ready"));
                }));
  }

  @Test
  void startupRejectsActiveCallbacksAndCloseDoesNotWaitForThem() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup4 = app::close;
          var workers = Executors.newVirtualThreadPerTaskExecutor()) {
        var callback =
            submitCallbackForStartupRejectsActiveCallbacksAndCloseDoesNotWaitForThem(
                workers, app, entered, release);

        try {
          assertTrue(entered.await(3, TimeUnit.SECONDS));
          assertThrows(IllegalStateException.class, app::start);
          assertThrows(IllegalStateException.class, app::port);

          workers
              .submit(
                  () -> {
                    app.close();

                    return null;
                  })
              .get(3, TimeUnit.SECONDS);
        } finally {
          release.countDown();
        }

        callback.get(3, TimeUnit.SECONDS);

        assertThrows(IllegalStateException.class, app::start);
      }
    }
  }

  private Future<Routes> submitCallbackForStartupRejectsActiveCallbacksAndCloseDoesNotWaitForThem(
      ExecutorService workers, Shoostr app, CountDownLatch entered, CountDownLatch release) {
    return workers.submit(
        () ->
            app.routes()
                .path(
                    "/api",
                    routes -> {
                      entered.countDown();
                      await(release);

                      assertThrows(
                          IllegalStateException.class,
                          () -> routes.get((ignoredRequest, ignoredResponse) -> {}));
                    }));
  }

  @Test
  void failedCallbacksDoNotHideOtherActiveCallbacksOrBlockLaterStartup() throws Exception {
    var entered = new CountDownLatch(2);
    var firstRelease = new CountDownLatch(1);
    var secondRelease = new CountDownLatch(1);

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup5 = app::close;
          var workers = Executors.newVirtualThreadPerTaskExecutor()) {
        var rootRoutes = app.routes();
        var first =
            submitFirstForFailedCallbacksDoNotHideOtherActiveCallbacksOrBlockLaterStartup(
                workers, rootRoutes, entered, firstRelease);
        var second =
            submitSecondForFailedCallbacksDoNotHideOtherActiveCallbacksOrBlockLaterStartup(
                workers, app, entered, secondRelease);

        try {
          assertTrue(entered.await(3, TimeUnit.SECONDS));

          firstRelease.countDown();
          first.get(3, TimeUnit.SECONDS);

          assertThrows(IllegalStateException.class, app::start);
        } finally {
          firstRelease.countDown();
          secondRelease.countDown();
        }

        second.get(3, TimeUnit.SECONDS);
        app.start();

        assertTrue(app.port() > 0);
      }
    }
  }

  private Future<IllegalArgumentException>
      submitFirstForFailedCallbacksDoNotHideOtherActiveCallbacksOrBlockLaterStartup(
          ExecutorService workers,
          Routes rootRoutes,
          CountDownLatch entered,
          CountDownLatch firstRelease) {
    return workers.submit(
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    rootRoutes.path(
                        "/first",
                        ignoredRoutes -> {
                          entered.countDown();
                          await(firstRelease);
                          throw new IllegalArgumentException("registration failed");
                        })));
  }

  private Future<Routes>
      submitSecondForFailedCallbacksDoNotHideOtherActiveCallbacksOrBlockLaterStartup(
          ExecutorService workers,
          Shoostr app,
          CountDownLatch entered,
          CountDownLatch secondRelease) {
    return workers.submit(
        () ->
            app.routes()
                .path(
                    "/second",
                    routes -> {
                      entered.countDown();
                      await(secondRelease);
                      routes.get((ignoredRequest, ignoredResponse) -> {});
                    }));
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
              workers.submit(
                  () -> {
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
        startup.get(5, TimeUnit.SECONDS);
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

  private Future<Shoostr> submitStartupForRacingStartupEitherIncludesOrRejectsEachRegistration(
      ExecutorService workers, CountDownLatch ready, Shoostr app) {
    return workers.submit(
        () -> {
          await(ready);

          return app.start();
        });
  }
}
