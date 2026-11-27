package io.github.suppierk.shoostr.example.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import io.github.suppierk.shoostr.testing.TestServer;
import io.github.suppierk.shoostr.testing.TestRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.Closeable;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

class ParametersExampleTest {
    @Test
    void distinguishesEmptyValuesFromAbsentCaseSensitiveNames() throws Exception {
        try (var test = TestServer.start(ParametersExample.application())) {
            var empty = test.send(request ->
                request.path("/search/books?ta%67=&tag=second&sort="),
                HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, empty.statusCode());
            assertEquals(
                "category=books\npath=books\nfirst=\ntags=|second\nsort=",
                empty.body()
            );

            Consumer<TestRequest> unrecognizedQuery = request -> request.pathTemplate(
                "/search/{category}", parts -> parts
                .pathParam("category", "books")
                .queryParam("Tag", "ignored")
                .queryParam("Sort", "ignored"));

            var absent = test.send(
                unrecognizedQuery,
                HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, absent.statusCode());
            assertEquals(
                "category=books\npath=books\nfirst=(missing)\ntags=\nsort=recent",
                absent.body()
            );
        }
    }

    @Test
    void decodesQueryValuesOnceWithoutRelaxingTransportPathValidation() throws Exception {
        try (var test = TestServer.start(ParametersExample.application())) {
            var response = test.send(request ->
                request.path("/search/books?tag=a%2520b"),
                HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, response.statusCode());
            assertEquals(
                "category=books\npath=books\nfirst=a%20b\ntags=a%20b\nsort=recent",
                response.body()
            );
            assertEquals(
                400,
                test.send(request ->
                        request.path("/search/a%2520b"))
                    .statusCode()
            );
        }
    }

    @Test
    void preservesEmptyFormValuesAndCaseSensitiveNames() throws Exception {
        try (var test = TestServer.start(ParametersExample.application())) {
            var response = sendResponseForPreservesEmptyFormValuesAndCaseSensitiveNames(test);

            assertEquals(200, response.statusCode());
            assertEquals(
                "category=books\nfirst=\ntags=|next\nsort=recent\nquery=query",
                response.body()
            );
        }
    }

    private HttpResponse<String> sendResponseForPreservesEmptyFormValuesAndCaseSensitiveNames(
            TestServer test) throws IOException, InterruptedException {
        return test.send(request -> request
            .pathTemplate("/search/{category}", parts -> parts
                .pathParam("category", "books")
                .queryParam("tag", "query"))
            .method("POST")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .body("ta%67=&tag=next&Sort=ignored".getBytes(StandardCharsets.UTF_8)),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "tag=good&bad=%FF",
        "tag=good&bad=%C3%28"
    })
    void rejectsTheWholeQueryWhenAnotherValueIsMalformed(String query) throws Exception {
        try (var test = TestServer.start(ParametersExample.application())) {
            assertEquals(
                400,
                test.send(request ->
                        request.path("/search/books?" + query))
                    .statusCode()
            );
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "%GG",
        "%",
        "%FF"
    })
    void rejectsMalformedFormValues(String value) throws Exception {
        try (var test = TestServer.start(ParametersExample.application())) {
            var response = sendResponseForRejectsMalformedFormValues(test, value);

            assertEquals(400, response.statusCode());
        }
    }

    private HttpResponse<byte[]> sendResponseForRejectsMalformedFormValues(
            TestServer test, String value) throws IOException, InterruptedException {
        return test.send(request -> request
            .pathTemplate("/search/{category}", parts -> parts.pathParam("category", "books"))
            .method("POST")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body(("tag=good&bad=" + value).getBytes(StandardCharsets.UTF_8))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "text/plain",
        "application/x-www-form-urlencoded; charset=ISO-8859-1"
    })
    void rejectsUnsupportedFormRepresentations(String contentType) throws Exception {
        try (var test = TestServer.start(ParametersExample.application())) {
            assertEquals(
                415,
                test.send(request -> request
                        .pathTemplate("/search/{category}", parts -> parts
                            .pathParam("category", "books"))
                        .method("POST")
                        .header("Content-Type", contentType)
                        .body("tag=java".getBytes(StandardCharsets.UTF_8)))
                    .statusCode());
        }
    }

