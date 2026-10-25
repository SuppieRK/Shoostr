package io.github.suppierk.shoostr.example.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ParametersExampleTest {
  @Test
  void distinguishesEmptyValuesFromAbsentCaseSensitiveNames() throws Exception {
    try (var test = TestServer.start(ParametersExample.application())) {
      var empty =
          test.send(
              request -> request.path("/search/books?ta%67=&tag=second&sort="),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, empty.statusCode());
      assertEquals("category=books\npath=books\nfirst=\ntags=|second\nsort=", empty.body());

      var absent =
          test.send(
              request -> request.path("/search/books?Tag=ignored&Sort=ignored"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, absent.statusCode());
      assertEquals(
          "category=books\npath=books\nfirst=(missing)\ntags=\nsort=recent", absent.body());
    }
  }

  @Test
  void decodesQueryValuesOnceWithoutRelaxingTransportPathValidation() throws Exception {
    try (var test = TestServer.start(ParametersExample.application())) {
      var response =
          test.send(
              request -> request.path("/search/books?tag=a%2520b"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals(
          "category=books\npath=books\nfirst=a%20b\ntags=a%20b\nsort=recent", response.body());
      assertEquals(400, test.send(request -> request.path("/search/a%2520b")).statusCode());
    }
  }

  @Test
  void preservesEmptyFormValuesAndCaseSensitiveNames() throws Exception {
    try (var test = TestServer.start(ParametersExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/search/books?tag=query")
                      .method("POST")
                      .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                      .body("ta%67=&tag=next&Sort=ignored".getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      assertEquals("category=books\nfirst=\ntags=|next\nsort=recent\nquery=query", response.body());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"tag=good&bad=%FF", "tag=good&bad=%C3%28"})
  void rejectsTheWholeQueryWhenAnotherValueIsMalformed(String query) throws Exception {
    try (var test = TestServer.start(ParametersExample.application())) {
      assertEquals(400, test.send(request -> request.path("/search/books?" + query)).statusCode());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"%GG", "%", "%FF"})
  void rejectsMalformedFormValues(String value) throws Exception {
    try (var test = TestServer.start(ParametersExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/search/books")
                      .method("POST")
                      .header("Content-Type", "application/x-www-form-urlencoded")
                      .body(("tag=good&bad=" + value).getBytes(StandardCharsets.UTF_8)));
      assertEquals(400, response.statusCode());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"text/plain", "application/x-www-form-urlencoded; charset=ISO-8859-1"})
  void rejectsUnsupportedFormRepresentations(String contentType) throws Exception {
    try (var test = TestServer.start(ParametersExample.application())) {
      assertEquals(
          415,
          test.send(
                  request ->
                      request
                          .path("/search/books")
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
          test.send(
                  request ->
                      request
                          .path("/search/books")
                          .method("POST")
                          .body("tag=java".getBytes(StandardCharsets.UTF_8)))
              .statusCode());
    }
  }

  @Test
  void exposesImmutableParameterMapsAndValueLists() throws Exception {
    try (var app = ParametersExample.application()) {
      app.routes()
          .post(
              "/snapshots/{id}",
              (request, response) -> {
                var path = request.pathParamMap();
                var query = request.queryParamMap();
                var form = request.formParamMap();
                assertEquals(Map.of("id", "a+b"), path);
                assertEquals(Map.of("tag", List.of("query", "second")), query);
                assertEquals(Map.of("tag", List.of("form", "next")), form);
                assertThrows(UnsupportedOperationException.class, () -> path.put("id", "changed"));
                var queryTags = query.get("tag");
                var formTags = form.get("tag");
                assertThrows(UnsupportedOperationException.class, query::clear);
                assertThrows(UnsupportedOperationException.class, () -> queryTags.add("changed"));
                assertThrows(UnsupportedOperationException.class, form::clear);
                assertThrows(UnsupportedOperationException.class, () -> formTags.add("changed"));
                response.text("checked");
              });

      try (var test = TestServer.start(app)) {
        var response =
            test.send(
                request ->
                    request
                        .path("/snapshots/a+b?tag=query&tag=second")
                        .method("POST")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .body("tag=form&tag=next".getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("checked", response.body());
      }
    }
  }

  @Test
  void keepsFormValuesSeparateFromQueryValues() throws Exception {
    try (var test = TestServer.start(ParametersExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/search/books?tag=query")
                      .method("POST")
                      .header("Content-Type", "application/x-www-form-urlencoded")
                      .body(
                          "tag=form+one&tag=form%2Btwo&sort=title"
                              .getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      assertEquals(
          "category=books\nfirst=form one\ntags=form one|form+two\nsort=title\nquery=query",
          response.body());
    }
  }

  @Test
  void readsDecodedPathAndOrderedQueryValues() throws Exception {
    try (var test = TestServer.start(ParametersExample.application())) {
      var response =
          test.send(
              request -> request.path("/search/caf%C3%A9+books?tag=Java+25&tag=a%2Bb&sort=title"),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      assertEquals(
          "category=café+books\npath=café+books\nfirst=Java 25\ntags=Java 25|a+b\nsort=title",
          response.body());
    }
  }
}
