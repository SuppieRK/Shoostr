package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class ConstrainedRoutingTest {
  @Test
  void unionsOnlyCompleteMatchesAfterConstrainedSuffixMiss() throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.routes()
          .post("/mix/{id:[0-9]+}/details", (request, response) -> response.text("constrained"))
          .put(
              "/mix/{name}/events",
              (request, response) -> response.text(request.pathParam("name").orElseThrow()))
          .delete("/mix/{*tail}", (request, response) -> response.text("tail"))
          .get(
              "/plain/{name}",
              (request, response) -> response.text(request.pathParam("name").orElseThrow()));

      try (var test = TestServer.start(app)) {
        var rejected = test.send(request -> request.path("/mix/42/events"));

        assertEquals(405, rejected.statusCode());
        assertEquals(
            Set.of("PUT", "DELETE"),
            Set.of(rejected.headers().firstValue("Allow").orElseThrow().split(", ")));
        assertEquals(
            "42",
            test.send(
                    request -> request.path("/mix/42/events").method("PUT"),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "alice",
            test.send(request -> request.path("/plain/alice"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void dispatchesByNamedSegmentConstraintAndExposesItsCapture() throws Exception {
    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(1024);
    configuredOptions.setMaxResponseBytes(1024);
    configuredOptions.setStreamBufferBytes(128);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(new MultipartOptions());

    {
      var app =
          createAppForDispatchesByNamedSegmentConstraintAndExposesItsCapture(configuredOptions);

      try (Closeable appCleanup = app::close) {
        app.routes()
            .get(
                "/users/{id:[0-9]+}",
                (request, response) -> response.text(request.pathParam("id").orElseThrow()));

        try (var test = TestServer.start(app)) {

          assertEquals(
              "123",
              test.send(
                      request -> request.path("/users/123").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              404,
              test.send(
                      request -> request.path("/users/abc").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .statusCode());
          assertEquals(
              404,
              test.send(
                      request -> request.path("/users/123/extra").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .statusCode());
        }
      }
    }
  }

  private Shoostr createAppForDispatchesByNamedSegmentConstraintAndExposesItsCapture(
      Options configuredOptions) {
    return new Shoostr(configuredOptions)
        .modifyServer(nativeServer -> {
          var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
          nativeConnector.setHost("127.0.0.1");
          nativeConnector.setPort(0);
          nativeConnector.setIdleTimeout(5000);
        });
  }

  @Test
  void completedRangeEndpointDoesNotStartAnotherRange() throws Exception {
    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(1024);
    configuredOptions.setMaxResponseBytes(1024);
    configuredOptions.setStreamBufferBytes(128);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(new MultipartOptions());

    {
      var app = createAppForCompletedRangeEndpointDoesNotStartAnotherRange(configuredOptions);

      try (Closeable appCleanup1 = app::close) {
        app.routes().get("/letters/{id:[a-c-e]}", (ignored, response) -> response.text("matched"));

        try (var test = TestServer.start(app)) {

          assertEquals(
              "matched",
              test.send(
                      request -> request.path("/letters/-").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              "matched",
              test.send(
                      request -> request.path("/letters/e").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              404,
              test.send(
                      request -> request.path("/letters/d").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .statusCode());
        }
      }
    }
  }

  private Shoostr createAppForCompletedRangeEndpointDoesNotStartAnotherRange(
      Options configuredOptions) {
    return new Shoostr(configuredOptions)
        .modifyServer(nativeServer -> {
          var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
          nativeConnector.setHost("127.0.0.1");
          nativeConnector.setPort(0);
          nativeConnector.setIdleTimeout(5000);
        });
  }

  @Test
  void rejectsAnInvalidConstraintAtRegistration() throws Exception {
    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup2 = app::close) {
        var routes = app.routes();

        assertThrows(
            IllegalArgumentException.class,
            () -> routes.get("/users/{id:[0-9+}", (ignoredRequest, ignoredResponse) -> {}));
      }
    }
  }

  @Test
  void prefersLiteralThenConstrainedThenPlainThenCatchAllAndFallsBackByMethod() throws Exception {
    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(1024);
    configuredOptions.setMaxResponseBytes(1024);
    configuredOptions.setStreamBufferBytes(128);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(new MultipartOptions());

    {
      var app =
          createAppForPrefersLiteralThenConstrainedThenPlainThenCatchAllAndFallsBackByMethod(
              configuredOptions);

      try (Closeable appCleanup3 = app::close) {
        app.routes().get("/users/{*tail}", (ignoredRequest, response) -> response.text("tail"));
        app.routes().get("/users/{name}", (ignoredRequest, response) -> response.text("plain"));
        app.routes()
            .get("/users/{id:[0-9]+}", (ignoredRequest, response) -> response.text("constrained"));
        app.routes().get("/users/123", (ignoredRequest, response) -> response.text("literal"));
        app.routes()
            .post("/users/{id:[0-9]+}", (ignoredRequest, response) -> response.text("post"));

        try (var test = TestServer.start(app)) {

          assertEquals(
              "literal",
              test.send(
                      request -> request.path("/users/123").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              "constrained",
              test.send(
                      request -> request.path("/users/456").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              "plain",
              test.send(
                      request -> request.path("/users/abc").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              "tail",
              test.send(
                      request -> request.path("/users/456/more").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              "post",
              test.send(
                      request ->
                          request.path("/users/456").timeout(Duration.ofSeconds(5)).method("POST"),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              405,
              test.send(
                      request ->
                          request
                              .path("/users/456")
                              .timeout(Duration.ofSeconds(5))
                              .method("DELETE"),
                      HttpResponse.BodyHandlers.ofString())
                  .statusCode());
          assertEquals(
              "GET, POST",
              test.send(
                      request ->
                          request
                              .path("/users/456")
                              .timeout(Duration.ofSeconds(5))
                              .method("DELETE"),
                      HttpResponse.BodyHandlers.ofString())
                  .headers()
                  .firstValue("Allow")
                  .orElseThrow());
        }
      }
    }
  }

  private Shoostr
      createAppForPrefersLiteralThenConstrainedThenPlainThenCatchAllAndFallsBackByMethod(
          Options configuredOptions) {
    return new Shoostr(configuredOptions)
        .modifyServer(nativeServer -> {
          var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
          nativeConnector.setHost("127.0.0.1");
          nativeConnector.setPort(0);
          nativeConnector.setIdleTimeout(5000);
        });
  }

  @Test
  void retainsTheEarlierWinnerWhenLaterConstraintAlsoMatches() throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.routes()
          .get("/items/{id:[0-9]+}", (request, response) -> response.text("first"))
          .get("/items/{id:[0-9]}", (request, response) -> response.text("later"));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "first",
            test.send(request -> request.path("/items/1"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void preservesRegistrationOrderAndCapturesForEquivalentConstraintSpellings(
      boolean compactRegisteredFirst) throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      var compact = "/items/{digit:[0-9]}/details";
      var expanded = "/items/{number:[0123456789]}/details";
      var first = compactRegisteredFirst ? compact : expanded;
      var second = compactRegisteredFirst ? expanded : compact;
      var firstName = compactRegisteredFirst ? "digit" : "number";
      var secondName = compactRegisteredFirst ? "number" : "digit";
      app.routes()
          .get(
              first,
              (request, response) ->
                  response.text("first:" + request.pathParam(firstName).orElseThrow()))
          .get(second, (request, response) -> response.text("second"))
          .post(
              second,
              (request, response) ->
                  response.text("post:" + request.pathParam(secondName).orElseThrow()));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "first:7",
            test.send(
                    request -> request.path("/items/7/details"),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "post:7",
            test.send(
                    request -> request.path("/items/7/details").method("POST"),
                    HttpResponse.BodyHandlers.ofString())
                .body());

        var rejected = test.send(request -> request.path("/items/7/details").method("DELETE"));
        assertEquals(405, rejected.statusCode());
        assertEquals(
            Set.of("GET", "POST"),
            Set.of(rejected.headers().firstValue("Allow").orElseThrow().split(", ")));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void ranksTheMatchedEndpointRatherThanAnotherRouteInTheSameConstraintBranch(
      boolean singleDigitRegisteredFirst) throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.routes().get("/items/{id:[0-9]+}/other", (request, response) -> response.text("other"));

      var first =
          singleDigitRegisteredFirst ? "/items/{id:[0-9]}/details" : "/items/{id:[0-9]+}/details";
      var second =
          singleDigitRegisteredFirst ? "/items/{id:[0-9]+}/details" : "/items/{id:[0-9]}/details";
      app.routes()
          .get(
              first,
              (request, response) ->
                  response.text("first:" + request.pathParam("id").orElseThrow()))
          .get(second, (request, response) -> response.text("second"))
          .post("/items/{id:[0-9]+}/details", (request, response) -> response.text("post"))
          .put("/items/{name}/details", (request, response) -> response.text("plain"));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "first:1",
            test.send(
                    request -> request.path("/items/1/details"),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "post",
            test.send(
                    request -> request.path("/items/1/details").method("POST"),
                    HttpResponse.BodyHandlers.ofString())
                .body());

        var rejected = test.send(request -> request.path("/items/1/details").method("DELETE"));
        assertEquals(405, rejected.statusCode());
        assertEquals(
            Set.of("GET", "POST", "PUT"),
            Set.of(rejected.headers().firstValue("Allow").orElseThrow().split(", ")));
      }
    }
  }

  @Test
  void firstMatchingConstrainedRouteForTheRequestedMethodWins() throws Exception {
    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(1024);
    configuredOptions.setMaxResponseBytes(1024);
    configuredOptions.setStreamBufferBytes(128);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(new MultipartOptions());

    {
      var app =
          createAppForFirstMatchingConstrainedRouteForTheRequestedMethodWins(configuredOptions);

      try (Closeable appCleanup4 = app::close) {
        app.routes()
            .post("/items/{id:[0-9]+}", (ignoredRequest, response) -> response.text("first-post"));
        app.routes()
            .get("/items/{id:[0-9]}", (ignoredRequest, response) -> response.text("first-get"));
        app.routes()
            .get("/items/{id:[0-9]+}", (ignoredRequest, response) -> response.text("later-get"));

        try (var test = TestServer.start(app)) {

          assertEquals(
              "first-get",
              test.send(
                      request -> request.path("/items/1").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              "later-get",
              test.send(
                      request -> request.path("/items/12").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              "first-post",
              test.send(
                      request ->
                          request.path("/items/1").timeout(Duration.ofSeconds(5)).method("POST"),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  private Shoostr createAppForFirstMatchingConstrainedRouteForTheRequestedMethodWins(
      Options configuredOptions) {
    return new Shoostr(configuredOptions)
        .modifyServer(nativeServer -> {
          var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
          nativeConnector.setHost("127.0.0.1");
          nativeConnector.setPort(0);
          nativeConnector.setIdleTimeout(5000);
        });
  }

  @Test
  void constraintTestsCanonicalEncodedSegmentBeforePathParameterDecoding() throws Exception {
    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(1024);
    configuredOptions.setMaxResponseBytes(1024);
    configuredOptions.setStreamBufferBytes(128);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(new MultipartOptions());

    {
      var app =
          createAppForConstraintTestsCanonicalEncodedSegmentBeforePathParameterDecoding(
              configuredOptions);

      try (Closeable appCleanup5 = app::close) {
        app.routes()
            .get("/users/{id:[0-9]+}", (ignoredRequest, response) -> response.text("digits"));
        app.routes()
            .get(
                "/digits/{id:[0-9]+}",
                (request, response) -> response.text(request.pathParam("id").orElseThrow()));
        app.routes()
            .get(
                "/users/{value}",
                (request, response) -> response.text(request.pathParam("value").orElseThrow()));

        try (var test = TestServer.start(app)) {

          assertEquals(
              "digits",
              test.send(
                      request -> request.path("/users/1").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              "digits",
              test.send(
                      request -> request.path("/users/%31").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              "1",
              test.send(
                      request -> request.path("/digits/%31").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              "a b",
              test.send(
                      request -> request.path("/users/a%20b").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
        }
      }
    }
  }

  private Shoostr createAppForConstraintTestsCanonicalEncodedSegmentBeforePathParameterDecoding(
      Options configuredOptions) {
    return new Shoostr(configuredOptions)
        .modifyServer(nativeServer -> {
          var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
          nativeConnector.setHost("127.0.0.1");
          nativeConnector.setPort(0);
          nativeConnector.setIdleTimeout(5000);
        });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/users/{id:(a+)+}",
        "/users/{id:[0-9]+[A-Z]+}",
        "/users/{id:[0-9]{1,}}",
        "/users/{id:[0-9]{,2}}",
        "/users/{id:[0-9]{1,2,3}}",
        "/users/{id:[0-9]{1,٢}}",
        "/users/{id:[0-9]{2,1}}",
        "/users/{id:[0-9]{0,0}}",
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
    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup6 = app::close) {
        var routes = app.routes();

        assertThrows(
            IllegalArgumentException.class,
            () -> routes.get(pattern, (ignoredRequest, ignoredResponse) -> {}));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"!", "é"})
  void rejectsUnsupportedConstraintAtomsBeforeRegisteringTheRoute(String atom) throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      var pattern = "/items/{id:[" + atom + "]}";
      var failure =
          assertThrows(
              IllegalArgumentException.class,
              () -> app.routes().get(pattern, (request, response) -> {}));

      assertEquals("Unsupported route constraint character: " + atom, failure.getMessage());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"Z", "z", "A-A", "Z-Z", "a-a", "z-z", "0-0", "9-9", "m-m"})
  void acceptsSingletonLettersAndEqualEndedRanges(String expression) throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.routes()
          .get(
              "/items/{id:[" + expression + "]}",
              (request, response) -> response.text(request.pathParam("id").orElseThrow()));

      try (var test = TestServer.start(app)) {
        var value = expression.substring(0, 1);
        var matched =
            test.send(
                request -> request.path("/items/" + value), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, matched.statusCode());
        assertEquals(value, matched.body());
        assertEquals(404, test.send(request -> request.path("/items/!")).statusCode());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void prefersLiteralSuffixWithinTheSameConstraintRegardlessOfRegistrationOrder(
      boolean parameterFirst) throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      var routes = app.routes();
      if (parameterFirst) {
        routes.get("/items/{id:[0-9]+}/{name}", (request, response) -> response.text("parameter"));
      }

      routes.get("/items/{id:[0-9]+}/literal", (request, response) -> response.text("literal"));
      if (!parameterFirst) {
        routes.get("/items/{id:[0-9]+}/{name}", (request, response) -> response.text("parameter"));
      }

      try (var test = TestServer.start(app)) {
        assertEquals(
            "literal",
            test.send(
                    request -> request.path("/items/42/literal"),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "parameter",
            test.send(
                    request -> request.path("/items/42/other"),
                    HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void rejectsConstraintsWithoutTheirOpeningBracket() throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      var failure =
          assertThrows(
              IllegalArgumentException.class,
              () -> app.routes().get("/items/{id:xA]}", (request, response) -> {}));
      assertEquals("Expected one character class in route constraint", failure.getMessage());
    }
  }

  @Test
  void capturesValuesAfterAnEncodedLiteralPrefixAndRepetitionBraces() throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.routes()
          .path(
              "/prefix%7B",
              prefix ->
                  prefix
                      .get(
                          "/{id:[0-9]{1,3}}/{*tail}",
                          (request, response) ->
                              response.text(
                                  request.pathParam("id").orElseThrow()
                                      + "|"
                                      + request.pathParam("tail").orElseThrow()))
                      .get("/static", (request, response) -> response.text("literal")));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "12|a+b/c d",
            test.send(
                    request -> request.path("/prefix%7B/12/a%2Bb/c%20d"),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "literal",
            test.send(
                    request -> request.path("/prefix%7B/static"),
                    HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void acceptsLeadingZeroBoundsWithoutChangingTheirMatchingRange() throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.routes()
          .get(
              "/bounded/{id:[0-9]{00001,00002}}",
              (request, response) -> response.text(request.pathParam("id").orElseThrow()))
          .get(
              "/exact/{id:[0-9]{00002}}",
              (request, response) -> response.text(request.pathParam("id").orElseThrow()));

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/bounded/1")).statusCode());
        assertEquals(200, test.send(request -> request.path("/bounded/12")).statusCode());
        assertEquals(404, test.send(request -> request.path("/bounded/123")).statusCode());
        assertEquals(200, test.send(request -> request.path("/exact/12")).statusCode());
        assertEquals(404, test.send(request -> request.path("/exact/1")).statusCode());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{000001}",
        "{1,000002}",
        "{1,٢}",
        "{2,1}",
        "{0,0}",
        "{+1}",
        "{1, 2}",
        "{1,2}suffix",
        "{1,2}\n",
        "{1",
        "1}",
        "{1,2,,3}"
      })
  void rejectsMalformedRepetitionWithTheOriginalError(String repetition) throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      var pattern = "/items/{id:[0-9]" + repetition + "}";
      var failure =
          assertThrows(
              IllegalArgumentException.class,
              () -> app.routes().get(pattern, (request, response) -> {}));

      assertEquals("Unsupported route constraint repetition: " + repetition, failure.getMessage());
    }
  }

  @Test
  void acceptsZeroLowerAndFiveDigitBoundsWithoutMatchingAnEmptySegment() throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.routes()
          .get("/zero/{id:[0-9]{0,1}}", (request, response) -> response.text("zero"))
          .get("/limit/{id:[0-9]{1,99999}}", (request, response) -> response.text("limit"))
          .get("/exact/{id:[0-9]{99999}}", (request, response) -> response.text("exact"));

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/zero/1")).statusCode());
        assertEquals(404, test.send(request -> request.path("/zero/")).statusCode());
        assertEquals(404, test.send(request -> request.path("/zero/12")).statusCode());
        assertEquals(200, test.send(request -> request.path("/limit/1")).statusCode());
        assertEquals(404, test.send(request -> request.path("/exact/1")).statusCode());
      }
    }
  }

  @Test
  void acceptsBoundedRepetitionAndRejectsEquivalentConstraintShapes() throws Exception {
    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(1024);
    configuredOptions.setMaxResponseBytes(1024);
    configuredOptions.setStreamBufferBytes(128);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(new MultipartOptions());

    {
      var app =
          createAppForAcceptsBoundedRepetitionAndRejectsEquivalentConstraintShapes(
              configuredOptions);

      try (Closeable appCleanup7 = app::close) {
        var routes = app.routes();
        routes.path(
            "/api",
            nestedRoutes ->
                nestedRoutes.get(
                    "/users/{id:[0-9]{1,3}}", (ignoredRequest, response) -> response.text("ok")));

        assertThrows(
            IllegalArgumentException.class,
            () ->
                routes.get(
                    "/api/users/{other:[0-9]{1,3}}", (ignoredRequest, ignoredResponse) -> {}));

        try (var test = TestServer.start(app)) {

          assertEquals(
              "ok",
              test.send(
                      request -> request.path("/api/users/123").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              404,
              test.send(
                      request -> request.path("/api/users/1234").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .statusCode());
        }
      }
    }
  }

  private Shoostr createAppForAcceptsBoundedRepetitionAndRejectsEquivalentConstraintShapes(
      Options configuredOptions) {
    return new Shoostr(configuredOptions)
        .modifyServer(nativeServer -> {
          var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
          nativeConnector.setHost("127.0.0.1");
          nativeConnector.setPort(0);
          nativeConnector.setIdleTimeout(5000);
        });
  }

  @Test
  void serverSentEventRouteUsesTheSameConstraint() throws Exception {
    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(1024);
    configuredOptions.setMaxResponseBytes(1024);
    configuredOptions.setStreamBufferBytes(128);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(new MultipartOptions());

    {
      var app = createAppForServerSentEventRouteUsesTheSameConstraint(configuredOptions);

      try (Closeable appCleanup8 = app::close) {
        app.routes()
            .sse(
                "/events/{id:[0-9]+}",
                (request, response) ->
                    response.startEventStream().send(request.pathParam("id").orElseThrow()));

        try (var test = TestServer.start(app)) {

          assertEquals(
              "data: 42\n\n",
              test.send(
                      request -> request.path("/events/42").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              404,
              test.send(
                      request -> request.path("/events/topic").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .statusCode());
        }
      }
    }
  }

  private Shoostr createAppForServerSentEventRouteUsesTheSameConstraint(Options configuredOptions) {
    return new Shoostr(configuredOptions)
        .modifyServer(nativeServer -> {
          var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
          nativeConnector.setHost("127.0.0.1");
          nativeConnector.setPort(0);
          nativeConnector.setIdleTimeout(5000);
        });
  }

  @Test
  void composesConstrainedSegmentsBeforeAndAfterLiteralSuffixes() throws Exception {
    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(1024);
    configuredOptions.setMaxResponseBytes(1024);
    configuredOptions.setStreamBufferBytes(128);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(new MultipartOptions());

    {
      var app =
          createAppForComposesConstrainedSegmentsBeforeAndAfterLiteralSuffixes(configuredOptions);

      try (Closeable appCleanup9 = app::close) {
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

        try (var test = TestServer.start(app)) {

          assertEquals(
              "42:Ab:/orders/{orderId:[0-9]+}/items/{itemCode:[A-Za-z]+}",
              test.send(
                      request -> request.path("/orders/42/items/Ab").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              404,
              test.send(
                      request ->
                          request.path("/orders/abc/items/Ab").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .statusCode());
          assertEquals(
              404,
              test.send(
                      request -> request.path("/orders/42/items/12").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .statusCode());
        }
      }
    }
  }

  private Shoostr createAppForComposesConstrainedSegmentsBeforeAndAfterLiteralSuffixes(
      Options configuredOptions) {
    return new Shoostr(configuredOptions)
        .modifyServer(nativeServer -> {
          var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
          nativeConnector.setHost("127.0.0.1");
          nativeConnector.setPort(0);
          nativeConnector.setIdleTimeout(5000);
        });
  }

  @Test
  void simpleRepetitionNeverMatchesAnEmptySegment() throws Exception {
    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(1024);
    configuredOptions.setMaxResponseBytes(1024);
    configuredOptions.setStreamBufferBytes(128);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(new MultipartOptions());

    {
      var app = createAppForSimpleRepetitionNeverMatchesAnEmptySegment(configuredOptions);

      try (Closeable appCleanup10 = app::close) {
        app.routes()
            .get(
                "/flags/{letter:[A-Z]?}",
                (request, response) -> response.text(request.pathParam("letter").orElseThrow()));
        app.routes()
            .get(
                "/slugs/{slug:[A-Za-z0-9_-]*}",
                (request, response) -> response.text(request.pathParam("slug").orElseThrow()));

        try (var test = TestServer.start(app)) {

          assertEquals(
              "A",
              test.send(
                      request -> request.path("/flags/A").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              404,
              test.send(
                      request -> request.path("/flags/AB").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .statusCode());
          assertEquals(
              404,
              test.send(
                      request -> request.path("/flags/").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .statusCode());
          assertEquals(
              "a-B_9",
              test.send(
                      request -> request.path("/slugs/a-B_9").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .body());
          assertEquals(
              404,
              test.send(
                      request -> request.path("/slugs/").timeout(Duration.ofSeconds(5)),
                      HttpResponse.BodyHandlers.ofString())
                  .statusCode());
        }
      }
    }
  }

  private Shoostr createAppForSimpleRepetitionNeverMatchesAnEmptySegment(
      Options configuredOptions) {
    return new Shoostr(configuredOptions)
        .modifyServer(nativeServer -> {
          var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
          nativeConnector.setHost("127.0.0.1");
          nativeConnector.setPort(0);
          nativeConnector.setIdleTimeout(5000);
        });
  }
}
