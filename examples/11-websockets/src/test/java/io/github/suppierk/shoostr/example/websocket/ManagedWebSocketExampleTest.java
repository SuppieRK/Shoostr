package io.github.suppierk.shoostr.example.websocket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
import io.github.suppierk.shoostr.testing.TestWebSocket;
import java.io.IOException;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Timeout(15)
class ManagedWebSocketExampleTest {
    private static final Duration MESSAGE_TIMEOUT = Duration.ofSeconds(3);

    @Test
    void handlesCompleteTextMessagesInOrderWithAnIndependentCounterForEachConnection() throws Exception {
        try (var test = TestServer.start(ManagedWebSocketExample.application());
             var first = connectToLobby(test);
             var second = connectToLobby(test)) {
            first.sendText("hel", false);
            first.sendText("lo", true);
            first.sendText("again");
            second.sendText("other");

            assertEquals("lobby:1:hello", first.receiveText(MESSAGE_TIMEOUT));
            assertEquals("lobby:2:again", first.receiveText(MESSAGE_TIMEOUT));
            assertEquals("lobby:1:other", second.receiveText(MESSAGE_TIMEOUT));
        }
    }

    @ParameterizedTest
    @MethodSource("rejectedOrigins")
    void rejectsMissingForeignOrAmbiguousOriginsBeforeUpgrading(List<String> origins) throws Exception {
        try (var test = TestServer.start(ManagedWebSocketExample.application())) {
            var failure = assertThrows(
                WebSocketHandshakeException.class,
                () -> test.openWebSocket(
                    "/rooms/{room}",
                    path -> path.pathParam("room", "lobby"),
                    builder -> origins.forEach(origin ->
                        builder.header("Origin", origin))));

            assertEquals(403, failure.getResponse().statusCode());
        }
    }

    private static Stream<List<String>> rejectedOrigins() {
        return Stream.of(
            List.of(),
            List.of("https://other.example"),
            List.of("null"),
            List.of("https://app.example", "https://app.example"),
            List.of("https://app.example, https://other.example"));
    }

    @Test
    void echoesCompleteBinaryBytesAndAcknowledgesNormalClosure() throws Exception {
        try (var test = TestServer.start(ManagedWebSocketExample.application());
             var socket = connectToLobby(test)) {
            socket.sendBinary(new byte[] {0, 1}, false);
            socket.sendBinary(new byte[] {(byte) 255}, true);

            assertArrayEquals(new byte[] {0, 1, (byte) 255}, socket.receiveBinary(MESSAGE_TIMEOUT));

            socket.sendClose(WebSocket.NORMAL_CLOSURE, "Finished");
            assertEquals(1000, socket.receiveClose(MESSAGE_TIMEOUT).code());
        }
    }

    @Test
    void reportsAnEmptyMessageWithoutClosingOrIncrementingItsCounter() throws Exception {
        try (var test = TestServer.start(ManagedWebSocketExample.application());
             var socket = connectToLobby(test)) {
            socket.sendText(" ");
            assertEquals("error: message required", socket.receiveText(MESSAGE_TIMEOUT));

            socket.sendText("hello");
            assertEquals("lobby:1:hello", socket.receiveText(MESSAGE_TIMEOUT));
        }
    }

    @Test
    void closesAnUnexpectedHandlerFailureWithoutSendingItsPrivateMessage() throws Exception {
        var app = ManagedWebSocketExample.application();
        app.routes().websocket("/fixture/failure", socket ->
            socket.onText((connection, text) -> {
                throw new IllegalStateException("private storage credential");
            }));

        try (var test = TestServer.start(app);
             var socket = test.openWebSocket("/fixture/failure")) {
            socket.sendText("hello");

            // The close must be the next event. An unexpected text message fails the receive.
            var close = socket.receiveClose(MESSAGE_TIMEOUT);
            assertEquals(1011, close.code());
            assertEquals("Processing failed", close.reason());
        }
    }

    private static TestWebSocket connectToLobby(
        TestServer test
    ) throws IOException, InterruptedException {
        return test.openWebSocket(
            "/rooms/{room}",
            path -> path.pathParam("room", "lobby"),
            builder -> builder.header("Origin", "https://app.example"));
    }
}
