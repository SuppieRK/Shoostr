package io.github.suppierk.shoostr.testing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.suppierk.shoostr.Shoostr;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;

@Timeout(15)
class FixtureLifecycleTest {
  @Test
  void serializesOverlappingStartsForTheSameApplication() throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.routes().get("/value", (ignoredRequest, response) -> response.text("still running"));
      var competingStarted = new CountDownLatch(1);
      var competing = competingStartup(app, competingStarted);

      try (var winner =
          TestServer.start(app, ignored -> checkOverlappingStartup(competing, competingStarted))) {
        competing.get(5, TimeUnit.SECONDS);
        assertEquals(200, winner.send(request -> request.path("/value")).statusCode());
      }
    }
  }

  private FutureTask<Void> competingStartup(Shoostr app, CountDownLatch started) {
    return new FutureTask<>(() -> {
      started.countDown();
      assertThrows(
          IllegalStateException.class,
          () -> TestServer.start(app, ignored -> fail("Losing startup configured a client")));
      return null;
    });
  }

  private void checkOverlappingStartup(
      FutureTask<Void> competing, CountDownLatch competingStarted) {
    Thread.ofVirtual().start(competing);
    var independent = new FutureTask<>(this::startIndependentApplication);
    Thread.ofVirtual().start(independent);

    assertDoesNotThrow(() -> assertTrue(competingStarted.await(5, TimeUnit.SECONDS)));
    assertEquals(404, assertDoesNotThrow(() -> independent.get(5, TimeUnit.SECONDS)));
    assertThrows(TimeoutException.class, () -> competing.get(200, TimeUnit.MILLISECONDS));
  }

  private int startIndependentApplication() throws Exception {
    try (var fixture = TestServer.start(new Shoostr())) {
      return fixture.send(request -> request.path("/missing")).statusCode();
    }
  }

  @Test
  void concurrentCloseWaitsForTheFirstCleanupToFinish() throws Exception {
    var cleanupEntered = new CountDownLatch(1);
    var releaseCleanup = new CountDownLatch(1);
    var cleanupCalls = new AtomicInteger();
    var app = new Shoostr();
    app.beforeClose(() -> {
      cleanupCalls.incrementAndGet();
      cleanupEntered.countDown();
      assertDoesNotThrow(() -> assertTrue(releaseCleanup.await(5, TimeUnit.SECONDS)));
    });

    try (var fixture = TestServer.start(app)) {
      var first = closeOnVirtualThread(fixture, new CountDownLatch(1));
      var secondStarted = new CountDownLatch(1);
      FutureTask<Void> second;

      try {
        assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
        second = closeOnVirtualThread(fixture, secondStarted);

        assertTrue(secondStarted.await(3, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
      } finally {
        releaseCleanup.countDown();
      }

      first.get(5, TimeUnit.SECONDS);
      second.get(5, TimeUnit.SECONDS);
      assertEquals(1, cleanupCalls.get());
    }
  }

  private FutureTask<Void> closeOnVirtualThread(TestServer fixture, CountDownLatch started) {
    var closing = closeTask(fixture, started);
    Thread.ofVirtual().start(closing);

    return closing;
  }

  private FutureTask<Void> closeTask(TestServer fixture, CountDownLatch started) {
    return new FutureTask<>(() -> {
      started.countDown();
      fixture.close();
      return null;
    });
  }

  @Test
  void cancelsAnUnfinishedResponseBeforeStoppingTheApplication() throws Exception {
    var app = new Shoostr();
    app.modifyServer(server -> server.setStopTimeout(1000));
    var payload = "x".repeat(16384);
    app.routes()
        .sse(
            "/events",
            (ignored, response) -> {
              var events = response.startEventStream();
              while (!Thread.currentThread().isInterrupted()) {
                events.send(payload);
              }
            });

    try (var test = TestServer.start(app)) {
      var client = test.httpClient();

      try (var body =
          test.send(request -> request.path("/events"), HttpResponse.BodyHandlers.ofInputStream())
              .body()) {
        assertEquals('d', body.read());
        assertTimeout(Duration.ofSeconds(5), test::close);
        assertTrue(client.isTerminated());
      }
    }
  }

  @Test
  void reportsNativeDrainTimeoutAfterCancellingTheOwnedClient() throws Exception {
    var release = new CountDownLatch(1);
    var app = new Shoostr();
    app.modifyServer(server -> server.setStopTimeout(100));
    app.routes()
        .sse(
            "/events",
            (ignoredRequest, response) -> {
              response.startEventStream().send("open");
              release.await();
            });

    try (var test = TestServer.start(app)) {
      var client = test.httpClient();

      try (var body =
          test.send(request -> request.path("/events"), HttpResponse.BodyHandlers.ofInputStream())
              .body()) {
        assertEquals('d', body.read());
        var failure =
            assertTimeout(
                Duration.ofSeconds(5), () -> assertThrows(IOException.class, test::close));
        assertInstanceOf(TimeoutException.class, failure.getCause());
        assertTrue(client.isTerminated());
        assertThrows(IllegalStateException.class, app::port);
      }
    } finally {
      release.countDown();
    }
  }

  @Test
  void startsAndStopsTheOwnedApplicationOnceEvenWhenCloseIsRepeated() throws Exception {
    var starts = new AtomicInteger();
    var stops = new AtomicInteger();
    var app = new Shoostr();
    app.modifyServer(server ->
        server.addEventListener(
            new LifeCycle.Listener() {
              @Override
              public void lifeCycleStarted(LifeCycle event) {
                starts.incrementAndGet();
              }

              @Override
              public void lifeCycleStopped(LifeCycle event) {
                stops.incrementAndGet();
              }
            }));

    try (var test = TestServer.start(app)) {
      assertEquals(1, starts.get());

      test.close();

      assertThrows(IllegalStateException.class, test::baseUri);
    }

    assertEquals(1, stops.get());
    assertThrows(IllegalStateException.class, app::start);
  }

  @Test
  void closesAppAndClientWhenAnAssertionFailsInsideTheFixtureScope() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close;
          var test = TestServer.start(app)) {
        var client = test.httpClient();
        var status = test.send(request -> request.path("/missing")).statusCode();

        assertThrows(
            AssertionError.class,
            () -> {
              try (test) {
                assertEquals(200, status);
              }
            });
        assertTrue(client.isTerminated());
        assertThrows(IllegalStateException.class, app::port);
      }
    }
  }

  @Test
  void closesAppAndClientWithoutReplacingCheckedScopeFailure() throws Exception {
    var client = new AtomicReference<HttpClient>();
    var expected = new IOException("caller fixture failure");

    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {
        var actual = assertScopeFailure(app, client, expected);

        assertSame(expected, actual);
        assertTrue(client.get().isTerminated());
        assertThrows(IllegalStateException.class, app::port);
      }
    }
  }

  private IOException assertScopeFailure(
      Shoostr app, AtomicReference<HttpClient> client, IOException expected) {
    return assertThrows(
        IOException.class,
        () -> {
          try (var test = TestServer.start(app)) {
            client.set(test.httpClient());
            throw expected;
          }
        });
  }

  @ParameterizedTest
  @NullSource
  void rejectsNullClientConfiguratorBeforeTakingOwnership(
      Consumer<HttpClient.Builder> configureClient) throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup2 = app::close) {
        assertThrows(NullPointerException.class, () -> TestServer.start(app, configureClient));

        app.routes().get("/value", (ignoredRequest, response) -> response.text("not closed"));

        try (var test = TestServer.start(app)) {
          assertEquals(200, test.send(request -> request.path("/value")).statusCode());
        }
      }
    }
  }

  @Test
  void rejectsRunningAppBeforeClientConfigurationWithoutStoppingIt() throws Exception {
    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup3 = app::close;
          var client = HttpClient.newHttpClient()) {
        app.routes().get("/value", (ignoredRequest, response) -> response.text("still running"));
        app.start();

        assertThrows(
            IllegalStateException.class,
            () ->
                TestServer.start(
                    app,
                    ignoredClientBuilder -> fail("Rejected apps must not configure a client")));

        var target = URI.create("http://127.0.0.1:" + app.port() + "/value");

        assertEquals(
            "still running",
            client
                .send(HttpRequest.newBuilder(target).build(), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }
}
