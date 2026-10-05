package io.github.suppierk.shoostr.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;

@Timeout(15)
class FixtureLifecycleTest {
  @Test
  void cancelsAnUnfinishedResponseBeforeStoppingTheApplication() throws Exception {
    var app = new Shoostr();
    app.modifyServer(server -> server.setStopTimeout(1000));
    var payload = "x".repeat(16384);
    app.routes()
        .sse(
            "/events",
            (_, response) -> {
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
            (_, response) -> {
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
    app.modifyServer(
        server ->
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
    try (var app = new Shoostr();
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

  @Test
  void closesAppAndClientWithoutReplacingACheckedScopeFailure() throws Exception {
    var client = new AtomicReference<HttpClient>();
    var expected = new IOException("caller fixture failure");

    try (var app = new Shoostr()) {
      var actual =
          assertThrows(
              IOException.class,
              () -> {
                try (var test = TestServer.start(app)) {
                  client.set(test.httpClient());
                  throw expected;
                }
              });
      assertSame(expected, actual);
      assertTrue(client.get().isTerminated());
      assertThrows(IllegalStateException.class, app::port);
    }
  }

  @ParameterizedTest
  @NullSource
  void rejectsANullClientConfiguratorBeforeTakingOwnership(
      Consumer<HttpClient.Builder> configureClient) throws Exception {
    try (var app = new Shoostr()) {
      assertThrows(NullPointerException.class, () -> TestServer.start(app, configureClient));
      app.routes().get("/value", (_, response) -> response.text("not closed"));

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/value")).statusCode());
      }
    }
  }

  @Test
  void rejectsARunningAppBeforeClientConfigurationWithoutStoppingIt() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/value", (_, response) -> response.text("still running"));
      app.start();
      assertThrows(
          IllegalStateException.class,
          () -> TestServer.start(app, _ -> fail("Rejected apps must not configure a client")));
      var target = URI.create("http://127.0.0.1:" + app.port() + "/value");
      assertEquals(
          "still running",
          client
              .send(HttpRequest.newBuilder(target).build(), HttpResponse.BodyHandlers.ofString())
              .body());
    }
  }
}
