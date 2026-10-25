package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MultiPart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadEqualityTest {
  @TempDir Path directory;

  @Test
  void identifiesEachRequestScopedUploadInstance() {
    EqualsVerifier.forClass(Upload.class)
        .withPrefabValues(
            MultiPart.Part.class, part("first.txt", "first"), part("second.txt", "second"))
        .withNonnullFields("streams")
        .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
        .verify();
  }

  @Test
  void preservesIdentityAndHashMembershipWhileReadingPersistingAndClosing() throws Exception {
    var upload = new Upload(part("same.txt", "payload"), () -> {});
    var other = new Upload(part("same.txt", "payload"), () -> {});
    var uploads = new HashSet<Upload>();
    var initialHash = upload.hashCode();
    assertTrue(uploads.add(upload));
    assertTrue(uploads.add(other));
    assertNotEquals(upload, other);

    try {
      try (var content = upload.content()) {
        assertEquals("payload", new String(content.readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(initialHash, upload.hashCode());
        assertTrue(uploads.contains(upload));
      }

      var target = directory.resolve("saved.txt");
      assertEquals(target, upload.persistTo(target));
      assertEquals("payload", Files.readString(target));
    } finally {
      upload.close();
      other.close();
    }

    assertEquals(upload, upload);
    assertEquals(initialHash, upload.hashCode());
    assertTrue(uploads.remove(upload));
    assertTrue(uploads.remove(other));
    assertTrue(uploads.isEmpty());
  }

  private static MultiPart.Part part(String name, String text) {
    return new MultiPart.ByteBufferPart(
        "file", name, HttpFields.EMPTY, ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
  }
}
