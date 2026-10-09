package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.suppierk.shoostr.pac4j.Pac4j;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.pac4j.core.credentials.UsernamePasswordCredentials;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.http.client.direct.DirectBasicAuthClient;

class EndpointAuthenticationTest {
  @ParameterizedTest
  @CsvSource({"wrong,401", "unauthorized,403"})
  void rejectsSelectedRequestForInvalidCredentialsOrAuthorization(String rejection, int status)
      throws Exception {
    var invoked = new AtomicBoolean();
    var providerVirtual = new AtomicBoolean(true);
    var client =
        new DirectBasicAuthClient(
            (_, supplied) -> {
              providerVirtual.set(Thread.currentThread().isVirtual());
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
    var transport = new QueuedThreadPool(16, 8);
    transport.setReservedThreads(0);
    var execution =
        new ExecutionSettings(transport, true, Executors.newFixedThreadPool(1), Set.of("/me"));

    try (var app = new Shoostr(Options.defaults().withPort(0), execution)) {
      var auth = new Pac4j(client, "Basic", (_, _, _) -> false);
      app.authentication(auth)
          .routes()
          .get(
              "/me",
              (_, response) -> {
                invoked.set(true);
                response.text("wrong");
              },
              extensions -> extensions.get(auth).required());
      var credentials =
          "wrong".equals(rejection) ? "Basic YWxpY2U6d3Jvbmc=" : "Basic YWxpY2U6Y29ycmVjdA==";

      try (var test = TestServer.start(app)) {
        assertEquals(
            status,
            test.send(request -> request.path("/me").header("Authorization", credentials))
                .statusCode());
        assertFalse(invoked.get());
        assertFalse(providerVirtual.get());
      }
    }
  }

  @Test
  void authenticatesWithPac4jOnSelectedPlatformThread() throws Exception {
    var providerVirtual = new AtomicBoolean(true);
    var client =
        new DirectBasicAuthClient(
            (_, supplied) -> {
              providerVirtual.set(Thread.currentThread().isVirtual());
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
    var transport = new QueuedThreadPool(16, 8);
    transport.setReservedThreads(0);
    var execution =
        new ExecutionSettings(transport, true, Executors.newFixedThreadPool(1), Set.of("/me"));

    try (var app = new Shoostr(Options.defaults().withPort(0), execution)) {
      var auth = new Pac4j(client, "Basic");
      app.authentication(auth)
          .routes()
          .get(
              "/me",
              (request, response) -> response.text(request.principal().orElseThrow().getName()),
              extensions -> extensions.get(auth).required());

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request.path("/me").header("Authorization", "Basic YWxpY2U6Y29ycmVjdA=="),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("alice", result.body());
        assertFalse(providerVirtual.get());
      }
    }
  }
}
