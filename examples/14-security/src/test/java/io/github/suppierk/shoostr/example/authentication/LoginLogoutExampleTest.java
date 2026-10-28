package io.github.suppierk.shoostr.example.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(30)
class LoginLogoutExampleTest {
  private static KeyStore identity;
  private static SSLContext clientTls;

  @BeforeAll
  static void createLocalTlsIdentity(@TempDir Path temporary) throws Exception {
    // Trust only the isolated identity generated for these HTTPS tests.
    var store = temporary.resolve("test.p12");
    var log = temporary.resolve("keytool.log");
    var tool = Path.of(System.getProperty("java.home"), "bin", "keytool");
    var process =
        new ProcessBuilder(
                tool.toString(),
                "-genkeypair",
                "-alias",
                "local",
                "-keyalg",
                "EC",
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=dns:localhost,ip:127.0.0.1",
                "-validity",
                "1",
                "-storetype",
                "PKCS12",
                "-keystore",
                store.toString(),
                "-storepass",
                "local-test-password",
                "-noprompt")
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    assertTrue(process.waitFor(20, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue(), Files.readString(log));
    identity = KeyStore.getInstance(store.toFile(), "local-test-password".toCharArray());
    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(identity);
    clientTls = SSLContext.getInstance("TLS");
    clientTls.init(null, trust.getTrustManagers(), null);
  }

  @Test
  void concurrentLoginsPublishOneIdentityAndRejectTheOldIdentifierAndProof() throws Exception {
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);

    try (var test =
            start(
                (username, password) -> {
                  entered.countDown();

                  try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                      throw new IllegalStateException("Credential verification was not released");
                    }
                  } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                  }

                  return Optional.of(username);
                });
        var workers = Executors.newFixedThreadPool(2)) {
      var anonymous = bootstrap(test);
      // Both requests pass CSRF and begin verification before either can publish an account.
      var first =
          workers.submit(
              () ->
                  post(
                      test,
                      "/login",
                      cookies(anonymous),
                      anonymous.body(),
                      "username=alice&password=fixture"));
      var second =
          workers.submit(
              () ->
                  post(
                      test,
                      "/login",
                      cookies(anonymous),
                      anonymous.body(),
                      "username=bob&password=fixture"));

      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }

      var alice = first.get(10, TimeUnit.SECONDS);
      var bob = second.get(10, TimeUnit.SECONDS);
      assertEquals(
          List.of(200, 409), Stream.of(alice.statusCode(), bob.statusCode()).sorted().toList());
      var winner = alice.statusCode() == 200 ? alice : bob;
      var loser = alice.statusCode() == 409 ? alice : bob;
      var account = alice.statusCode() == 200 ? "alice" : "bob";
      assertEquals(1, winner.headers().allValues("Set-Cookie").size());
      assertTrue(loser.headers().allValues("Set-Cookie").isEmpty());
      assertNotEquals(cookie(anonymous), cookie(winner));
      assertNotEquals(anonymous.body(), winner.body());
      assertEquals(account + ":light", account(test, cookies(winner)).body());
      assertEquals(401, account(test, cookies(anonymous)).statusCode());
      assertEquals(
          403,
          post(test, "/account/theme", cookies(winner), anonymous.body(), "theme=dark")
              .statusCode());
      assertEquals(account + ":light", account(test, cookies(winner)).body());
      assertEquals(
          200,
          post(test, "/account/theme", cookies(winner), winner.body(), "theme=dark").statusCode());
      assertEquals(account + ":dark", account(test, cookies(winner)).body());
    }
  }

  @Test
  void renewsTheAnonymousSessionAndRotatesProofBeforeUsingTheVerifiedCanonicalIdentity()
      throws Exception {
    try (var test = start(LoginLogoutExampleTest::verifyCredentials)) {
      var anonymous = bootstrap(test);
      var login =
          post(
              test,
              "/login",
              cookies(anonymous),
              anonymous.body(),
              "username=reader&password=local-only");
      assertEquals(200, login.statusCode());
      assertNotEquals(cookie(anonymous), cookie(login));
      assertNotEquals(anonymous.body(), login.body());
      assertEquals(1, login.headers().allValues("Set-Cookie").size());
      assertEquals("no-store", login.headers().firstValue("Cache-Control").orElseThrow());
      var firstRead = account(test, cookies(login));
      assertEquals("account-42:light", firstRead.body());
      assertEquals("no-store", firstRead.headers().firstValue("Cache-Control").orElseThrow());
      var repeatedRead = account(test, cookies(login));
      assertEquals("account-42:light", repeatedRead.body());
      assertEquals("no-store", repeatedRead.headers().firstValue("Cache-Control").orElseThrow());
      assertEquals(401, account(test, cookies(anonymous)).statusCode());

      // The proof is bound to the renewed native identifier; the old proof cannot authorize a
      // write.
      assertEquals(
          403,
          post(test, "/account/theme", cookies(login), anonymous.body(), "theme=dark")
              .statusCode());
      assertEquals("account-42:light", account(test, cookies(login)).body());
      assertEquals(
          200,
          post(test, "/account/theme", cookies(login), login.body(), "theme=dark").statusCode());
      assertEquals("account-42:dark", account(test, cookies(login)).body());
    }
  }

  @Test
  void rejectsWrongCredentialsWithoutRenewalRotationOrIdentityAndCanRetry() throws Exception {
    try (var test = start(LoginLogoutExampleTest::verifyCredentials)) {
      var anonymous = bootstrap(test);
      var rejected =
          post(
              test,
              "/login",
              cookies(anonymous),
              anonymous.body(),
              "username=reader&password=wrong");
      assertEquals(401, rejected.statusCode());
      assertTrue(rejected.headers().allValues("Set-Cookie").isEmpty());
      assertEquals(401, account(test, cookies(anonymous)).statusCode());
      // The unchanged anonymous cookie/proof still permits a later valid credential submission.
      var accepted =
          post(
              test,
              "/login",
              cookies(anonymous),
              anonymous.body(),
              "username=reader&password=local-only");
      assertEquals(200, accepted.statusCode());
      assertEquals("account-42:light", account(test, cookies(accepted)).body());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "username=reader",
        "password=local-only",
        "username=reader&username=other&password=local-only",
        "username=reader&password=local-only&password=other"
      })
  void rejectsMissingOrDuplicateCredentialFieldsBeforeChangingAnonymousState(String form)
      throws Exception {
    try (var test = start(LoginLogoutExampleTest::verifyCredentials)) {
      var anonymous = bootstrap(test);
      var rejected = post(test, "/login", cookies(anonymous), anonymous.body(), form);
      assertEquals(400, rejected.statusCode());
      assertTrue(rejected.headers().allValues("Set-Cookie").isEmpty());
      assertEquals(401, account(test, cookies(anonymous)).statusCode());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"proof", "origin"})
  void rejectsUnverifiedLoginBeforeCallingTheApplicationVerifier(String invalid) throws Exception {
    var attempts = new AtomicInteger();

    try (var test =
        start(
            (username, password) -> {
              attempts.incrementAndGet();
              return verifyCredentials(username, password);
            })) {
      var anonymous = bootstrap(test);
      var rejected =
          test.send(
              request ->
                  request
                      .path("/login")
                      .method("POST")
                      .header("Cookie", cookies(anonymous))
                      .header(
                          "Origin",
                          "origin".equals(invalid)
                              ? "https://untrusted.example"
                              : test.baseUri().getScheme()
                                  + "://"
                                  + test.baseUri().getRawAuthority())
                      .header(
                          "X-CSRF-Token", "proof".equals(invalid) ? "invalid" : anonymous.body())
                      .header("Content-Type", "application/x-www-form-urlencoded")
                      .body(
                          "username=reader&password=local-only".getBytes(StandardCharsets.UTF_8)));
      assertEquals(403, rejected.statusCode());
      assertEquals(0, attempts.get());
      assertTrue(rejected.headers().allValues("Set-Cookie").isEmpty());
      assertEquals(401, account(test, cookies(anonymous)).statusCode());
    }
  }

  @Test
  void doesNotUseQueryParametersAsLoginCredentials() throws Exception {
    try (var test = start(LoginLogoutExampleTest::verifyCredentials)) {
      var anonymous = bootstrap(test);
      var rejected =
          post(
              test,
              "/login?username=reader&password=local-only",
              cookies(anonymous),
              anonymous.body(),
              "");
      assertEquals(400, rejected.statusCode());
      assertTrue(rejected.headers().allValues("Set-Cookie").isEmpty());
      assertEquals(401, account(test, cookies(anonymous)).statusCode());
    }
  }

  @Test
  void leavesTheAnonymousSessionAndProofUsableWhenCredentialVerificationThrows() throws Exception {
    var unavailable = new AtomicBoolean(true);

    try (var test =
        start(
            (username, password) -> {
              if (unavailable.getAndSet(false)) {
                throw new IllegalStateException("Credential service unavailable");
              }

              return verifyCredentials(username, password);
            })) {
      var anonymous = bootstrap(test);
      var failed =
          post(
              test,
              "/login",
              cookies(anonymous),
              anonymous.body(),
              "username=reader&password=local-only");
      assertEquals(500, failed.statusCode());
      assertTrue(failed.headers().allValues("Set-Cookie").isEmpty());
      assertEquals(401, account(test, cookies(anonymous)).statusCode());
      assertEquals(
          200,
          post(
                  test,
                  "/login",
                  cookies(anonymous),
                  anonymous.body(),
                  "username=reader&password=local-only")
              .statusCode());
    }
  }

  @Test
  void rejectsAccountSwitchingWithoutReplacingAuthenticatedState() throws Exception {
    try (var test = start((username, password) -> Optional.of(username))) {
      var anonymous = bootstrap(test);
      var login =
          post(
              test,
              "/login",
              cookies(anonymous),
              anonymous.body(),
              "username=first&password=fixture");
      assertEquals(200, login.statusCode());
      var switching =
          post(test, "/login", cookies(login), login.body(), "username=second&password=fixture");
      assertEquals(409, switching.statusCode());
      assertTrue(switching.headers().allValues("Set-Cookie").isEmpty());
      assertEquals("first:light", account(test, cookies(login)).body());
    }
  }

  @Test
  void logoutInvalidatesIdentityExpiresTheSessionCookieAndAllowsAFreshLoginInALaterRequest()
      throws Exception {
    try (var test = start(LoginLogoutExampleTest::verifyCredentials)) {
      var anonymous = bootstrap(test);
      var login =
          post(
              test,
              "/login",
              cookies(anonymous),
              anonymous.body(),
              "username=reader&password=local-only");
      assertEquals(200, login.statusCode());
      var logout = post(test, "/account/logout", cookies(login), login.body(), "");
      assertEquals(200, logout.statusCode());
      assertEquals("no-store", logout.headers().firstValue("Cache-Control").orElseThrow());
      assertEquals(1, logout.headers().allValues("Set-Cookie").size());
      var expired = logout.headers().firstValue("Set-Cookie").orElseThrow();
      assertTrue(expired.startsWith("__Host-SHOOSTR-AUTH="));
      assertTrue(expired.contains("Max-Age=0"));
      assertTrue(
          expired.contains("Secure") && expired.contains("HttpOnly") && expired.contains("Path=/"));
      assertFalse(expired.contains("Domain="));
      // Restoring the old cookie/proof pair cannot restore the invalidated server-side identity.
      assertEquals(401, account(test, cookies(login)).statusCode());
      assertEquals(
          401,
          post(test, "/account/theme", cookies(login), login.body(), "theme=dark").statusCode());
      var fresh = bootstrap(test);
      assertNotEquals(cookie(login), cookie(fresh));
      var relogin =
          post(test, "/login", cookies(fresh), fresh.body(), "username=reader&password=local-only");
      assertEquals(200, relogin.statusCode());
      assertEquals("account-42:light", account(test, cookies(relogin)).body());
      assertEquals(
          200,
          post(test, "/account/theme", cookies(relogin), relogin.body(), "theme=dark")
              .statusCode());
      assertEquals("account-42:dark", account(test, cookies(relogin)).body());
    }
  }

  private static Optional<String> verifyCredentials(String username, String password) {
    return "reader".equals(username) && "local-only".equals(password)
        ? Optional.of("account-42")
        : Optional.empty();
  }

  private static TestServer start(BiFunction<String, String, Optional<String>> authenticate)
      throws Exception {
    var app = LoginLogoutExample.application(authenticate);
    app.tls(
        tls -> {
          tls.setKeyStore(identity);
          tls.setKeyStorePassword("local-test-password");
        });
    return TestServer.start(
        app,
        client ->
            client
                .sslContext(clientTls)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE)));
  }

  private static HttpResponse<String> bootstrap(TestServer test) throws Exception {
    var response =
        test.send(request -> request.path("/login"), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    return response;
  }

  private static HttpResponse<String> account(TestServer test, String cookies) throws Exception {
    return test.send(
        request -> request.path("/account/me").header("Cookie", cookies),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> post(
      TestServer test, String path, String cookies, String proof, String form) throws Exception {
    return test.send(
        request ->
            request
                .path(path)
                .method("POST")
                .header("Cookie", cookies)
                .header(
                    "Origin", test.baseUri().getScheme() + "://" + test.baseUri().getRawAuthority())
                .header("X-CSRF-Token", proof)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(form.getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String cookies(HttpResponse<?> response) {
    return response.headers().allValues("Set-Cookie").stream()
        .map(value -> value.split(";", 2)[0])
        .collect(Collectors.joining("; "));
  }

  private static String cookie(HttpResponse<?> response) {
    return response.headers().allValues("Set-Cookie").stream()
        .filter(value -> value.startsWith("__Host-SHOOSTR-AUTH="))
        .map(value -> value.split(";", 2)[0])
        .findFirst()
        .orElseThrow();
  }
}
