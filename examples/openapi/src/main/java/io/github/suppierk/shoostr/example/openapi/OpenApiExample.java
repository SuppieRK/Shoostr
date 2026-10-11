package io.github.suppierk.shoostr.example.openapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.suppierk.shoostr.Codec;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.openapi.OpenApi;
import io.github.suppierk.shoostr.pac4j.Pac4j;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.pac4j.core.credentials.UsernamePasswordCredentials;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.http.client.direct.DirectBasicAuthClient;

/** Local development example; demonstration credentials are not a production identity store. */
public final class OpenApiExample {
  /** Prevents instantiation of the example factory. */
  private OpenApiExample() {}

  /**
   * Starts the loopback example; core keeps the server alive and closes it during JVM shutdown.
   *
   * @throws Exception if startup fails
   */
  static void main() throws Exception {
    var app = application(Options.defaults()).start();
    System.out.println("Open http://127.0.0.1:" + app.port() + "/docs/");
    System.out.println("Demo username: demo");
    System.out.println("Demo password: local-only");
  }

  /**
   * Creates an unstarted application; callers control its port and lifetime. Models, codec and
   * authentication are explicit application dependencies, not inferred by documentation.
   *
   * @param options server settings, normally loopback-only
   * @return configured unstarted app
   */
  public static Shoostr application(Options options) {
    var docs =
        new OpenApi("Users API", "1.0")
            .description(
                "Explicit schemas, optional typed handlers, and protected local documentation")
            .tag("users", "User operations");
    var internal = new OpenApi("Operations API", "1.0");
    var basic = new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic");
    docs.securityScheme("basic", basic);
    internal.securityScheme("basic", basic);
    var userSchema = new JsonSchema();
    userSchema.setTypes(Set.of("object"));
    userSchema.addProperty("name", new JsonSchema().types(Set.of("string")).minLength(1));
    userSchema.setRequired(List.of("name"));
    var user = docs.schema("User", userSchema);
    var security = authentication();
    var ui = docs.uiHandler("/docs/", "/openapi.json");
    var app = new Shoostr(options);
    app.authentication(security)
        .extensions(docs, internal)
        .routes(
            routes -> {
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
              routes.get(
                  "/internal-openapi.json", internal.handler(), e -> e.get(security).required());
            });
    app.codec(new UserCodec())
        .routes(
            routes ->
                routes.post(
                    "/users",
                    (request, response) -> {
                      var value = request.body(User.class);
                      response.status(201);
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
                    }));
    return app;
  }

  /**
   * Configures real Pac4j HTTP Basic for this local fixture only. Use TLS and a proper identity
   * provider for production; Basic credentials are not encrypted by this example.
   *
   * @return explicit managed authentication extension
   */
  private static Pac4j authentication() {
    return new Pac4j(
        new DirectBasicAuthClient(
            (_, supplied) -> {
              if (!(supplied instanceof UsernamePasswordCredentials credentials)
                  || !"demo".equals(credentials.getUsername())
                  || !"local-only".equals(credentials.getPassword())) {
                return Optional.empty();
              }

              var profile = new CommonProfile();
              profile.setId("demo");
              credentials.setUserProfile(profile);
              return Optional.of(credentials);
            }),
        "Basic realm=\"shoostr-example\", charset=\"UTF-8\"");
  }

  /**
   * Explicit example payload, not an inferred schema or annotation-scanned model.
   *
   * @param name nonblank user name
   */
  public record User(String name) {
    /**
     * Establishes the payload invariant before the handler receives it.
     *
     * @param name nonblank user name
     * @throws NullPointerException if the name is missing
     * @throws IllegalArgumentException if the name is blank
     */
    public User {
      Objects.requireNonNull(name, "User name is required");
      if (name.isBlank()) {
        throw new IllegalArgumentException("User name is required");
      }
    }
  }

  /** Explicit tree-based JSON conversion with no application-model reflection or annotations. */
  private static final class UserCodec implements Codec {
    /** Creates the stateless example codec. */
    private UserCodec() {}

    /**
     * Decodes the single explicit example type and classifies only malformed input as 400.
     *
     * @param source JSON bytes
     * @param type explicit payload class
     * @param <T> result type
     * @return validated payload
     * @throws IOException if the JSON reader fails unexpectedly
     * @throws BadRequestException if input JSON or the required name is malformed
     * @throws IllegalArgumentException if this example does not support the requested type
     */
    @Override
    public <T> T read(byte[] source, Class<T> type) throws IOException {
      if (type != User.class) {
        throw new IllegalArgumentException("Example codec supports only User");
      }

      try {
        var tree = Json31.mapper().readTree(source);
        if (tree == null || !tree.path("name").isTextual()) {
          throw new BadRequestException("User name must be a string");
        }

        return type.cast(new User(tree.path("name").asText()));
      } catch (JsonProcessingException | IllegalArgumentException failure) {
        throw new BadRequestException("Malformed user", failure);
      }
    }

    /**
     * Serializes the explicit payload through a JSON tree; no codec/schema inference occurs.
     *
     * @param value user payload
     * @return JSON bytes
     * @throws IOException if serialization fails
     * @throws IllegalArgumentException if the value is unsupported
     */
    @Override
    public byte[] write(Object value) throws IOException {
      if (!(value instanceof User(var name))) {
        throw new IllegalArgumentException("Example codec supports only User");
      }

      return Json31.mapper()
          .writeValueAsBytes(Json31.mapper().createObjectNode().put("name", name));
    }
  }
}
