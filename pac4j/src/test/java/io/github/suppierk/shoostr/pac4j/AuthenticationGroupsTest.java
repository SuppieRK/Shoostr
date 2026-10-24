package io.github.suppierk.shoostr.pac4j;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.pac4j.core.credentials.TokenCredentials;
import org.pac4j.core.credentials.UsernamePasswordCredentials;
import org.pac4j.core.credentials.authenticator.Authenticator;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.http.client.direct.DirectBasicAuthClient;
import org.pac4j.http.client.direct.DirectBearerAuthClient;

class AuthenticationGroupsTest {
  @ParameterizedTest
  @CsvSource({"/rest, Basic YWxpY2U6Y29ycmVjdA==, alice", "/mcp, Bearer correct, bob"})
  void establishesTheIdentityFromTheSelectedAuthenticationScheme(
      String path, String credentials, String identity) throws Exception {
    try (var app = new Shoostr()) {
      registerAuthenticationGroups(app);

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request -> request.path(path).header("Authorization", credentials),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, result.statusCode());
        assertEquals(identity, result.body());
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"/rest, Bearer correct, Basic", "/mcp, Basic YWxpY2U6Y29ycmVjdA==, Bearer"})
  void rejectsTheOtherGroupsCredentialSchemeWithTheSelectedChallenge(
      String path, String credentials, String challenge) throws Exception {
    try (var app = new Shoostr()) {
      registerAuthenticationGroups(app);

      try (var test = TestServer.start(app)) {
        var result = test.send(request -> request.path(path).header("Authorization", credentials));

        assertEquals(401, result.statusCode());
        assertEquals(challenge, result.headers().firstValue("WWW-Authenticate").orElseThrow());
      }
    }
  }

  private static void registerAuthenticationGroups(Shoostr app) {
    var basic =
        new Pac4j(
            new DirectBasicAuthClient(
                (ignored, supplied) -> {
                  if (!(supplied instanceof UsernamePasswordCredentials credentials)
                      || !"alice".equals(credentials.getUsername())
                      || !"correct".equals(credentials.getPassword())) {
                    return Optional.empty();
                  }

                  var profile = new CommonProfile();
                  profile.setId("alice");
                  supplied.setUserProfile(profile);
                  return Optional.of(supplied);
                }),
            "Basic");
    Authenticator bearerAuthenticator =
        (ignored2, supplied) -> {
          if (!(supplied instanceof TokenCredentials credentials)
              || !"correct".equals(credentials.getToken())) {
            return Optional.empty();
          }

          var profile = new CommonProfile();
          profile.setId("bob");
          supplied.setUserProfile(profile);
          return Optional.of(supplied);
        };
    var bearer = new Pac4j(new DirectBearerAuthClient(bearerAuthenticator), "Bearer");

    app.authentication(basic, bearer)
        .routes(
            routes -> {
              routes.path(
                  "/rest",
                  child ->
                      child.get(
                          (request, response) ->
                              response.text(request.principal().orElseThrow().getName())),
                  extensions -> extensions.with(basic));
              routes.path(
                  "/mcp",
                  child ->
                      child.get(
                          (request, response) ->
                              response.text(request.principal().orElseThrow().getName())),
                  extensions -> extensions.with(bearer));
            });
  }
}
