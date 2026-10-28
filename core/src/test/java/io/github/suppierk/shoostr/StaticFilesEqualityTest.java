package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticFilesEqualityTest {
  @TempDir Path directory;

  @Test
  void comparesMountBehaviorOptionsAndOwnedNativeResources() throws Exception {
    var first = Files.createDirectory(directory.resolve("first"));
    var second = Files.createDirectory(directory.resolve("second"));

    try (var firstFactory = ResourceFactory.closeable();
        var secondFactory = ResourceFactory.closeable()) {
      // Behavior is assigned during registration; ownership maps use identity throughout.
      EqualsVerifier.forClass(StaticFiles.class)
          .withPrefabValues(
              Csrf.class,
              new Csrf(Set.of("https://first.example")),
              new Csrf(Set.of("https://second.example")))
          .withPrefabValues(ResourceFactory.Closeable.class, firstFactory, secondFactory)
          .withPrefabValues(
              Resource.class, firstFactory.newResource(first), secondFactory.newResource(second))
          .withNonnullFields("mount", "options", "resourceFactory", "source")
          .suppress(Warning.NONFINAL_FIELDS)
          .verify();
    }
  }

  @Test
  void separateOpeningsOfTheSameDirectoryRetainDistinctOwnedDescriptors() {
    try (var first = new StaticFiles("/files", directory);
        var second = new StaticFiles("/files", directory)) {
      assertNotEquals(first, second);
    }
  }
}
