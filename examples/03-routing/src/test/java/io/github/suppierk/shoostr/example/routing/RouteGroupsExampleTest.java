package io.github.suppierk.shoostr.example.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import io.github.suppierk.shoostr.testing.TestServer;
import io.github.suppierk.shoostr.testing.TestRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpResponse;
import java.util.function.Consumer;
import java.util.stream.Stream;

class RouteGroupsExampleTest {
    @ParameterizedTest
    @MethodSource("groupRoutes")
    void reusesDefinitionsWithoutLosingParentPrefixes(Consumer<TestRequest> configure, String body) throws Exception {
        try (var test = TestServer.start(RouteGroupsExample.application())) {
            var response = test.send(configure, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(body, response.body());
        }
    }

    @ParameterizedTest
    @MethodSource("inheritedHeaders")
    void inheritsConfigurationWithoutLeakingItToSiblings(
        Consumer<TestRequest> configure,
        String apiHeader,
        String versionHeader,
        String groupHeader
    ) throws Exception {
        try (var test = TestServer.start(RouteGroupsExample.application())) {
            var response = test.send(configure);

            assertEquals(200, response.statusCode());
            assertEquals(apiHeader, response.headers()
                    .firstValue("X-API").orElse(null));
            assertEquals(versionHeader, response.headers()
                    .firstValue("X-Version").orElse(null));
            assertEquals(groupHeader, response.headers()
                    .firstValue("X-Group").orElse(null));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/notes",
        "/v1/notes",
        "/v2/notes",
        "/api/status"
    })
    void keepsLeadingSlashesWithinTheirParentGroup(String path) throws Exception {
        try (var test = TestServer.start(RouteGroupsExample.application())) {
            assertEquals(
                404,
                test.send(request -> request.path(path)).statusCode()
            );
        }
    }

    private static Stream<Arguments> groupRoutes() {
        return Stream.of(
            arguments(literalPath("/api/v1/notes"), "notes"),
            arguments(literalPath("/api/v1/notes/"), "notes with slash"),
            arguments(versionedNote("v1", "7"), "note:7"),
            arguments(literalPath("/api/v2/notes"), "notes"),
            arguments(versionedNote("v2", "8"), "note:8"),
            arguments(accountNote("acme", "42"), "acme:42"),
            arguments(literalPath("/status"), "ready"),
            arguments(literalPath("/public"), "public")
        );
    }

    private static Stream<Arguments> inheritedHeaders() {
        return Stream.of(
            arguments(literalPath("/api/v1/notes"), "yes", null, null),
            arguments(literalPath("/api/v2/notes"), "yes", "2", null),
            arguments(accountNote("acme", "42"), "yes", null, null),
            arguments(literalPath("/status"), null, null, "status"),
            arguments(literalPath("/public"), null, null, null)
        );
    }

    private static Consumer<TestRequest> literalPath(String path) {
        return request -> request.path(path);
    }

    private static Consumer<TestRequest> versionedNote(String version, String id) {
        return request -> request.pathTemplate("/api/{version}/notes/{id}", path -> path
            .pathParam("version", version)
            .pathParam("id", id));
    }

    private static Consumer<TestRequest> accountNote(String accountId, String id) {
        return request -> request.pathTemplate("/api/accounts/{accountId}/notes/{id}", path -> path
            .pathParam("accountId", accountId)
            .pathParam("id", id));
    }
}
