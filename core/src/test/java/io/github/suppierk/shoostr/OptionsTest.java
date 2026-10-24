package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OptionsTest {
  @ParameterizedTest
  @CsvSource({
    "0, 128, 32, 4",
    "-1, 128, 32, 4",
    "2147483647, 128, 32, 4",
    "64, 0, 32, 4",
    "64, -1, 32, 4",
    "64, 128, 0, 4",
    "64, 128, -1, 4",
    "64, 128, 32, 0",
    "64, 128, 32, -1"
  })
  void rejectsInvalidOwnedLimits(
      int requestBytes, int responseBytes, int bufferBytes, int parameters) {
    var multipart = MultipartOptions.defaults();
    assertThrows(
        IllegalArgumentException.class,
        () -> new Options(requestBytes, responseBytes, bufferBytes, parameters, multipart));
  }

  @Test
  void requiresMultipartLimitsAtConstruction() {
    assertThrows(IllegalArgumentException.class, () -> new Options(64, 128, 32, 4, null));
  }

  @Test
  void copiesEachOwnedLimitWithoutChangingTheOriginal() {
    var multipart = MultipartOptions.defaults();
    var original = new Options(64, 128, 32, 4, multipart);
    var changedMultipart = new MultipartOptions(1000, 500, 7, 256, 64, null);

    assertEquals(new Options(65, 128, 32, 4, multipart), original.withMaxRequestBytes(65));
    assertEquals(new Options(64, 129, 32, 4, multipart), original.withMaxResponseBytes(129));
    assertEquals(new Options(64, 128, 33, 4, multipart), original.withStreamBufferBytes(33));
    assertEquals(new Options(64, 128, 32, 5, multipart), original.withMaxParameters(5));
    assertEquals(
        new Options(64, 128, 32, 4, changedMultipart), original.withMultipart(changedMultipart));
    assertEquals(new Options(64, 128, 32, 4, multipart), original);
  }

  @Test
  void rejectsInvalidCopiesBeforeReturningThem() {
    var limits = Options.defaults();

    assertThrows(
        IllegalArgumentException.class, () -> limits.withMaxRequestBytes(Integer.MAX_VALUE));
    assertThrows(IllegalArgumentException.class, () -> limits.withMaxResponseBytes(0));
    assertThrows(IllegalArgumentException.class, () -> limits.withStreamBufferBytes(0));
    assertThrows(IllegalArgumentException.class, () -> limits.withMaxParameters(0));
    assertThrows(IllegalArgumentException.class, () -> limits.withMultipart(null));
  }
}
