package io.github.suppierk.shoostr.pac4j;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pac4j.core.context.Cookie;
import org.pac4j.core.credentials.TokenCredentials;
import org.pac4j.core.credentials.UsernamePasswordCredentials;
import org.pac4j.core.credentials.authenticator.Authenticator;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.http.client.direct.DirectBasicAuthClient;
import org.pac4j.http.client.direct.HeaderClient;
import org.pac4j.http.client.direct.ParameterClient;
import org.pac4j.jwt.config.signature.SecretSignatureConfiguration;
import org.pac4j.jwt.credentials.authenticator.JwtAuthenticator;
import org.pac4j.jwt.profile.JwtGenerator;

class Pac4jTest {
  @TempDir private Path temporary;

  @Test
  void exposesEveryDuplicateCookieToTheAuthenticationProvider() throws Exception {
    var received = new CompletableFuture<List<String>>();
    var provider =
        new DirectBasicAuthClient(
            (context, supplied) -> {
              received.complete(
                  context.webContext().getRequestCookies().stream()
                      .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                      .toList());
              var profile = new CommonProfile();
              profile.setId("alice");
              supplied.setUserProfile(profile);
              return Optional.of(supplied);
            });

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      var security = new Pac4j(provider, "Basic");
      app.authentication(security)
          .routes(
              routes ->
                  routes.get(
                      "/me",
                      (_, response) -> response.text("authenticated"),
                      e -> e.get(security).required()));
      app.start();

      var encoded =
          Base64.getEncoder().encodeToString("alice:correct".getBytes(StandardCharsets.UTF_8));
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/me"))
                  .timeout(Duration.ofSeconds(5))
                  .header("Authorization", "Basic " + encoded)
                  .header("Cookie", "token=first; token=second; Token=upper")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals(
          List.of("token=first", "token=second", "Token=upper"), received.get(3, TimeUnit.SECONDS));
    }
  }

  @Test
  void preservesSecurePrefixedCookiesEmittedByTheProvider() throws Exception {
    var expectedPort = new AtomicInteger();
    var provider =
        new DirectBasicAuthClient(
            (context, supplied) -> {
              assertEquals(expectedPort.get(), context.webContext().getServerPort());
              for (String name : List.of("__Host-token", "__Secure-token")) {
                var cookie = new Cookie(name, "token");
                cookie.setPath("/");
                cookie.setSecure(true);
                cookie.setHttpOnly(true);
                cookie.setSameSitePolicy("None");
                context.webContext().addResponseCookie(cookie);
              }
              var profile = new CommonProfile();
              profile.setId("alice");
              supplied.setUserProfile(profile);
              return Optional.of(supplied);
            });

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      var auth = new Pac4j(provider, "Basic");
      app.authentication(auth)
          .routes()
          .path(
              "/",
              routes -> routes.get("/me", (_, response) -> response.text("authenticated")),
              e -> e.get(auth).required());
      app.start();
      expectedPort.set(app.port());

      var encoded =
          Base64.getEncoder().encodeToString("alice:correct".getBytes(StandardCharsets.UTF_8));
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/me"))
                  .timeout(Duration.ofSeconds(5))
                  .header("Authorization", "Basic " + encoded)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals(
          List.of(
              "__Host-token=token; Path=/; Secure; HttpOnly; SameSite=None",
              "__Secure-token=token; Path=/; Secure; HttpOnly; SameSite=None"),
          result.headers().allValues("Set-Cookie"));
    }
  }

  @Test
  void rejectsAnExpiredProviderProfileBeforeEstablishingAPrincipal() throws Exception {
    var provider =
        new DirectBasicAuthClient(
            (_, supplied) -> {
              var profile =
                  new CommonProfile() {
                    @Override
                    public boolean isExpired() {
                      return true;
                    }
                  };
              profile.setId("alice");
              supplied.setUserProfile(profile);
              return Optional.of(supplied);
            });

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.exception(
          AuthenticationRequiredException.class,
          (_, request, response) ->
              response.text(request.principal().map(Object::toString).orElse("null")));
      var auth = new Pac4j(provider, "Basic");
      app.authentication(auth)
          .routes()
          .path(
              "/",
              routes -> routes.get("/me", (_, response) -> response.text("private")),
              e -> e.get(auth).required());
      app.start();

      var encoded =
          Base64.getEncoder().encodeToString("alice:correct".getBytes(StandardCharsets.UTF_8));
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/me"))
                  .timeout(Duration.ofSeconds(5))
                  .header("Authorization", "Basic " + encoded)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(401, result.statusCode());
      assertEquals("null", result.body());
    }
  }

  @Test
  void mapsProviderFailuresToServerErrorsWithoutLeakingDetailsOrChallenges() throws Exception {
    var provider =
        new DirectBasicAuthClient(
            (_, _) -> {
              throw new IllegalStateException("sensitive identity-store diagnostic");
            });

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      var auth = new Pac4j(provider, "Basic");
      app.authentication(auth)
          .routes()
          .path(
              "/",
              routes -> routes.get("/me", (_, response) -> response.text("private")),
              e -> e.get(auth).required());
      app.start();

      var encoded =
          Base64.getEncoder().encodeToString("alice:correct".getBytes(StandardCharsets.UTF_8));
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/me"))
                  .timeout(Duration.ofSeconds(5))
                  .header("Authorization", "Basic " + encoded)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(500, result.statusCode());
      assertEquals("Internal Server Error", result.body());
      assertEquals(Optional.empty(), result.headers().firstValue("WWW-Authenticate"));
    }
  }

  @Test
  void rejectsAmbiguousAuthorizationHeaders() throws Exception {
    var provider =
        new DirectBasicAuthClient(
            (_, supplied) -> {
              var profile = new CommonProfile();
              profile.setId("alice");
              supplied.setUserProfile(profile);
              return Optional.of(supplied);
            });

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      var auth = new Pac4j(provider, "Basic");
      app.authentication(auth)
          .routes()
          .path(
              "/",
              routes -> routes.get("/me", (_, response) -> response.text("private")),
              e -> e.get(auth).required());
      app.start();

      var encoded =
          Base64.getEncoder().encodeToString("alice:correct".getBytes(StandardCharsets.UTF_8));
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/me"))
                  .timeout(Duration.ofSeconds(5))
                  .header("Authorization", "Basic " + encoded)
                  .header("Authorization", "Basic " + encoded)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(401, result.statusCode());
    }
  }

  @Test
  void exposesOrderedQueryAndFormValuesToTheConfiguredProvider() throws Exception {
    var provider =
        new DirectBasicAuthClient(
            (context, supplied) -> {
              var parameters = context.webContext().getRequestParameters();
              assertArrayEquals(new String[] {"query", "form"}, parameters.get("shared"));
              assertArrayEquals(new String[] {"query-only"}, parameters.get("query"));
              assertArrayEquals(new String[] {"form-only"}, parameters.get("form"));
              assertEquals(
                  Optional.of("query"), context.webContext().getRequestParameter("shared"));
              var profile = new CommonProfile();
              profile.setId("alice");
              supplied.setUserProfile(profile);
              return Optional.of(supplied);
            });

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      var auth = new Pac4j(provider, "Basic");
      app.authentication(auth)
          .routes()
          .path(
              "/",
              routes -> routes.post("/me", (_, response) -> response.text("authenticated")),
              e -> e.get(auth).required());
      app.start();
      var encoded =
          Base64.getEncoder().encodeToString("alice:correct".getBytes(StandardCharsets.UTF_8));
      var result =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://127.0.0.1:" + app.port() + "/me?shared=query&query=query-only"))
                  .timeout(Duration.ofSeconds(5))
                  .header("Authorization", "Basic " + encoded)
                  .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                  .POST(HttpRequest.BodyPublishers.ofString("shared=form&form=form-only"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("authenticated", result.body());
    }
  }

  @Test
  void leavesNonFormBodiesAvailableWhileExposingQueryCredentials() throws Exception {
    var provider =
        new DirectBasicAuthClient(
            (context, supplied) -> {
              assertEquals(Optional.of("query"), context.webContext().getRequestParameter("token"));
              assertEquals(Optional.empty(), context.webContext().getRequestParameter("other"));
              var profile = new CommonProfile();
              profile.setId("alice");
              supplied.setUserProfile(profile);
              return Optional.of(supplied);
            });

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      var auth = new Pac4j(provider, "Basic");
      app.authentication(auth)
          .routes()
          .path(
              "/",
              routes ->
                  routes.post("/me", (request, response) -> response.text(request.bodyText())),
              e -> e.get(auth).required());
      app.start();
      var encoded =
          Base64.getEncoder().encodeToString("alice:correct".getBytes(StandardCharsets.UTF_8));
      var result =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + app.port() + "/me?token=query"))
                  .timeout(Duration.ofSeconds(5))
                  .header("Authorization", "Basic " + encoded)
                  .header("Content-Type", "text/plain")
                  .POST(HttpRequest.BodyPublishers.ofString("token=body&other=form"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("token=body&other=form", result.body());
    }
  }

  @Test
  void exposesProviderRequestMetadataAndRemovesEmptyResponseHeaders() throws Exception {
    var provider =
        new DirectBasicAuthClient(
            (context, supplied) -> {
              assertEquals("GET", context.webContext().getRequestMethod());
              context.webContext().setResponseHeader("X-Provider", "present");
              assertEquals(
                  Optional.of("present"), context.webContext().getResponseHeader("X-Provider"));
              context.webContext().setResponseHeader("X-Provider", "");
              var profile = new CommonProfile();
              profile.setId("alice");
              supplied.setUserProfile(profile);
              return Optional.of(supplied);
            });

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      var auth = new Pac4j(provider, "Basic");
      app.authentication(auth)
          .routes()
          .path(
              "/",
              routes -> routes.get("/me", (_, response) -> response.text("authenticated")),
              e -> e.get(auth).required());
      app.start();
      var encoded =
          Base64.getEncoder().encodeToString("alice:correct".getBytes(StandardCharsets.UTF_8));
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/me"))
                  .timeout(Duration.ofSeconds(5))
                  .header("Authorization", "Basic " + encoded)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertTrue(result.headers().allValues("X-Provider").isEmpty());
    }
  }

  @Test
  void supportsExplicitProviderFormCredentialsUsingTheFrameworkBodyLimits() throws Exception {
    var provider =
        new ParameterClient(
            "token",
            (Authenticator)
                (_, supplied) -> {
                  var credentials = (TokenCredentials) supplied;
                  if (!"allowed".equals(credentials.getToken())) {
                    return Optional.empty();
                  }

                  var profile = new CommonProfile();
                  profile.setId("alice");
                  credentials.setUserProfile(profile);
                  return Optional.of(credentials);
                });
    provider.setSupportPostRequest(true);
    provider.setSupportGetRequest(false);

    try (var app = new Shoostr(new Options("127.0.0.1", 0, 16, 1024, 1024, 5000));
        var client = HttpClient.newHttpClient()) {
      var auth = new Pac4j(provider, "Bearer");
      app.authentication(auth)
          .routes()
          .path(
              "/",
              routes ->
                  routes.post("/me", (request, response) -> response.text(request.bodyText())),
              e -> e.get(auth).required());
      app.start();

      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/me"))
                  .timeout(Duration.ofSeconds(5))
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(HttpRequest.BodyPublishers.ofString("token=allowed"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("token=allowed", result.body());
      var oversized =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/me"))
                  .timeout(Duration.ofSeconds(5))
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(
                      HttpRequest.BodyPublishers.ofInputStream(
                          () ->
                              new ByteArrayInputStream(
                                  ("token=" + "x".repeat(32)).getBytes(StandardCharsets.UTF_8))))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(413, oversized.statusCode());
    }
  }

  @Test
  void rejectsMissingHeaderCredentialsBeforeReadingTheRequestBody() throws Exception {
    var provider = new DirectBasicAuthClient((_, _) -> Optional.empty());

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      var auth = new Pac4j(provider, "Basic realm=\"api\"");
      app.authentication(auth)
          .routes()
          .path(
              "/",
              routes ->
                  routes.post("/upload", (request, response) -> response.text(request.bodyText())),
              e -> e.get(auth).required());
      app.start();

      try (var socket = new Socket(InetAddress.getAllByName("127.0.0.1")[0], app.port())) {
        socket.setSoTimeout(5000);
        socket
            .getOutputStream()
            .write(
                String.join(
                        "\r\n",
                        "POST /upload HTTP/1.1",
                        "Host: localhost",
                        "Content-Length: 10",
                        "Connection: close",
                        "",
                        "")
                    .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        var reader =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        assertEquals("HTTP/1.1 401 Unauthorized", reader.readLine());
      }
    }
  }

  @Test
  void rejectsForgedHttpsAbsoluteTargetOnPlainConnectionWhenTlsIsRequired() throws Exception {
    var provider =
        new DirectBasicAuthClient(
            (_, supplied) -> {
              var profile = new CommonProfile();
              profile.setId("alice");
              supplied.setUserProfile(profile);
              return Optional.of(supplied);
            });

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      var auth = new Pac4j(provider, "Basic", (context, _, _) -> context.isSecure());
      app.authentication(auth)
          .routes()
          .path(
              "/",
              routes -> routes.get("/secure", (_, response) -> response.text("private")),
              e -> e.get(auth).required());
      app.start();

      var encoded =
          Base64.getEncoder().encodeToString("alice:correct".getBytes(StandardCharsets.UTF_8));

      try (var socket = new Socket(InetAddress.getAllByName("127.0.0.1")[0], app.port())) {
        socket.setSoTimeout(5000);
        socket
            .getOutputStream()
            .write(
                ("GET https://virtual.example/secure HTTP/1.1\r\nHost: virtual.example\r\n"
                        + "Authorization: Basic "
                        + encoded
                        + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        var reader =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        assertEquals("HTTP/1.1 403 Forbidden", reader.readLine());
      }
    }
  }

  @Test
  void authorizesActualTlsConnectionWhenTlsIsRequired() throws Exception {
    var store = KeyStore.getInstance("PKCS12");

    try (var input =
        Objects.requireNonNull(getClass().getResourceAsStream("/localhost-test.p12"))) {
      store.load(input, "changeit".toCharArray());
    }

    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(store);
    var tls = SSLContext.getInstance("TLS");
    tls.init(null, trust.getTrustManagers(), null);
    var keyStorePath = temporary.resolve("localhost-test.p12");

    try (var input =
        Objects.requireNonNull(getClass().getResourceAsStream("/localhost-test.p12"))) {
      Files.copy(input, keyStorePath);
    }

    var provider =
        new DirectBasicAuthClient(
            (_, supplied) -> {
              var profile = new CommonProfile();
              profile.setId("alice");
              supplied.setUserProfile(profile);
              return Optional.of(supplied);
            });

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newBuilder().sslContext(tls).build()) {
      app.tls(
          server -> {
            server.setKeyStorePath(keyStorePath.toString());
            server.setKeyStorePassword("changeit");
          });
      var auth = new Pac4j(provider, "Basic", (context, _, _) -> context.isSecure());
      app.authentication(auth)
          .routes()
          .path(
              "/",
              routes -> routes.get("/secure", (_, response) -> response.text("private")),
              e -> e.get(auth).required());
      app.start();

      var encoded =
          Base64.getEncoder().encodeToString("alice:correct".getBytes(StandardCharsets.UTF_8));
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("https://localhost:" + app.port() + "/secure"))
                  .timeout(Duration.ofSeconds(5))
                  .header("Authorization", "Basic " + encoded)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("private", result.body());
    }
  }

  @Test
  void keepsPrincipalsIsolatedAcrossConcurrentRequestsAndPublicRoutes() throws Exception {
    var provider =
        new DirectBasicAuthClient(
            (_, supplied) -> {
              var credentials = (UsernamePasswordCredentials) supplied;
              if (!"test-password".equals(credentials.getPassword())) {
                return Optional.empty();
              }

              var profile = new CommonProfile();
              profile.setId(credentials.getUsername());
              credentials.setUserProfile(profile);
              return Optional.of(credentials);
            });

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      var auth = new Pac4j(provider, "Basic");
      app.authentication(auth)
          .routes()
          .path(
              "/",
              routes ->
                  routes.get(
                      "/me",
                      (request, response) ->
                          response.text(request.principal().orElseThrow().getName())),
              e -> e.get(auth).required());
      app.routes()
          .get(
              "/public",
              (request, response) ->
                  response.text(request.principal().map(Object::toString).orElse("null")));
      app.start();

      var requests = new ArrayList<CompletableFuture<HttpResponse<String>>>();
      for (int index = 0; index < 20; index++) {
        var credentials = "user-" + index + ":test-password";
        requests.add(
            client.sendAsync(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/me"))
                    .timeout(Duration.ofSeconds(5))
                    .header(
                        "Authorization",
                        "Basic "
                            + Base64.getEncoder()
                                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()));
      }
      for (int index = 0; index < requests.size(); index++) {
        var result = requests.get(index).get(5, TimeUnit.SECONDS);
        assertEquals(200, result.statusCode());
        assertEquals("user-" + index, result.body());
      }
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/public"))
                  .timeout(Duration.ofSeconds(5))
                  .header("Authorization", "garbage")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("null", result.body());
    }
  }

  @Test
  void usesProviderSignatureAndExpiryValidationForBearerTokens() throws Exception {
    var signing = new SecretSignatureConfiguration("test-only-signing-key-32-bytes!!!!");
    var generator = new JwtGenerator(signing);
    var profile = new CommonProfile();
    profile.setId("alice");
    var valid = generator.generate(profile);
    var wrongKey =
        new JwtGenerator(new SecretSignatureConfiguration("other-test-signing-key-32-bytes!!!"))
            .generate(profile);
    var unsigned = new JwtGenerator().generate(profile);
    generator.setExpirationTime(Date.from(Instant.EPOCH));
    var expired = generator.generate(profile);
    var provider = new HeaderClient("Authorization", "Bearer ", new JwtAuthenticator(signing));

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      var auth = new Pac4j(provider, "Bearer");
      app.authentication(auth)
          .routes()
          .path(
              "/",
              routes ->
                  routes.get(
                      "/me",
                      (request, response) ->
                          response.text(request.principal().orElseThrow().getName())),
              e -> e.get(auth).required());
      app.start();

      var uri = URI.create("http://127.0.0.1:" + app.port() + "/me");
      var accepted =
          client.send(
              HttpRequest.newBuilder(uri)
                  .timeout(Duration.ofSeconds(5))
                  .header("Authorization", "Bearer " + valid)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, accepted.statusCode());
      assertEquals("alice", accepted.body());
      for (var token : List.of(wrongKey, unsigned, expired, "malformed")) {
        var rejected =
            client.send(
                HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, rejected.statusCode());
        assertEquals("Bearer", rejected.headers().firstValue("WWW-Authenticate").orElseThrow());
      }
    }
  }

  @Test
  void distinguishesMissingAndInvalidCredentialsFromDeniedPermissions() throws Exception {
    var provider =
        new DirectBasicAuthClient(
            (_, supplied) -> {
              var credentials = (UsernamePasswordCredentials) supplied;
              if (!"alice".equals(credentials.getUsername())
                  || !"correct".equals(credentials.getPassword())) {
                return Optional.empty();
              }

              var profile = new CommonProfile();
              profile.setId("alice");
              credentials.setUserProfile(profile);
              return Optional.of(credentials);
            });
    var security = new Pac4j(provider, "Basic realm=\"api\"", (_, _, _) -> false);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.authentication(security)
          .routes()
          .path(
              "/",
              routes -> routes.get("/private", (_, response) -> response.text("secret")),
              e -> e.get(security).required());
      app.start();

      var uri = URI.create("http://127.0.0.1:" + app.port() + "/private");
      for (var credentials : new String[] {"", "alice:wrong", "alice:correct"}) {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5));
        if (!credentials.isEmpty()) {
          request.header(
              "Authorization",
              "Basic "
                  + Base64.getEncoder()
                      .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }

        var result = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("alice:correct".equals(credentials) ? 403 : 401, result.statusCode());
        assertEquals(
            "alice:correct".equals(credentials)
                ? Optional.empty()
                : Optional.of("Basic realm=\"api\""),
            result.headers().firstValue("WWW-Authenticate"));
      }
    }
  }

  @Test
  void validatesCredentialsWithPac4jBeforePropagatingThePrincipal() throws Exception {
    var provider =
        new DirectBasicAuthClient(
            (_, supplied) -> {
              var credentials = (UsernamePasswordCredentials) supplied;
              if (!"alice".equals(credentials.getUsername())
                  || !"correct".equals(credentials.getPassword())) {
                return Optional.empty();
              }

              var profile = new CommonProfile();
              profile.setId("alice");
              credentials.setUserProfile(profile);
              return Optional.of(credentials);
            });
    var security = new Pac4j(provider, "Basic realm=\"api\"");

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.authentication(security)
          .routes()
          .path(
              "/",
              routes ->
                  routes.get(
                      "/me",
                      (request, response) ->
                          response.text(request.principal().orElseThrow().getName())),
              e -> e.get(security).required());
      app.start();

      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/me"))
              .timeout(Duration.ofSeconds(5))
              .header(
                  "Authorization",
                  "Basic "
                      + Base64.getEncoder()
                          .encodeToString("alice:correct".getBytes(StandardCharsets.UTF_8)))
              .GET()
              .build();
      var result = client.send(request, HttpResponse.BodyHandlers.ofString());

      assertEquals(200, result.statusCode());
      assertEquals("alice", result.body());
    }
  }
}
