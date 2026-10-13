package io.github.suppierk.shoostr.example.openapi;

import io.github.suppierk.shoostr.openapi.OpenApi;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import java.util.Set;

/** Owns explicit document metadata and schemas, not request handling. */
final class OpenApiConfiguration {
  private final OpenApi docs;
  private final OpenApi internal;
  private final JsonSchema user;

  /** Defines the two independent documents and named user schema eagerly. */
  OpenApiConfiguration() {
    docs =
        new OpenApi("Users API", "1.0")
            .description(
                "Explicit schemas, optional typed handlers, and protected local documentation")
            .tag("users", "User operations");
    internal = new OpenApi("Operations API", "1.0");
    var basic = new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic");
    docs.securityScheme("basic", basic);
    internal.securityScheme("basic", basic);
    var userSchema = new JsonSchema();
    userSchema.setTypes(Set.of("object"));
    userSchema.addProperty("name", new JsonSchema().types(Set.of("string")).minLength(1));
    userSchema.setRequired(List.of("name"));
    user = docs.schema("User", userSchema);
  }

  /**
   * Returns external documentation to install as an extension.
   *
   * @return external document
   */
  OpenApi docs() {
    return docs;
  }

  /**
   * Returns the separate internal document.
   *
   * @return internal document
   */
  OpenApi internal() {
    return internal;
  }

  /**
   * Returns the explicit reusable user reference.
   *
   * @return named user schema reference
   */
  JsonSchema user() {
    return user;
  }
}
