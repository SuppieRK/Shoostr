package io.github.suppierk.shoostr.example.websocket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.testing.TestServer;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TypedWebSocketExampleTest {
  private static final Duration MESSAGE_TIMEOUT = Duration.ofSeconds(3);

  @Test
  @Timeout(15)
  void incrementsBinaryCounterAndKeepsRawTextIndependent() throws Exception {
    try (var test = TestServer.start(TypedWebSocketExample.application());
        var socket = test.openWebSocket("/counter")) {
      // The client sends the four-byte counter in two fragments.
      socket.sendBinary(new byte[] {0, 0}, false);
      socket.sendBinary(new byte[] {0, 41}, true);

      assertArrayEquals(new byte[] {0, 0, 0, 42}, socket.receiveBinary(MESSAGE_TIMEOUT));

      socket.sendText("hello");
      assertEquals("text: hello", socket.receiveText(MESSAGE_TIMEOUT));
    }
  }
}
