package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class CatchAllRoutingTest {
  @Test
  void capturesOneOrManyPathSegmentsButNotAnEmptyTail() throws Exception {
    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(1024);
    configuredOptions.setMaxResponseBytes(1024);
    configuredOptions.setStreamBufferBytes(128);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(MultipartOptions.defaults());

    {
      var app = createAppForCapturesOneOrManyPathSegmentsButNotAnEmptyTail(configuredOptions);

      try (Closeable appCleanup = app::close) {
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
  }

  private Shoostr createAppForCapturesOneOrManyPathSegmentsButNotAnEmptyTail(
      Options configuredOptions) {
    return new Shoostr(configuredOptions)
        .modifyServer(
            nativeServer -> {
              var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
              nativeConnector.setHost("127.0.0.1");
              nativeConnector.setPort(0);
              nativeConnector.setIdleTimeout(5000);
            });
  }

  @Test
  void prefersLiteralAndSingleSegmentsBeforeCatchAllInEitherRegistrationOrder() throws Exception {
    for (boolean catchAllFirst : new boolean[] {true, false}) {
      var configuredOptions = new Options();
      configuredOptions.setMaxRequestBytes(1024);
      configuredOptions.setMaxResponseBytes(1024);
      configuredOptions.setStreamBufferBytes(128);
      configuredOptions.setMaxParameters(1000);
      configuredOptions.setMultipart(MultipartOptions.defaults());

      {
        var app = createAppForCatchAllPrecedence(configuredOptions);

        try (Closeable appCleanup1 = app::close) {
          Consumer<Routes> catchAll = this::registerCatchAll;
          Consumer<Routes> specific = this::registerSpecificRoutes;
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
                        request ->
                            request.path("/files/fixed/other").timeout(Duration.ofSeconds(5)),
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
  }

  private Shoostr createAppForCatchAllPrecedence(Options configuredOptions) {
    return new Shoostr(configuredOptions)
        .modifyServer(
            nativeServer -> {
              var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
              nativeConnector.setHost("127.0.0.1");
              nativeConnector.setPort(0);
              nativeConnector.setIdleTimeout(5000);
            });
  }

  private void registerCatchAll(Routes routes) {
    routes.get(
        "/{*path}",
        (request, response) -> response.text("tail:" + request.pathParam("path").orElseThrow()));
    routes.post("/{*path}", (ignored, response) -> response.text("post-tail"));
  }

  private void registerSpecificRoutes(Routes routes) {
    routes.get("/fixed", (ignoredRequest, response) -> response.text("literal"));
    routes.get(
        "/{id}",
        (request, response) -> response.text("segment:" + request.pathParam("id").orElseThrow()));
    routes.get("/fixed/end", (ignoredRequest, response) -> response.text("literal-end"));
  }

  @Test
  void composesCatchAllWithParentParametersAndPreservesDecodedTailSpelling() throws Exception {
    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(1024);
    configuredOptions.setMaxResponseBytes(1024);
    configuredOptions.setStreamBufferBytes(128);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(MultipartOptions.defaults());

    {
      var app =
          createAppForComposesCatchAllWithParentParametersAndPreservesDecodedTailSpelling(
              configuredOptions);

      try (Closeable appCleanup2 = app::close) {
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
                          request
                              .path("/accounts/a/files/a%20b/c+d/")
                              .timeout(Duration.ofSeconds(5)),
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
  }

  private Shoostr createAppForComposesCatchAllWithParentParametersAndPreservesDecodedTailSpelling(
      Options configuredOptions) {
    return new Shoostr(configuredOptions)
        .modifyServer(
            nativeServer -> {
              var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
              nativeConnector.setHost("127.0.0.1");
              nativeConnector.setPort(0);
              nativeConnector.setIdleTimeout(5000);
            });
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
    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup3 = app::close) {
        var routes = app.routes();

        assertThrows(
            IllegalArgumentException.class,
            () -> routes.get(pattern, (ignoredRequest, ignoredResponse) -> {}));
      }
    }
  }

  @Test
  void rejectsEquivalentCatchAllShapesForTheSameMethod() throws Exception {
    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup4 = app::close) {
        var routes = app.routes();
        routes.get("/files/{*path}", (ignoredRequest, ignoredResponse) -> {});

        assertThrows(
            IllegalArgumentException.class,
            () -> routes.get("/files/{*rest}", (ignoredRequest, ignoredResponse) -> {}));

        routes.post("/files/{*rest}", (ignoredRequest, ignoredResponse) -> {});
      }
    }
  }

  @Test
  void serverSentEventRouteUsesTheSameCatchAllCapture() throws Exception {
    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(1024);
    configuredOptions.setMaxResponseBytes(1024);
    configuredOptions.setStreamBufferBytes(128);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(MultipartOptions.defaults());

    {
      var app = createAppForServerSentEventRouteUsesTheSameCatchAllCapture(configuredOptions);

      try (Closeable appCleanup5 = app::close) {
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
  }

  private Shoostr createAppForServerSentEventRouteUsesTheSameCatchAllCapture(
      Options configuredOptions) {
    return new Shoostr(configuredOptions)
        .modifyServer(
            nativeServer -> {
              var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
              nativeConnector.setHost("127.0.0.1");
              nativeConnector.setPort(0);
              nativeConnector.setIdleTimeout(5000);
            });
  }

  @Test
  void keepsCaseAndTrailingSeparatorsDistinctWithoutReadingAnUnusedCapture() throws Exception {
    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(1024);
    configuredOptions.setMaxResponseBytes(1024);
    configuredOptions.setStreamBufferBytes(128);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(MultipartOptions.defaults());

    {
      var app =
          createAppForKeepsCaseAndTrailingSeparatorsDistinctWithoutReadingAnUnusedCapture(
              configuredOptions);

      try (Closeable appCleanup6 = app::close) {
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

  private Shoostr createAppForKeepsCaseAndTrailingSeparatorsDistinctWithoutReadingAnUnusedCapture(
      Options configuredOptions) {
    return new Shoostr(configuredOptions)
        .modifyServer(
            nativeServer -> {
              var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
              nativeConnector.setHost("127.0.0.1");
              nativeConnector.setPort(0);
              nativeConnector.setIdleTimeout(5000);
            });
  }
}
