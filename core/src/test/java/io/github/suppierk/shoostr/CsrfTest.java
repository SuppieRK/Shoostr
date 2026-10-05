package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.testing.TestRequest;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class CsrfTest {
  @ParameterizedTest
  @EnumSource(
      value = HttpMethods.class,
      names = {"GET", "HEAD", "OPTIONS", "TRACE"})
  @Timeout(15)
  void bypassesTokensForExplicitlyRegisteredSafeMethods(HttpMethods method) throws Exception {
    var csrf = new Csrf();
    var executions = new AtomicInteger();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.sessions().extensions(csrf);
      app.routes().get("/token", (request, response) -> response.text(csrf.token(request)));
      app.routes()
          .route(
              method,
              "/safe",
              (request, response) -> {
                assertTrue(request.session(false).isPresent());
                executions.incrementAndGet();
                response.text("accepted");
              },
              e -> e.get(csrf).required());

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {

        var token =
            test.send(
                request -> request.path("/token").timeout(Duration.ofSeconds(3)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, token.statusCode());
        assertFalse(token.body().isBlank());
        var cookie = token.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];

        var result =
            test.send(
                request ->
                    request
                        .path("/safe")
                        .timeout(Duration.ofSeconds(3))
                        .header("Cookie", cookie)
                        .header("Origin", "http://127.0.0.1:" + app.port())
                        .method(method.value()),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, result.statusCode());
        assertEquals(method == HttpMethods.HEAD ? "" : "accepted", result.body());
        assertEquals(1, executions.get());
        assertTrue(result.headers().allValues("Set-Cookie").isEmpty());
      }
    }
  }

  @ParameterizedTest
  @CsvSource({
    "POST, ''",
    "POST, invalid",
    "PUT, ''",
    "PUT, invalid",
    "PATCH, ''",
    "PATCH, invalid",
    "DELETE, ''",
    "DELETE, invalid"
  })
  @Timeout(15)
  void rejectsMissingOrInvalidTokensForMutatingMethodsWithValidSessionAndOrigin(
      HttpMethods method, String suppliedToken) throws Exception {
    var csrf = new Csrf();
    var executions = new AtomicInteger();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.sessions().extensions(csrf);
      app.routes().get("/token", (request, response) -> response.text(csrf.token(request)));
      app.routes()
          .route(
              method,
              "/mutation",
              (_, response) -> {
                executions.incrementAndGet();
                response.text("accepted");
              },
              e -> e.get(csrf).required());

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {

        var token =
            test.send(
                request -> request.path("/token").timeout(Duration.ofSeconds(3)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, token.statusCode());
        assertFalse(token.body().isBlank());
        var cookie = token.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        var origin = "http://127.0.0.1:" + app.port();

        var accepted =
            test.send(
                request ->
                    request
                        .path("/mutation")
                        .timeout(Duration.ofSeconds(3))
                        .header("Cookie", cookie)
                        .header("Origin", origin)
                        .header("X-CSRF-Token", token.body())
                        .method(method.value()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, accepted.statusCode());
        assertEquals("accepted", accepted.body());
        assertEquals(1, executions.get());

        var rejected =
            test.send(
                request -> {
                  request
                      .path("/mutation")
                      .timeout(Duration.ofSeconds(3))
                      .header("Cookie", cookie)
                      .header("Origin", origin)
                      .method(method.value());
                  if (!suppliedToken.isEmpty()) {
                    request.header("X-CSRF-Token", suppliedToken);
                  }
                },
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, rejected.statusCode());
        assertEquals(1, executions.get());
        assertTrue(rejected.headers().allValues("Set-Cookie").isEmpty());
      }
    }
  }

  @Test
  void repeatedInheritedRequirementsRegisterExactlyOneVerification() throws Exception {
    var csrf = new Csrf();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.extensions(csrf)
          .routes()
          .path(
              "/browser",
              routes ->
                  routes.post("/submit", (_, _) -> {}, e -> e.get(csrf).required().required()),
              e -> e.get(csrf).required().required());

      var router = app.routes().compile();

      try {
        var endpoint = router.match("/browser/submit", HttpMethods.POST);
        assertNotNull(endpoint);
        var behavior = endpoint.behavior();
        assertNotNull(behavior);
        assertEquals(1, behavior.before().size());
      } finally {
        router.close();
      }
    }
  }

  @Test
  void installationAloneDoesNotProtectRoutesOrEnableSessions() throws Exception {
    var csrf = new Csrf();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.extensions(csrf)
          .routes()
          .post(
              "/unselected",
              (request, response) ->
                  response.text(
                      request.session(true).isEmpty() ? "no session" : "session enabled"));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request -> request.path("/unselected").method("POST"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("no session", result.body());
        assertTrue(result.headers().allValues("Set-Cookie").isEmpty());
      }
    }
  }

  @Test
  @SuppressWarnings("NullAway") // A missing token is part of the rejection scenario.
  void protectsCookieAuthenticatedMutationWithSessionTokenAndOrigin() throws Exception {
    var executions = new AtomicInteger();
    var csrf = new Csrf();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.sessions().extensions(csrf);
      app.routes()
          .path(
              "/",
              routes -> {
                routes.get("/form", (request, response) -> response.text(csrf.token(request)));
                routes.post(
                    "/submit",
                    (_, response) -> {
                      executions.incrementAndGet();
                      response.text("accepted");
                    });
              },
              e -> e.get(csrf).required());

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {

        var form =
            test.send(request -> request.path("/form"), HttpResponse.BodyHandlers.ofString());
        var cookie = form.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        assertFalse(form.body().isBlank());

        var missing =
            test.send(
                request ->
                    request
                        .path("/submit")
                        .header("Cookie", cookie)
                        .header("Origin", "http://127.0.0.1:" + app.port())
                        .method("POST"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, missing.statusCode());
        assertEquals(0, executions.get());

        var accepted =
            test.send(
                request ->
                    request
                        .path("/submit")
                        .header("Cookie", cookie)
                        .header("Origin", "http://127.0.0.1:" + app.port())
                        .header("X-CSRF-Token", form.body())
                        .method("POST"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, accepted.statusCode());
        assertEquals("accepted", accepted.body());
        assertEquals(1, executions.get());
      }
    }
  }

  @Test
  void acceptsOneTokenInAFormFieldWhenTheOriginHeaderIsUnavailable() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      var csrf = new Csrf();
      app.sessions();
      app.extensions(csrf);
      app.routes()
          .path(
              "/",
              routes -> {
                routes.get("/form", (request, response) -> response.text(csrf.token(request)));
                routes.post("/submit", (_, response) -> response.text("accepted"));
              },
              e -> e.get(csrf).required());

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {

        var form =
            test.send(request -> request.path("/form"), HttpResponse.BodyHandlers.ofString());
        var cookie = form.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];

        var result =
            test.send(
                request ->
                    request
                        .path("/submit")
                        .header("Cookie", cookie)
                        .header("Referer", "http://127.0.0.1:" + app.port() + "/form")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .method("POST")
                        .body(("_csrf=" + form.body()).getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("accepted", result.body());

        var boundary = "csrf-boundary";
        var multipartBody =
            "--"
                + boundary
                + "\r\n"
                + "Content-Disposition: form-data; name=\"_csrf\"\r\n\r\n"
                + form.body()
                + "\r\n--"
                + boundary
                + "--\r\n";

        assertEquals(
            200,
            test.send(
                    request ->
                        request
                            .path("/submit")
                            .header("Cookie", cookie)
                            .header("Origin", "http://127.0.0.1:" + app.port())
                            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                            .method("POST")
                            .body(multipartBody.getBytes(StandardCharsets.UTF_8)),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
      }
    }
  }

  @Test
  void rejectsFileOnlyMultipartWithoutCsrfTokenBeforeInvokingHandler() throws Exception {
    var executions = new AtomicInteger();
    var csrf = new Csrf();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.sessions().extensions(csrf);
      app.routes().get("/form", (request, response) -> response.text(csrf.token(request)));
      app.routes()
          .post(
              "/submit",
              (_, response) -> {
                executions.incrementAndGet();
                response.text("accepted");
              },
              e -> e.get(csrf).required());

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {

        var form =
            test.send(
                request -> request.path("/form").timeout(Duration.ofSeconds(3)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, form.statusCode());
        assertFalse(form.body().isBlank());
        var cookie = form.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        var resumed =
            test.send(
                request ->
                    request.path("/form").timeout(Duration.ofSeconds(3)).header("Cookie", cookie),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resumed.statusCode());
        assertEquals(form.body(), resumed.body());
        assertTrue(resumed.headers().allValues("Set-Cookie").isEmpty());

        var body =
            """
          --file-only\r
          Content-Disposition: form-data; name="file"; filename="note.txt"\r
          Content-Type: text/plain\r
          \r
          uploaded content\r
          --file-only--\r
          """;
        var rejected =
            test.send(
                request ->
                    request
                        .path("/submit")
                        .timeout(Duration.ofSeconds(3))
                        .header("Cookie", cookie)
                        .header("Origin", "http://127.0.0.1:" + app.port())
                        .header("Content-Type", "multipart/form-data; boundary=file-only")
                        .method("POST")
                        .body(body.getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, rejected.statusCode());
        assertEquals(0, executions.get());
      }
    }
  }

  @Test
  void rejectsUntrustedOriginsMissingSessionsAndAmbiguousTokensBeforeBusinessCode()
      throws Exception {
    var executions = new AtomicInteger();
    var csrf = new Csrf();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.sessions();
      app.extensions(csrf);
      app.routes()
          .path(
              "/",
              routes -> {
                routes.get("/form", (request, response) -> response.text(csrf.token(request)));
                routes.post("/submit", (_, _) -> executions.incrementAndGet());
              },
              e -> e.get(csrf).required());

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {

        var form =
            test.send(request -> request.path("/form"), HttpResponse.BodyHandlers.ofString());
        var cookie = form.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        var token = form.body();
        var base = "http://127.0.0.1:" + app.port();
        List<Consumer<TestRequest>> rejected =
            List.of(
                request ->
                    request
                        .path("/submit")
                        .header("Cookie", cookie)
                        .header("X-CSRF-Token", token)
                        .method("POST"),
                request ->
                    request
                        .path("/submit")
                        .header("Cookie", cookie)
                        .header("X-CSRF-Token", token)
                        .header("Origin", "https://attacker.example")
                        .method("POST"),
                request ->
                    request
                        .path("/submit")
                        .header("Cookie", cookie)
                        .header("X-CSRF-Token", token)
                        .header("Origin", "null")
                        .method("POST"),
                request ->
                    request
                        .path("/submit")
                        .header("Cookie", cookie)
                        .header("X-CSRF-Token", token)
                        .header("Origin", base + " https://attacker.example")
                        .method("POST"),
                request ->
                    request
                        .path("/submit")
                        .header("Cookie", cookie)
                        .header("X-CSRF-Token", token)
                        .header("Origin", base)
                        .header("Origin", base)
                        .method("POST"),
                request ->
                    request
                        .path("/submit")
                        .header("Cookie", cookie)
                        .header("X-CSRF-Token", token)
                        .header("Origin", base + "/path")
                        .method("POST"),
                request ->
                    request
                        .path("/submit")
                        .header("Cookie", cookie)
                        .header("X-CSRF-Token", "wrong")
                        .header("Origin", base)
                        .method("POST"),
                request ->
                    request
                        .path("/submit")
                        .header("Cookie", cookie)
                        .header("X-CSRF-Token", token)
                        .header("X-CSRF-Token", token)
                        .header("Origin", base)
                        .method("POST"),
                request ->
                    request
                        .path("/submit")
                        .header("Cookie", "JSESSIONID=unknown")
                        .header("X-CSRF-Token", token)
                        .header("Origin", base)
                        .method("POST"));
        for (var candidate : rejected) {
          var result = test.send(candidate, HttpResponse.BodyHandlers.ofString());
          assertEquals(403, result.statusCode());
          assertFalse(result.headers().firstValue("Set-Cookie").isPresent());
        }

        assertEquals(
            403,
            test.send(
                    request ->
                        request
                            .path("/submit")
                            .header("Cookie", cookie)
                            .header("Origin", base)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .method("POST")
                            .body(
                                ("_csrf=" + token + "&_csrf=" + token)
                                    .getBytes(StandardCharsets.UTF_8)),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(0, executions.get());
      }
    }
  }

  @Test
  void rejectsMalformedRefererWithoutCreatingASessionOrRunningBusinessCode() throws Exception {
    var executions = new AtomicInteger();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      var csrf = new Csrf();
      app.sessions();
      app.extensions(csrf);
      app.routes()
          .path(
              "/",
              routes -> routes.post("/submit", (_, _) -> executions.incrementAndGet()),
              e -> e.get(csrf).required());

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {

        var result =
            test.send(
                request -> request.path("/submit").header("Referer", "http://[").method("POST"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, result.statusCode());
        assertEquals(0, executions.get());
        assertFalse(result.headers().firstValue("Set-Cookie").isPresent());
      }
    }
  }

  @Test
  void leavesBearerOnlyRoutesOutsideTheBrowserProtectionScope() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      var csrf = new Csrf();
      app.sessions();
      app.extensions(csrf);
      app.routes()
          .path(
              "/",
              routes -> routes.post("/browser", (_, response) -> response.text("browser")),
              e -> e.get(csrf).required());
      app.routes().post("/api", (_, response) -> response.text("api"));

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {

        var api =
            test.send(
                request ->
                    request
                        .path("/api")
                        .header("Authorization", "Bearer supplied-by-client")
                        .method("POST"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, api.statusCode());
        assertEquals("api", api.body());
        assertEquals(
            403,
            test.send(
                    request -> request.path("/browser").method("POST"),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
      }
    }
  }

  @Test
  void rotatesTokenAfterSessionRenewalAndRejectsReplayAfterLogout() throws Exception {
    var csrf = new Csrf();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.sessions();
      app.extensions(csrf);
      app.routes()
          .path(
              "/",
              routes -> {
                routes.get("/form", (request, response) -> response.text(csrf.token(request)));
                routes.post(
                    "/renew",
                    (request, response) -> {
                      request.renewSessionId();
                      response.text(csrf.token(request));
                    });
                routes.post("/submit", (_, response) -> response.text("accepted"));
                routes.post(
                    "/logout",
                    (request, response) -> {
                      request.session(false).orElseThrow().invalidate();
                      response.removeCookie("JSESSIONID").text("logged out");
                    });
              },
              e -> e.get(csrf).required());

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {

        var form =
            test.send(request -> request.path("/form"), HttpResponse.BodyHandlers.ofString());
        var oldCookie = form.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        var origin = "http://127.0.0.1:" + app.port();
        var renewed =
            test.send(
                request ->
                    request
                        .path("/renew")
                        .header("Cookie", oldCookie)
                        .header("Origin", origin)
                        .header("X-CSRF-Token", form.body())
                        .method("POST"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, renewed.statusCode());
        var newCookie = renewed.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        assertNotEquals(form.body(), renewed.body());
        assertNotEquals(oldCookie, newCookie);
        assertEquals(
            403,
            test.send(
                    request ->
                        request
                            .path("/submit")
                            .header("Cookie", newCookie)
                            .header("Origin", "http://127.0.0.1:" + app.port())
                            .header("X-CSRF-Token", form.body())
                            .method("POST"),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(
            200,
            test.send(
                    request ->
                        request
                            .path("/submit")
                            .header("Cookie", newCookie)
                            .header("Origin", "http://127.0.0.1:" + app.port())
                            .header("X-CSRF-Token", renewed.body())
                            .method("POST"),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());

        var logout =
            test.send(
                request ->
                    request
                        .path("/logout")
                        .header("Cookie", newCookie)
                        .header("Origin", origin)
                        .header("X-CSRF-Token", renewed.body())
                        .method("POST"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, logout.statusCode());
        assertEquals("logged out", logout.body());
        assertEquals(
            403,
            test.send(
                    request ->
                        request
                            .path("/submit")
                            .header("Cookie", newCookie)
                            .header("Origin", "http://127.0.0.1:" + app.port())
                            .header("X-CSRF-Token", renewed.body())
                            .method("POST"),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
      }
    }
  }

  @Test
  void allowsExplicitTrustedBrowserOriginOnlyAfterCorsPreflightAndActualCsrfCheck()
      throws Exception {
    var csrf = new Csrf(Set.of("https://client.example"));
    var executions = new AtomicInteger();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.sessions();
      app.cors(
          new CorsPolicy(
              Set.of("https://client.example"),
              Set.of(HttpMethods.GET, HttpMethods.POST),
              Set.of(HttpHeaders.of("X-CSRF-Token")),
              true,
              Set.of(),
              5));
      app.extensions(csrf);
      app.routes()
          .path(
              "/",
              routes -> {
                routes.get("/form", (request, response) -> response.text(csrf.token(request)));
                routes.post(
                    "/submit",
                    (_, response) -> {
                      executions.incrementAndGet();
                      response.text("accepted");
                    });
              },
              e -> e.get(csrf).required());

      try (var test =
          TestServer.start(
              app,
              client -> client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {

        var form =
            test.send(request -> request.path("/form"), HttpResponse.BodyHandlers.ofString());
        var cookie = form.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        var preflight =
            test.send(
                request ->
                    request
                        .path("/submit")
                        .header("Origin", "https://client.example")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "X-CSRF-Token")
                        .method("OPTIONS"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, preflight.statusCode());
        assertTrue(preflight.body().isEmpty());
        assertFalse(preflight.headers().firstValue("Set-Cookie").isPresent());
        assertEquals(0, executions.get());

        var denied =
            test.send(
                request ->
                    request
                        .path("/submit")
                        .header("Cookie", cookie)
                        .header("Origin", "https://client.example")
                        .method("POST"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, denied.statusCode());
        assertEquals(0, executions.get());

        var accepted =
            test.send(
                request ->
                    request
                        .path("/submit")
                        .header("Cookie", cookie)
                        .header("Origin", "https://client.example")
                        .header("X-CSRF-Token", form.body())
                        .method("POST"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, accepted.statusCode());
        assertEquals(
            "https://client.example",
            accepted.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals(1, executions.get());
      }
    }
  }
}
