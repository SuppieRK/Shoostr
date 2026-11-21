package io.github.suppierk.shoostr.openapi;

import io.github.suppierk.shoostr.http.HttpCharacters;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Startup-only translation of registered Shoostr templates into representable OpenAPI paths. */
final class OpenApiPaths {
  /**
   * Prevents instantiation of stateless path translation.
   *
   * @throws UnsupportedOperationException always
   */
  private OpenApiPaths() {
    throw new UnsupportedOperationException("Cannot instantiate class");
  }

  /**
   * Normalizes segment constraints and validates explicitly declared path parameters.
   *
   * @param template final registered template
   * @param operation detached operation whose constraints are updated
   * @return representable OpenAPI path
   * @throws IllegalArgumentException if the template or parameters cannot be represented
   */
  static String normalize(String template, Operation operation) {
    final var names = new LinkedHashMap<String, String>();
    final var normalized = normalizeSegments(template, names);

    validateParameters(template, operation, names);

    return normalized;
  }

  /**
   * Collects named constraints while translating each representable parameter segment.
   *
   * @param template registered route template
   * @param names collected parameter names and constraints
   * @return normalized OpenAPI path
   * @throws IllegalArgumentException if a catch-all cannot be represented
   */
  private static String normalizeSegments(String template, Map<String, String> names) {
    final var segments = template.split(HttpCharacters.PATH_SEPARATOR_STRING, -1);
    for (var segmentIndex = 0; segmentIndex < segments.length; segmentIndex++) {
      final var segment = segments[segmentIndex];
      if (!segment.startsWith(HttpCharacters.OPEN_CURLY_BRACE_STRING)) {
        continue;
      }

      final var content = segment.substring(1, segment.length() - 1);
      if (content.startsWith(HttpCharacters.ASTERISK_STRING)) {
        throw new IllegalArgumentException("OpenAPI cannot represent catch-all route: " + template);
      }

      final var colon = content.indexOf(HttpCharacters.COLON_SIGN);
      final var name = colon < 0 ? content : content.substring(0, colon);
      names.put(name, colon < 0 ? "" : content.substring(colon + 1));
      segments[segmentIndex] =
          HttpCharacters.OPEN_CURLY_BRACE_STRING + name + HttpCharacters.CLOSE_CURLY_BRACE_STRING;
    }

    return String.join(HttpCharacters.PATH_SEPARATOR_STRING, segments);
  }

  /**
   * Requires complete, required path declarations and applies their actual route constraints.
   *
   * @param template registered route template for diagnostics
   * @param operation detached operation being validated
   * @param names actual parameter names and constraints
   * @throws IllegalArgumentException if a declaration is missing or incompatible
   */
  private static void validateParameters(
      String template, Operation operation, Map<String, String> names) {
    final var missing = new HashSet<>(names.keySet());
    final List<Parameter> parameters =
        Objects.requireNonNullElse(operation.getParameters(), List.of());
    for (final var parameter : parameters) {
      if (!"path".equals(parameter.getIn())) {
        continue;
      }

      final var name = Objects.requireNonNull(parameter.getName());
      final var constraint = names.get(name);
      if (constraint == null || !Boolean.TRUE.equals(parameter.getRequired())) {
        throw new IllegalArgumentException(
            "Invalid declared path parameter: " + name + " on " + template);
      }

      missing.remove(name);
      if (!constraint.isEmpty()) {
        applyConstraint(parameter, constraint, name, template);
      }
    }

    if (!missing.isEmpty()) {
      throw new IllegalArgumentException(
          "Missing declared path parameters: " + missing + " on " + template);
    }
  }

  /**
   * Intersects an explicit string declaration with its registered segment constraint.
   *
   * @param parameter detached declared path parameter
   * @param constraint registered safe-subset regex
   * @param name parameter name for diagnostics
   * @param template route template for diagnostics
   * @throws IllegalArgumentException if its schema cannot represent the constraint
   */
  private static void applyConstraint(
      Parameter parameter, String constraint, String name, String template) {
    final var declared = parameter.getSchema();
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

    final var pattern = "^(?:" + constraint + ")$";
    if (declared.getPattern() == null) {
      declared.setPattern(pattern);
      return;
    }

    final var routeSchema = new JsonSchema().types(Set.of("string")).pattern(pattern);
    parameter.setSchema(new JsonSchema().addAllOfItem(declared).addAllOfItem(routeSchema));
  }
}
