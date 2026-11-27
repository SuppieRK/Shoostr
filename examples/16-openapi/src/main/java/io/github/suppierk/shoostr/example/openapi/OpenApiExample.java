package io.github.suppierk.shoostr.example.openapi;

import static io.github.suppierk.shoostr.example.openapi.OpenApiConfiguration.BASIC_SCHEME;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.pac4j.Pac4j;
import io.swagger.v3.core.util.Json31;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Composes the explicit OpenAPI, codec and authentication demonstration. */
public final class OpenApiExample {
  private static final Logger LOGGER = LoggerFactory.getLogger(OpenApiExample.class);

  /** Prevents instantiation of the example factory. */
  private OpenApiExample() {}

  /**
   * Starts the example; core owns its JVM shutdown lifecycle.
   *
   * @param args unused command-line arguments
   * @throws Exception if startup fails
   */
  public static void main(String[] args) throws Exception {
    var app = application();

    app.start();

    LOGGER.info("Open http://127.0.0.1:{}/docs/", app.port());

    LOGGER.info("Demo username: demo");

    LOGGER.info("Demo password: local-only");
  }

  /**
   * Builds an unstarted app with independently configured dependencies.
   *
   * <p>The app owns its installed documents.
   *
   * @return app owned by its caller
   */
  public static Shoostr application() {
    // Example-local configuration creates two OpenApi extensions from Shoostr's openapi module.
    var configuration = new OpenApiConfiguration();
    // This example-local factory creates the Pac4j extension from Shoostr's pac4j module.
    var security = OpenApiAuthentication.create();
    var app = documentedApplication(configuration, security);
    // UserCodec is example-local and implements core's Codec using Swagger's Jackson mapper.
    // Only this typed route registration uses the codec; earlier byte/text routes remain untyped.
    app.codec(new UserCodec())
        .routes(
            routes -> {
              var docs = configuration.docs();
              var user = configuration.user();
              routes.post(
                  "/users",
                  (request, response) -> {
                    var value = request.body(User.class);
                    response.status(HttpStatusCodes.CREATED.value());

                    response.body(MediaType.APPLICATION_JSON, value);
                  },
                  lifecycle -> {
                    lifecycle.withExtension(security);
                    lifecycle
                        .withExtension(docs)
                        .summary("Create a user")
                        .operationId("createUser")
                        .tag("users")
                        .security(BASIC_SCHEME)
                        .requestBody("User to create", MediaType.APPLICATION_JSON, user, true)
                        .response(201, "Created", MediaType.APPLICATION_JSON, user);
                  });
            });

    return app;
  }

  /**
   * Creates the application with its public and private document selections.
   *
   * @param configuration document and schema configuration
   * @param security HTTP authentication or authorization policy
   * @return unstarted application owned by its caller
   */
  private static Shoostr documentedApplication(OpenApiConfiguration configuration, Pac4j security) {
    return new Shoostr()
        .routes(
            routes -> {
              var docs = configuration.docs();
              var internal = configuration.internal();
              var user = configuration.user();
              // Shoostr's OpenAPI extension supplies the UI handler; register its URLs
              // explicitly.
              var ui = docs.uiHandler("/docs/", "/openapi.json");
              // e.withExtension(docs) configures this endpoint's documentation on that
              // installed
              // instance.
              routes
                  .get(
                      "/health",
                      (request, response) -> response.text("healthy"),
                      lifecycle ->
                          lifecycle
                              .withExtension(docs)
                              .summary("Health")
                              .operationId("health")
                              .response(200, "Healthy", MediaType.TEXT_PLAIN))
                  // OpenAPI settings on a route group become defaults for its endpoints.
                  .path(
                      "/users",
                      group -> {
                        group
                            .get(
                                "/",
                                (request, response) ->
                                    response.text("Use a numeric user ID"),
                                lifecycle ->
                                    lifecycle
                                        .withExtension(docs)
                                        .summary("User help")
                                        .response(200, "Plain text help", MediaType.TEXT_PLAIN))
                            .get(
                                "/{id:[0-9]+}",
                                (request, response) ->
                                    response.body(
                                        MediaType.APPLICATION_JSON,
                                        Json31.mapper()
                                            .writeValueAsBytes(
                                                Json31.mapper()
                                                    .createObjectNode()
                                                    .put("name", "Alice"))),
                                lifecycle ->
                                    lifecycle
                                        .withExtension(docs)
                                        .summary("Find a user")
                                        .operationId("findUser")
                                        .pathParameter("id", "Numeric user identifier")
                                        .response(
                                            200, "User found", MediaType.APPLICATION_JSON, user));
                      },
                      lifecycle ->
                          lifecycle
                              .withExtension(docs)
                              .tag("users")
                              .description("Grouped defaults copied independently")
                              .response(404, "Not found"))
                  // Authentication enforces access; OpenAPI's security entry only documents it.
                  .get(
                      "/me",
                      (request, response) ->
                          response.text(request.principal().orElseThrow().getName()),
                      lifecycle -> {
                        lifecycle.withExtension(security);
                        lifecycle
                            .withExtension(docs)
                            .summary("Authenticated identity")
                            .security(BASIC_SCHEME)
                            .response(200, "Identity", MediaType.TEXT_PLAIN);
                      })
                  // Include this operation only in the internal OpenAPI document.
                  // Authentication is configured separately.
                  .get(
                      "/admin/ping",
                      (request, response) -> response.text("pong"),
                      lifecycle -> {
                        lifecycle.withExtension(security);
                        lifecycle
                            .withExtension(internal)
                            .summary("Internal ping")
                            .security(BASIC_SCHEME)
                            .response(200, "Pong", MediaType.TEXT_PLAIN);
                      })
                  // when controls runtime availability; selected OpenAPI documents still include
                  // it.
                  .when(
                      () -> true,
                      conditional ->
                          conditional.get(
                              "/dev-only",
                              (request, response) ->
                                  response.text("Conditional development route"),
                              lifecycle ->
                                  lifecycle
                                      .withExtension(internal)
                                      .summary("Development endpoint")
                                      .response(200, "Available", MediaType.TEXT_PLAIN)))
                  // Serve UI assets through two routes; hidden keeps them out of the document.
                  .path(
                      "/docs",
                      group -> {
                        group.get("/", ui).get("/{*resource}", ui);
                      },
                      lifecycle -> {
                        lifecycle.withExtension(security);
                        lifecycle.withExtension(docs).hidden();
                      })
                  // handler() serves the specification generated once during application startup.
                  .get(
                      "/openapi.json",
                      docs.handler(),
                      lifecycle -> {
                        lifecycle.withExtension(security);
                        lifecycle
                            .withExtension(docs)
                            .summary("This generated specification")
                            .security(BASIC_SCHEME)
                            .response(200, "OpenAPI document", MediaType.APPLICATION_JSON);
                      })
                  .get(
                      "/internal-openapi.json",
                      internal.handler(),
                      lifecycle -> lifecycle.withExtension(security));
            });
  }
}
