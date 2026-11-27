package io.github.suppierk.shoostr.example.websocket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
import io.github.suppierk.shoostr.testing.TestWebSocket;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class NativeWebSocketExampleTest {
    private static final Duration MESSAGE_TIMEOUT = Duration.ofSeconds(3);

    @ParameterizedTest
    @MethodSource("rejectedOrigins")
    void rejectsMissingForeignAndAmbiguousOrigins(List<String> origins) throws Exception {
        try (var test = TestServer.start(NativeWebSocketExample.application())) {
            assertUpgradeRejected(
                test,
                403,
                handshake -> {
                    handshake.subprotocols("chat.v2");
                    origins.forEach(origin -> handshake.header("Origin", origin));
                });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "chat.v1"})
    void rejectsHandshakeThatDoesNotOfferTheRequiredSubprotocol(String protocol) throws Exception {
        try (var test = TestServer.start(NativeWebSocketExample.application())) {
            assertUpgradeRejected(
                test,
                400,
                handshake -> {
                    handshake.header("Origin", "https://app.example");
                    if (!protocol.isEmpty()) {
                        handshake.subprotocols(protocol);
                    }
                });
        }
    }

    @Test
    void selectsAnOfferedSubprotocol() throws Exception {
        try (var test = TestServer.start(NativeWebSocketExample.application());
             var client = HttpClient.newHttpClient()) {
            var socket = connectForSubprotocolCheck(test, client);

            try {
                assertEquals("chat.v2", socket.getSubprotocol());
            } finally {
                socket.abort();
            }
        }
    }

    @Test
    void demandsEachCompleteTextMessage() throws Exception {
        try (var test = TestServer.start(NativeWebSocketExample.application());
             var socket = connectToLobby(test)) {
            socket.sendText("hel", false);
            socket.sendText("lo", true);
            socket.sendText("again");

            assertEquals("lobby:hello", socket.receiveText(MESSAGE_TIMEOUT));
            assertEquals("lobby:again", socket.receiveText(MESSAGE_TIMEOUT));
        }
    }

    @Test
    void echoesBinaryAfterItsReceiveBufferIsReleasedAndContinuesReceiving() throws Exception {
        try (var test = TestServer.start(NativeWebSocketExample.application());
             var socket = connectToLobby(test)) {
            socket.sendBinary(new byte[]{0, 1}, false);
            socket.sendBinary(new byte[]{(byte) 255}, true);

            assertArrayEquals(
                new byte[]{0, 1, (byte) 255},
                socket.receiveBinary(MESSAGE_TIMEOUT)
            );

            socket.sendText("after binary");

            assertEquals("lobby:after binary", socket.receiveText(MESSAGE_TIMEOUT));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"text", "binary"})
    void closesIncomingMessagesAboveTheConfiguredLimit(String type) throws Exception {
        try (var test = TestServer.start(NativeWebSocketExample.application());
             var socket = connectToLobby(test)) {
            if ("text".equals(type)) {
                socket.sendText("a".repeat(65));
            } else {
                socket.sendBinary(new byte[65]);
            }

            assertEquals(1009, socket.receiveClose(MESSAGE_TIMEOUT).code());
        }
    }

    @Test
    void acceptsMessagesAtTheLimitAndAcknowledgesNormalClosure() throws Exception {
        try (var test = TestServer.start(NativeWebSocketExample.application());
             var socket = connectToLobby(test)) {
            socket.sendText("a".repeat(64));
            assertEquals("lobby:" + "a".repeat(64), socket.receiveText(MESSAGE_TIMEOUT));

            socket.sendBinary(new byte[64]);
            assertArrayEquals(new byte[64], socket.receiveBinary(MESSAGE_TIMEOUT));

            socket.sendClose(WebSocket.NORMAL_CLOSURE, "Finished");
            assertEquals(1000, socket.receiveClose(MESSAGE_TIMEOUT).code());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {
        32,
        33
    })
    void limitsMultibyteTextByItsUtf8BytesRatherThanItsCharacterCount(int characters) throws Exception {
        try (var test = TestServer.start(NativeWebSocketExample.application());
             var socket = connectToLobby(test)) {
            socket.sendText("é".repeat(characters));

            if (characters == 32) {
                assertEquals("lobby:" + "é".repeat(32), socket.receiveText(MESSAGE_TIMEOUT));
            } else {
                assertEquals(1009, socket.receiveClose(MESSAGE_TIMEOUT).code());
            }
        }
    }

    private static void assertUpgradeRejected(
        TestServer test,
        int status,
        Consumer<WebSocket.Builder> configureHandshake
    ) {
        var failure = assertThrows(
            WebSocketHandshakeException.class,
            () -> connectToLobby(test, configureHandshake)
        );

        assertEquals(status, failure.getResponse().statusCode());
    }

    private static Stream<List<String>> rejectedOrigins() {
        return Stream.of(
            List.of(),
            List.of("https://evil.example"),
            List.of("null"),
            List.of("not an origin"),
            List.of("https://app.example, https://evil.example"),
            List.of("https://app.example", "https://app.example"));
    }

    private static WebSocket connectForSubprotocolCheck(TestServer test, HttpClient client)
            throws Exception {
        var target = URI.create("ws://" + test.baseUri().getRawAuthority() + "/native/lobby");

        // The JDK client exposes the negotiated subprotocol directly.
        return client.newWebSocketBuilder()
            .header("Origin", "https://app.example")
            .subprotocols("chat.v1", "chat.v2")
            .buildAsync(target, new WebSocket.Listener() {})
            .get(3, TimeUnit.SECONDS);
    }

    private static TestWebSocket connectToLobby(
        TestServer test
    ) throws IOException, InterruptedException {
        return connectToLobby(test, handshake -> handshake
            .header("Origin", "https://app.example")
            .subprotocols("chat.v2"));
    }

    private static TestWebSocket connectToLobby(
        TestServer test,
        Consumer<WebSocket.Builder> configureHandshake
    ) throws IOException, InterruptedException {
        return test.openWebSocket("/native/{room}", path -> path
            .pathParam("room", "lobby"), configureHandshake);
    }
}
