package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class ConstrainedRoutingTest {
  @Test
  void dispatchesByNamedSegmentConstraintAndExposesItsCapture() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/users/{id:[0-9]+}",
              (request, response) -> response.text(request.pathParam("id").orElseThrow()));
      app.start();

      assertEquals("123", send(client, app, "/users/123").body());
      assertEquals(404, send(client, app, "/users/abc").statusCode());
      assertEquals(404, send(client, app, "/users/123/extra").statusCode());
    }
  }

  @Test
  void completedRangeEndpointDoesNotStartAnotherRange() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/letters/{id:[a-c-e]}", (_, response) -> response.text("matched"));
      app.start();

      assertEquals("matched", send(client, app, "/letters/-").body());
      assertEquals("matched", send(client, app, "/letters/e").body());
      assertEquals(404, send(client, app, "/letters/d").statusCode());
    }
  }

  @Test
  void rejectsAnInvalidConstraintAtRegistration() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> app.routes().get("/users/{id:[0-9+}", (_, _) -> {}));
    }
  }

  @Test
  void prefersLiteralThenConstrainedThenPlainThenCatchAllAndFallsBackByMethod() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/users/{*tail}", (_, response) -> response.text("tail"));
      app.routes().get("/users/{name}", (_, response) -> response.text("plain"));
      app.routes().get("/users/{id:[0-9]+}", (_, response) -> response.text("constrained"));
      app.routes().get("/users/123", (_, response) -> response.text("literal"));
      app.routes().post("/users/{id:[0-9]+}", (_, response) -> response.text("post"));
      app.start();

      assertEquals("literal", send(client, app, "/users/123").body());
      assertEquals("constrained", send(client, app, "/users/456").body());
      assertEquals("plain", send(client, app, "/users/abc").body());
      assertEquals("tail", send(client, app, "/users/456/more").body());
      assertEquals("post", send(client, app, "/users/456", "POST").body());
      assertEquals(405, send(client, app, "/users/456", "DELETE").statusCode());
      assertEquals(
          "GET, POST",
          send(client, app, "/users/456", "DELETE").headers().firstValue("Allow").orElseThrow());
    }
  }

  @Test
  void firstMatchingConstrainedRouteForTheRequestedMethodWins() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var client = HttpClient.newHttpClient()) {
      app.routes().post("/items/{id:[0-9]+}", (_, response) -> response.text("first-post"));
      app.routes().get("/items/{id:[0-9]}", (_, response) -> response.text("first-get"));
      app.routes().get("/items/{id:[0-9]+}", (_, response) -> response.text("later-get"));
      app.start();

      assertEquals("first-get", send(client, app, "/items/1").body());
      assertEquals("later-get", send(client, app, "/items/12").body());
      assertEquals("first-post", send(client, app, "/items/1", "POST").body());
    }
  }

  @Test
  void constraintTestsCanonicalEncodedSegmentBeforePathParameterDecoding() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var client = HttpClient.newHttpClient()) {
      app.routes().get("/users/{id:[0-9]+}", (_, response) -> response.text("digits"));
      app.routes()
          .get(
              "/digits/{id:[0-9]+}",
              (request, response) -> response.text(request.pathParam("id").orElseThrow()));
      app.routes()
          .get(
              "/users/{value}",
              (request, response) -> response.text(request.pathParam("value").orElseThrow()));
      app.start();

      assertEquals("digits", send(client, app, "/users/1").body());
      assertEquals("digits", send(client, app, "/users/%31").body());
      assertEquals("1", send(client, app, "/digits/%31").body());
      assertEquals("a b", send(client, app, "/users/a%20b").body());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/users/{id:(a+)+}",
        "/users/{id:[0-9]+[A-Z]+}",
        "/users/{id:[0-9]{1,}}",
        "/users/{id:[0-9]{999999}}",
        "/users/{id:[z-a]+}",
        "/users/{id:[--z]}",
        "/users/{id:[A-z]+}",
        "/users/{id:[]+}",
        "/users/{id:[0-9]{0}}",
        "/users/{id:[0-9]+",
        "/users/{bad name:[0-9]+}"
      })
  void rejectsUnsupportedOrMalformedConstraintSyntax(String pattern) throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      assertThrows(IllegalArgumentException.class, () -> app.routes().get(pattern, (_, _) -> {}));
    }
  }

  @Test
  void acceptsBoundedRepetitionAndRejectsEquivalentConstraintShapes() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .path(
              "/api",
              routes -> routes.get("/users/{id:[0-9]{1,3}}", (_, response) -> response.text("ok")));
      assertThrows(
          IllegalArgumentException.class,
          () -> app.routes().get("/api/users/{other:[0-9]{1,3}}", (_, _) -> {}));
      app.start();

      assertEquals("ok", send(client, app, "/api/users/123").body());
      assertEquals(404, send(client, app, "/api/users/1234").statusCode());
    }
  }

  @Test
  void serverSentEventRouteUsesTheSameConstraint() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .sse(
              "/events/{id:[0-9]+}",
              (request, response) ->
                  response.startEventStream().send(request.pathParam("id").orElseThrow()));
      app.start();

      assertEquals("data: 42\n\n", send(client, app, "/events/42").body());
      assertEquals(404, send(client, app, "/events/topic").statusCode());
    }
  }

  @Test
  void composesConstrainedSegmentsBeforeAndAfterLiteralSuffixes() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .path(
              "/orders/{orderId:[0-9]+}",
              order ->
                  order.get(
                      "/items/{itemCode:[A-Za-z]+}",
                      (request, response) ->
                          response.text(
                              request.pathParam("orderId").orElseThrow()
                                  + ":"
                                  + request.pathParam("itemCode").orElseThrow()
                                  + ":"
                                  + request.routePattern().orElseThrow())));
      app.start();

      assertEquals(
          "42:Ab:/orders/{orderId:[0-9]+}/items/{itemCode:[A-Za-z]+}",
          send(client, app, "/orders/42/items/Ab").body());
      assertEquals(404, send(client, app, "/orders/abc/items/Ab").statusCode());
      assertEquals(404, send(client, app, "/orders/42/items/12").statusCode());
    }
  }

  @Test
  void simpleRepetitionNeverMatchesAnEmptySegment() throws Exception {
    try (var app = new Shoostr(new Options("127.0.0.1", 0, 1024, 1024, 128, 5000));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/flags/{letter:[A-Z]?}",
              (request, response) -> response.text(request.pathParam("letter").orElseThrow()));
      app.routes()
          .get(
              "/slugs/{slug:[A-Za-z0-9_-]*}",
              (request, response) -> response.text(request.pathParam("slug").orElseThrow()));
      app.start();

      assertEquals("A", send(client, app, "/flags/A").body());
      assertEquals(404, send(client, app, "/flags/AB").statusCode());
      assertEquals(404, send(client, app, "/flags/").statusCode());
      assertEquals("a-B_9", send(client, app, "/slugs/a-B_9").body());
      assertEquals(404, send(client, app, "/slugs/").statusCode());
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
