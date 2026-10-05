package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.AuthenticationExtension;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.Shoostr;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
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
    var auth =
        new AuthenticationExtension() {
          @Override
          public void handle(Request request, Response response) {
            calls.incrementAndGet();
            request.principal(() -> "alice");
          }
        };

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.authentication(auth)
          .routes(
              routes ->
                  routes.path(
                      "/api",
                      group ->
                          group.when(
                              enabled::get,
                              conditional -> {
                                conditional.sse(
                                    "/events",
                                    (_, response) -> response.startEventStream().send("hello"));
                                conditional.websocket(
                                    "/socket", (_, _) -> new Session.Listener.AutoDemanding() {});
                                conditional.classpathResources("/files", "/");
                              }),
                      e -> {
                        e.get(auth).required();
                        e.beforeRouteHandler(
                            (request, response) ->
                                response.setHeader(
                                    "X-Principal", request.principal().orElseThrow().getName()));
                        e.onRoute((method, path) -> facts.add(method.value() + " " + path));
                      }));
      app.start();
      assertEquals(List.of("GET /api/events"), facts);
      for (var path : List.of("/api/events", "/api/files/static-resource.txt")) {
        var result =
            client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, result.statusCode());
        assertEquals("alice", result.headers().firstValue("X-Principal").orElseThrow());
      }
      var failure =
          assertThrows(
              ExecutionException.class,
              () ->
                  client
                      .newWebSocketBuilder()
                      .buildAsync(
                          URI.create("ws://127.0.0.1:" + app.port() + "/api/socket"),
                          new WebSocket.Listener() {})
                      .get(5, TimeUnit.SECONDS));
      assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());
      assertEquals(
          404, ((WebSocketHandshakeException) failure.getCause()).getResponse().statusCode());
      enabled.set(true);
      assertEquals(
          "data: hello\n\n",
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/api/events"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          200,
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create(
                              "http://127.0.0.1:" + app.port() + "/api/files/static-resource.txt"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  URI.create("ws://127.0.0.1:" + app.port() + "/api/socket"),
                  new WebSocket.Listener() {})
              .get(5, TimeUnit.SECONDS);
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
      assertEquals(6, calls.get());
    }
  }
}
