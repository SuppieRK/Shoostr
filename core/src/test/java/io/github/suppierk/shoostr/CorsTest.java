package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CorsTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(10)
  void preservesCorsSharingWhenFormParsingRejectsUnsupportedMedia(boolean withOrigin)
      throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.cors(
          new CorsPolicy(
              Set.of("https://client.example"),
              Set.of(HttpMethods.POST),
              Set.of(),
              false,
              Set.of(),
              5));
      app.routes()
          .post(
              "/form",
              (request, response) -> response.text(request.formParam("name").orElseThrow()));

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request -> {
                  request
                      .path("/form")
                      .timeout(Duration.ofSeconds(3))
                      .header("Content-Type", "application/json")
                      .method("POST")
                      .body("{\"name\":\"Ada\"}".getBytes(StandardCharsets.UTF_8));
                  if (withOrigin) {
                    request.header("Origin", "https://client.example");
                  }
                },
                HttpResponse.BodyHandlers.ofString());

        assertEquals(415, result.statusCode());
        assertEquals("Unsupported Media Type", result.body());
        assertEquals(List.of("Origin"), result.headers().allValues("Vary"));
        assertEquals(
            withOrigin ? List.of("https://client.example") : List.of(),
            result.headers().allValues("Access-Control-Allow-Origin"));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(10)
  void sharesAcceptedFormResponsesOnlyWhenAnAllowedOriginIsPresent(boolean withOrigin)
      throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.cors(
          new CorsPolicy(
              Set.of("https://client.example"),
              Set.of(HttpMethods.POST),
              Set.of(),
              false,
              Set.of(),
              5));
      app.routes()
          .post(
              "/form",
              (request, response) -> response.text(request.formParam("name").orElseThrow()));

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request -> {
                  request
                      .path("/form")
                      .timeout(Duration.ofSeconds(3))
                      .header("Content-Type", "application/x-www-form-urlencoded")
                      .method("POST")
                      .body("name=Ada".getBytes(StandardCharsets.UTF_8));
                  if (withOrigin) {
                    request.header("Origin", "https://client.example");
                  }
                },
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, result.statusCode());
        assertEquals("Ada", result.body());
        assertEquals(List.of("Origin"), result.headers().allValues("Vary"));
        assertEquals(
            withOrigin ? List.of("https://client.example") : List.of(),
            result.headers().allValues("Access-Control-Allow-Origin"));
      }
    }
  }

  @Test
  void sharesAnAllowedActualResponseAndPreservesItsVaryFields() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.cors(new CorsPolicy(Set.of("https://client.example")));
      app.routes()
          .get(
              "/data", (_, response) -> response.setHeader("Vary", "Accept-Encoding").text("data"));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/data")
                        .timeout(Duration.ofSeconds(5))
                        .header("Origin", "https://client.example"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("data", result.body());
        assertEquals(
            "https://client.example",
            result.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertTrue(result.headers().allValues("Vary").toString().contains("Accept-Encoding"));
        assertTrue(result.headers().allValues("Vary").toString().contains("Origin"));
      }
    }
  }

  @Test
  void admitsPreflightWithoutExecutingRouteAuthenticationOrBusinessLogic() throws Exception {
    var admissions = new AtomicInteger();
    var gates = new AtomicInteger();
    var completions = new ArrayBlockingQueue<RequestOutcome>(2);
    var authentications = new AtomicInteger();
    var executions = new AtomicInteger();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.cors(
          new CorsPolicy(
              Set.of("https://client.example"),
              Set.of(HttpMethods.GET),
              Set.of(),
              true,
              Set.of(),
              5));
      app.beforeRouteHandler((_, _) -> gates.incrementAndGet());
      app.afterRequest(completions::add);
      app.onRequestHeaders((_, _) -> admissions.incrementAndGet());
      var admission =
          new AuthenticationExtension() {
            @Override
            public void handle(Request request, Response response) throws Exception {

              authentications.incrementAndGet();
              throw new AuthenticationRequiredException("Bearer");
            }
          };
      app.authentication(admission)
          .routes()
          .path(
              "/",
              routes -> routes.get("/data", (_, _) -> executions.incrementAndGet()),
              e -> e.get(admission).required());

      try (var test = TestServer.start(app)) {
        var preflight =
            test.send(
                request ->
                    request
                        .path("/data")
                        .timeout(Duration.ofSeconds(5))
                        .method("OPTIONS")
                        .header("Origin", "https://client.example")
                        .header("Access-Control-Request-Method", "GET"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, preflight.statusCode());
        assertEquals("", preflight.body());
        assertEquals(1, admissions.get());
        assertEquals(0, authentications.get());
        assertEquals(0, gates.get());
        assertEquals(0, executions.get());
        var actual =
            test.send(
                request ->
                    request
                        .path("/data")
                        .timeout(Duration.ofSeconds(5))
                        .header("Origin", "https://client.example"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, actual.statusCode());
        assertEquals("Bearer", actual.headers().firstValue("WWW-Authenticate").orElseThrow());
        assertEquals(
            "https://client.example",
            actual.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals(2, admissions.get());
        assertEquals(1, authentications.get());
        assertEquals(0, gates.get());
        assertEquals(
            "true", actual.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
        var firstOutcome = Objects.requireNonNull(completions.poll(5, TimeUnit.SECONDS));
        var secondOutcome = Objects.requireNonNull(completions.poll(5, TimeUnit.SECONDS));
        var preflightOutcome =
            "OPTIONS".equals(firstOutcome.method()) ? firstOutcome : secondOutcome;
        var actualOutcome = "GET".equals(firstOutcome.method()) ? firstOutcome : secondOutcome;
        assertEquals(204, preflightOutcome.statusCode());
        assertNull(preflightOutcome.routePattern());
        assertEquals(401, actualOutcome.statusCode());
        assertEquals("/data", actualOutcome.routePattern());
        assertEquals(0, executions.get());
      }
    }
  }

  @Test
  void answersCredentialedPreflightWithExplicitMethodAndHeaderPermissions() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.cors(
          new CorsPolicy(
              Set.of("https://client.example"),
              Set.of(HttpMethods.PUT),
              Set.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE),
              true,
              Set.of(HttpHeaders.ETAG),
              600));
      app.routes().put("/data", (_, response) -> response.text("updated"));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/data")
                        .timeout(Duration.ofSeconds(5))
                        .method("OPTIONS")
                        .header("Origin", "https://client.example")
                        .header("Access-Control-Request-Method", "PUT")
                        .header("Access-Control-Request-Headers", "authorization,, Content-Type")
                        .header("Access-Control-Request-Headers", "AUTHORIZATION"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, result.statusCode());
        assertEquals(
            "true", result.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
        assertEquals(
            "PUT", result.headers().firstValue("Access-Control-Allow-Methods").orElseThrow());
        assertEquals(
            "authorization, content-type",
            result.headers().firstValue("Access-Control-Allow-Headers").orElseThrow());
        assertEquals("600", result.headers().firstValue("Access-Control-Max-Age").orElseThrow());
        var actual =
            test.send(
                request ->
                    request
                        .path("/data")
                        .timeout(Duration.ofSeconds(5))
                        .method("PUT")
                        .header("Origin", "https://client.example"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, actual.statusCode());
        assertEquals(
            "ETag", actual.headers().firstValue("Access-Control-Expose-Headers").orElseThrow());
      }
    }
  }

  @Test
  void acceptsTheLargestOriginPortAndRejectsNonExactPortSpelling() {
    var nonExactOrigin = Set.of("https://client.example:00080");
    assertDoesNotThrow(() -> new CorsPolicy(Set.of("https://client.example:65535")));
    assertThrows(IllegalArgumentException.class, () -> new CorsPolicy(nonExactOrigin));
  }

  @Test
  void rejectsUnsafeOrMalformedPolicyConfigurationBeforeStartup() {
    var wildcardOrigin = Set.of("*");
    var validOrigin = Set.of("https://client.example");
    var getMethod = Set.of(HttpMethods.GET);
    var emptyHeaders = Set.<HttpHeaders>of();
    var wildcardHeader = Set.of(HttpHeaders.of("*"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CorsPolicy(wildcardOrigin, getMethod, emptyHeaders, true, emptyHeaders, 5));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CorsPolicy(validOrigin, getMethod, emptyHeaders, false, emptyHeaders, -1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CorsPolicy(validOrigin, getMethod, wildcardHeader, false, emptyHeaders, 5));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CorsPolicy(validOrigin, getMethod, emptyHeaders, false, wildcardHeader, 5));
    for (var origin :
        Set.of(
            "",
            "https://client.example/",
            "https://user@client.example",
            "https://client.example?q=x",
            "https://client.example#part",
            "https://client.example:99999",
            "file://host",
            "https://client.example\r\nX-Header: injected",
            "https://*.example")) {
      var origins = Set.of(origin);
      assertThrows(IllegalArgumentException.class, () -> new CorsPolicy(origins), origin);
    }
  }

  @Test
  void rejectsAmbiguousOrDisallowedPreflightAfterAdmissionWithoutExecutingRoutes()
      throws Exception {
    var admissions = new AtomicInteger();
    var executions = new AtomicInteger();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.cors(new CorsPolicy(Set.of("https://client.example")));
      app.onRequestHeaders((_, _) -> admissions.incrementAndGet());
      app.routes().options("/data", (_, _) -> executions.incrementAndGet());

      try (var test = TestServer.start(app)) {
        String[][] invalidFields = {
          {
            "Origin",
            "https://client.example",
            "Origin",
            "https://client.example",
            "Access-Control-Request-Method",
            "GET"
          },
          {
            "Origin",
            "https://client.example",
            "Access-Control-Request-Method",
            "GET",
            "Access-Control-Request-Method",
            "GET"
          },
          {"Origin", "https://client.example/", "Access-Control-Request-Method", "GET"},
          {"Origin", "*", "Access-Control-Request-Method", "GET"},
          {"Origin", "https://client.example", "Access-Control-Request-Method", "GET, POST"},
          {
            "Origin",
            "https://client.example",
            "Access-Control-Request-Method",
            "GET",
            "Access-Control-Request-Headers",
            "bad name"
          },
          {
            "Origin",
            "https://client.example",
            "Access-Control-Request-Method",
            "GET",
            "Access-Control-Request-Headers",
            ", ,"
          }
        };
        for (var fields : invalidFields) {
          var result =
              test.send(
                  request -> {
                    request.path("/data").timeout(Duration.ofSeconds(5)).method("OPTIONS");
                    for (int index = 0; index < fields.length; index += 2) {
                      request.header(fields[index], fields[index + 1]);
                    }
                  },
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(400, result.statusCode(), Arrays.toString(fields));
          assertTrue(result.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        }
        String[][] deniedFields = {
          {"Origin", "null", "Access-Control-Request-Method", "GET"},
          {"Origin", "https://other.example", "Access-Control-Request-Method", "GET"},
          {"Origin", "https://client.example", "Access-Control-Request-Method", "POST"},
          {"Origin", "https://client.example", "Access-Control-Request-Method", "get"},
          {
            "Origin",
            "https://client.example",
            "Access-Control-Request-Method",
            "GET",
            "Access-Control-Request-Headers",
            "Authorization"
          }
        };
        for (var fields : deniedFields) {
          var result =
              test.send(
                  request -> {
                    request.path("/data").timeout(Duration.ofSeconds(5)).method("OPTIONS");
                    for (int index = 0; index < fields.length; index += 2) {
                      request.header(fields[index], fields[index + 1]);
                    }
                  },
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(403, result.statusCode(), Arrays.toString(fields));
          assertTrue(result.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        }
        assertEquals(invalidFields.length + deniedFields.length, admissions.get());
        assertEquals(0, executions.get());
      }
    }
  }

  @Test
  void preservesSameOriginAndAbsentOriginDispatchWithoutCrossOriginPermissions() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.cors(new CorsPolicy(Set.of()));
      app.routes().post("/data", (_, response) -> response.text("local"));

      try (var test = TestServer.start(app)) {
        var sameOrigin =
            test.send(
                request ->
                    request
                        .path("/data")
                        .timeout(Duration.ofSeconds(5))
                        .method("POST")
                        .header("Origin", "http://127.0.0.1:" + app.port()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, sameOrigin.statusCode());
        assertEquals("local", sameOrigin.body());
        assertTrue(sameOrigin.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        assertEquals(
            200,
            test.send(
                    request -> request.path("/data").timeout(Duration.ofSeconds(5)).method("POST"),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"*", "null"})
  void sharesWildcardAndExplicitOpaqueOriginsWithoutCredentialReflection(String allowed)
      throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.cors(new CorsPolicy(Set.of(allowed)));
      app.routes().get("/data", (_, response) -> response.text("shared"));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request.path("/data").timeout(Duration.ofSeconds(5)).header("Origin", "null"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals(
            allowed, result.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertTrue(result.headers().firstValue("Access-Control-Allow-Credentials").isEmpty());
      }
    }
  }

  @Test
  void protectsSharingAndCacheFieldsAcrossFiniteStreamingPreflightAndErrorResponses()
      throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.cors(new CorsPolicy(Set.of("https://client.example")));
      Handler conflictingHeaders =
          (_, response) -> {
            response
                .setHeader("Vary", "Accept-Encoding, origin")
                .addHeader("Vary", "Accept-Language");
            response
                .setHeader("Access-Control-Allow-Origin", "https://attacker.example")
                .addHeader("Access-Control-Allow-Origin", "*")
                .setHeader("Access-Control-Allow-Credentials", "true")
                .setHeader("Access-Control-Allow-Methods", "*")
                .setHeader("Access-Control-Allow-Headers", "*")
                .setHeader("Access-Control-Expose-Headers", "*")
                .setHeader("Access-Control-Max-Age", "999");
          };
      app.onRequestHeaders(conflictingHeaders);
      app.exception(
          RuntimeException.class,
          (_, request, response) -> {
            conflictingHeaders.handle(request, response);
            response.text("mapped");
          });
      app.routes()
          .get(
              "/data",
              (request, response) -> {
                if (request.header("X-Test-Mode").filter("error"::equals).isPresent()) {
                  throw new IllegalStateException("failure");
                }

                if (request.header("X-Test-Mode").filter("stream"::equals).isPresent()) {
                  response.startStream("text/plain").write("stream");
                } else {
                  response.text("finite");
                }
              });

      try (var test = TestServer.start(app)) {
        for (var scenario :
            Map.of(
                    "finite",
                    200,
                    "stream",
                    200,
                    "error",
                    500,
                    "preflight",
                    204,
                    "denied",
                    403,
                    "absent",
                    200)
                .entrySet()) {
          var mode = scenario.getKey();
          var result =
              test.send(
                  request -> {
                    request.path("/data").timeout(Duration.ofSeconds(5));
                    if ("preflight".equals(mode)) {
                      request
                          .method("OPTIONS")
                          .header("Origin", "https://client.example")
                          .header("Access-Control-Request-Method", "GET");
                    } else if (!"absent".equals(mode)) {
                      request
                          .header(
                              "Origin",
                              "denied".equals(mode)
                                  ? "https://other.example"
                                  : "https://client.example")
                          .header("X-Test-Mode", mode);
                    }
                  },
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(scenario.getValue(), result.statusCode(), mode);
          assertEquals(
              Set.of("absent", "denied").contains(mode)
                  ? List.of()
                  : List.of("https://client.example"),
              result.headers().allValues("Access-Control-Allow-Origin"),
              mode);
          assertTrue(
              result.headers().firstValue("Access-Control-Allow-Credentials").isEmpty(), mode);
          assertTrue(result.headers().firstValue("Access-Control-Allow-Headers").isEmpty(), mode);
          assertTrue(result.headers().firstValue("Access-Control-Expose-Headers").isEmpty(), mode);
          assertEquals(
              "preflight".equals(mode) ? List.of("GET") : List.of(),
              result.headers().allValues("Access-Control-Allow-Methods"),
              mode);
          assertEquals(
              "preflight".equals(mode) ? List.of("5") : List.of(),
              result.headers().allValues("Access-Control-Max-Age"),
              mode);
          var vary =
              result.headers().allValues("Vary").stream()
                  .flatMap(value -> Arrays.stream(value.split(",", -1)))
                  .map(String::trim)
                  .map(value -> value.toLowerCase(Locale.ROOT))
                  .sorted()
                  .toList();
          assertEquals(
              "preflight".equals(mode)
                  ? List.of(
                      "accept-encoding",
                      "accept-language",
                      "access-control-request-headers",
                      "access-control-request-method",
                      "origin")
                  : List.of("accept-encoding", "accept-language", "origin"),
              vary,
              mode);
        }
      }
    }
  }

  @Test
  void preventsAdmissionFromCommittingBeforeAnInterceptedPreflightDecision() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.cors(new CorsPolicy(Set.of("https://client.example")));
      app.onRequestHeaders(
          (request, response) -> {
            assertThrows(IllegalStateException.class, () -> response.startStream("text/plain"));
            if (request.header("X-Deny").isPresent()) {
              throw new ForbiddenException();
            }
          });

      try (var test = TestServer.start(app)) {
        var allowed =
            test.send(
                request ->
                    request
                        .path("/data")
                        .timeout(Duration.ofSeconds(5))
                        .method("OPTIONS")
                        .header("Origin", "https://client.example")
                        .header("Access-Control-Request-Method", "GET"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, allowed.statusCode());
        var denied =
            test.send(
                request ->
                    request
                        .path("/data")
                        .timeout(Duration.ofSeconds(5))
                        .method("OPTIONS")
                        .header("Origin", "https://client.example")
                        .header("Access-Control-Request-Method", "GET")
                        .header("X-Deny", "yes"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, denied.statusCode());
        assertEquals(
            403,
            test.send(
                    request ->
                        request
                            .path("/data")
                            .timeout(Duration.ofSeconds(5))
                            .method("OPTIONS")
                            .header("Origin", "https://other.example")
                            .header("Access-Control-Request-Method", "GET"),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
      }
    }
  }

  @Test
  void replacesAdmissionBodyWithAnEmptyPreflightWhilePreservingAdmissionHeaders() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.cors(new CorsPolicy(Set.of("https://client.example")));
      app.onRequestHeaders(
          (_, response) -> response.setHeader("X-Admission", "checked").text("staged"));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/data")
                        .timeout(Duration.ofSeconds(5))
                        .method("OPTIONS")
                        .header("Origin", "https://client.example")
                        .header("Access-Control-Request-Method", "GET"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, result.statusCode());
        assertEquals("", result.body());
        assertEquals("checked", result.headers().firstValue("X-Admission").orElseThrow());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preservesExplicitOptionsWithDisabledCorsAndOrdinaryOptionsWithEnabledCors(boolean enabled)
      throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      if (enabled) {
        app.cors(
            new CorsPolicy(
                Set.of("https://client.example"),
                Set.of(HttpMethods.OPTIONS),
                Set.of(),
                false,
                Set.of(),
                0));
      }

      app.routes()
          .options(
              "/data",
              (_, response) ->
                  response.setHeader("Access-Control-Allow-Origin", "manual").text("explicit"));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request -> {
                  request
                      .path("/data")
                      .timeout(Duration.ofSeconds(5))
                      .method("OPTIONS")
                      .header("Origin", "https://client.example");
                  if (!enabled) {
                    request.header("Access-Control-Request-Method", "GET");
                  }
                },
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("explicit", result.body());
        assertEquals(
            enabled ? "https://client.example" : "manual",
            result.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertTrue(result.headers().firstValue("Access-Control-Allow-Methods").isEmpty());
        assertEquals(
            "explicit",
            test.send(
                    request ->
                        request.path("/data").timeout(Duration.ofSeconds(5)).method("OPTIONS"),
                    HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void keepsSharingDecisionsIndependentAcrossConcurrentRequestsAndConfigurationMutation()
      throws Exception {
    var origins = new HashSet<>(Set.of("https://one.example", "https://two.example"));
    var methods = new HashSet<>(Set.of(HttpMethods.GET));
    var headers = new HashSet<>(Set.of(HttpHeaders.AUTHORIZATION));
    var exposed = new HashSet<>(Set.of(HttpHeaders.ETAG));
    var policy = new CorsPolicy(origins, methods, headers, true, exposed, 0);
    origins.clear();
    methods.clear();
    headers.clear();
    exposed.clear();
    var retainedOrigins = policy.origins();
    var retainedMethods = policy.methods();
    var retainedHeaders = policy.headers();
    var retainedExposedHeaders = policy.exposedHeaders();
    assertThrows(UnsupportedOperationException.class, retainedOrigins::clear);
    assertThrows(UnsupportedOperationException.class, retainedMethods::clear);
    assertThrows(UnsupportedOperationException.class, retainedHeaders::clear);
    assertThrows(UnsupportedOperationException.class, retainedExposedHeaders::clear);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      app.cors(policy);
      app.routes().get("/data", (_, response) -> response.text("shared"));

      try (var test = TestServer.start(app)) {
        var futures = new ArrayList<Future<Map.Entry<String, HttpResponse<String>>>>();
        for (int index = 0; index < 12; index++) {
          var origin = index % 2 == 0 ? "https://one.example" : "https://two.example";
          futures.add(
              executor.submit(
                  () ->
                      Map.entry(
                          origin,
                          test.send(
                              request ->
                                  request
                                      .path("/data")
                                      .timeout(Duration.ofSeconds(5))
                                      .header("Origin", origin),
                              HttpResponse.BodyHandlers.ofString()))));
        }
        for (var future : futures) {
          var result = future.get(10, TimeUnit.SECONDS);
          assertEquals(200, result.getValue().statusCode());
          assertEquals(
              List.of(result.getKey()),
              result.getValue().headers().allValues("Access-Control-Allow-Origin"));
          assertEquals(
              "true",
              result
                  .getValue()
                  .headers()
                  .firstValue("Access-Control-Allow-Credentials")
                  .orElseThrow());
          assertEquals(
              "ETag",
              result
                  .getValue()
                  .headers()
                  .firstValue("Access-Control-Expose-Headers")
                  .orElseThrow());
        }
        var preflight =
            test.send(
                request ->
                    request
                        .path("/data")
                        .timeout(Duration.ofSeconds(5))
                        .method("OPTIONS")
                        .header("Origin", "https://one.example")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, preflight.statusCode());
        assertEquals("0", preflight.headers().firstValue("Access-Control-Max-Age").orElseThrow());
      }
    }
  }

  @Test
  void usesOnlyTrustedEffectiveOriginsForSameOriginDispatch() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.trustedProxies(InetAddress::isLoopbackAddress);
      app.cors(new CorsPolicy(Set.of()));
      app.routes().post("/data", (_, response) -> response.text("local"));

      try (var test = TestServer.start(app)) {
        assertEquals(
            403,
            test.send(
                    request ->
                        request
                            .path("/data")
                            .timeout(Duration.ofSeconds(5))
                            .method("POST")
                            .header("Origin", "https://api.example"),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(
            200,
            test.send(
                    request ->
                        request
                            .path("/data")
                            .timeout(Duration.ofSeconds(5))
                            .method("POST")
                            .header("Origin", "https://api.example")
                            .header("Forwarded", "for=192.0.2.1;proto=https;host=api.example"),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(
            200,
            test.send(
                    request ->
                        request
                            .path("/data")
                            .timeout(Duration.ofSeconds(5))
                            .method("POST")
                            .header("Origin", "https://api.example:443")
                            .header("Forwarded", "for=192.0.2.1;proto=https;host=api.example"),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(
            403,
            test.send(
                    request ->
                        request
                            .path("/data")
                            .timeout(Duration.ofSeconds(5))
                            .method("POST")
                            .header("Origin", "https://api.example:8443")
                            .header("Forwarded", "for=192.0.2.1;proto=https;host=api.example"),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(
            403,
            test.send(
                    request ->
                        request
                            .path("/data")
                            .timeout(Duration.ofSeconds(5))
                            .method("POST")
                            .header("Origin", "https://api.example:0")
                            .header("Forwarded", "for=192.0.2.1;proto=https;host=api.example"),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
      }
    }
  }

  @Test
  void acceptsExplicitHttpOriginFormsAndDoesNotInferPermissionFromReferer() throws Exception {
    var origins =
        Set.of(
            "http://127.0.0.1",
            "https://[::1]:8443",
            "https://xn--bcher-kva.example",
            "https://client.example.",
            "https://client.example:8443");

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.cors(new CorsPolicy(origins));
      app.routes().get("/data", (_, response) -> response.text("shared"));

      try (var test = TestServer.start(app)) {
        for (var origin : origins) {
          var result =
              test.send(
                  request ->
                      request.path("/data").timeout(Duration.ofSeconds(5)).header("Origin", origin),
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(200, result.statusCode(), origin);
          assertEquals(
              origin, result.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        }
        var result =
            test.send(
                request ->
                    request
                        .path("/data")
                        .timeout(Duration.ofSeconds(5))
                        .header("Referer", "https://client.example:8443/page"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertTrue(result.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {404, 405})
  void preservesWildcardVaryAndCredentialedSharingOnGeneratedErrors(int expected) throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.cors(
          new CorsPolicy(
              Set.of("https://client.example"),
              Set.of(HttpMethods.GET),
              Set.of(),
              true,
              Set.of(),
              5));
      app.onRequestHeaders((_, response) -> response.setHeader("Vary", "*"));
      if (expected == 405) {
        app.routes().post("/data", (_, response) -> response.text("post"));
      }

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/data")
                        .timeout(Duration.ofSeconds(5))
                        .header("Origin", "https://client.example"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, result.statusCode());
        assertEquals(
            "https://client.example",
            result.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals(
            "true", result.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
        assertEquals(List.of("*"), result.headers().allValues("Vary"));
      }
    }
  }

  @Test
  @SuppressWarnings("NullAway") // Null policy is the invalid registration under test.
  void freezesCorsRegistrationAtStartupAndRejectsRepeatedConfiguration() throws Exception {
    var policy = new CorsPolicy(Set.of("https://client.example"));

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      assertThrows(NullPointerException.class, () -> app.cors(null));
      app.cors(policy);
      assertThrows(IllegalStateException.class, () -> app.cors(policy));
    }

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.start();
      assertThrows(IllegalStateException.class, () -> app.cors(policy));
    }

    var closed = new Shoostr(Options.defaults().withPort(0));
    closed.close();
    assertThrows(IllegalStateException.class, () -> closed.cors(policy));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Accept-Language", "*"})
  void retainsAdmissionVaryOnBuiltInCorsErrorResponses(String vary) throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.cors(new CorsPolicy(Set.of("https://client.example")));
      app.onRequestHeaders(
          (_, response) -> {
            response.setHeader("Vary", vary);
            throw new ForbiddenException();
          });

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request ->
                    request
                        .path("/data")
                        .timeout(Duration.ofSeconds(5))
                        .header("Origin", "https://client.example"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, result.statusCode());
        assertEquals(
            "https://client.example",
            result.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        var variation = result.headers().allValues("Vary");
        if ("*".equals(vary)) {
          assertEquals(List.of("*"), variation);
        } else {
          assertTrue(variation.contains("Accept-Language"));
          assertTrue(variation.contains("Origin"));
        }
      }
    }
  }

  @Test
  void preservesCorsHeadersAcrossExplicitStreamingFlushAndFrameworkClosure() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.cors(new CorsPolicy(Set.of("https://client.example")));
      app.routes()
          .get(
              "/data",
              (_, response) -> {
                var stream = response.startStream("text/plain");
                stream.write("first");
                stream.flush();
                stream.write("second");
              });

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request ->
                    request
                        .path("/data")
                        .timeout(Duration.ofSeconds(5))
                        .header("Origin", "https://client.example"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("firstsecond", result.body());
        assertEquals(
            List.of("https://client.example"),
            result.headers().allValues("Access-Control-Allow-Origin"));
        assertTrue(result.headers().allValues("Vary").contains("Origin"));
      }
    }
  }

  @Test
  void preservesRawQueryDispatchForSameAndCrossOriginRequests() throws Exception {
    var admissions = new AtomicInteger();
    var executions = new AtomicInteger();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.cors(new CorsPolicy(Set.of("http://example.test", "https://client.example")));
      app.onRequestHeaders((_, _) -> admissions.incrementAndGet());
      app.routes()
          .get(
              "/raw",
              (request, response) -> {
                executions.incrementAndGet();
                response.text(request.queryString().orElseThrow());
              });
      app.start();

      String withoutOrigin;

      try (var socket = new Socket()) {
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), app.port()), 3000);
        socket.setSoTimeout(5000);
        var outgoing =
            "GET /raw?bad=%GG HTTP/1.1\r\nHost: example.test\r\nConnection: close\r\n\r\n";
        socket.getOutputStream().write(outgoing.getBytes(StandardCharsets.US_ASCII));
        withoutOrigin =
            new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
      }

      assertTrue(withoutOrigin.startsWith("HTTP/1.1 200"), withoutOrigin);
      assertTrue(withoutOrigin.endsWith("bad=%GG"), withoutOrigin);

      String sameOrigin;

      try (var socket = new Socket()) {
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), app.port()), 3000);
        socket.setSoTimeout(5000);
        var outgoing =
            """
            GET /raw?bad=%GG HTTP/1.1\r
            Host: example.test\r
            Origin: http://example.test\r
            Connection: close\r
            \r
            """;
        socket.getOutputStream().write(outgoing.getBytes(StandardCharsets.US_ASCII));
        sameOrigin = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
      }

      assertTrue(sameOrigin.startsWith("HTTP/1.1 200"), sameOrigin);
      assertFalse(sameOrigin.toLowerCase(Locale.ROOT).contains("access-control-allow-origin:"));
      assertTrue(sameOrigin.endsWith("bad=%GG"), sameOrigin);

      String crossOrigin;

      try (var socket = new Socket()) {
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), app.port()), 3000);
        socket.setSoTimeout(5000);
        var outgoing =
            """
            GET /raw?bad=%GG HTTP/1.1\r
            Host: example.test\r
            Origin: https://client.example\r
            Connection: close\r
            \r
            """;
        socket.getOutputStream().write(outgoing.getBytes(StandardCharsets.US_ASCII));
        crossOrigin = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
      }

      assertTrue(crossOrigin.startsWith("HTTP/1.1 200"), crossOrigin);
      assertTrue(
          crossOrigin
              .toLowerCase(Locale.ROOT)
              .contains("access-control-allow-origin: https://client.example"),
          crossOrigin);
      assertTrue(crossOrigin.endsWith("bad=%GG"), crossOrigin);
      assertEquals(3, admissions.get());
      assertEquals(3, executions.get());
    }
  }
}
