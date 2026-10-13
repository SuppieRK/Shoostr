package io.github.suppierk.shoostr.example.openapi;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.MediaType;
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
   * @throws Exception if startup fails
   */
  static void main() throws Exception {
    var app = application();
    app.start();
    LOGGER.info("Open http://127.0.0.1:{}/docs/", app.port());
    LOGGER.info("Demo username: demo");
    LOGGER.info("Demo password: local-only");
  }

  /**
   * Builds an unstarted app with independently configured dependencies.
   *
   * @return app owned by its caller
   */
  public static Shoostr application() {
    var configuration = new OpenApiConfiguration();
    var security = OpenApiAuthentication.create();
    var app =
        new Shoostr()
            .authentication(security)
            .extensions(configuration.docs(), configuration.internal())
            .routes(
                routes -> {
                  var docs = configuration.docs();
                  var internal = configuration.internal();
                  var user = configuration.user();
                  var ui = docs.uiHandler("/docs/", "/openapi.json");
                  routes.get(
                      "/health",
                      (_, response) -> response.text("healthy"),
                      e ->
                          e.get(docs)
                              .summary("Health")
                              .operationId("health")
                              .response(200, "Healthy", MediaType.TEXT_PLAIN));
                  routes.path(
                      "/users",
                      group -> {
                        group.get(
                            "/",
                            (_, response) -> response.text("Use a numeric user ID"),
                            e ->
                                e.get(docs)
                                    .summary("User help")
                                    .response(200, "Plain text help", MediaType.TEXT_PLAIN));
                        group.get(
                            "/{id:[0-9]+}",
                            (_, response) ->
                                response.body(
                                    MediaType.APPLICATION_JSON,
                                    Json31.mapper()
                                        .writeValueAsBytes(
                                            Json31.mapper()
                                                .createObjectNode()
                                                .put("name", "Alice"))),
                            e ->
                                e.get(docs)
                                    .summary("Find a user")
                                    .operationId("findUser")
                                    .pathParameter("id", "Numeric user identifier")
                                    .response(200, "User found", MediaType.APPLICATION_JSON, user));
                      },
                      e ->
                          e.get(docs)
                              .tag("users")
                              .description("Grouped defaults copied independently")
                              .response(404, "Not found"));
                  routes.get(
                      "/me",
                      (request, response) ->
                          response.text(request.principal().orElseThrow().getName()),
                      e -> {
                        e.get(security).required();
                        e.get(docs)
                            .summary("Authenticated identity")
                            .security("basic")
                            .response(200, "Identity", MediaType.TEXT_PLAIN);
                      });
                  routes.get(
                      "/admin/ping",
                      (_, response) -> response.text("pong"),
                      e -> {
                        e.get(security).required();
                        e.get(internal)
                            .summary("Internal ping")
                            .security("basic")
                            .response(200, "Pong", MediaType.TEXT_PLAIN);
                      });
                  routes.when(
                      () -> true,
                      conditional ->
                          conditional.get(
                              "/dev-only",
                              (_, response) -> response.text("Conditional development route"),
                              e ->
                                  e.get(internal)
                                      .summary("Development endpoint")
                                      .response(200, "Available", MediaType.TEXT_PLAIN)));
                  routes.path(
                      "/docs",
                      group -> {
                        group.get("/", ui);
                        group.get("/{*resource}", ui);
                      },
                      e -> {
                        e.get(security).required();
                        e.get(docs).hidden();
                      });
                  routes.get(
                      "/openapi.json",
                      docs.handler(),
                      e -> {
                        e.get(security).required();
                        e.get(docs)
                            .summary("This generated specification")
                            .security("basic")
                            .response(200, "OpenAPI document", MediaType.APPLICATION_JSON);
                      });
                  routes.get(
                      "/internal-openapi.json",
                      internal.handler(),
                      e -> e.get(security).required());
                });
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
                  e -> {
                    e.get(security).required();
                    e.get(docs)
                        .summary("Create a user")
                        .operationId("createUser")
                        .tag("users")
                        .security("basic")
                        .requestBody("User to create", MediaType.APPLICATION_JSON, user, true)
                        .response(201, "Created", MediaType.APPLICATION_JSON, user);
                  });
            });
    return app;
  }
}
