package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CsrfTest {
  @Test
  @SuppressWarnings("NullAway") // A missing token is part of the rejection scenario.
  void protectsCookieAuthenticatedMutationWithSessionTokenAndOrigin() throws Exception {
    var executions = new AtomicInteger();
    var csrf = new Csrf();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.sessions();
      app.routes()
          .protect(
              csrf::verify,
              routes -> {
                routes.get("/form", (request, response) -> response.text(csrf.token(request)));
                routes.post(
                    "/submit",
                    (request, response) -> {
                      executions.incrementAndGet();
                      response.text("accepted");
                    });
              });
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
      app.routes()
          .protect(
              csrf::verify,
              routes -> {
                routes.get("/form", (request, response) -> response.text(csrf.token(request)));
                routes.post("/submit", (request, response) -> response.text("accepted"));
              });
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
  void rejectsUntrustedOriginsMissingSessionsAndAmbiguousTokensBeforeBusinessCode()
      throws Exception {
    var executions = new AtomicInteger();
    var csrf = new Csrf();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.sessions();
      app.routes()
          .protect(
              csrf::verify,
              routes -> {
                routes.get("/form", (request, response) -> response.text(csrf.token(request)));
                routes.post("/submit", (request, response) -> executions.incrementAndGet());
              });
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
      app.routes()
          .protect(
              csrf::verify,
              routes ->
                  routes.post("/submit", (request, response) -> executions.incrementAndGet()));
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
      app.routes()
          .protect(
              csrf::verify,
              routes -> routes.post("/browser", (request, response) -> response.text("browser")));
      app.routes().post("/api", (request, response) -> response.text("api"));
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
      app.routes()
          .protect(
              csrf::verify,
              routes -> {
                routes.get("/form", (request, response) -> response.text(csrf.token(request)));
                routes.post(
                    "/renew",
                    (request, response) -> {
                      request.renewSessionId();
                      response.text(csrf.token(request));
                    });
                routes.post("/submit", (request, response) -> response.text("accepted"));
                routes.post(
                    "/logout",
                    (request, response) -> {
                      Objects.requireNonNull(request.session(false)).invalidate();
                      response.removeCookie("JSESSIONID").text("logged out");
                    });
              });
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
      assertFalse(form.body().equals(renewed.body()));
      assertFalse(oldCookie.equals(newCookie));
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
      app.routes()
          .protect(
              csrf::verify,
              routes -> {
                routes.get("/form", (request, response) -> response.text(csrf.token(request)));
                routes.post(
                    "/submit",
                    (request, response) -> {
                      executions.incrementAndGet();
                      response.text("accepted");
                    });
              });
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
