package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScopedCorsTest {
  @Test
  void inheritedSsePolicySkipsPreflightWorkAndPreservesSharingThroughEventFlushes()
      throws Exception {
    var conditions = new AtomicInteger();
    var handlers = new AtomicInteger();
    var cors = new Cors(CorsPolicy.builder().origins("https://reader.example").build());

    try (var app = new Shoostr()) {
      app.extensions(cors);
      app.routes()
          .path(
              "/feed",
              group ->
                  group.when(
                      request -> {
                        conditions.incrementAndGet();
                        return true;
                      },
                      selected ->
                          selected.sse(
                              "/events",
                              (request, response) -> {
                                handlers.incrementAndGet();
                                response.setHeader(HttpHeaders.VARY.value(), "Accept");
                                var events = response.startEventStream();
                                events.send("ready");
                                events.send("done");
                              })),
              e -> e.with(cors));

      try (var test = TestServer.start(app)) {
        var preflight =
            test.send(
                r ->
                    r.path("/feed/events")
                        .method("OPTIONS")
                        .header("Origin", "https://reader.example")
                        .header("Access-Control-Request-Method", "GET"));
        assertEquals(204, preflight.statusCode());
        assertEquals(
            "https://reader.example",
            preflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals(0, conditions.get());
        assertEquals(0, handlers.get());

        var stream =
            test.send(
                r ->
                    r.path("/feed/events")
                        .header("Origin", "https://reader.example")
                        .header("Accept", "text/event-stream"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, stream.statusCode());
        assertEquals(
            "text/event-stream; charset=utf-8",
            stream.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("data: ready\n\ndata: done\n\n", stream.body());
        assertEquals(
            "https://reader.example",
            stream.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        var vary = String.join(",", stream.headers().allValues("Vary")).split(",\\s*");
        assertTrue(Arrays.stream(vary).anyMatch("Origin"::equalsIgnoreCase));
        assertTrue(Arrays.stream(vary).anyMatch("Accept"::equalsIgnoreCase));
        assertEquals(1, conditions.get());
        assertEquals(1, handlers.get());

        var denied =
            test.send(
                r ->
                    r.path("/feed/events")
                        .header("Origin", "https://foreign.example")
                        .header("Accept", "text/event-stream"));
        assertEquals(403, denied.statusCode());
        assertFalse(denied.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        assertEquals(1, handlers.get());
      }
    }
  }

  @Test
  void overlappingRoutesUseTheMatchedPolicyWithoutBorrowingAnotherRoutesGrant() throws Exception {
    var parameterReads = new AtomicInteger();
    var literalReads = new AtomicInteger();
    var cors = new Cors(CorsPolicy.builder().origins("https://parameter.example").build());
    var literal = CorsPolicy.builder().origins("https://literal.example").build();

    try (var app = new Shoostr()) {
      app.extensions(cors);
      app.routes()
          .get(
              "/items/{id}",
              (request, response) -> {
                parameterReads.incrementAndGet();
                response.text(request.pathParam("id").orElseThrow());
              },
              e -> e.with(cors))
          .get(
              "/items/fixed",
              (request, response) -> {
                literalReads.incrementAndGet();
                response.text("fixed");
              },
              e -> e.with(cors).policy(literal));

      try (var test = TestServer.start(app)) {
        var fixed =
            test.send(
                r -> r.path("/items/fixed").header("Origin", "https://literal.example"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, fixed.statusCode());
        assertEquals("fixed", fixed.body());
        assertEquals(
            "https://literal.example",
            fixed.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        var fixedPreflight =
            test.send(
                r ->
                    r.path("/items/fixed")
                        .method("OPTIONS")
                        .header("Origin", "https://literal.example")
                        .header("Access-Control-Request-Method", "GET"));
        assertEquals(204, fixedPreflight.statusCode());
        assertEquals(
            "https://literal.example",
            fixedPreflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());

        var denied =
            test.send(r -> r.path("/items/fixed").header("Origin", "https://parameter.example"));
        assertEquals(403, denied.statusCode());
        assertFalse(denied.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        var deniedPreflight =
            test.send(
                r ->
                    r.path("/items/fixed")
                        .method("OPTIONS")
                        .header("Origin", "https://parameter.example")
                        .header("Access-Control-Request-Method", "GET"));
        assertEquals(403, deniedPreflight.statusCode());
        assertFalse(
            deniedPreflight.headers().firstValue("Access-Control-Allow-Origin").isPresent());

        var parameter =
            test.send(
                r -> r.path("/items/other").header("Origin", "https://parameter.example"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, parameter.statusCode());
        assertEquals("other", parameter.body());
        assertEquals(
            "https://parameter.example",
            parameter.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        var parameterPreflight =
            test.send(
                r ->
                    r.path("/items/other")
                        .method("OPTIONS")
                        .header("Origin", "https://parameter.example")
                        .header("Access-Control-Request-Method", "GET"));
        assertEquals(204, parameterPreflight.statusCode());
        assertEquals(
            "https://parameter.example",
            parameterPreflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals(1, literalReads.get());
        assertEquals(1, parameterReads.get());
      }
    }
  }

  @Test
  void handlesHeadOnlyForExplicitRoutesAndStaticFilesWithTheirSelectedPolicies(
      @TempDir Path directory) throws Exception {
    Files.writeString(directory.resolve("asset.txt"), "asset");
    var reads = new AtomicInteger();
    var heads = new AtomicInteger();
    var cors = new Cors(CorsPolicy.builder().origins("https://reader.example").build());
    var headPolicy =
        CorsPolicy.builder().origins("https://head.example").methods(HttpMethods.HEAD).build();

    try (var app = new Shoostr()) {
      app.extensions(cors).configureExtensionsDefaults(e -> e.with(cors));
      app.routes()
          .get(
              "/get-only",
              (request, response) -> {
                reads.incrementAndGet();
                response.text("read");
              })
          .get("/value", (request, response) -> response.text("get"))
          .head(
              "/value",
              (request, response) -> {
                heads.incrementAndGet();
                response.text("head");
              },
              e -> e.with(cors).policy(headPolicy))
          .staticFiles("/files", directory);

      try (var test = TestServer.start(app)) {
        var missing =
            test.send(
                r -> r.path("/get-only").method("HEAD").header("Origin", "https://reader.example"));
        assertEquals(405, missing.statusCode());
        assertFalse(missing.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        var missingPreflight =
            test.send(
                r ->
                    r.path("/get-only")
                        .method("OPTIONS")
                        .header("Origin", "https://reader.example")
                        .header("Access-Control-Request-Method", "HEAD"));
        assertEquals(405, missingPreflight.statusCode());
        assertFalse(
            missingPreflight.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        assertEquals(0, reads.get());

        var head =
            test.send(
                r -> r.path("/value").method("HEAD").header("Origin", "https://head.example"));
        assertEquals(200, head.statusCode());
        assertEquals(0, head.body().length);
        assertEquals(
            "https://head.example",
            head.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        var preflight =
            test.send(
                r ->
                    r.path("/value")
                        .method("OPTIONS")
                        .header("Origin", "https://head.example")
                        .header("Access-Control-Request-Method", "HEAD"));
        assertEquals(204, preflight.statusCode());
        assertEquals(
            "https://head.example",
            preflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals(
            "HEAD", preflight.headers().firstValue("Access-Control-Allow-Methods").orElseThrow());
        assertEquals(1, heads.get());
        var denied =
            test.send(
                r -> r.path("/value").method("HEAD").header("Origin", "https://reader.example"));
        assertEquals(403, denied.statusCode());
        assertFalse(denied.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        assertEquals(1, heads.get());
        var get =
            test.send(
                r -> r.path("/value").header("Origin", "https://reader.example"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, get.statusCode());
        assertEquals("get", get.body());

        var asset =
            test.send(
                r ->
                    r.path("/files/asset.txt")
                        .method("HEAD")
                        .header("Origin", "https://reader.example"));
        assertEquals(200, asset.statusCode());
        assertEquals(0, asset.body().length);
        assertEquals("5", asset.headers().firstValue("Content-Length").orElseThrow());
        assertEquals(
            "https://reader.example",
            asset.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        var assetPreflight =
            test.send(
                r ->
                    r.path("/files/asset.txt")
                        .method("OPTIONS")
                        .header("Origin", "https://reader.example")
                        .header("Access-Control-Request-Method", "HEAD"));
        assertEquals(204, assetPreflight.statusCode());
        assertEquals(
            "https://reader.example",
            assetPreflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals(
            "HEAD",
            assetPreflight.headers().firstValue("Access-Control-Allow-Methods").orElseThrow());
      }
    }
  }

  @Test
  void exclusionRetainsLocalPolicyForReinclusionWithoutChangingParentsOrSiblings()
      throws Exception {
    var base = CorsPolicy.builder().origins("https://base.example").build();
    var branch = CorsPolicy.builder().origins("https://branch.example").build();
    var leaf = CorsPolicy.builder().origins("https://leaf.example").build();
    var cors = new Cors(base);
    var retained = new AtomicReference<Cors.Configuration>();

    try (var app = new Shoostr()) {
      app.extensions(cors).configureExtensionsDefaults(e -> e.with(cors));
      app.routes()
          .path(
              "/branch",
              routes ->
                  routes
                      .get("/excluded", (request, response) -> response.text("excluded"))
                      .path(
                          "/included",
                          included ->
                              included
                                  .get("/value", (request, response) -> response.text("included"))
                                  .get(
                                      "/leaf",
                                      (request, response) -> response.text("leaf"),
                                      e -> e.with(cors).policy(leaf)),
                          e -> {
                            var configuration = e.with(cors);
                            e.without(cors);
                            assertSame(configuration, e.with(cors));
                          })
                      .get("/sibling", (request, response) -> response.text("sibling")),
              e -> {
                var configuration = e.with(cors);
                retained.set(configuration);
                e.without(cors);
                // Exclusion preserves editable configuration for a later child selection.
                configuration.policy(branch);
              })
          .get("/root", (request, response) -> response.text("root"));
      var configuration = retained.get();
      assertThrows(IllegalStateException.class, () -> configuration.policy(leaf));

      try (var test = TestServer.start(app)) {
        for (var path : new String[] {"/branch/excluded", "/branch/sibling"}) {
          var response = test.send(r -> r.path(path).header("Origin", "https://foreign.example"));
          assertEquals(200, response.statusCode());
          assertFalse(response.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        }
        var included =
            test.send(
                r -> r.path("/branch/included/value").header("Origin", "https://branch.example"));
        assertEquals(200, included.statusCode());
        assertEquals(
            "https://branch.example",
            included.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals(
            403,
            test.send(
                    r -> r.path("/branch/included/value").header("Origin", "https://leaf.example"))
                .statusCode());
        var child =
            test.send(
                r -> r.path("/branch/included/leaf").header("Origin", "https://leaf.example"));
        assertEquals(200, child.statusCode());
        assertEquals(
            "https://leaf.example",
            child.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals(
            403,
            test.send(
                    r -> r.path("/branch/included/leaf").header("Origin", "https://branch.example"))
                .statusCode());
        var root = test.send(r -> r.path("/root").header("Origin", "https://base.example"));
        assertEquals(200, root.statusCode());
        assertEquals(
            "https://base.example",
            root.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals(
            403,
            test.send(r -> r.path("/root").header("Origin", "https://branch.example"))
                .statusCode());
        assertEquals(base, cors.policy());
      }
    }
  }

  @Test
  void permitsIndependentGroupsAtTheSamePrefixToSelectPoliciesForTheirOwnEndpoints()
      throws Exception {
    var reader = new Cors(CorsPolicy.builder().origins(Set.of("https://reader.example")).build());
    var writer =
        new Cors(
            CorsPolicy.builder()
                .origins(Set.of("https://writer.example"))
                .methods(Set.of(HttpMethods.POST))
                .build());

    try (var app = new Shoostr()) {
      app.extensions(reader, writer);
      app.routes()
          .path(
              "/api",
              routes -> routes.get("/value", (request, response) -> response.text("read")),
              e -> e.with(reader))
          .path(
              "/api",
              routes -> routes.post("/value", (request, response) -> response.text("written")),
              e -> e.with(writer));

      try (var test = TestServer.start(app)) {
        var read =
            test.send(
                r -> r.path("/api/value").header("Origin", "https://reader.example"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, read.statusCode());
        assertEquals("read", read.body());
        assertEquals(
            "https://reader.example",
            read.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        var written =
            test.send(
                r -> r.path("/api/value").method("POST").header("Origin", "https://writer.example"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, written.statusCode());
        assertEquals("written", written.body());
        assertEquals(
            "https://writer.example",
            written.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals(
            403,
            test.send(
                    r ->
                        r.path("/api/value")
                            .method("POST")
                            .header("Origin", "https://reader.example"))
                .statusCode());
        assertEquals(
            403,
            test.send(r -> r.path("/api/value").header("Origin", "https://writer.example"))
                .statusCode());
      }
    }
  }

  @Test
  void neverGrantsGroupPolicyToMissingOrIndependentlyRegisteredTargets() throws Exception {
    var cors = new Cors(CorsPolicy.builder().origins(Set.of("https://client.example")).build());

    try (var app = new Shoostr()) {
      app.extensions(cors);
      app.routes()
          .path(
              "/api",
              routes -> routes.get("/selected", (request, response) -> response.text("selected")),
              e -> e.with(cors));
      app.routes().get("/api/independent", (request, response) -> response.text("independent"));

      try (var test = TestServer.start(app)) {
        var missing =
            test.send(
                r ->
                    r.path("/api/missing")
                        .method("OPTIONS")
                        .header("Origin", "https://client.example")
                        .header("Access-Control-Request-Method", "GET"));
        assertEquals(404, missing.statusCode());
        assertFalse(missing.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        var wrongMethod =
            test.send(
                r ->
                    r.path("/api/selected")
                        .method("OPTIONS")
                        .header("Origin", "https://client.example")
                        .header("Access-Control-Request-Method", "POST"));
        assertEquals(405, wrongMethod.statusCode());
        assertFalse(wrongMethod.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        var independent =
            test.send(r -> r.path("/api/independent").header("Origin", "https://client.example"));
        assertEquals(200, independent.statusCode());
        assertFalse(independent.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        var selected =
            test.send(r -> r.path("/api/selected").header("Origin", "https://client.example"));
        assertEquals(
            "https://client.example",
            selected.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
      }
    }
  }

  @Test
  void replacesTheCompletePolicyWithoutLeakingInheritedGrantsOrMutatingSiblings() throws Exception {
    var parent =
        CorsPolicy.builder()
            .origins(Set.of("https://parent.example"))
            .methods(Set.of(HttpMethods.POST))
            .allowedHeaders(Set.of(HttpHeaders.of("X-Parent")))
            .exposedHeaders(Set.of(HttpHeaders.ETAG))
            .credentials(true)
            .maxAgeSeconds(600)
            .build();
    var replacement = CorsPolicy.builder().origins(Set.of("https://child.example")).build();
    var cors = new Cors(parent);

    try (var app = new Shoostr()) {
      app.extensions(cors).configureExtensionsDefaults(e -> e.with(cors));
      app.routes()
          .get(
              "/child",
              (request, response) -> response.text("child"),
              e -> e.with(cors).policy(replacement))
          .post(
              "/child",
              (request, response) -> response.text("changed"),
              e -> e.with(cors).policy(replacement))
          .post("/sibling", (request, response) -> response.text("sibling"));

      try (var test = TestServer.start(app)) {
        var denied =
            test.send(
                r -> r.path("/child").method("POST").header("Origin", "https://parent.example"));
        assertEquals(403, denied.statusCode());
        assertFalse(denied.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        var accepted =
            test.send(
                r ->
                    r.path("/child")
                        .method("OPTIONS")
                        .header("Origin", "https://child.example")
                        .header("Access-Control-Request-Method", "GET"));
        assertEquals(204, accepted.statusCode());
        assertEquals("5", accepted.headers().firstValue("Access-Control-Max-Age").orElseThrow());
        assertFalse(accepted.headers().firstValue("Access-Control-Allow-Credentials").isPresent());
        assertEquals(
            403,
            test.send(
                    r ->
                        r.path("/child")
                            .method("OPTIONS")
                            .header("Origin", "https://child.example")
                            .header("Access-Control-Request-Method", "POST"))
                .statusCode());
        assertEquals(
            403,
            test.send(
                    r ->
                        r.path("/child")
                            .method("OPTIONS")
                            .header("Origin", "https://child.example")
                            .header("Access-Control-Request-Method", "GET")
                            .header("Access-Control-Request-Headers", "X-Parent"))
                .statusCode());
        var actual = test.send(r -> r.path("/child").header("Origin", "https://child.example"));
        assertEquals(200, actual.statusCode());
        assertFalse(actual.headers().firstValue("Access-Control-Expose-Headers").isPresent());
        var sibling =
            test.send(
                r -> r.path("/sibling").method("POST").header("Origin", "https://parent.example"));
        assertEquals(200, sibling.statusCode());
        assertEquals(
            "true", sibling.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
        assertEquals(parent, cors.policy());
      }
    }
  }

  @Test
  void selectsDifferentPoliciesForGetAndPostAtTheSamePath() throws Exception {
    var read = new Cors(CorsPolicy.builder().origins(Set.of("https://reader.example")).build());
    var write =
        new Cors(
            CorsPolicy.builder()
                .origins(Set.of("https://writer.example"))
                .methods(Set.of(HttpMethods.POST))
                .build());

    try (var app = new Shoostr()) {
      app.extensions(read, write)
          .routes(
              routes ->
                  routes
                      .get(
                          "/shared",
                          (request, response) -> response.text("read"),
                          extensions -> extensions.with(read))
                      .post(
                          "/shared",
                          (request, response) -> response.text("write"),
                          extensions -> extensions.with(write)));

      try (var test = TestServer.start(app)) {
        assertEquals(
            204,
            test.send(
                    request ->
                        request
                            .path("/shared")
                            .method("OPTIONS")
                            .header("Origin", "https://reader.example")
                            .header("Access-Control-Request-Method", "GET"))
                .statusCode());
        assertEquals(
            204,
            test.send(
                    request ->
                        request
                            .path("/shared")
                            .method("OPTIONS")
                            .header("Origin", "https://writer.example")
                            .header("Access-Control-Request-Method", "POST"))
                .statusCode());
        assertEquals(
            403,
            test.send(
                    request ->
                        request
                            .path("/shared")
                            .method("OPTIONS")
                            .header("Origin", "https://reader.example")
                            .header("Access-Control-Request-Method", "POST"))
                .statusCode());
        assertEquals(
            403,
            test.send(
                    request ->
                        request
                            .path("/shared")
                            .method("OPTIONS")
                            .header("Origin", "https://writer.example")
                            .header("Access-Control-Request-Method", "GET"))
                .statusCode());
      }
    }
  }

  @Test
  void
      inheritsAnImmutableOwnerAndRejectsEvenAnEqualPolicyFromAnotherInstanceWithoutLeakingTheFailedRoute()
          throws Exception {
    var builder =
        CorsPolicy.builder()
            .origins(Set.of("https://parent.example"))
            .allowedHeaders(Set.of(HttpHeaders.of("X-Proof")))
            .exposedHeaders(Set.of(HttpHeaders.ETAG));
    var policy = builder.build();
    var parent = new Cors(policy);
    var conflicting = new Cors(policy);
    builder.origins(Set.of("https://changed.example"));
    var allowedHeaders = policy.allowedHeaders();
    var exposedHeaders = parent.policy().exposedHeaders();
    org.junit.jupiter.api.Assertions.assertThrows(
        UnsupportedOperationException.class, allowedHeaders::clear);
    org.junit.jupiter.api.Assertions.assertThrows(
        UnsupportedOperationException.class, exposedHeaders::clear);

    try (var app = new Shoostr()) {
      app.extensions(parent, conflicting)
          .routes(
              routes ->
                  routes.path(
                      "/parent",
                      child -> {
                        org.junit.jupiter.api.Assertions.assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                child.get(
                                    "/retry",
                                    (request, response) -> response.text("wrong"),
                                    extensions -> extensions.with(conflicting)));
                        child.get(
                            "/retry",
                            (request, response) -> response.text("parent"),
                            extensions -> extensions.with(parent));
                      },
                      extensions -> extensions.with(parent)));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request -> request.path("/parent/retry").header("Origin", "https://parent.example"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("parent", result.body());
        assertEquals(
            java.util.List.of("https://parent.example"),
            result.headers().allValues("Access-Control-Allow-Origin"));
        assertEquals(
            403,
            test.send(
                    request ->
                        request.path("/parent/retry").header("Origin", "https://changed.example"))
                .statusCode());
      }
    }
  }

  @Test
  void inspectsConditionalAndStaticTargetsWithoutEvaluatingTargetConditions(
      @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
    java.nio.file.Files.writeString(directory.resolve("asset.txt"), "asset");
    var calls = new AtomicInteger();
    var cors = new Cors(CorsPolicy.builder().origins(Set.of("https://client.example")).build());

    try (var app = new Shoostr()) {
      app.extensions(cors)
          .routes(
              routes ->
                  routes.path(
                      "/",
                      selected ->
                          selected
                              .when(
                                  request -> {
                                    calls.incrementAndGet();
                                    return false;
                                  },
                                  conditional ->
                                      conditional.get(
                                          "/conditional",
                                          (request, response) -> response.text("never")))
                              .staticFiles("/files", directory),
                      extensions -> extensions.with(cors)));

      try (var test = TestServer.start(app)) {
        for (var path : new String[] {"/conditional", "/files/asset.txt"}) {
          assertEquals(
              204,
              test.send(
                      request ->
                          request
                              .path(path)
                              .method("OPTIONS")
                              .header("Origin", "https://client.example")
                              .header("Access-Control-Request-Method", "GET"))
                  .statusCode());
        }
        assertEquals(0, calls.get());
        assertEquals(404, test.send(request -> request.path("/conditional")).statusCode());
        assertEquals(1, calls.get());
      }
    }
  }

  @Test
  void resolvesTheRegisteredPreflightTargetWithoutEnteringItsSessionOrLifecycle() throws Exception {
    var policy =
        CorsPolicy.builder()
            .origins(Set.of("https://client.example"))
            .methods(Set.of(HttpMethods.POST))
            .allowedHeaders(Set.of(HttpHeaders.of("X-Proof")))
            .build();
    var cors = new Cors(policy);
    var csrf = new Csrf();

    try (var app = new Shoostr()) {
      app.sessions().extensions(cors, csrf);
      app.onRequestHeaders(
          (request, response) -> {
            if ("OPTIONS".equals(request.method())) {
              org.junit.jupiter.api.Assertions.assertTrue(request.getSession().isEmpty());
            }
          });
      app.routes()
          .post(
              "/target",
              (request, response) -> response.text("posted"),
              extensions -> {
                extensions.with(cors);
                extensions.with(csrf);
                extensions
                    .lifecycle()
                    .beforeRouteHandler(
                        (request, response) -> {
                          request.getOrCreateSession().setAttribute("admitted", true);
                          response.setHeader("X-Target-Hook", "ran");
                        });
              });

      try (var test = TestServer.start(app)) {
        var accepted =
            test.send(
                request ->
                    request
                        .path("/target")
                        .method("OPTIONS")
                        .header("Origin", "https://client.example")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "X-Proof"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, accepted.statusCode());
        assertEquals(
            "https://client.example",
            accepted.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertFalse(accepted.headers().firstValue("X-Target-Hook").isPresent());
        assertFalse(accepted.headers().firstValue("Set-Cookie").isPresent());
        assertEquals(
            405,
            test.send(
                    request ->
                        request
                            .path("/target")
                            .method("OPTIONS")
                            .header("Origin", "https://client.example")
                            .header("Access-Control-Request-Method", "DELETE"))
                .statusCode());
        assertEquals(
            404,
            test.send(
                    request ->
                        request
                            .path("/missing")
                            .method("OPTIONS")
                            .header("Origin", "https://client.example")
                            .header("Access-Control-Request-Method", "POST"))
                .statusCode());
      }
    }
  }
}
