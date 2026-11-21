package io.github.suppierk.shoostr.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.suppierk.shoostr.Shoostr;
import java.io.Closeable;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class ClientConfigurationTest {
  @Test
  void appliesExplicitClientConfigurationAfterTheDefaults() throws Exception {
    var app = new Shoostr();
    app.routes()
        .get(
            "/redirect",
            (ignored, response) -> response.status(302).setHeader("Location", "/target"));
    app.routes().get("/target", (ignoredRequest, response) -> response.text("target"));

    try (var test =
        TestServer.start(app, builder -> builder.followRedirects(HttpClient.Redirect.ALWAYS))) {
      var reply =
          test.send(request -> request.path("/redirect"), HttpResponse.BodyHandlers.ofString());

      assertEquals(200, reply.statusCode());
      assertEquals("target", reply.body());
    }
  }

  @Test
  void appliesTheDefaultClientSettingsWithoutRequiredConfiguration() throws Exception {
    var checked = new AtomicBoolean();

    try (var test =
        openTestForAppliesTheDefaultClientSettingsWithoutRequiredConfiguration(checked)) {
      assertTrue(checked.get());
      assertEquals(404, test.send(request -> request.path("/missing")).statusCode());
    }
  }

  private TestServer openTestForAppliesTheDefaultClientSettingsWithoutRequiredConfiguration(
      AtomicBoolean checked) throws Exception {
    return TestServer.start(new Shoostr(), builder -> inspectDefaultClient(builder, checked));
  }

  private void inspectDefaultClient(HttpClient.Builder builder, AtomicBoolean checked) {
    try (var client = builder.build()) {
      assertEquals(Duration.ofSeconds(3), client.connectTimeout().orElseThrow());
      assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
      assertEquals(HttpClient.Version.HTTP_2, client.version());
      assertSame(HttpClient.Builder.NO_PROXY, client.proxy().orElseThrow());
      assertTrue(client.cookieHandler().isPresent());

      checked.set(true);
    }
  }

  @Test
  void leavesTheConfiguredExecutorOwnedByTheCaller() throws Exception {
    try (var executor = Executors.newSingleThreadExecutor()) {
      var app = new Shoostr();
      app.routes().get("/value", (ignoredRequest, response) -> response.text("value"));

      try (var test = TestServer.start(app, builder -> builder.executor(executor))) {
        assertEquals(200, test.send(request -> request.path("/value")).statusCode());
      }

      assertEquals("still usable", executor.submit(() -> "still usable").get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void closesTheOwnedApplicationWhenClientConfigurationThrows() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        var expected = new IllegalArgumentException("invalid client configuration");
        var actual =
            assertConfigurationFailureForClosesTheOwnedApplicationWhenClientConfigurationThrows(
                app, expected);

        assertSame(expected, actual);
        assertThrows(IllegalStateException.class, app::start);
      }
    }
  }

  private IllegalArgumentException
      assertConfigurationFailureForClosesTheOwnedApplicationWhenClientConfigurationThrows(
          Shoostr app, IllegalArgumentException expected) {
    return assertThrows(
        IllegalArgumentException.class,
        () ->
            TestServer.start(
                app,
                ignoredClientBuilder -> {
                  throw expected;
                }));
  }

  @Test
  void doesNotConfigureTheClientForAnAlreadyClosedApplication() throws Exception {
    var app = new Shoostr();
    app.close();

    assertThrows(
        IllegalStateException.class,
        () ->
            TestServer.start(
                app,
                ignoredClientBuilder -> fail("must not configure a client for a rejected app")));
  }

  @Test
  void terminatesTheOwnedClientWhenTheFixtureCloses() throws Exception {
    var test = TestServer.start(new Shoostr());
    var client = test.httpClient();
    test.close();

    assertTrue(client.isTerminated());
    assertThrows(IllegalStateException.class, test::httpClient);
  }
}
