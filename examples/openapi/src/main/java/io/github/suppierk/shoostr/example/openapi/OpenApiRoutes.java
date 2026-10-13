package io.github.suppierk.shoostr.example.openapi;

import io.github.suppierk.shoostr.Routes;
import io.github.suppierk.shoostr.TypedRoutes;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.pac4j.Pac4j;
import io.swagger.v3.core.util.Json31;

/** Keeps documented endpoints separate from schemas, codecs and startup. */
final class OpenApiRoutes {
  /** Prevents construction of the registration function. */
  private OpenApiRoutes() {}

  /**
   * Registers byte/text handlers, protected documents and their bundled UI.
   *
   * @param routes application route scope
   * @param configuration explicit installed documents and schemas
   * @param security installed Basic authentication fixture
   */
  static void register(Routes routes, OpenApiConfiguration configuration, Pac4j security) {
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
                              Json31.mapper().createObjectNode().put("name", "Alice"))),
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
        (request, response) -> response.text(request.principal().orElseThrow().getName()),
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
    routes.get("/internal-openapi.json", internal.handler(), e -> e.get(security).required());
  }

  /**
   * Registers the codec-enabled operation without deriving documentation from its model.
   *
   * @param routes codec-enabled registration scope
   * @param configuration explicit installed documents and schema
   * @param security installed Basic authentication fixture
   */
  static void registerTyped(
      TypedRoutes routes, OpenApiConfiguration configuration, Pac4j security) {
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
  }
}
