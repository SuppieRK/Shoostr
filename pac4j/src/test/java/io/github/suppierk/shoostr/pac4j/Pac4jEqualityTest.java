package io.github.suppierk.shoostr.pac4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.junit.jupiter.api.Test;
import org.pac4j.core.authorization.authorizer.Authorizer;
import org.pac4j.core.client.DirectClient;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.credentials.UsernamePasswordCredentials;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.profile.UserProfile;
import org.pac4j.http.client.direct.DirectBasicAuthClient;

class Pac4jEqualityTest {
  @Test
  void policyComparesClientChallengeAndAuthorizer() {
    var first = authorizer("alice");
    var second = authorizer("bob");
    EqualsVerifier.forClass(Pac4j.class)
        .withPrefabValues(DirectClient.class, client("alice"), client("bob"))
        .withPrefabValues(Authorizer.class, first, second)
        .withNonnullFields("client", "challenge", "authorizer")
        .verify();
  }

  @Test
  void equalPoliciesInstallIndependentlyButCannotSelectTwoAuthenticationOwners() throws Exception {
    var client = client("alice");
    var authorizer = authorizer("alice");
    var first = new Pac4j(client, "Basic", authorizer);
    var second = new Pac4j(client, "Basic", authorizer);
    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
    assertNotEquals(first, new Pac4j(client("bob"), "Basic", authorizer));
    assertNotEquals(first, new Pac4j(client, "Basic realm=\"other\"", authorizer));
    assertNotEquals(first, new Pac4j(client, "Basic", authorizer("bob")));
    var values = new HashSet<Pac4j>();
    values.add(first);
    values.add(second);
    assertEquals(1, values.size());

    try (var app = new Shoostr().authentication(first, second)) {
      var routes = app.routes();
      assertThrows(
          IllegalArgumentException.class,
          () ->
              routes.get(
                  "/conflict",
                  (request, response) -> {},
                  e -> {
                    e.with(first);
                    e.with(second);
                  }));
      routes
          .get(
              "/first",
              (request, response) -> response.text(request.principal().orElseThrow().getName()),
              e -> e.with(first))
          .get(
              "/second",
              (request, response) -> response.text(request.principal().orElseThrow().getName()),
              e -> e.with(second));

      try (var server = TestServer.start(app)) {
        for (var path : new String[] {"/first", "/second"}) {
          var response =
              server.send(
                  request ->
                      request.path(path).header("Authorization", "Basic YWxpY2U6Y29ycmVjdA=="),
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(200, response.statusCode());
          assertEquals("alice", response.body());
        }
      }
    }
  }

  @Test
  void contextRetainsIdentityWhileItsLiveExchangeChanges() throws Exception {
    var contexts = new ArrayList<Pac4jContext>();
    var exchanges = new ArrayList<Map.Entry<Request, Response>>();
    var hashes = new ArrayList<Integer>();
    var retained = new HashSet<Pac4jContext>();

    try (var app = new Shoostr()) {
      app.routes()
          .post(
              "/context",
              (request, response) -> {
                var context = new Pac4jContext(request, response);
                assertNotEquals(context, new Pac4jContext(request, response));
                contexts.add(context);
                exchanges.add(Map.entry(request, response));
                hashes.add(context.hashCode());
                retained.add(context);
                context.setRequestAttribute("processed", true);
                context.setResponseHeader("X-Checked", "yes");
                response.text(request.bodyText());
              });

      try (var server = TestServer.start(app)) {
        for (int index = 0; index < 2; index++) {
          var response =
              server.send(
                  request ->
                      request
                          .path("/context")
                          .method("POST")
                          .body("same".getBytes(StandardCharsets.UTF_8)),
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(200, response.statusCode());
          assertEquals("same", response.body());
          assertEquals("yes", response.headers().firstValue("X-Checked").orElseThrow());
        }
        EqualsVerifier.forClass(Pac4jContext.class)
            .withPrefabValues(Request.class, exchanges.get(0).getKey(), exchanges.get(1).getKey())
            .withPrefabValues(
                Response.class, exchanges.get(0).getValue(), exchanges.get(1).getValue())
            .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
            .verify();
      }
    }

    assertNotEquals(contexts.get(0), contexts.get(1));
    for (int index = 0; index < contexts.size(); index++) {
      assertEquals(hashes.get(index).intValue(), contexts.get(index).hashCode());
      assertTrue(retained.remove(contexts.get(index)));
    }
    assertTrue(retained.isEmpty());
  }

  @Test
  void statelessSessionStoreUsesEnumEquality() {
    EqualsVerifier.forClass(StatelessSessionStore.class).verify();
  }

  private static DirectBasicAuthClient client(String identity) {
    var client =
        new DirectBasicAuthClient(
            (context, supplied) -> {
              if (!(supplied instanceof UsernamePasswordCredentials credentials)
                  || !identity.equals(credentials.getUsername())
                  || !"correct".equals(credentials.getPassword())) {
                return Optional.empty();
              }

              var profile = new CommonProfile();
              profile.setId(identity);
              credentials.setUserProfile(profile);
              return Optional.of(credentials);
            });
    client.setName(identity);
    return client;
  }

  private static Authorizer authorizer(String identity) {
    return new MatchingAuthorizer(identity);
  }

  private record MatchingAuthorizer(String identity) implements Authorizer {
    private MatchingAuthorizer {
      Objects.requireNonNull(identity);
    }

    @Override
    public boolean isAuthorized(
        WebContext context, SessionStore sessions, List<UserProfile> profiles) {
      return profiles.stream().anyMatch(profile -> identity.equals(profile.getId()));
    }
  }
}
