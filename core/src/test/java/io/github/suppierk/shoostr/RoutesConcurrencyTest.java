package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class RoutesConcurrencyTest {
  @Test
  void preservesRegistrationsAcrossRootAndSharedPathScopes() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        var client = HttpClient.newHttpClient()) {
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
                    scope.route("GET", prefix(index) + path, (req, res) -> res.text(path));
                  }
                }));
      }
      ready.countDown();
      for (var task : tasks) {
        task.get(5, TimeUnit.SECONDS);
      }
      app.start();
      for (int worker = 0; worker < 8; worker++) {
        for (int route = 0; route < 8; route++) {
          String path = worker + "/" + route;
          assertEquals(path, send(client, app, "/api/" + path).body());
        }
      }
    }
  }

  @Test
  void rejectsConcurrentDuplicateShapesAcrossDifferentScopes() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        var client = HttpClient.newHttpClient()) {
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
                            (req, res) -> res.text(Integer.toString(index)));
                    return true;
                  } catch (IllegalArgumentException _) {
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
      app.start();
      assertEquals(Integer.toString(winner), send(client, app, "/api/42").body());
    }
  }

  @Test
  void callbacksCanWaitForRegistrationsFromOtherThreads() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .path(
              "/api",
              routes -> {
                var task =
                    workers.submit(
                        () ->
                            routes.path(
                                "/nested", nested -> nested.get((req, res) -> res.text("ready"))));
                assertDoesNotThrow(() -> task.get(3, TimeUnit.SECONDS));
                var startup =
                    workers.submit(() -> assertThrows(IllegalStateException.class, app::start));
                assertDoesNotThrow(() -> startup.get(3, TimeUnit.SECONDS));
              });
      app.start();
      assertEquals("ready", send(client, app, "/api/nested").body());
    }
  }

  @Test
  void startupRejectsActiveCallbacksAndCloseDoesNotWaitForThem() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var callback =
          workers.submit(
              () ->
                  app.routes()
                      .path(
                          "/api",
                          routes -> {
                            entered.countDown();
                            await(release);
                            assertThrows(
                                IllegalStateException.class, () -> routes.get((req, res) -> {}));
                          }));

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

  @Test
  void failedCallbacksDoNotHideOtherActiveCallbacksOrBlockLaterStartup() throws Exception {
    var entered = new CountDownLatch(2);
    var firstRelease = new CountDownLatch(1);
    var secondRelease = new CountDownLatch(1);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          workers.submit(
              () ->
                  assertThrows(
                      IllegalArgumentException.class,
                      () ->
                          app.routes()
                              .path(
                                  "/first",
                                  routes -> {
                                    entered.countDown();
                                    await(firstRelease);
                                    throw new IllegalArgumentException("registration failed");
                                  })));
      var second =
          workers.submit(
              () ->
                  app.routes()
                      .path(
                          "/second",
                          routes -> {
                            entered.countDown();
                            await(secondRelease);
                            routes.get((req, res) -> {});
                          }));

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

  @RepeatedTest(5)
  void racingStartupEitherIncludesOrRejectsEachRegistration() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
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
                        .get(prefix(index) + index, (req, res) -> res.text("registered"));
                    return true;
                  } catch (IllegalStateException _) {
                    return false;
                  }
                }));
      }
      var startup =
          workers.submit(
              () -> {
                await(ready);
                return app.start();
              });
      ready.countDown();
      startup.get(5, TimeUnit.SECONDS);
      for (int index = 0; index < tasks.size(); index++) {
        boolean registered = tasks.get(index).get(3, TimeUnit.SECONDS);
        assertEquals(registered ? 200 : 404, send(client, app, "/api/" + index).statusCode());
      }
    }
  }

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

  private static HttpResponse<String> send(HttpClient client, Shoostr app, String path)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
            .timeout(Duration.ofSeconds(3))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static void await(CountDownLatch latch) {
    assertDoesNotThrow(
        () -> assertTrue(latch.await(5, TimeUnit.SECONDS), "Coordination timed out"));
  }
}
