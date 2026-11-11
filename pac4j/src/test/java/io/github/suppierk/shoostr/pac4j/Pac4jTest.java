package io.github.suppierk.shoostr.pac4j;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.MultipartOptions;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetAddress;
import java.net.Socket;
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
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pac4j.core.context.CallContext;
import org.pac4j.core.context.Cookie;
import org.pac4j.core.credentials.Credentials;
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

  private static DirectBasicAuthClient authenticatedAliceClient() {
    return new DirectBasicAuthClient(
        (ignoredCallContext, supplied) -> {
          if (!(supplied instanceof UsernamePasswordCredentials credentials)
              || !"alice".equals(credentials.getUsername())
              || !"correct".equals(credentials.getPassword())) {
            return Optional.empty();
          }

          var profile = new CommonProfile();
          profile.setId("alice");
          credentials.setUserProfile(profile);

          return Optional.of(credentials);
        });
  }

  @Test
  @Timeout(10)
  void authenticatesWithoutCreatingSessionWhenSessionsAreEnabled() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        var auth = new Pac4j(authenticatedAliceClient(), "Basic");

        app.sessions()
            .routes()
            .get(
                "/me",
                (request, response) -> {
                  assertTrue(request.getSession().isEmpty());

                  response.text(request.principal().orElseThrow().getName());
                },
                lifecycle -> lifecycle.withExtension(auth));

        try (var test =
            TestServer.start(
                app,
                client ->
                    client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {

          var result = sendResultForAuthenticatesWithoutCreatingSessionWhenSessionsAreEnabled(test);

          assertEquals(200, result.statusCode());
          assertEquals("alice", result.body());
          assertTrue(result.headers().allValues("Set-Cookie").isEmpty());
        }
      }
    }
  }

  private HttpResponse<String>
      sendResultForAuthenticatesWithoutCreatingSessionWhenSessionsAreEnabled(TestServer test)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/me")
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Basic YWxpY2U6Y29ycmVjdA=="),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @Timeout(15)
  void createsAndResumesSessionOnlyWhenTheAuthenticatedHandlerRequestsIt() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {
        var auth = new Pac4j(authenticatedAliceClient(), "Basic");

        app.sessions()
            .routes()
            .get(
                "/session",
                (request, response) -> {
                  var identity = request.principal().orElseThrow().getName();
                  var existing = request.getSession();
                  if (existing.isPresent()) {
                    assertEquals("handler-owned", existing.orElseThrow().getAttribute("marker"));

                    response.text(identity + ":resumed");
                  } else {
                    request.getOrCreateSession().setAttribute("marker", "handler-owned");
                    response.text(identity + ":created");
                  }
                },
                lifecycle -> lifecycle.withExtension(auth));

        try (var test =
            TestServer.start(
                app,
                client ->
                    client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {

          var created = sendCreatedSessionRequestForLazySession(test);

          assertEquals(200, created.statusCode());
          assertEquals("alice:created", created.body());

          var cookies = created.headers().allValues("Set-Cookie");

          assertEquals(1, cookies.size());

          var cookie = cookies.getFirst().split(";", 2)[0];

          assertTrue(cookie.startsWith("JSESSIONID="));

          var resumed = sendResumedRequestForLazySession(test, cookie);

          assertEquals(200, resumed.statusCode());
          assertEquals("alice:resumed", resumed.body());
          assertTrue(resumed.headers().allValues("Set-Cookie").isEmpty());
        }
      }
    }
  }

  private HttpResponse<String> sendCreatedSessionRequestForLazySession(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/session")
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Basic YWxpY2U6Y29ycmVjdA=="),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> sendResumedRequestForLazySession(TestServer test, String cookie)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/session")
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Basic YWxpY2U6Y29ycmVjdA==")
                .header("Cookie", cookie),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void exposesEveryDuplicateCookieToTheAuthenticationProvider() throws Exception {
    var received = new CompletableFuture<List<String>>();
    var provider =
        createProviderForExposesEveryDuplicateCookieToTheAuthenticationProvider(received);

    {
      var app = new Shoostr();

      try (Closeable appCleanup2 = app::close) {
        var security = new Pac4j(provider, "Basic");
        app.routes(
            routes ->
                routes.get(
                    "/me",
                    (ignored, response) -> response.text("authenticated"),
                    lifecycle -> lifecycle.withExtension(security)));

        try (var test =
            TestServer.start(
                app,
                client ->
                    client.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)))) {

          var encoded =
              Base64.getEncoder().encodeToString("alice:correct".getBytes(StandardCharsets.UTF_8));
          var result =
              sendResultForExposesEveryDuplicateCookieToTheAuthenticationProvider(test, encoded);

          assertEquals(200, result.statusCode());
          assertEquals(
              List.of("token=first", "token=second", "Token=upper"),
              received.get(3, TimeUnit.SECONDS));
        }
      }
    }
  }

  private DirectBasicAuthClient
      createProviderForExposesEveryDuplicateCookieToTheAuthenticationProvider(
          CompletableFuture<List<String>> received) {
    return new DirectBasicAuthClient(
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
  }

  private HttpResponse<String> sendResultForExposesEveryDuplicateCookieToTheAuthenticationProvider(
      TestServer test, String encoded) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/me")
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Basic " + encoded)
                .header("Cookie", "token=first; token=second; Token=upper"),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void preservesSecurePrefixedCookiesEmittedByTheProvider() throws Exception {
    var expectedPort = new AtomicInteger();
    var provider =
        createProviderForPreservesSecurePrefixedCookiesEmittedByTheProvider(expectedPort);

    {
      var app = new Shoostr();

      try (Closeable appCleanup3 = app::close) {
        var auth = new Pac4j(provider, "Basic");
        app.routes()
            .path(
                "/",
                routes ->
                    routes.get("/me", (ignoredRequest, response) -> response.text("authenticated")),
                lifecycle -> lifecycle.withExtension(auth));

        try (var test = TestServer.start(app)) {
          expectedPort.set(app.port());

          var encoded =
              Base64.getEncoder().encodeToString("alice:correct".getBytes(StandardCharsets.UTF_8));
          var result =
              sendResultForPreservesSecurePrefixedCookiesEmittedByTheProvider(test, encoded);

          assertEquals(200, result.statusCode());
          assertEquals(
              List.of(
                  "__Host-token=token; Path=/; Secure; HttpOnly; SameSite=None",
                  "__Secure-token=token; Path=/; Secure; HttpOnly; SameSite=None"),
              result.headers().allValues("Set-Cookie"));
        }
      }
    }
  }

  private DirectBasicAuthClient createProviderForPreservesSecurePrefixedCookiesEmittedByTheProvider(
      AtomicInteger expectedPort) {
    return new DirectBasicAuthClient(
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
  }

  private HttpResponse<String> sendResultForPreservesSecurePrefixedCookiesEmittedByTheProvider(
      TestServer test, String encoded) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/me")
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Basic " + encoded),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsAnExpiredProviderProfileBeforeEstablishingPrincipal() throws Exception {
    var provider = expiredProfileClient();

    {
      var app = new Shoostr();

      try (Closeable appCleanup4 = app::close) {
        app.exception(
            AuthenticationRequiredException.class,
            (ignoredFailure, request, response) ->
                response.text(request.principal().map(Object::toString).orElse("null")));
        var auth = new Pac4j(provider, "Basic");
        app.routes()
            .path(
                "/",
                routes -> routes.get("/me", (ignoredRequest, response) -> response.text("private")),
                lifecycle -> lifecycle.withExtension(auth));

        try (var test = TestServer.start(app)) {

          var encoded =
              Base64.getEncoder().encodeToString("alice:correct".getBytes(StandardCharsets.UTF_8));
          var result =
              sendResultForRejectsAnExpiredProviderProfileBeforeEstablishingPrincipal(
                  test, encoded);

          assertEquals(401, result.statusCode());
          assertEquals("null", result.body());
        }
      }
    }
  }

  private DirectBasicAuthClient expiredProfileClient() {
    return new DirectBasicAuthClient(
        (ignoredCallContext, supplied) -> {
          var profile =
              createProfileForRejectsAnExpiredProviderProfileBeforeEstablishingPrincipal();
          profile.setId("alice");
          supplied.setUserProfile(profile);

          return Optional.of(supplied);
        });
  }

  private CommonProfile
      createProfileForRejectsAnExpiredProviderProfileBeforeEstablishingPrincipal() {
    return new CommonProfile() {
      @Override
      public boolean isExpired() {
        return true;
      }
    };
  }

  private HttpResponse<String>
      sendResultForRejectsAnExpiredProviderProfileBeforeEstablishingPrincipal(
          TestServer test, String encoded) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/me")
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Basic " + encoded),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void mapsProviderFailuresToServerErrorsWithoutLeakingDetailsOrChallenges() throws Exception {
    var provider =
        new DirectBasicAuthClient(
            (ignoredCallContext, ignoredCredentials) -> {
              throw new IllegalStateException("sensitive identity-store diagnostic");
            });

    {
      var app = new Shoostr();

      try (Closeable appCleanup5 = app::close) {
        var auth = new Pac4j(provider, "Basic");
        app.routes()
            .path(
                "/",
                routes -> routes.get("/me", (ignoredRequest, response) -> response.text("private")),
                lifecycle -> lifecycle.withExtension(auth));

        try (var test = TestServer.start(app)) {

          var encoded =
              Base64.getEncoder().encodeToString("alice:correct".getBytes(StandardCharsets.UTF_8));
          var result =
              sendResultForMapsProviderFailuresToServerErrorsWithoutLeakingDetailsOrChallenges(
                  test, encoded);

          assertEquals(500, result.statusCode());
          assertEquals("Internal Server Error", result.body());
          assertEquals(Optional.empty(), result.headers().firstValue("WWW-Authenticate"));
        }
      }
    }
  }

  private HttpResponse<String>
      sendResultForMapsProviderFailuresToServerErrorsWithoutLeakingDetailsOrChallenges(
          TestServer test, String encoded) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/me")
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Basic " + encoded),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsAmbiguousAuthorizationHeaders() throws Exception {
    var provider = permissiveAliceClient();

    {
      var app = new Shoostr();

      try (Closeable appCleanup6 = app::close) {
        var auth = new Pac4j(provider, "Basic");
        app.routes()
            .path(
                "/",
                routes -> routes.get("/me", (ignoredRequest, response) -> response.text("private")),
                lifecycle -> lifecycle.withExtension(auth));

        try (var test = TestServer.start(app)) {

          var encoded =
              Base64.getEncoder().encodeToString("alice:correct".getBytes(StandardCharsets.UTF_8));
          var result = sendResultForRejectsAmbiguousAuthorizationHeaders(test, encoded);

          assertEquals(401, result.statusCode());
        }
      }
    }
  }

  private HttpResponse<String> sendResultForRejectsAmbiguousAuthorizationHeaders(
      TestServer test, String encoded) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/me")
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Basic " + encoded)
                .header("Authorization", "Basic " + encoded),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void exposesOrderedQueryAndFormValuesToTheConfiguredProvider() throws Exception {
    var provider = createProviderForExposesOrderedQueryAndFormValuesToTheConfiguredProvider();

    {
      var app = new Shoostr();

      try (Closeable appCleanup7 = app::close) {
        var auth = new Pac4j(provider, "Basic");
        app.routes()
            .path(
                "/",
                routes ->
                    routes.post(
                        "/me", (ignoredRequest, response) -> response.text("authenticated")),
                lifecycle -> lifecycle.withExtension(auth));

        try (var test = TestServer.start(app)) {
          var encoded =
              Base64.getEncoder().encodeToString("alice:correct".getBytes(StandardCharsets.UTF_8));
          var result =
              sendResultForExposesOrderedQueryAndFormValuesToTheConfiguredProvider(test, encoded);

          assertEquals(200, result.statusCode());
          assertEquals("authenticated", result.body());
        }
      }
    }
  }

  private DirectBasicAuthClient
      createProviderForExposesOrderedQueryAndFormValuesToTheConfiguredProvider() {
    return new DirectBasicAuthClient(
        (context, supplied) -> {
          var parameters = context.webContext().getRequestParameters();

          assertArrayEquals(new String[] {"query", "form"}, parameters.get("shared"));
          assertArrayEquals(new String[] {"query-only"}, parameters.get("query"));
          assertArrayEquals(new String[] {"form-only"}, parameters.get("form"));
          assertEquals(Optional.of("query"), context.webContext().getRequestParameter("shared"));

          var profile = new CommonProfile();
          profile.setId("alice");
          supplied.setUserProfile(profile);

          return Optional.of(supplied);
        });
  }

  private HttpResponse<String> sendResultForExposesOrderedQueryAndFormValuesToTheConfiguredProvider(
      TestServer test, String encoded) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/me?shared=query&query=query-only")
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Basic " + encoded)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .method("POST")
                .body("shared=form&form=form-only".getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void leavesNonFormBodiesAvailableWhileExposingQueryCredentials() throws Exception {
    var provider = createProviderForLeavesNonFormBodiesAvailableWhileExposingQueryCredentials();

    {
      var app = new Shoostr();

      try (Closeable appCleanup8 = app::close) {
        var auth = new Pac4j(provider, "Basic");
        app.routes()
            .path(
                "/",
                routes ->
                    routes.post("/me", (request, response) -> response.text(request.bodyText())),
                lifecycle -> lifecycle.withExtension(auth));

        try (var test = TestServer.start(app)) {
          var encoded =
              Base64.getEncoder().encodeToString("alice:correct".getBytes(StandardCharsets.UTF_8));
          var result =
              sendResultForLeavesNonFormBodiesAvailableWhileExposingQueryCredentials(test, encoded);

          assertEquals(200, result.statusCode());
          assertEquals("token=body&other=form", result.body());
        }
      }
    }
  }

  private DirectBasicAuthClient
      createProviderForLeavesNonFormBodiesAvailableWhileExposingQueryCredentials() {
    return new DirectBasicAuthClient(
        (context, supplied) -> {
          assertEquals(Optional.of("query"), context.webContext().getRequestParameter("token"));
          assertEquals(Optional.empty(), context.webContext().getRequestParameter("other"));

          var profile = new CommonProfile();
          profile.setId("alice");
          supplied.setUserProfile(profile);

          return Optional.of(supplied);
        });
  }

  private HttpResponse<String>
      sendResultForLeavesNonFormBodiesAvailableWhileExposingQueryCredentials(
          TestServer test, String encoded) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/me?token=query")
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Basic " + encoded)
                .header("Content-Type", "text/plain")
                .method("POST")
                .body("token=body&other=form".getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void exposesProviderRequestMetadataAndRemovesEmptyResponseHeaders() throws Exception {
    var provider = createProviderForExposesProviderRequestMetadataAndRemovesEmptyResponseHeaders();

    {
      var app = new Shoostr();

      try (Closeable appCleanup9 = app::close) {
        var auth = new Pac4j(provider, "Basic");
        app.routes()
            .path(
                "/",
                routes ->
                    routes.get("/me", (ignoredRequest, response) -> response.text("authenticated")),
                lifecycle -> lifecycle.withExtension(auth));

        try (var test = TestServer.start(app)) {
          var encoded =
              Base64.getEncoder().encodeToString("alice:correct".getBytes(StandardCharsets.UTF_8));
          var result =
              sendResultForExposesProviderRequestMetadataAndRemovesEmptyResponseHeaders(
                  test, encoded);

          assertEquals(200, result.statusCode());
          assertTrue(result.headers().allValues("X-Provider").isEmpty());
        }
      }
    }
  }

  private DirectBasicAuthClient
      createProviderForExposesProviderRequestMetadataAndRemovesEmptyResponseHeaders() {
    return new DirectBasicAuthClient(
        (context, supplied) -> {
          assertEquals("GET", context.webContext().getRequestMethod());

          context.webContext().setResponseHeader("X-Provider", "present");

          assertEquals(
              Optional.of("present"), context.webContext().getResponseHeader("X-Provider"));

          context.webContext().setResponseHeader("X-Provider", "");

          assertEquals(Optional.empty(), context.webContext().getResponseHeader("X-Provider"));

          var profile = new CommonProfile();
          profile.setId("alice");
          supplied.setUserProfile(profile);

          return Optional.of(supplied);
        });
  }

  private HttpResponse<String>
      sendResultForExposesProviderRequestMetadataAndRemovesEmptyResponseHeaders(
          TestServer test, String encoded) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/me")
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Basic " + encoded),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void supportsExplicitProviderFormCredentialsUsingTheFrameworkBodyLimits() throws Exception {
    var provider = formClient();

    var configuredOptions = new Options();
    configuredOptions.setMaxRequestBytes(16);
    configuredOptions.setMaxResponseBytes(1024);
    configuredOptions.setStreamBufferBytes(1024);
    configuredOptions.setMaxParameters(1000);
    configuredOptions.setMultipart(MultipartOptions.defaults());

    {
      var app = createAppForFormCredentials(configuredOptions);

      try (Closeable appCleanup10 = app::close;
          var client = HttpClient.newHttpClient()) {
        var auth = new Pac4j(provider, "Bearer");
        app.routes()
            .path(
                "/",
                routes ->
                    routes.post("/me", (request, response) -> response.text(request.bodyText())),
                lifecycle -> lifecycle.withExtension(auth));

        try (var test = TestServer.start(app)) {
          var result = sendResultForFormCredentials(test);

          assertEquals(200, result.statusCode());
          assertEquals("token=allowed", result.body());
          // An independent publisher preserves the unknown-length body limit under test.
          var oversized = sendOversizedRequestForFormCredentials(client, test);

          assertEquals(413, oversized.statusCode());
        }
      }
    }
  }

  private ParameterClient formClient() {
    var client = new ParameterClient("token", (Authenticator) this::authenticateFormToken);
    configureFormClient(client);

    return client;
  }

  private void configureFormClient(ParameterClient client) {
    client.setSupportPostRequest(true);
    client.setSupportGetRequest(false);
  }

  private Shoostr createAppForFormCredentials(Options configuredOptions) {
    return new Shoostr(configuredOptions)
        .modifyServer(
            nativeServer -> {
              var nativeConnector = (ServerConnector) nativeServer.getConnectors()[0];
              nativeConnector.setHost("127.0.0.1");
              nativeConnector.setPort(0);
              nativeConnector.setIdleTimeout(5000);
            });
  }

  private HttpResponse<String> sendResultForFormCredentials(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/me")
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .method("POST")
                .body("token=allowed".getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> sendOversizedRequestForFormCredentials(
      HttpClient client, TestServer test) throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(test.baseUri().resolve("/me"))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofInputStream(
                    () ->
                        new ByteArrayInputStream(
                            ("token=" + "x".repeat(32)).getBytes(StandardCharsets.UTF_8))))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsMissingHeaderCredentialsBeforeReadingTheRequestBody() throws Exception {
    var provider =
        new DirectBasicAuthClient((ignoredCallContext, ignoredCredentials) -> Optional.empty());

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup11 = app::close) {
        var auth = new Pac4j(provider, "Basic realm=\"api\"");
        app.routes()
            .path(
                "/",
                routes ->
                    routes.post(
                        "/upload", (request, response) -> response.text(request.bodyText())),
                lifecycle -> lifecycle.withExtension(auth));
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
  }

  @Test
  void rejectsForgedHttpsAbsoluteTargetOnPlainConnectionWhenTlsIsRequired() throws Exception {
    var provider = permissiveAliceClient();

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup12 = app::close) {
        var auth =
            new Pac4j(
                provider,
                "Basic",
                (context, ignoredSessionStore, ignoredList) -> context.isSecure());
        app.routes()
            .path(
                "/",
                routes ->
                    routes.get("/secure", (ignoredRequest, response) -> response.text("private")),
                lifecycle -> lifecycle.withExtension(auth));
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

    var provider = permissiveAliceClient();

    {
      var app = new Shoostr();

      try (Closeable appCleanup13 = app::close) {
        app.tls(
            server -> {
              server.setKeyStorePath(keyStorePath.toString());
              server.setKeyStorePassword("changeit");
            });
        var auth =
            new Pac4j(
                provider,
                "Basic",
                (context, ignoredSessionStore, ignoredList) -> context.isSecure());
        app.routes()
            .path(
                "/",
                routes ->
                    routes.get("/secure", (ignoredRequest, response) -> response.text("private")),
                lifecycle -> lifecycle.withExtension(auth));

        try (var test = TestServer.start(app, client -> client.sslContext(tls))) {

          var encoded =
              Base64.getEncoder().encodeToString("alice:correct".getBytes(StandardCharsets.UTF_8));
          var result = sendResultForAuthorizesActualTlsConnectionWhenTlsIsRequired(test, encoded);

          assertEquals(200, result.statusCode());
          assertEquals("private", result.body());
        }
      }
    }
  }

  private DirectBasicAuthClient permissiveAliceClient() {
    return new DirectBasicAuthClient(
        (ignoredCallContext, supplied) -> {
          var profile = new CommonProfile();
          profile.setId("alice");
          supplied.setUserProfile(profile);

          return Optional.of(supplied);
        });
  }

  private HttpResponse<String> sendResultForAuthorizesActualTlsConnectionWhenTlsIsRequired(
      TestServer test, String encoded) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/secure")
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Basic " + encoded),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void keepsPrincipalsIsolatedAcrossConcurrentRequestsAndPublicRoutes() throws Exception {
    var provider =
        createProviderForKeepsPrincipalsIsolatedAcrossConcurrentRequestsAndPublicRoutes();

    {
      var app = new Shoostr();

      try (Closeable appCleanup14 = app::close;
          var testRequests = Executors.newVirtualThreadPerTaskExecutor()) {
        var auth = new Pac4j(provider, "Basic");
        app.routes()
            .path(
                "/",
                routes ->
                    routes.get(
                        "/me",
                        (request, response) ->
                            response.text(request.principal().orElseThrow().getName())),
                lifecycle -> lifecycle.withExtension(auth));
        app.routes()
            .get(
                "/public",
                (request, response) ->
                    response.text(request.principal().map(Object::toString).orElse("null")));

        try (var test = TestServer.start(app)) {

          var requests = new ArrayList<Future<HttpResponse<String>>>();
          for (int index = 0; index < 20; index++) {
            var credentials = "user-" + index + ":test-password";
            requests.add(
                testRequests.submit(
                    () ->
                        test.send(
                            request ->
                                request
                                    .path("/me")
                                    .timeout(Duration.ofSeconds(5))
                                    .header(
                                        "Authorization",
                                        "Basic "
                                            + Base64.getEncoder()
                                                .encodeToString(
                                                    credentials.getBytes(StandardCharsets.UTF_8))),
                            HttpResponse.BodyHandlers.ofString())));
          }
          for (int index = 0; index < requests.size(); index++) {
            var result = requests.get(index).get(5, TimeUnit.SECONDS);

            assertEquals(200, result.statusCode());
            assertEquals("user-" + index, result.body());
          }
          var result =
              sendResultForKeepsPrincipalsIsolatedAcrossConcurrentRequestsAndPublicRoutes(test);

          assertEquals(200, result.statusCode());
          assertEquals("null", result.body());
        }
      }
    }
  }

  private DirectBasicAuthClient
      createProviderForKeepsPrincipalsIsolatedAcrossConcurrentRequestsAndPublicRoutes() {
    return new DirectBasicAuthClient(
        (ignoredCallContext, supplied) -> {
          var credentials = (UsernamePasswordCredentials) supplied;
          if (!"test-password".equals(credentials.getPassword())) {
            return Optional.empty();
          }

          var profile = new CommonProfile();
          profile.setId(credentials.getUsername());
          credentials.setUserProfile(profile);

          return Optional.of(credentials);
        });
  }

  private HttpResponse<String>
      sendResultForKeepsPrincipalsIsolatedAcrossConcurrentRequestsAndPublicRoutes(TestServer test)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/public")
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "garbage"),
        HttpResponse.BodyHandlers.ofString());
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

    {
      var app = new Shoostr();

      try (Closeable appCleanup15 = app::close) {
        var auth = new Pac4j(provider, "Bearer");
        app.routes()
            .path(
                "/",
                routes ->
                    routes.get(
                        "/me",
                        (request, response) ->
                            response.text(request.principal().orElseThrow().getName())),
                lifecycle -> lifecycle.withExtension(auth));

        try (var test = TestServer.start(app)) {

          var accepted = sendBearerRequest(test, valid);

          assertEquals(200, accepted.statusCode());
          assertEquals("alice", accepted.body());

          for (var token : List.of(wrongKey, unsigned, expired, "malformed")) {
            var rejected = sendBearerRequest(test, token);

            assertEquals(401, rejected.statusCode());
            assertEquals("Bearer", rejected.headers().firstValue("WWW-Authenticate").orElseThrow());
          }
        }
      }
    }
  }

  private HttpResponse<String> sendBearerRequest(TestServer test, String token)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/me")
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void distinguishesMissingAndInvalidCredentialsFromDeniedPermissions() throws Exception {
    var provider = passwordClient();
    var security =
        new Pac4j(
            provider,
            "Basic realm=\"api\"",
            (ignoredWebContext, ignoredSessionStore, ignoredList) -> false);

    {
      var app = new Shoostr();

      try (Closeable appCleanup16 = app::close) {
        app.routes()
            .path(
                "/",
                routes ->
                    routes.get("/private", (ignoredRequest, response) -> response.text("secret")),
                lifecycle -> lifecycle.withExtension(security));

        try (var test = TestServer.start(app)) {

          for (var credentials : new String[] {"", "alice:wrong", "alice:correct"}) {

            var result =
                sendResultForDistinguishesMissingAndInvalidCredentialsFromDeniedPermissions(
                    test, credentials);

            assertEquals("alice:correct".equals(credentials) ? 403 : 401, result.statusCode());
            assertEquals(
                "alice:correct".equals(credentials)
                    ? Optional.empty()
                    : Optional.of("Basic realm=\"api\""),
                result.headers().firstValue("WWW-Authenticate"));
          }
        }
      }
    }
  }

  private HttpResponse<String>
      sendResultForDistinguishesMissingAndInvalidCredentialsFromDeniedPermissions(
          TestServer test, String credentials) throws IOException, InterruptedException {
    return test.send(
        request -> {
          request.path("/private").timeout(Duration.ofSeconds(5));
          if (!credentials.isEmpty()) {
            request.header(
                "Authorization",
                "Basic "
                    + Base64.getEncoder()
                        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
          }
        },
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void validatesCredentialsWithPac4jBeforePropagatingThePrincipal() throws Exception {
    var provider = passwordClient();
    var security = new Pac4j(provider, "Basic realm=\"api\"");

    {
      var app = new Shoostr();

      try (Closeable appCleanup17 = app::close) {
        app.routes()
            .path(
                "/",
                routes ->
                    routes.get(
                        "/me",
                        (request, response) ->
                            response.text(request.principal().orElseThrow().getName())),
                lifecycle -> lifecycle.withExtension(security));

        try (var test = TestServer.start(app)) {

          var result =
              sendResultForValidatesCredentialsWithPac4jBeforePropagatingThePrincipal(test);

          assertEquals(200, result.statusCode());
          assertEquals("alice", result.body());
        }
      }
    }
  }

  private DirectBasicAuthClient passwordClient() {
    return new DirectBasicAuthClient(
        (ignoredCallContext, supplied) -> {
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
  }

  private HttpResponse<String>
      sendResultForValidatesCredentialsWithPac4jBeforePropagatingThePrincipal(TestServer test)
          throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/me")
                .timeout(Duration.ofSeconds(5))
                .header(
                    "Authorization",
                    "Basic "
                        + Base64.getEncoder()
                            .encodeToString("alice:correct".getBytes(StandardCharsets.UTF_8))),
        HttpResponse.BodyHandlers.ofString());
  }

  private Optional<Credentials> authenticateFormToken(CallContext context, Credentials supplied) {
    var credentials = (TokenCredentials) supplied;
    if (!"allowed".equals(credentials.getToken())) {
      return Optional.empty();
    }

    var profile = new CommonProfile();
    profile.setId("alice");
    credentials.setUserProfile(profile);

    return Optional.of(credentials);
  }
}