    @Test
    void requiresFormRepresentationMetadata() throws Exception {
        try (var test = TestServer.start(ParametersExample.application())) {
            assertEquals(
                415,
                test.send(request -> request
                    .pathTemplate("/search/{category}", parts -> parts
                        .pathParam("category", "books"))
                    .method("POST")
                    .body("tag=java".getBytes(StandardCharsets.UTF_8)))
                    .statusCode()
            );
        }
    }

    @Test
    void exposesImmutableParameterMapsAndValueLists() throws Exception {
        var app = ParametersExample.application();

        try (Closeable appCleanup = app::close) {
            app.routes().post(
                "/snapshots/{id}",
                (request, response) -> {
                    var path = request.pathParamMap();
                    var query = request.queryParamMap();
                    var form = request.formParamMap();

                    assertEquals(
                        Map.of("id", "a+b"),
                        path
                    );
                    assertEquals(
                        Map.of("tag", List.of("query", "second")),
                        query
                    );
                    assertEquals(
                        Map.of("tag", List.of("form", "next")),
                        form
                    );
                    assertThrows(
                        UnsupportedOperationException.class,
                        () -> path.put("id", "changed")
                    );

                    var queryTags = query.get("tag");
                    final var formTags = form.get("tag");

                    assertThrows(
                        UnsupportedOperationException.class,
                        query::clear
                    );
                    assertThrows(
                        UnsupportedOperationException.class,
                        () -> queryTags.add("changed")
                    );
                    assertThrows(
                        UnsupportedOperationException.class,
                        form::clear
                    );
                    assertThrows(
                        UnsupportedOperationException.class,
                        () -> formTags.add("changed")
                    );

                    response.text("checked");
                });

            try (var test = TestServer.start(app)) {
                var response = sendResponseForExposesImmutableParameterMapsAndValueLists(test);

                assertEquals(200, response.statusCode());
                assertEquals("checked", response.body());
            }
        }
    }

    private HttpResponse<String> sendResponseForExposesImmutableParameterMapsAndValueLists(
            TestServer test) throws IOException, InterruptedException {
        return test.send(request -> request
            .pathTemplate("/snapshots/{id}", parts -> parts
                .pathParam("id", "a+b")
                .queryParam("tag", "query")
                .queryParam("tag", "second"))
            .method("POST")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body("tag=form&tag=next".getBytes(StandardCharsets.UTF_8)),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    @Test
    void keepsFormValuesSeparateFromQueryValues() throws Exception {
        try (var test = TestServer.start(ParametersExample.application())) {
            var response = sendResponseForKeepsFormValuesSeparateFromQueryValues(test);

            assertEquals(200, response.statusCode());
            assertEquals(
                "category=books\nfirst=form one\ntags=form one|form+two\nsort=title\nquery=query",
                response.body()
            );
        }
    }

    private HttpResponse<String> sendResponseForKeepsFormValuesSeparateFromQueryValues(
            TestServer test) throws IOException, InterruptedException {
        return test.send(request -> request
            .pathTemplate("/search/{category}", parts -> parts
                .pathParam("category", "books")
                .queryParam("tag", "query"))
            .method("POST")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body("tag=form+one&tag=form%2Btwo&sort=title".getBytes(StandardCharsets.UTF_8)),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    @Test
    void readsDecodedPathAndOrderedQueryValues() throws Exception {
        try (var test = TestServer.start(ParametersExample.application())) {
            var response = test.send(request -> request
                .path("/search/caf%C3%A9+books?tag=Java+25&tag=a%2Bb&sort=title"),
                HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, response.statusCode());
            assertEquals(
                "category=café+books\npath=café+books\nfirst=Java 25\ntags=Java 25|a+b\nsort=title",
                response.body()
            );
        }
    }
}
