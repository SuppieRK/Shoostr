package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;

class MultipartOptionsTest {
  @Test
  void satisfiesEqualsAndHashCodeContract() {
    // Both per-file limits fit under either aggregate limit when fields vary independently.
    var first = new MultipartOptions(1000, 500, 7, 256, 64, Path.of("first-uploads"));
    var second = new MultipartOptions(2000, 750, 8, 512, 128, Path.of("second-uploads"));

    EqualsVerifier.forExamples(first, second).verify();
  }

  @Test
  void copiesCustomLimitsWhenChangingTheTemporaryDirectoryAndCanRestoreTheOriginal() {
    var original = new MultipartOptions(1000, 500, 7, 256, 64, null);
    var directory = Path.of("temporary-uploads");

    var configured = original.withTemporaryDirectory(directory);

    assertNull(original.temporaryDirectory());
    assertEquals(new MultipartOptions(1000, 500, 7, 256, 64, directory), configured);
    assertEquals(original, configured.withTemporaryDirectory(null));
  }
}
