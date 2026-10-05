package io.github.suppierk.shoostr.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.suppierk.shoostr.Shoostr;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class ClientConfigurationTest {
  @Test
  void appliesExplicitClientConfigurationAfterTheDefaults() throws Exception {
    var app = new Shoostr();
    app.routes()
        .get("/redirect", (_, response) -> response.status(302).setHeader("Location", "/target"));
    app.routes().get("/target", (_, response) -> response.text("target"));

    try (var test =
        TestServer.start(app, builder -> builder.followRedirects(HttpClient.Redirect.ALWAYS))) {
      var reply =
          test.send(request -> request.path("/redirect"), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, reply.statusCode());
      assertEquals("target", reply.body());
    }
  }

  @Test
  void exposesTheDefaultClientSettingsWithoutRequiredConfiguration() throws Exception {
    try (var test = TestServer.start(new Shoostr())) {
      var client = test.httpClient();
      assertEquals(Duration.ofSeconds(3), client.connectTimeout().orElseThrow());
      assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
      assertEquals(HttpClient.Version.HTTP_2, client.version());
      assertSame(HttpClient.Builder.NO_PROXY, client.proxy().orElseThrow());
      assertTrue(client.cookieHandler().isPresent());
    }
  }

  @Test
  void servesAsyncRequestsThroughTheBorrowedClient() throws Exception {
    var app = new Shoostr();
    app.routes().get("/value", (_, response) -> response.text("async"));

    try (var test = TestServer.start(app)) {
      var reply =
          test.httpClient()
              .sendAsync(
                  HttpRequest.newBuilder(test.baseUri().resolve("value"))
                      .timeout(Duration.ofSeconds(2))
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .get(5, TimeUnit.SECONDS);
      assertEquals(200, reply.statusCode());
      assertEquals("async", reply.body());
    }
  }

  @Test
  void leavesTheConfiguredExecutorOwnedByTheCaller() throws Exception {
    try (var executor = Executors.newSingleThreadExecutor()) {
      var app = new Shoostr();
      app.routes().get("/value", (_, response) -> response.text("value"));

      try (var test = TestServer.start(app, builder -> builder.executor(executor))) {
        assertEquals(200, test.send(request -> request.path("/value")).statusCode());
      }

      assertEquals("still usable", executor.submit(() -> "still usable").get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void closesTheOwnedApplicationWhenClientConfigurationThrows() throws Exception {
    try (var app = new Shoostr()) {
      var expected = new IllegalArgumentException("invalid client configuration");
      var actual =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  TestServer.start(
                      app,
                      _ -> {
                        throw expected;
                      }));
      assertSame(expected, actual);
      assertThrows(IllegalStateException.class, app::start);
    }
  }

  @Test
  void doesNotConfigureTheClientForAnAlreadyClosedApplication() throws Exception {
    var app = new Shoostr();
    app.close();
    assertThrows(
        IllegalStateException.class,
        () ->
            TestServer.start(
                app, _ -> fail("must not configure a client for a rejected app")));
  }

  @Test
  void closesTheBorrowedClientWithTheFixture() throws Exception {
    var test = TestServer.start(new Shoostr());
    var client = test.httpClient();
    test.close();
    assertTrue(client.isTerminated());
    assertThrows(IllegalStateException.class, test::httpClient);
  }
}
