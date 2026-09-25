package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CorsTest {
  @Test
  void sharesAnAllowedActualResponseAndPreservesItsVaryFields() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.cors(new CorsPolicy(Set.of("https://client.example")));
      app.routes()
          .get(
              "/data",
              (request, response) -> response.header("Vary", "Accept-Encoding").text("data"));
      app.start();
      var result = send(client, app, "GET", "Origin", "https://client.example");
      assertEquals(200, result.statusCode());
      assertEquals("data", result.body());
      assertEquals(
          "https://client.example",
          result.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
      assertTrue(result.headers().allValues("Vary").toString().contains("Accept-Encoding"));
      assertTrue(result.headers().allValues("Vary").toString().contains("Origin"));
    }
  }

  @Test
  void admitsPreflightWithoutExecutingRouteAuthenticationOrBusinessLogic() throws Exception {
    var admissions = new AtomicInteger();
    var gates = new AtomicInteger();
    var completions = new ArrayBlockingQueue<RequestOutcome>(2);
    var authentications = new AtomicInteger();
    var executions = new AtomicInteger();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.cors(
          new CorsPolicy(
              Set.of("https://client.example"),
              Set.of(HttpMethods.GET),
              Set.of(),
              true,
              Set.of(),
              5));
      app.beforeRouteHandler((request, response) -> gates.incrementAndGet());
      app.afterRequest(completions::add);
      app.onRequestHeaders((request, response) -> admissions.incrementAndGet());
      app.routes()
          .protect(
              (request, response) -> {
                authentications.incrementAndGet();
                throw new AuthenticationRequiredException("Bearer");
              },
              routes -> routes.get("/data", (request, response) -> executions.incrementAndGet()));
      app.start();
      var preflight =
          send(
              client,
              app,
              "OPTIONS",
              "Origin",
              "https://client.example",
              "Access-Control-Request-Method",
              "GET");
      assertEquals(204, preflight.statusCode());
      assertEquals("", preflight.body());
      assertEquals(1, admissions.get());
      assertEquals(0, authentications.get());
      assertEquals(0, gates.get());
      assertEquals(0, executions.get());
      var actual = send(client, app, "GET", "Origin", "https://client.example");
      assertEquals(401, actual.statusCode());
      assertEquals("Bearer", actual.headers().firstValue("WWW-Authenticate").orElseThrow());
      assertEquals(
          "https://client.example",
          actual.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
      assertEquals(2, admissions.get());
      assertEquals(1, authentications.get());
      assertEquals(1, gates.get());
      assertEquals(
          "true", actual.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
      var firstOutcome = Objects.requireNonNull(completions.poll(5, TimeUnit.SECONDS));
      var secondOutcome = Objects.requireNonNull(completions.poll(5, TimeUnit.SECONDS));
      var preflightOutcome = "OPTIONS".equals(firstOutcome.method()) ? firstOutcome : secondOutcome;
      var actualOutcome = "GET".equals(firstOutcome.method()) ? firstOutcome : secondOutcome;
      assertEquals(204, preflightOutcome.statusCode());
      assertNull(preflightOutcome.routePattern());
      assertEquals(401, actualOutcome.statusCode());
      assertEquals("/data", actualOutcome.routePattern());
      assertEquals(0, executions.get());
    }
  }

  @Test
  void answersCredentialedPreflightWithExplicitMethodAndHeaderPermissions() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.cors(
          new CorsPolicy(
              Set.of("https://client.example"),
              Set.of(HttpMethods.PUT),
              Set.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE),
              true,
              Set.of(HttpHeaders.ETAG),
              600));
      app.routes().put("/data", (request, response) -> response.text("updated"));
      app.start();
      var result =
          send(
              client,
              app,
              "OPTIONS",
              "Origin",
              "https://client.example",
              "Access-Control-Request-Method",
              "PUT",
              "Access-Control-Request-Headers",
              "authorization,, Content-Type",
              "Access-Control-Request-Headers",
              "AUTHORIZATION");
      assertEquals(204, result.statusCode());
      assertEquals(
          "true", result.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
      assertEquals(
          "PUT", result.headers().firstValue("Access-Control-Allow-Methods").orElseThrow());
      assertEquals(
          "authorization, content-type",
          result.headers().firstValue("Access-Control-Allow-Headers").orElseThrow());
      assertEquals("600", result.headers().firstValue("Access-Control-Max-Age").orElseThrow());
      var actual = send(client, app, "PUT", "Origin", "https://client.example");
      assertEquals(200, actual.statusCode());
      assertEquals(
          "ETag", actual.headers().firstValue("Access-Control-Expose-Headers").orElseThrow());
    }
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

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.cors(new CorsPolicy(Set.of("https://client.example")));
      app.onRequestHeaders((request, response) -> admissions.incrementAndGet());
      app.routes().options("/data", (request, response) -> executions.incrementAndGet());
      app.start();
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
        var result = send(client, app, "OPTIONS", fields);
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
        var result = send(client, app, "OPTIONS", fields);
        assertEquals(403, result.statusCode(), Arrays.toString(fields));
        assertTrue(result.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
      }
      assertEquals(invalidFields.length + deniedFields.length, admissions.get());
      assertEquals(0, executions.get());
    }
  }

  @Test
  void preservesSameOriginAndAbsentOriginDispatchWithoutCrossOriginPermissions() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.cors(new CorsPolicy(Set.of()));
      app.routes().post("/data", (request, response) -> response.text("local"));
      app.start();
      var sameOrigin = send(client, app, "POST", "Origin", "http://127.0.0.1:" + app.port());
      assertEquals(200, sameOrigin.statusCode());
      assertEquals("local", sameOrigin.body());
      assertTrue(sameOrigin.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
      assertEquals(200, send(client, app, "POST").statusCode());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"*", "null"})
  void sharesWildcardAndExplicitOpaqueOriginsWithoutCredentialReflection(String allowed)
      throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.cors(new CorsPolicy(Set.of(allowed)));
      app.routes().get("/data", (request, response) -> response.text("shared"));
      app.start();
      var result = send(client, app, "GET", "Origin", "null");
      assertEquals(200, result.statusCode());
      assertEquals(
          allowed, result.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
      assertTrue(result.headers().firstValue("Access-Control-Allow-Credentials").isEmpty());
    }
  }

  @Test
  void protectsSharingAndCacheFieldsAcrossFiniteStreamingPreflightAndErrorResponses()
      throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.cors(new CorsPolicy(Set.of("https://client.example")));
      Handler conflictingHeaders =
          (request, response) -> {
            response.header("Vary", "Accept-Encoding, origin").addHeader("Vary", "Accept-Language");
            response
                .header("Access-Control-Allow-Origin", "https://attacker.example")
                .addHeader("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Allow-Credentials", "true")
                .header("Access-Control-Allow-Methods", "*")
                .header("Access-Control-Allow-Headers", "*")
                .header("Access-Control-Expose-Headers", "*")
                .header("Access-Control-Max-Age", "999");
          };
      app.onRequestHeaders(conflictingHeaders);
      app.exception(
          RuntimeException.class,
          (failure, request, response) -> {
            conflictingHeaders.handle(request, response);
            response.text("mapped");
          });
      app.routes()
          .get(
              "/data",
              (request, response) -> {
                if ("error".equals(request.header("X-Test-Mode"))) {
                  throw new IllegalStateException("failure");
                }

                if ("stream".equals(request.header("X-Test-Mode"))) {
                  response.startStream("text/plain").write("stream");
                } else {
                  response.text("finite");
                }
              });
      app.start();
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
            "preflight".equals(mode)
                ? send(
                    client,
                    app,
                    "OPTIONS",
                    "Origin",
                    "https://client.example",
                    "Access-Control-Request-Method",
                    "GET")
                : "absent".equals(mode)
                    ? send(client, app, "GET")
                    : send(
                        client,
                        app,
                        "GET",
                        "Origin",
                        "denied".equals(mode) ? "https://other.example" : "https://client.example",
                        "X-Test-Mode",
                        mode);
        assertEquals(scenario.getValue(), result.statusCode(), mode);
        assertEquals(
            Set.of("absent", "denied").contains(mode)
                ? List.of()
                : List.of("https://client.example"),
            result.headers().allValues("Access-Control-Allow-Origin"),
            mode);
        assertTrue(result.headers().firstValue("Access-Control-Allow-Credentials").isEmpty(), mode);
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

  @Test
  void preventsAdmissionFromCommittingBeforeAnInterceptedPreflightDecision() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.cors(new CorsPolicy(Set.of("https://client.example")));
      app.onRequestHeaders(
          (request, response) -> {
            assertThrows(IllegalStateException.class, () -> response.startStream("text/plain"));
            if (request.header("X-Deny") != null) {
              throw new ForbiddenException();
            }
          });
      app.start();
      var allowed =
          send(
              client,
              app,
              "OPTIONS",
              "Origin",
              "https://client.example",
              "Access-Control-Request-Method",
              "GET");
      assertEquals(204, allowed.statusCode());
      var denied =
          send(
              client,
              app,
              "OPTIONS",
              "Origin",
              "https://client.example",
              "Access-Control-Request-Method",
              "GET",
              "X-Deny",
              "yes");
      assertEquals(403, denied.statusCode());
      assertEquals(
          403,
          send(
                  client,
                  app,
                  "OPTIONS",
                  "Origin",
                  "https://other.example",
                  "Access-Control-Request-Method",
                  "GET")
              .statusCode());
    }
  }

  @Test
  void replacesAdmissionBodyWithAnEmptyPreflightWhilePreservingAdmissionHeaders() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.cors(new CorsPolicy(Set.of("https://client.example")));
      app.onRequestHeaders(
          (request, response) -> response.header("X-Admission", "checked").text("staged"));
      app.start();
      var result =
          send(
              client,
              app,
              "OPTIONS",
              "Origin",
              "https://client.example",
              "Access-Control-Request-Method",
              "GET");
      assertEquals(204, result.statusCode());
      assertEquals("", result.body());
      assertEquals("checked", result.headers().firstValue("X-Admission").orElseThrow());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preservesExplicitOptionsWithDisabledCorsAndOrdinaryOptionsWithEnabledCors(boolean enabled)
      throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
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
              (request, response) ->
                  response.header("Access-Control-Allow-Origin", "manual").text("explicit"));
      app.start();
      var result =
          enabled
              ? send(client, app, "OPTIONS", "Origin", "https://client.example")
              : send(
                  client,
                  app,
                  "OPTIONS",
                  "Origin",
                  "https://client.example",
                  "Access-Control-Request-Method",
                  "GET");
      assertEquals(200, result.statusCode());
      assertEquals("explicit", result.body());
      assertEquals(
          enabled ? "https://client.example" : "manual",
          result.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
      assertTrue(result.headers().firstValue("Access-Control-Allow-Methods").isEmpty());
      assertEquals("explicit", send(client, app, "OPTIONS").body());
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
        var client = HttpClient.newHttpClient();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      app.cors(policy);
      app.routes().get("/data", (request, response) -> response.text("shared"));
      app.start();
      var futures = new ArrayList<Future<Map.Entry<String, HttpResponse<String>>>>();
      for (int index = 0; index < 12; index++) {
        var origin = index % 2 == 0 ? "https://one.example" : "https://two.example";
        futures.add(
            executor.submit(() -> Map.entry(origin, send(client, app, "GET", "Origin", origin))));
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
            result.getValue().headers().firstValue("Access-Control-Expose-Headers").orElseThrow());
      }
      var preflight =
          send(
              client,
              app,
              "OPTIONS",
              "Origin",
              "https://one.example",
              "Access-Control-Request-Method",
              "GET",
              "Access-Control-Request-Headers",
              "Authorization");
      assertEquals(204, preflight.statusCode());
      assertEquals("0", preflight.headers().firstValue("Access-Control-Max-Age").orElseThrow());
    }
  }

  @Test
  void usesOnlyTrustedEffectiveOriginsForSameOriginDispatch() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.trustedProxies(InetAddress::isLoopbackAddress);
      app.cors(new CorsPolicy(Set.of()));
      app.routes().post("/data", (request, response) -> response.text("local"));
      app.start();
      assertEquals(403, send(client, app, "POST", "Origin", "https://api.example").statusCode());
      assertEquals(
          200,
          send(
                  client,
                  app,
                  "POST",
                  "Origin",
                  "https://api.example",
                  "Forwarded",
                  "for=192.0.2.1;proto=https;host=api.example")
              .statusCode());
      assertEquals(
          200,
          send(
                  client,
                  app,
                  "POST",
                  "Origin",
                  "https://api.example:443",
                  "Forwarded",
                  "for=192.0.2.1;proto=https;host=api.example")
              .statusCode());
      assertEquals(
          403,
          send(
                  client,
                  app,
                  "POST",
                  "Origin",
                  "https://api.example:8443",
                  "Forwarded",
                  "for=192.0.2.1;proto=https;host=api.example")
              .statusCode());
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

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.cors(new CorsPolicy(origins));
      app.routes().get("/data", (request, response) -> response.text("shared"));
      app.start();
      for (var origin : origins) {
        var result = send(client, app, "GET", "Origin", origin);
        assertEquals(200, result.statusCode(), origin);
        assertEquals(
            origin, result.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
      }
      var result = send(client, app, "GET", "Referer", "https://client.example:8443/page");
      assertEquals(200, result.statusCode());
      assertTrue(result.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {404, 405})
  void preservesWildcardVaryAndCredentialedSharingOnGeneratedErrors(int expected) throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.cors(
          new CorsPolicy(
              Set.of("https://client.example"),
              Set.of(HttpMethods.GET),
              Set.of(),
              true,
              Set.of(),
              5));
      app.onRequestHeaders((request, response) -> response.header("Vary", "*"));
      if (expected == 405) {
        app.routes().post("/data", (request, response) -> response.text("post"));
      }

      app.start();
      var result = send(client, app, "GET", "Origin", "https://client.example");
      assertEquals(expected, result.statusCode());
      assertEquals(
          "https://client.example",
          result.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
      assertEquals(
          "true", result.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
      assertEquals(List.of("*"), result.headers().allValues("Vary"));
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
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.cors(new CorsPolicy(Set.of("https://client.example")));
      app.onRequestHeaders(
          (request, response) -> {
            response.header("Vary", vary);
            throw new ForbiddenException();
          });
      app.start();

      var result = send(client, app, "GET", "Origin", "https://client.example");
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

  @Test
  void preservesCorsHeadersAcrossExplicitStreamingFlushAndFrameworkClosure() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.cors(new CorsPolicy(Set.of("https://client.example")));
      app.routes()
          .get(
              "/data",
              (request, response) -> {
                var stream = response.startStream("text/plain");
                stream.write("first");
                stream.flush();
                stream.write("second");
              });
      app.start();

      var result = send(client, app, "GET", "Origin", "https://client.example");
      assertEquals(200, result.statusCode());
      assertEquals("firstsecond", result.body());
      assertEquals(
          List.of("https://client.example"),
          result.headers().allValues("Access-Control-Allow-Origin"));
      assertTrue(result.headers().allValues("Vary").contains("Origin"));
    }
  }

  @Test
  void preservesRawQueryDispatchForSameAndCrossOriginRequests() throws Exception {
    var admissions = new AtomicInteger();
    var executions = new AtomicInteger();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.cors(new CorsPolicy(Set.of("http://example.test", "https://client.example")));
      app.onRequestHeaders((request, response) -> admissions.incrementAndGet());
      app.routes()
          .get(
              "/raw",
              (request, response) -> {
                executions.incrementAndGet();
                response.text(Objects.requireNonNull(request.queryString()));
              });
      app.start();

      var withoutOrigin = rawRequest(app, "");
      assertTrue(withoutOrigin.startsWith("HTTP/1.1 200"), withoutOrigin);
      assertTrue(withoutOrigin.endsWith("bad=%GG"), withoutOrigin);

      var sameOrigin = rawRequest(app, "http://example.test");
      assertTrue(sameOrigin.startsWith("HTTP/1.1 200"), sameOrigin);
      assertFalse(sameOrigin.toLowerCase(Locale.ROOT).contains("access-control-allow-origin:"));
      assertTrue(sameOrigin.endsWith("bad=%GG"), sameOrigin);

      var crossOrigin = rawRequest(app, "https://client.example");
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

  private static String rawRequest(Shoostr app, String origin) throws Exception {
    try (var socket = new Socket()) {
      socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), app.port()), 3000);
      socket.setSoTimeout(5000);
      var originField = origin.isEmpty() ? "" : "Origin: " + origin + "\r\n";
      var request =
          "GET /raw?bad=%GG HTTP/1.1\r\nHost: example.test\r\n"
              + originField
              + "Connection: close\r\n\r\n";
      socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
    }
  }

  private static HttpResponse<String> send(
      HttpClient client, Shoostr app, String method, String... headers) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/data"))
            .timeout(Duration.ofSeconds(5))
            .method(method, HttpRequest.BodyPublishers.noBody());
    if (headers.length != 0) {
      builder.headers(headers);
    }

    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}
