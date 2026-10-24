package io.github.suppierk.shoostr.example.websocket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TypedWebSocketExampleTest {
  @Test
  @Timeout(15)
  void incrementsABinaryCounterAndKeepsRawTextIndependent() throws Exception {
    try (var server = TestServer.start(TypedWebSocketExample.application());
        var client = HttpClient.newHttpClient()) {
      var binary = new CompletableFuture<byte[]>();
      var text = new CompletableFuture<String>();
      var bytes = new ByteArrayOutputStream();
      var characters = new StringBuilder();
      var socket =
          client
              .newWebSocketBuilder()
              .buildAsync(
                  new URI("ws", server.baseUri().getRawAuthority(), "/counter", null, null),
                  new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onBinary(
                        WebSocket connection, ByteBuffer data, boolean last) {
                      var part = new byte[data.remaining()];
                      data.get(part);
                      bytes.writeBytes(part);
                      if (last) {
                        binary.complete(bytes.toByteArray());
                      }

                      connection.request(1);
                      return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletionStage<?> onText(
                        WebSocket connection, CharSequence data, boolean last) {
                      characters.append(data);
                      if (last) {
                        text.complete(characters.toString());
                      }

                      connection.request(1);
                      return CompletableFuture.completedFuture(null);
                    }
                  })
              .get(3, TimeUnit.SECONDS);

      try {
        // The client shares the four-byte format, not the server's Java record type.
        socket.sendBinary(ByteBuffer.wrap(new byte[] {0, 0}), false).get(3, TimeUnit.SECONDS);
        socket.sendBinary(ByteBuffer.wrap(new byte[] {0, 41}), true).get(3, TimeUnit.SECONDS);
        assertArrayEquals(new byte[] {0, 0, 0, 42}, binary.get(3, TimeUnit.SECONDS));

        socket.sendText("hello", true).get(3, TimeUnit.SECONDS);
        assertEquals("text: hello", text.get(3, TimeUnit.SECONDS));
      } finally {
        socket.abort();
      }
    }
  }
}
