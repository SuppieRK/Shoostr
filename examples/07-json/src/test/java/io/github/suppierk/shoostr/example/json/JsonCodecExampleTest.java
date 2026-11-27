package io.github.suppierk.shoostr.example.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.suppierk.shoostr.example.json.JsonCodecExample.User;
import io.github.suppierk.shoostr.testing.TestServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

@Timeout(15)
class JsonCodecExampleTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void leavesMediaEnforcementToTheHandlerRatherThanTheCodec() throws Exception {
        try (var test = TestServer.start(JsonCodecExample.application())) {
            var body = MAPPER.createObjectNode()
                .put("name", "Ada")
                .put("ignored", true);

            var response = postUser(
                test,
                MAPPER.writeValueAsBytes(body),
                "text/plain",
                "application/xml"
            );

            assertEquals(201, response.statusCode());
            assertEquals("application/json", response.headers()
                .firstValue("Content-Type")
                .orElseThrow());
            assertEquals(
                new User("Ada"),
                MAPPER.readValue(response.body(), User.class)
            );
        }
    }

    @Test
    void keepsOrdinaryTextAvailableWithTypedApplication() throws Exception {
        try (var test = TestServer.start(JsonCodecExample.application())) {
            var response = test.send(request -> request
                .path("/help"),
                HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, response.statusCode());
            assertEquals(
                "text/plain; charset=utf-8",
                response.headers()
                    .firstValue("Content-Type")
                    .orElseThrow()
            );
            assertEquals(
                "Send a JSON object with a nonblank name",
                response.body()
            );
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "{broken",
        "[]",
        "null",
        "{}",
        "{\"name\":null}",
        "{\"name\":1}",
        "{\"name\":1.5}",
        "{\"name\":false}",
        "{\"name\":\" \"}",
        "{\"name\":\"Ada\"} trailing",
        "{\"name\":\"Ada\"}{}"
    })
    void rejectsMalformedJsonAndInvalidNamesWithSafeJsonError(String body) throws Exception {
        try (var test = TestServer.start(JsonCodecExample.application())) {
            var response = postUser(
                test,
                body.getBytes(StandardCharsets.UTF_8),
                "application/json",
                "application/json"
            );

            assertEquals(400, response.statusCode());
            assertEquals(
                "application/json",
                response.headers()
                    .firstValue("Content-Type")
                    .orElseThrow()
            );
            assertEquals(
                MAPPER.readTree("{\"error\":\"invalid_user\"}"),
                MAPPER.readTree(response.body())
            );
        }
    }

    @Test
    void returnsValidatedUserAsJsonWithUnicodeAndEscapedCharacters() throws Exception {
        var user = new User("Zoë \"Z\"\nLee");

        try (var test = TestServer.start(JsonCodecExample.application())) {
            var response = postUser(
                test,
                MAPPER.writeValueAsBytes(user),
                "application/json",
                "application/json"
            );

            assertEquals(201, response.statusCode());
            assertEquals(
                "application/json",
                response.headers()
                    .firstValue("Content-Type")
                    .orElseThrow()
            );
            assertEquals(user, MAPPER.readValue(response.body(), User.class));
        }
    }

    private HttpResponse<byte[]> postUser(
        TestServer test,
        byte[] body,
        String contentType,
        String accept
    ) throws IOException, InterruptedException {
        return test.send(request -> request
            .path("/users")
            .method("POST")
            .header("Content-Type", contentType)
            .header("Accept", accept)
            .body(body));
    }
}
