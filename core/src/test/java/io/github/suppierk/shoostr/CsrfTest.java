package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
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
      app.start();

      var token =
          client.send(
              request(app, "/token").timeout(Duration.ofSeconds(3)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, token.statusCode());
      assertFalse(token.body().isBlank());
      var cookie = token.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];

      var result =
          client.send(
              request(app, "/safe")
                  .timeout(Duration.ofSeconds(3))
                  .header("Cookie", cookie)
                  .header("Origin", "http://localhost:" + app.port())
                  .method(method.value(), HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, result.statusCode());
      assertEquals(method == HttpMethods.HEAD ? "" : "accepted", result.body());
      assertEquals(1, executions.get());
      assertTrue(result.headers().allValues("Set-Cookie").isEmpty());
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

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
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
      app.start();

      var token =
          client.send(
              request(app, "/token").timeout(Duration.ofSeconds(3)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, token.statusCode());
      assertFalse(token.body().isBlank());
      var cookie = token.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
      var origin = "http://localhost:" + app.port();

      var accepted =
          client.send(
              request(app, "/mutation")
                  .timeout(Duration.ofSeconds(3))
                  .header("Cookie", cookie)
                  .header("Origin", origin)
                  .header("X-CSRF-Token", token.body())
                  .method(method.value(), HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, accepted.statusCode());
      assertEquals("accepted", accepted.body());
      assertEquals(1, executions.get());

      var rejectedRequest =
          request(app, "/mutation")
              .timeout(Duration.ofSeconds(3))
              .header("Cookie", cookie)
              .header("Origin", origin);
      if (!suppliedToken.isEmpty()) {
        rejectedRequest.header("X-CSRF-Token", suppliedToken);
      }

      var rejected =
          client.send(
              rejectedRequest.method(method.value(), HttpRequest.BodyPublishers.noBody()).build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(403, rejected.statusCode());
      assertEquals(1, executions.get());
      assertTrue(rejected.headers().allValues("Set-Cookie").isEmpty());
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

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.extensions(csrf)
          .routes()
          .post(
              "/unselected",
              (request, response) ->
                  response.text(
                      request.session(true).isEmpty() ? "no session" : "session enabled"));
      app.start();
      var result =
          client.send(
              request(app, "/unselected").POST(HttpRequest.BodyPublishers.noBody()).build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("no session", result.body());
      assertTrue(result.headers().allValues("Set-Cookie").isEmpty());
    }
  }

  @Test
  @SuppressWarnings("NullAway") // A missing token is part of the rejection scenario.
  void protectsCookieAuthenticatedMutationWithSessionTokenAndOrigin() throws Exception {
    var executions = new AtomicInteger();
    var csrf = new Csrf();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
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
      app.start();

      var form =
          client.send(request(app, "/form").GET().build(), HttpResponse.BodyHandlers.ofString());
      var cookie = form.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
      assertFalse(form.body().isBlank());

      var missing = post(client, app, cookie, null);
      assertEquals(403, missing.statusCode());
      assertEquals(0, executions.get());

      var accepted = post(client, app, cookie, form.body());
      assertEquals(200, accepted.statusCode());
      assertEquals("accepted", accepted.body());
      assertEquals(1, executions.get());
    }
  }

  @Test
  void acceptsOneTokenInAFormFieldWhenTheOriginHeaderIsUnavailable() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
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
      app.start();

      var form =
          client.send(request(app, "/form").GET().build(), HttpResponse.BodyHandlers.ofString());
      var cookie = form.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
      var submit =
          request(app, "/submit")
              .header("Cookie", cookie)
              .header("Referer", "http://localhost:" + app.port() + "/form")
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + form.body()))
              .build();
      var result = client.send(submit, HttpResponse.BodyHandlers.ofString());
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
      var multipart =
          request(app, "/submit")
              .header("Cookie", cookie)
              .header("Origin", "http://localhost:" + app.port())
              .header("Content-Type", "multipart/form-data; boundary=" + boundary)
              .POST(HttpRequest.BodyPublishers.ofString(multipartBody))
              .build();
      assertEquals(200, client.send(multipart, HttpResponse.BodyHandlers.ofString()).statusCode());
    }
  }

  @Test
  void rejectsFileOnlyMultipartWithoutCsrfTokenBeforeInvokingHandler() throws Exception {
    var executions = new AtomicInteger();
    var csrf = new Csrf();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
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
      app.start();

      var form =
          client.send(
              request(app, "/form").timeout(Duration.ofSeconds(3)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, form.statusCode());
      assertFalse(form.body().isBlank());
      var cookie = form.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
      var resumed =
          client.send(
              request(app, "/form")
                  .timeout(Duration.ofSeconds(3))
                  .header("Cookie", cookie)
                  .GET()
                  .build(),
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
          client.send(
              request(app, "/submit")
                  .timeout(Duration.ofSeconds(3))
                  .header("Cookie", cookie)
                  .header("Origin", "http://localhost:" + app.port())
                  .header("Content-Type", "multipart/form-data; boundary=file-only")
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(403, rejected.statusCode());
      assertEquals(0, executions.get());
    }
  }

  @Test
  void rejectsUntrustedOriginsMissingSessionsAndAmbiguousTokensBeforeBusinessCode()
      throws Exception {
    var executions = new AtomicInteger();
    var csrf = new Csrf();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
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
      app.start();

      var form =
          client.send(request(app, "/form").GET().build(), HttpResponse.BodyHandlers.ofString());
      var cookie = form.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
      var token = form.body();
      var base = "http://localhost:" + app.port();
      var rejected =
          List.of(
              request(app, "/submit").header("Cookie", cookie).header("X-CSRF-Token", token),
              request(app, "/submit")
                  .header("Cookie", cookie)
                  .header("X-CSRF-Token", token)
                  .header("Origin", "https://attacker.example"),
              request(app, "/submit")
                  .header("Cookie", cookie)
                  .header("X-CSRF-Token", token)
                  .header("Origin", "null"),
              request(app, "/submit")
                  .header("Cookie", cookie)
                  .header("X-CSRF-Token", token)
                  .header("Origin", base + " https://attacker.example"),
              request(app, "/submit")
                  .header("Cookie", cookie)
                  .header("X-CSRF-Token", token)
                  .header("Origin", base)
                  .header("Origin", base),
              request(app, "/submit")
                  .header("Cookie", cookie)
                  .header("X-CSRF-Token", token)
                  .header("Origin", base + "/path"),
              request(app, "/submit")
                  .header("Cookie", cookie)
                  .header("X-CSRF-Token", "wrong")
                  .header("Origin", base),
              request(app, "/submit")
                  .header("Cookie", cookie)
                  .header("X-CSRF-Token", token)
                  .header("X-CSRF-Token", token)
                  .header("Origin", base),
              request(app, "/submit")
                  .header("Cookie", "JSESSIONID=unknown")
                  .header("X-CSRF-Token", token)
                  .header("Origin", base));
      for (var candidate : rejected) {
        var result =
            client.send(
                candidate.POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, result.statusCode());
        assertFalse(result.headers().firstValue("Set-Cookie").isPresent());
      }

      var duplicatedForm =
          request(app, "/submit")
              .header("Cookie", cookie)
              .header("Origin", base)
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + token + "&_csrf=" + token))
              .build();
      assertEquals(
          403, client.send(duplicatedForm, HttpResponse.BodyHandlers.ofString()).statusCode());
      assertEquals(0, executions.get());
    }
  }

  @Test
  void rejectsMalformedRefererWithoutCreatingASessionOrRunningBusinessCode() throws Exception {
    var executions = new AtomicInteger();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      var csrf = new Csrf();
      app.sessions();
      app.extensions(csrf);
      app.routes()
          .path(
              "/",
              routes -> routes.post("/submit", (_, _) -> executions.incrementAndGet()),
              e -> e.get(csrf).required());
      app.start();

      var result =
          client.send(
              request(app, "/submit")
                  .header("Referer", "http://[")
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(403, result.statusCode());
      assertEquals(0, executions.get());
      assertFalse(result.headers().firstValue("Set-Cookie").isPresent());
    }
  }

  @Test
  void leavesBearerOnlyRoutesOutsideTheBrowserProtectionScope() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      var csrf = new Csrf();
      app.sessions();
      app.extensions(csrf);
      app.routes()
          .path(
              "/",
              routes -> routes.post("/browser", (_, response) -> response.text("browser")),
              e -> e.get(csrf).required());
      app.routes().post("/api", (_, response) -> response.text("api"));
      app.start();

      var api =
          client.send(
              request(app, "/api")
                  .header("Authorization", "Bearer supplied-by-client")
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, api.statusCode());
      assertEquals("api", api.body());
      assertEquals(
          403,
          client
              .send(
                  request(app, "/browser").POST(HttpRequest.BodyPublishers.noBody()).build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
    }
  }

  @Test
  void rotatesTokenAfterSessionRenewalAndRejectsReplayAfterLogout() throws Exception {
    var csrf = new Csrf();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
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
      app.start();

      var form =
          client.send(request(app, "/form").GET().build(), HttpResponse.BodyHandlers.ofString());
      var oldCookie = form.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
      var origin = "http://localhost:" + app.port();
      var renewed =
          client.send(
              request(app, "/renew")
                  .header("Cookie", oldCookie)
                  .header("Origin", origin)
                  .header("X-CSRF-Token", form.body())
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, renewed.statusCode());
      var newCookie = renewed.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
      assertNotEquals(form.body(), renewed.body());
      assertNotEquals(oldCookie, newCookie);
      assertEquals(403, post(client, app, newCookie, form.body()).statusCode());
      assertEquals(200, post(client, app, newCookie, renewed.body()).statusCode());

      var logout =
          client.send(
              request(app, "/logout")
                  .header("Cookie", newCookie)
                  .header("Origin", origin)
                  .header("X-CSRF-Token", renewed.body())
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, logout.statusCode());
      assertEquals("logged out", logout.body());
      assertEquals(403, post(client, app, newCookie, renewed.body()).statusCode());
    }
  }

  @Test
  void allowsExplicitTrustedBrowserOriginOnlyAfterCorsPreflightAndActualCsrfCheck()
      throws Exception {
    var csrf = new Csrf(Set.of("https://client.example"));
    var executions = new AtomicInteger();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
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
      app.start();

      var form =
          client.send(request(app, "/form").GET().build(), HttpResponse.BodyHandlers.ofString());
      var cookie = form.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
      var preflight =
          client.send(
              request(app, "/submit")
                  .header("Origin", "https://client.example")
                  .header("Access-Control-Request-Method", "POST")
                  .header("Access-Control-Request-Headers", "X-CSRF-Token")
                  .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(204, preflight.statusCode());
      assertTrue(preflight.body().isEmpty());
      assertFalse(preflight.headers().firstValue("Set-Cookie").isPresent());
      assertEquals(0, executions.get());

      var denied =
          client.send(
              request(app, "/submit")
                  .header("Cookie", cookie)
                  .header("Origin", "https://client.example")
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(403, denied.statusCode());
      assertEquals(0, executions.get());

      var accepted =
          client.send(
              request(app, "/submit")
                  .header("Cookie", cookie)
                  .header("Origin", "https://client.example")
                  .header("X-CSRF-Token", form.body())
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, accepted.statusCode());
      assertEquals(
          "https://client.example",
          accepted.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
      assertEquals(1, executions.get());
    }
  }

  private static HttpResponse<String> post(
      HttpClient client, Shoostr app, String cookie, String token) throws Exception {
    var builder =
        request(app, "/submit")
            .header("Cookie", cookie)
            .header("Origin", "http://localhost:" + app.port());
    if (token != null) {
      builder.header("X-CSRF-Token", token);
    }

    return client.send(
        builder.POST(HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpRequest.Builder request(Shoostr app, String path) {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path));
  }
}
