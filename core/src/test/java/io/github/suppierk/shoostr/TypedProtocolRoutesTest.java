package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class TypedProtocolRoutesTest {
  @Test
  void carriesCodecThroughGroupsConditionsExecutorsAndAllManagedOverloads() throws Exception {
    try (var executor = Executors.newSingleThreadExecutor()) {
      var owner = executor.submit(Thread::currentThread).get(3, TimeUnit.SECONDS);
      Consumer<Extensions> local =
          extensions ->
              extensions
                  .lifecycle()
                  .beforeRouteHandler(
                      (ignored, response) -> response.setHeader("X-Local", "present"));
      Consumer<TypedWebSocket> callbacks =
          socket ->
              socket
                  .beforeUpgrade(
                      (request, ignored2) -> {
                        if (request.path().contains("executor")) {
                          assertSame(owner, Thread.currentThread());
                        }
                      })
                  .onOpen(connection -> connection.sendObject(connection.path()));
      TypedHandler events =
          (request, response) -> {
            if (request.path().contains("executor")) {
              assertSame(owner, Thread.currentThread());
            }

            response.startEventStream().sendObject(request.path());
          };
      var app =
          new Shoostr()
              .codec(new PathCodec())
              .routes(
                  root ->
                      root.path(
                          "/api",
                          group ->
                              group.when(
                                  () -> true,
                                  available ->
                                      available.when(
                                          request -> request.path().startsWith("/api/"),
                                          routes ->
                                              routes
                                                  .websocket("/ws/direct", callbacks)
                                                  .websocket("/ws/extensions", callbacks, local)
                                                  .websocket("/ws/executor", executor, callbacks)
                                                  .websocket(
                                                      "/ws/executor-extensions",
                                                      executor,
                                                      callbacks,
                                                      local)
                                                  .path(
                                                      "/ws/group",
                                                      child -> child.websocket(callbacks))
                                                  .path(
                                                      "/ws/group-extensions",
                                                      child -> child.websocket(callbacks, local))
                                                  .path(
                                                      "/ws/group-executor",
                                                      child -> child.websocket(executor, callbacks))
                                                  .path(
                                                      "/ws/group-executor-extensions",
                                                      child ->
                                                          child.websocket(
                                                              executor, callbacks, local))
                                                  .sse("/sse/direct", events)
                                                  .sse("/sse/extensions", events, local)
                                                  .sse("/sse/executor", executor, events)
                                                  .sse(
                                                      "/sse/executor-extensions",
                                                      executor,
                                                      events,
                                                      local)
                                                  .path("/sse/group", child -> child.sse(events))
                                                  .path(
                                                      "/sse/group-extensions",
                                                      child -> child.sse(events, local))
                                                  .path(
                                                      "/sse/group-executor",
                                                      child -> child.sse(executor, events))
                                                  .path(
                                                      "/sse/group-executor-extensions",
                                                      child ->
                                                          child.sse(executor, events, local)))),
                          extensions ->
                              extensions
                                  .lifecycle()
                                  .beforeRouteHandler(
                                      (ignored3, response) ->
                                          response.setHeader("X-Scope", "group"))));

      try (var server = TestServer.start(app);
          var client = HttpClient.newHttpClient()) {
        for (var suffix :
            List.of(
                "direct",
                "extensions",
                "executor",
                "executor-extensions",
                "group",
                "group-extensions",
                "group-executor",
                "group-executor-extensions")) {
          var received = new CompletableFuture<byte[]>();
          var bytes = new ByteArrayOutputStream();
          String path = "/api/ws/" + suffix;
          var socket =
              client
                  .newWebSocketBuilder()
                  .buildAsync(
                      new URI("ws", server.baseUri().getRawAuthority(), path, null, null),
                      new WebSocket.Listener() {
                        @Override
                        public CompletionStage<?> onBinary(
                            WebSocket connection, ByteBuffer data, boolean last) {
                          var part = new byte[data.remaining()];
                          data.get(part);
                          bytes.writeBytes(part);
                          if (last) {
                            received.complete(bytes.toByteArray());
                          }

                          connection.request(1);
                          return CompletableFuture.completedFuture(null);
                        }
                      })
                  .get(3, TimeUnit.SECONDS);

          try {
            assertArrayEquals(
                path.getBytes(StandardCharsets.UTF_8), received.get(3, TimeUnit.SECONDS));
          } finally {
            socket.abort();
          }

          String eventPath = "/api/sse/" + suffix;
          var result =
              server.send(request -> request.path(eventPath), HttpResponse.BodyHandlers.ofString());
          assertEquals("data: " + eventPath + "\n\n", result.body());
          assertEquals("group", result.headers().firstValue("X-Scope").orElseThrow());
          assertEquals(
              suffix.contains("extensions"), result.headers().firstValue("X-Local").isPresent());
        }
      }
    }
  }

  private static final class PathCodec implements Codec {
    @Override
    public <T> T read(byte[] source, Class<T> type) {
      return type.cast(new String(source, StandardCharsets.UTF_8));
    }

    @Override
    public byte[] write(Object value) {
      return value.toString().getBytes(StandardCharsets.UTF_8);
    }
  }
}
