package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;

class McpLimitsYamlTest {
  @Test
  void partialSessionLimitsPreserveTheExistingDefaults() {
    var limits = new Yaml().loadAs("maxSessions: 8", McpSessionLimits.class);

    assertEquals(8, limits.maxSessions());
    assertEquals(32, limits.maxSessionsPerOwner());
    assertEquals(Duration.ofMinutes(30), limits.idleTimeout());
  }

  @Test
  void allSessionLimitsLoadWithAnApplicationOwnedDurationDescription() {
    var limits = createLimitsForAllSessionLimitsLoadWithAnApplicationOwnedDurationDescription();

    assertEquals(12, limits.maxSessions());
    assertEquals(3, limits.maxSessionsPerOwner());
    assertEquals(Duration.ofMinutes(2), limits.idleTimeout());
  }

  private McpSessionLimits
      createLimitsForAllSessionLimitsLoadWithAnApplicationOwnedDurationDescription() {
    return yaml()
        .loadAs(
            """
        maxSessions: 12
        maxSessionsPerOwner: 3
        idleTimeout: PT2M
        """,
            McpSessionLimits.class);
  }

  @Test
  void emptyNullAndResetValuesRestoreOperationalDefaults() {
    for (var source :
        new String[] {"{}", "maxSessions: null\nmaxSessionsPerOwner: null\nidleTimeout: null"}) {
      var limits = yaml().loadAs(source, McpSessionLimits.class);

      assertEquals(1024, limits.maxSessions());
      assertEquals(32, limits.maxSessionsPerOwner());
      assertEquals(Duration.ofMinutes(30), limits.idleTimeout());
    }
    var limits = new McpSessionLimits(4, 2, Duration.ofSeconds(1));
    limits.setMaxSessions(null);
    limits.setMaxSessionsPerOwner(null);
    limits.setIdleTimeout(null);

    assertEquals(1024, limits.maxSessions());
    assertEquals(32, limits.maxSessionsPerOwner());
    assertEquals(Duration.ofMinutes(30), limits.idleTimeout());
  }

  @Test
  void rejectedValuesKeepThePreviouslyValidLimits() {
    var limits = new McpSessionLimits(4, 2, Duration.ofSeconds(1));

    assertThrows(IllegalArgumentException.class, () -> limits.setMaxSessions(0));
    assertThrows(IllegalArgumentException.class, () -> limits.setMaxSessionsPerOwner(-1));
    assertThrows(IllegalArgumentException.class, () -> limits.setIdleTimeout(Duration.ZERO));
    assertEquals(4, limits.maxSessions());
    assertEquals(2, limits.maxSessionsPerOwner());
    assertEquals(Duration.ofSeconds(1), limits.idleTimeout());
    assertThrows(
        YAMLException.class, () -> yaml().loadAs("maxSessions: 0", McpSessionLimits.class));
    assertThrows(
        YAMLException.class, () -> yaml().loadAs("idleTimeout: PT0S", McpSessionLimits.class));
  }

  private static Yaml yaml() {
    var constructor = new Constructor(McpSessionLimits.class, new LoaderOptions());
    constructor.addTypeDescription(
        new TypeDescription(Duration.class) {
          @Override
          public Object newInstance(Node node) {
            if (!(node instanceof ScalarNode scalar)) {
              throw new IllegalArgumentException("Expected an ISO-8601 duration scalar");
            }

            return Duration.parse(scalar.getValue());
          }
        });

    return new Yaml(constructor);
  }
}
