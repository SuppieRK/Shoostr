package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
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
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/files/{*path}",
              (request, response) -> response.text(request.pathParam("path").orElseThrow()));
      app.start();

      assertEquals("a", send(client, app, "/files/a").body());
      assertEquals("a/b", send(client, app, "/files/a/b").body());
      assertEquals(404, send(client, app, "/files/").statusCode());
      assertEquals(404, send(client, app, "/files").statusCode());
    }
  }

  @Test
  void prefersLiteralAndSingleSegmentsBeforeCatchAllInEitherRegistrationOrder() throws Exception {
    for (boolean catchAllFirst : new boolean[] {true, false}) {
      try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
          var client = HttpClient.newHttpClient()) {
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
        app.start();

        assertEquals("literal", send(client, app, "/files/fixed").body());
        assertEquals("segment:a", send(client, app, "/files/a").body());
        assertEquals("literal-end", send(client, app, "/files/fixed/end").body());
        assertEquals("tail:fixed/other", send(client, app, "/files/fixed/other").body());
        assertEquals("post-tail", send(client, app, "/files/fixed", "POST").body());
        var wrongMethod = send(client, app, "/files/fixed", "PUT");
        assertEquals(405, wrongMethod.statusCode());
        assertEquals("GET, POST", wrongMethod.headers().firstValue("Allow").orElseThrow());
      }
    }
  }

  @Test
  void composesCatchAllWithParentParametersAndPreservesDecodedTailSpelling() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var client = HttpClient.newHttpClient()) {
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
      app.start();

      assertEquals("a:a b/c+d/", send(client, app, "/accounts/a/files/a%20b/c+d/").body());
      assertEquals(
          "é:café/€", send(client, app, "/accounts/%C3%A9/files/caf%C3%A9/%E2%82%AC").body());
      assertEquals(404, send(client, app, "/accounts/a/files/").statusCode());
      assertEquals(400, send(client, app, "/accounts/a/files/%252F").statusCode());
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
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .sse(
              "/events/{*topic}",
              (request, response) ->
                  response.startEventStream().send(request.pathParam("topic").orElseThrow()));
      app.start();

      var result = send(client, app, "/events/a/b");
      assertEquals(200, result.statusCode());
      assertEquals("data: a/b\n\n", result.body());
      assertEquals(404, send(client, app, "/events/").statusCode());
    }
  }

  @Test
  void keepsCaseAndTrailingSeparatorsDistinctWithoutReadingAnUnusedCapture() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/Docs/{*path}",
              (request, response) ->
                  response.text(request.routePattern().orElseThrow() + ":" + request.path()));
      app.start();

      assertEquals("/Docs/{*path}:/Docs/a/", send(client, app, "/Docs/a/").body());
      assertEquals(404, send(client, app, "/docs/a/").statusCode());
      assertEquals(400, send(client, app, "/Docs/a%2Fb").statusCode());
      assertEquals(400, send(client, app, "/Docs/a%252Fb").statusCode());
    }
  }

  private static HttpResponse<String> send(HttpClient client, Shoostr app, String path)
      throws Exception {
    return send(client, app, path, "GET");
  }

  private static HttpResponse<String> send(
      HttpClient client, Shoostr app, String path, String method) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
            .timeout(Duration.ofSeconds(5))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
