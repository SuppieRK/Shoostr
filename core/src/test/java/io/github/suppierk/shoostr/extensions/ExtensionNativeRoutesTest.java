package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.AuthenticationExtension;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.jupiter.api.Test;

class ExtensionNativeRoutesTest {
  @Test
  void inheritsAdmissionAcrossEventsHandshakesAndStaticMountsWithoutInventingHttpFacts()
      throws Exception {
    var calls = new AtomicInteger();
    var enabled = new AtomicBoolean();
    var facts = new ArrayList<String>();
    var auth = countingAuthentication(calls);

    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close;
          var client = HttpClient.newHttpClient()) {
        app.routes(routes ->
            routes.path(
                "/api",
                group ->
                    group.when(
                        enabled::get,
                        conditional -> {
                          conditional.sse(
                              "/events",
                              (ignored, response) -> response.startEventStream().send("hello"));
                          conditional.websocket(
                              "/socket",
                              (ignoredRequest, ignoredServerUpgradeResponse) ->
                                  new Session.Listener.AutoDemanding() {});
                          conditional.classpathResources("/files", "/");
                        }),
                lifecycle -> {
                  lifecycle.withExtension(auth);
                  lifecycle.beforeRouteHandler((request, response) ->
                      response.setHeader(
                          "X-Principal", request.principal().orElseThrow().getName()));
                  lifecycle.onRoute((method, path) -> facts.add(method.value() + " " + path));
                }));

        try (var test = TestServer.start(app)) {
          assertEquals(List.of("GET /api/events"), facts);

          for (var path : List.of("/api/events", "/api/files/static-resource.txt")) {
            var result =
                test.send(request -> request.path(path), HttpResponse.BodyHandlers.ofString());

            assertEquals(404, result.statusCode());
            assertEquals("alice", result.headers().firstValue("X-Principal").orElseThrow());
          }
          var failure = expectRejectedHandshake(client, app);

          assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());
          assertEquals(
              404, ((WebSocketHandshakeException) failure.getCause()).getResponse().statusCode());

          enabled.set(true);

          assertEquals(
              "data: hello\n\n",
              test.send(
                      request -> request.path("/api/events"), HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              200,
              test.send(
                      request -> request.path("/api/files/static-resource.txt"),
                      HttpResponse.BodyHandlers.ofString())
                  .statusCode());

          var socket = openAuthenticatedSocket(client, app);
          socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);

          assertEquals(6, calls.get());
        }
      }
    }
  }

  private AuthenticationExtension countingAuthentication(AtomicInteger calls) {
    return new AuthenticationExtension() {
      @Override
      public void handle(Request request, Response response) {
        calls.incrementAndGet();
        request.principal(() -> "alice");
      }
    };
  }

  private ExecutionException expectRejectedHandshake(HttpClient client, Shoostr app) {
    return assertThrows(
        ExecutionException.class,
        () ->
            client
                .newWebSocketBuilder()
                .buildAsync(
                    URI.create("ws://127.0.0.1:" + app.port() + "/api/socket"),
                    new WebSocket.Listener() {})
                .get(5, TimeUnit.SECONDS));
  }

  private WebSocket openAuthenticatedSocket(HttpClient client, Shoostr app)
      throws InterruptedException, ExecutionException, TimeoutException {
    return client
        .newWebSocketBuilder()
        .buildAsync(
            URI.create("ws://127.0.0.1:" + app.port() + "/api/socket"), new WebSocket.Listener() {})
        .get(5, TimeUnit.SECONDS);
  }
}
