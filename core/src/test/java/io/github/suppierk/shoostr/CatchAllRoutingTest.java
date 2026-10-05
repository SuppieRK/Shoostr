package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class CatchAllRoutingTest {
  @Test
  void capturesOneOrManyPathSegmentsButNotAnEmptyTail() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000))) {
      app.routes()
          .get(
              "/files/{*path}",
              (request, response) -> response.text(request.pathParam("path").orElseThrow()));

      try (var test = TestServer.start(app)) {

        assertEquals(
            "a",
            test.send(
                    request -> request.path("/files/a").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "a/b",
            test.send(
                    request -> request.path("/files/a/b").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            404,
            test.send(
                    request -> request.path("/files/").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(
            404,
            test.send(
                    request -> request.path("/files").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
      }
    }
  }

  @Test
  void prefersLiteralAndSingleSegmentsBeforeCatchAllInEitherRegistrationOrder() throws Exception {
    for (boolean catchAllFirst : new boolean[] {true, false}) {
      try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000))) {
        Consumer<Routes> catchAll =
            routes -> {
              routes.get(
                  "/{*path}",
                  (request, response) ->
                      response.text("tail:" + request.pathParam("path").orElseThrow()));
              routes.post("/{*path}", (_, response) -> response.text("post-tail"));
            };
        Consumer<Routes> specific =
            routes -> {
              routes.get("/fixed", (_, response) -> response.text("literal"));
              routes.get(
                  "/{id}",
                  (request, response) ->
                      response.text("segment:" + request.pathParam("id").orElseThrow()));
              routes.get("/fixed/end", (_, response) -> response.text("literal-end"));
            };
        app.routes()
            .path(
                "/files",
                routes -> {
                  if (catchAllFirst) {
                    catchAll.accept(routes);
                    specific.accept(routes);
                  } else {
                    specific.accept(routes);
                    catchAll.accept(routes);
                  }
                });

        try (var test = TestServer.start(app)) {

          assertEquals(
              "literal",
              test.send(
                      request -> request.path("/files/fixed").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              "segment:a",
              test.send(
                      request -> request.path("/files/a").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              "literal-end",
              test.send(
                      request -> request.path("/files/fixed/end").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              "tail:fixed/other",
              test.send(
                      request -> request.path("/files/fixed/other").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              "post-tail",
              test.send(
                      request ->
                          request
                              .path("/files/fixed")
                              .timeout(Duration.ofSeconds(5))
                              .method("POST"),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          var wrongMethod =
              test.send(
                  request ->
                      request.path("/files/fixed").timeout(Duration.ofSeconds(5)).method("PUT"),
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(405, wrongMethod.statusCode());
          assertEquals("GET, POST", wrongMethod.headers().firstValue("Allow").orElseThrow());
        }
      }
    }
  }

  @Test
  void composesCatchAllWithParentParametersAndPreservesDecodedTailSpelling() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000))) {
      app.routes()
          .path(
              "/accounts/{accountId}",
              account ->
                  account.path(
                      "files",
                      files ->
                          files.get(
                              "/{*path}",
                              (request, response) ->
                                  response.text(
                                      request.pathParam("accountId").orElseThrow()
                                          + ":"
                                          + request.pathParam("path").orElseThrow()))));

      try (var test = TestServer.start(app)) {

        assertEquals(
            "a:a b/c+d/",
            test.send(
                    request ->
                        request.path("/accounts/a/files/a%20b/c+d/").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "é:café/€",
            test.send(
                    request ->
                        request
                            .path("/accounts/%C3%A9/files/caf%C3%A9/%E2%82%AC")
                            .timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            404,
            test.send(
                    request -> request.path("/accounts/a/files/").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(
            400,
            test.send(
                    request ->
                        request.path("/accounts/a/files/%252F").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/files/{*}",
        "/files/{*bad name}",
        "/files/{*path}/extra",
        "/files/{*path}/{*rest}",
        "/files/{id}/{*id}",
        "/files/file-{*path}",
        "/files/{*path}.json",
        "/files/*"
      })
  void rejectsInvalidCatchAllSyntaxAtRegistration(String pattern) throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      var routes = app.routes();
      assertThrows(IllegalArgumentException.class, () -> routes.get(pattern, (_, _) -> {}));
    }
  }

  @Test
  void rejectsEquivalentCatchAllShapesForTheSameMethod() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      var routes = app.routes();
      routes.get("/files/{*path}", (_, _) -> {});
      assertThrows(
          IllegalArgumentException.class, () -> routes.get("/files/{*rest}", (_, _) -> {}));
      routes.post("/files/{*rest}", (_, _) -> {});
    }
  }

  @Test
  void serverSentEventRouteUsesTheSameCatchAllCapture() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000))) {
      app.routes()
          .sse(
              "/events/{*topic}",
              (request, response) ->
                  response.startEventStream().send(request.pathParam("topic").orElseThrow()));

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request -> request.path("/events/a/b").timeout(Duration.ofSeconds(5)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("data: a/b\n\n", result.body());
        assertEquals(
            404,
            test.send(
                    request -> request.path("/events/").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
      }
    }
  }

  @Test
  void keepsCaseAndTrailingSeparatorsDistinctWithoutReadingAnUnusedCapture() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000))) {
      app.routes()
          .get(
              "/Docs/{*path}",
              (request, response) ->
                  response.text(request.routePattern().orElseThrow() + ":" + request.path()));

      try (var test = TestServer.start(app)) {

        assertEquals(
            "/Docs/{*path}:/Docs/a/",
            test.send(
                    request -> request.path("/Docs/a/").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            404,
            test.send(
                    request -> request.path("/docs/a/").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(
            400,
            test.send(
                    request -> request.path("/Docs/a%2Fb").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(
            400,
            test.send(
                    request -> request.path("/Docs/a%252Fb").timeout(Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
      }
    }
  }
}
