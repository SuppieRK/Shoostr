package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(20)
class NativeExecutionTest {
  private static Shoostr application(AtomicReference<ExecutorService> owned) {
    var configuredOptions = new Options();
    configuredOptions.setVirtualThreads(true);

    return new Shoostr(configuredOptions)
        .modifyServer(server ->
            owned.set(
                (ExecutorService)
                    ((QueuedThreadPool) server.getThreadPool()).getVirtualThreadsExecutor()))
        .routes(routes ->
            routes.get(
                "/thread",
                (request, response) ->
                    response.text("virtual=" + Thread.currentThread().isVirtual())));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void runsHandlersOnTheSelectedNativeExecutionMode(boolean virtual) throws Exception {
    var owned = new AtomicReference<ExecutorService>();

    var configuredOptions = new Options();
    configuredOptions.setVirtualThreads(virtual);

    {
      var app = new Shoostr(configuredOptions);

      try (Closeable appCleanup = app::close) {

        app.modifyServer(server -> {
          var pool = assertInstanceOf(QueuedThreadPool.class, server.getThreadPool());

          pool.setMaxThreads(64);
          if (virtual) {
            owned.set(assertInstanceOf(ExecutorService.class, pool.getVirtualThreadsExecutor()));
          } else {
            assertNull(pool.getVirtualThreadsExecutor());
          }
        });
        app.routes()
            .get(
                "/thread",
                (request, response) ->
                    response.text("virtual=" + Thread.currentThread().isVirtual()));

        try (var test = TestServer.start(app)) {
          var result =
              test.send(request -> request.path("/thread"), HttpResponse.BodyHandlers.ofString());

          assertEquals(200, result.statusCode());
          assertEquals("virtual=" + virtual, result.body());
        }
      }
    }

    if (virtual) {
      assertTrue(owned.get().isShutdown());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nativeHooksOverrideVirtualOptInAndKeepReplacementExecutorsBorrowed(boolean clear)
      throws Exception {
    var owned = new AtomicReference<ExecutorService>();
    var nativePool = new AtomicReference<QueuedThreadPool>();

    try (var borrowed = Executors.newVirtualThreadPerTaskExecutor()) {
      var configuredOptions = new Options();
      configuredOptions.setVirtualThreads(true);

      {
        var app =
            createAppForNativeHooksOverrideVirtualOptInAndKeepReplacementExecutorsBorrowed(
                configuredOptions, nativePool, owned, clear, borrowed);

        try (Closeable appCleanup1 = app::close) {
          app.routes()
              .get(
                  "/thread",
                  (request, response) ->
                      response.text("virtual=" + Thread.currentThread().isVirtual()));

          try (var test = TestServer.start(app)) {
            var result =
                test.send(request -> request.path("/thread"), HttpResponse.BodyHandlers.ofString());

            assertEquals(200, result.statusCode());
            assertEquals(clear ? "virtual=false" : "virtual=true", result.body());
          }
        }
      }

      assertTrue(owned.get().isShutdown());

      if (clear) {
        assertNull(nativePool.get().getVirtualThreadsExecutor());
      } else {
        assertSame(borrowed, nativePool.get().getVirtualThreadsExecutor());
      }

      assertFalse(borrowed.isShutdown());
      assertEquals("available", borrowed.submit(() -> "available").get(3, TimeUnit.SECONDS));
    }
  }

  private Shoostr createAppForNativeHooksOverrideVirtualOptInAndKeepReplacementExecutorsBorrowed(
      Options configuredOptions,
      AtomicReference<QueuedThreadPool> nativePool,
      AtomicReference<ExecutorService> owned,
      boolean clear,
      ExecutorService borrowed) {
    return new Shoostr(configuredOptions)
        .modifyServer(server -> {
          var pool = (QueuedThreadPool) server.getThreadPool();
          nativePool.set(pool);
          owned.set(assertInstanceOf(ExecutorService.class, pool.getVirtualThreadsExecutor()));
          pool.setVirtualThreadsExecutor(clear ? null : borrowed);
        });
  }

  @Test
  void failedNativeConfigurationClosesOnlyTheFrameworkCreatedExecutor() throws Exception {
    var owned = new AtomicReference<ExecutorService>();

    var configuredOptions = new Options();
    configuredOptions.setVirtualThreads(true);

    try (var borrowed = Executors.newVirtualThreadPerTaskExecutor()) {
      var app = new Shoostr(configuredOptions);

      try (Closeable appCleanup2 = app::close) {
        app.modifyServer(server -> {
          var pool = (QueuedThreadPool) server.getThreadPool();
          owned.set(assertInstanceOf(ExecutorService.class, pool.getVirtualThreadsExecutor()));
          pool.setVirtualThreadsExecutor(borrowed);
          throw new IllegalStateException("configuration failed");
        });

        var failure = assertThrows(IllegalStateException.class, app::start);
        assertEquals("configuration failed", failure.getMessage());
        assertTrue(owned.get().isShutdown());
        assertFalse(borrowed.isShutdown());
        assertEquals("available", borrowed.submit(() -> "available").get(3, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, app::start);
      }
    }
  }

  @Test
  void closingOneApplicationLeavesAnotherApplicationUsingItsOwnVirtualExecutor() throws Exception {
    var firstOwned = new AtomicReference<ExecutorService>();
    var secondOwned = new AtomicReference<ExecutorService>();

    {
      var first = application(firstOwned);

      try (Closeable firstCleanup = first::close) {
        var second = application(secondOwned);

        try (Closeable secondCleanup = second::close;
            var firstServer = TestServer.start(first);
            var secondServer = TestServer.start(second)) {
          first.close();

          assertTrue(firstOwned.get().isShutdown());
          assertFalse(secondOwned.get().isShutdown());

          var result =
              secondServer.send(
                  request -> request.path("/thread"), HttpResponse.BodyHandlers.ofString());

          assertEquals(200, result.statusCode());
          assertEquals("virtual=true", result.body());
        }
      }
    }

    assertTrue(secondOwned.get().isShutdown());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void progressesMultiplexedHttp2ResponsesWithBoundedNativePlatformPool(boolean virtual)
      throws Exception {
    var payload = "x".repeat(131_072);

    var configuredOptions = new Options();
    configuredOptions.setVirtualThreads(virtual);
    configuredOptions.setHttp2(true);

    {
      var app = new Shoostr(configuredOptions);

      try (Closeable appCleanup3 = app::close;
          var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build()) {

        app.modifyServer(server -> ((QueuedThreadPool) server.getThreadPool()).setMaxThreads(32));
        app.routes()
            .get(
                "/flow",
                (request, response) ->
                    response
                        .setHeader(
                            "X-Client-Port",
                            Integer.toString(
                                ((InetSocketAddress) request.remoteAddress().orElseThrow())
                                    .getPort()))
                        .text(payload));

        try (var server = TestServer.start(app)) {
          var request =
              HttpRequest.newBuilder(server.baseUri().resolve("flow"))
                  .timeout(Duration.ofSeconds(10))
                  .build();
          var warmup = client.send(request, HttpResponse.BodyHandlers.ofString());

          assertEquals(HttpClient.Version.HTTP_2, warmup.version());

          var connection = warmup.headers().firstValue("X-Client-Port").orElseThrow();
          var pending = new ArrayList<CompletableFuture<HttpResponse<String>>>();
          for (int index = 0; index < 64; index++) {
            pending.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
          }

          CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
              .get(10, TimeUnit.SECONDS);
          for (var future : pending) {
            var result = future.join();

            assertEquals(200, result.statusCode());
            assertEquals(HttpClient.Version.HTTP_2, result.version());
            assertEquals(connection, result.headers().firstValue("X-Client-Port").orElseThrow());
            assertEquals(payload, result.body());
          }
        }
      }
    }
  }
}
