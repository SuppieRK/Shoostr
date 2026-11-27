package io.github.suppierk.shoostr.example.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import io.github.suppierk.shoostr.testing.TestServer;
import io.github.suppierk.shoostr.testing.TestRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.Closeable;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.function.Consumer;

class PathPatternsExampleTest {
    @ParameterizedTest
    @CsvSource({
        "GET, /items/latest, latest",
        "GET, /items/a%20b, name:a b",
        "GET, /items/caf%C3%A9, name:café",
        "GET, /items/a+b, name:a+b",
        "GET, /items/a/b, tail:a/b",
        "GET, /items/a%20b/c+d/, tail:a b/c+d/",
        "POST, /items/latest, post:latest",
        "GET, /status, ok"
    })
    void selectsTheMostSpecificCompleteMatch(
            String method,
            String path,
            String body
    ) throws Exception {
        assertMatch(request -> request.path(path).method(method), body);
    }

    @ParameterizedTest
    @CsvSource({
        "/items/{id}, id, 42, id:42",
        "/items/{name}, name, Ada, name:Ada",
        "/codes/{single}, single, 7, single:7",
        "/codes/{digits}, digits, 12, digits:12",
        "/fallback/{name}/y, name, fixed, fallback:fixed"
    })
    void selectsTheMostSpecificMatchForFilledSegments(
            String template,
            String name,
            String value,
            String body
    ) throws Exception {
        assertMatch(request -> request
            .pathTemplate(template, parts -> parts
                .pathParam(name, value)), body);
    }

    private static void assertMatch(Consumer<TestRequest> configure, String body) throws Exception {
        try (var test = TestServer.start(PathPatternsExample.application())) {
            var response = test.send(configure, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(body, response.body());
        }
    }

    @Test
    void discoversMethodsAcrossAllMatchingPatterns() throws Exception {
        try (var test = TestServer.start(PathPatternsExample.application())) {
            var response = test.send(
                request -> request.path("/items/latest").method("DELETE")
            );

            assertEquals(405, response.statusCode());
            assertEquals(
                Set.of("GET", "POST"),
                Set.of(response.headers()
                    .firstValue("Allow")
                    .orElseThrow()
                    .split(",\\s*")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/Status",
        "/status/",
        "/items",
        "/items/",
        "/codes/1234",
        "/codes/abc"
    })
    void rejectsPathsOutsideTheRegisteredShapes(String path) throws Exception {
        try (var test = TestServer.start(PathPatternsExample.application())) {
            assertEquals(
                404,
                test.send(request -> request.path(path)).statusCode()
            );
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/items/%2F",
        "/items/%252F"
    })
    void rejectsEncodedSlashesBeforeServingTheCapture(String path) throws Exception {
        try (var test = TestServer.start(PathPatternsExample.application())) {
            assertEquals(
                400,
                test.send(request -> request.path(path)).statusCode()
            );
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/invalid/{id}.json",
        "/invalid/{*tail}/more",
        "/invalid/{id:(a+)+}",
        "/invalid/{id}/{id}"
    })
    void rejectsUnsupportedPatternSyntaxAtRegistration(String pattern) throws Exception {
        var app = PathPatternsExample.application();

        try (Closeable appCleanup = app::close) {
            var routes = app.routes();

            assertThrows(
                IllegalArgumentException.class,
                () -> routes.get(
                    pattern,
                    (request, response) -> response.text("unused")
                )
            );
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/items/{other}",
        "/items/{other:[0-9]+}",
        "/items/{*other}"
    })
    void rejectsEquivalentShapesEvenWithDifferentParameterNames(String pattern) throws Exception {
        var app = PathPatternsExample.application();

        try (Closeable appCleanup = app::close) {
            var routes = app.routes();

            assertThrows(
                IllegalArgumentException.class,
                () -> routes.get(
                    pattern,
                    (request, response) -> response.text("unused")
                )
            );
        }
    }
}
