package io.github.suppierk.shoostr.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Set;

/** Structural checks on generated documents, not handler output, application models or prose. */
final class OpenApiValidation {
  private static final Set<String> DATA = Set.of("example", "examples", "default", "enum", "const");
  private static final Set<String> DICTIONARIES =
      Set.of(
          "schemas",
          "properties",
          "patternProperties",
          "dependentSchemas",
          "$defs",
          "definitions",
          "paths",
          "responses",
          "headers",
          "content",
          "securitySchemes");

  /** Prevents instantiation of stateless document checks. */
  private OpenApiValidation() {}

  /**
   * Checks required scheme properties before publishing explicit component metadata.
   *
   * @param scheme detached explicit security scheme
   * @throws IllegalArgumentException if required fields are absent
   */
  static void scheme(SecurityScheme scheme) {
    if (scheme.get$ref() != null) {
      return;
    }

    if (scheme.getType() == null) {
      throw new IllegalArgumentException("OpenAPI security schemes require a type");
    }

    boolean valid =
        switch (scheme.getType()) {
          case HTTP -> scheme.getScheme() != null && !scheme.getScheme().isBlank();
          case APIKEY ->
              scheme.getName() != null && !scheme.getName().isBlank() && scheme.getIn() != null;
          case OAUTH2 -> scheme.getFlows() != null;
          case OPENIDCONNECT ->
              scheme.getOpenIdConnectUrl() != null && !scheme.getOpenIdConnectUrl().isBlank();
          case MUTUALTLS -> true;
        };
    if (!valid) {
      throw new IllegalArgumentException("Missing required fields in OpenAPI security scheme");
    }
  }

  /**
   * Rejects security requirements referencing undeclared component schemes.
   *
   * @param operation detached operation
   * @param components document's explicitly declared components
   * @throws IllegalArgumentException if a scheme is absent
   */
  static void security(Operation operation, Components components) {
    if (operation.getSecurity() == null) {
      return;
    }

    for (var requirement : operation.getSecurity()) {
      for (var name : requirement.keySet()) {
        if (components.getSecuritySchemes() == null
            || !components.getSecuritySchemes().containsKey(name)) {
          throw new IllegalArgumentException("Unknown OpenAPI security scheme: " + name);
        }
      }
    }
  }

  /**
   * Validates known local references in model positions, without fetching external references or
   * interpreting example/default data as models. Recursive references remain valid.
   *
   * @param node generated model subtree
   * @param root generated document root
   * @param dictionary whether field names are arbitrary map keys rather than model keywords
   * @throws IllegalArgumentException if a local reference has no target
   */
  static void references(JsonNode node, JsonNode root, boolean dictionary) {
    if (node.isArray()) {
      for (var item : node) {
        references(item, root, false);
      }
    } else if (node.isObject()) {
      for (var property : node.properties()) {
        var name = property.getKey();
        var value = property.getValue();
        if (!dictionary && "$ref".equals(name) && value.isTextual()) {
          var reference = value.asText();
          if (reference.startsWith("#/components/")
              && root.at(reference.substring(1)).isMissingNode()) {
            throw new IllegalArgumentException("Unknown local OpenAPI reference: " + reference);
          }
        } else if (dictionary || (!DATA.contains(name) && !name.startsWith("x-"))) {
          references(value, root, !dictionary && DICTIONARIES.contains(name));
        }
      }
    }
  }
}
