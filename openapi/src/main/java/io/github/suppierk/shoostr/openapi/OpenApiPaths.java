package io.github.suppierk.shoostr.openapi;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;

/** Startup-only translation of registered Shoostr templates into representable OpenAPI paths. */
final class OpenApiPaths {
  /** Prevents instantiation of stateless path translation. */
  private OpenApiPaths() {}

  /**
   * Normalizes segment constraints and validates explicitly declared path parameters.
   *
   * @param template final registered template
   * @param operation detached operation whose constraints are updated
   * @return representable OpenAPI path
   * @throws IllegalArgumentException if the template or parameters cannot be represented
   */
  static String normalize(String template, Operation operation) {
    var names = new LinkedHashMap<String, String>();
    var segments = template.split("/", -1);
    for (int i = 0; i < segments.length; i++) {
      var segment = segments[i];
      if (segment.startsWith("{")) {
        var content = segment.substring(1, segment.length() - 1);
        if (content.startsWith("*")) {
          throw new IllegalArgumentException(
              "OpenAPI cannot represent catch-all route: " + template);
        }

        int colon = content.indexOf(':');
        var name = colon < 0 ? content : content.substring(0, colon);
        names.put(name, colon < 0 ? "" : content.substring(colon + 1));
        segments[i] = "{" + name + "}";
      }
    }
    var missing = new HashSet<>(names.keySet());
    if (operation.getParameters() != null) {
      for (var parameter : operation.getParameters()) {
        if ("path".equals(parameter.getIn())) {
          var name = Objects.requireNonNull(parameter.getName());
          var constraint = names.get(name);
          if (constraint == null || !Boolean.TRUE.equals(parameter.getRequired())) {
            throw new IllegalArgumentException(
                "Invalid declared path parameter: " + name + " on " + template);
          }

          missing.remove(name);
          if (!constraint.isEmpty()) {
            var declared = parameter.getSchema();
            if (parameter.getContent() != null
                || declared == null
                || declared.get$ref() != null
                || !Set.of("string").equals(declared.getTypes())) {
              throw new IllegalArgumentException(
                  "Constrained path parameters require an explicit string schema: "
                      + name
                      + " on "
                      + template);
            }

            Schema<?> schema =
                new JsonSchema().types(Set.of("string")).pattern("^(?:" + constraint + ")$");
            if (declared.getPattern() == null) {
              declared.setPattern("^(?:" + constraint + ")$");
              schema = declared;
            } else {
              schema = new JsonSchema().addAllOfItem(declared).addAllOfItem(schema);
            }

            parameter.setSchema(schema);
          }
        }
      }
    }

    if (!missing.isEmpty()) {
      throw new IllegalArgumentException(
          "Missing declared path parameters: " + missing + " on " + template);
    }

    return String.join("/", segments);
  }
}
