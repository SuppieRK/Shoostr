package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MultipartOptionsTest {
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
