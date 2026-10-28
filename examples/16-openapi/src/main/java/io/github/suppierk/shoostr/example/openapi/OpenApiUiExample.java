package io.github.suppierk.shoostr.example.openapi;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.openapi.OpenApi;
import io.github.suppierk.shoostr.pac4j.Pac4j;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Optional;
import org.pac4j.core.credentials.UsernamePasswordCredentials;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.http.client.direct.DirectBasicAuthClient;

/** Protects the local documentation page, its assets and its specification together. */
public final class OpenApiUiExample {
  private static final String BASIC_SECURITY_SCHEME = "basic";

  /** Prevents construction of the factory. */
  private OpenApiUiExample() {}

  /**
   * Creates an unstarted application with development-only documentation credentials.
   *
   * @return unstarted application
   */
  public static Shoostr application() {
    // These credentials exist only for the local example, not for production authentication.
    var client =
        new DirectBasicAuthClient(
            (ignored, supplied) -> {
              if (!(supplied instanceof UsernamePasswordCredentials credentials)
                  || !"reader".equals(credentials.getUsername())
                  || !"local-only".equals(credentials.getPassword())) {
                return Optional.empty();
              }

              var profile = new CommonProfile();
              profile.setId("reader");
              credentials.setUserProfile(profile);
              return Optional.of(credentials);
            });
    var authentication = new Pac4j(client, "Basic realm=\"docs\", charset=\"UTF-8\"");
    // Declaring a security scheme describes it; route authentication below enforces access.
    var docs =
        new OpenApi("Account API", "1.0")
            .securityScheme(
                BASIC_SECURITY_SCHEME,
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme(BASIC_SECURITY_SCHEME));
    var ui = docs.uiHandler("/docs/", "/openapi.json");
    return new Shoostr()
        .authentication(authentication)
        .extensions(docs)
        // Protect the specification and every local UI asset, not just the HTML entry point.
        .routes(
            routes ->
                routes
                    .get(
                        "/openapi.json",
                        docs.handler(),
                        extensions -> extensions.with(authentication))
                    .path(
                        "/docs",
                        group -> group.get("/", ui).get("/{*asset}", ui),
                        extensions -> {
                          extensions.with(authentication);
                          // UI resource endpoints are not operations in the generated API document.
                          extensions.with(docs).hidden();
                        })
                    .get(
                        "/me",
                        (request, response) ->
                            response.text(request.principal().orElseThrow().getName()),
                        extensions -> {
                          extensions.with(authentication);
                          extensions
                              .with(docs)
                              .summary("Read the authenticated identity")
                              .security(BASIC_SECURITY_SCHEME)
                              .response(200, "Identity", MediaType.TEXT_PLAIN);
                        }));
  }
}
