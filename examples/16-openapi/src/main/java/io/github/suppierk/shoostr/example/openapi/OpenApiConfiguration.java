package io.github.suppierk.shoostr.example.openapi;

import io.github.suppierk.shoostr.openapi.OpenApi;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import java.util.Set;

/** Owns explicit document metadata and schemas, not request handling. */
final class OpenApiConfiguration {
  static final String BASIC_SCHEME = "basic";
  private final OpenApi docs;
  private final OpenApi internal;
  private final JsonSchema user;

  /**
   * Defines the two independent documents and named user schema eagerly.
   *
   * <p>The retained documents contain metadata and acquire no close-required resources.
   */
  OpenApiConfiguration() {
    // Each OpenApi instance from Shoostr's openapi module owns an independent document.
    docs =
        new OpenApi("Users API", "1.0")
            .description(
                "Explicit schemas, optional typed handlers, and protected local documentation")
            .tag("users", "User operations");
    internal = new OpenApi("Operations API", "1.0");
    // Swagger's model types describe auth and JSON shapes; they do not enforce either at runtime.
    var basic = new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme(BASIC_SCHEME);
    docs.securityScheme(BASIC_SCHEME, basic);
    internal.securityScheme(BASIC_SCHEME, basic);
    // Declare the User schema explicitly; neither the codec nor the Java record is scanned.
    var userSchema = new JsonSchema();
    userSchema.setTypes(Set.of("object"));
    userSchema.addProperty("name", new JsonSchema().types(Set.of("string")).minLength(1));
    userSchema.setRequired(List.of("name"));
    // Register the reusable schema and retain its reference for endpoint request/response
    // descriptions.
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
