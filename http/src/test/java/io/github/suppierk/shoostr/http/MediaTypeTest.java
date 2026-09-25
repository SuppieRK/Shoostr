package io.github.suppierk.shoostr.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class MediaTypeTest {
  @Test
  void commonTypesHaveValueSemantics() {
    assertEquals("application/json", MediaType.APPLICATION_JSON.value());
    assertEquals("application/octet-stream", MediaType.APPLICATION_OCTET_STREAM.value());
    assertEquals("text/plain", MediaType.TEXT_PLAIN.value());
    assertEquals("text/html", MediaType.TEXT_HTML.value());
    assertEquals(MediaType.APPLICATION_JSON, MediaType.of("APPLICATION/JSON"));
    var values =
        new HashSet<>(
            List.of(
                MediaType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8),
                MediaType.of("TEXT/PLAIN").withCharset(Charset.forName("utf8"))));
    assertEquals(1, values.size());
    assertNotEquals(MediaType.TEXT_PLAIN, MediaType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
    assertNotNull(MediaType.TEXT_PLAIN);
  }

  @Test
  void acceptsRegistrationNameBoundaries() {
    for (String name :
        List.of(
            "a/b",
            "1/2",
            "application/a!#$&-^_.+z",
            "application/prs.example+json",
            "unfamiliar/custom",
            "multipart/form-data",
            "a".repeat(127) + "/" + "b".repeat(127))) {
      assertEquals(name, MediaType.of(name).value());
    }
  }

  @Test
  void normalizesIndependentlyOfDefaultLocale() {
    var previous = Locale.getDefault();

    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertEquals("application/json", MediaType.of("APPLICATION/JSON").value());
    } finally {
      Locale.setDefault(previous);
    }
  }

  @Test
  @SuppressWarnings("NullAway")
  void requiresExplicitInputsWithoutInventingTypeSemantics() {
    assertThrows(NullPointerException.class, () -> MediaType.of(null));
    assertThrows(NullPointerException.class, () -> MediaType.TEXT_PLAIN.withCharset(null));
    assertEquals(
        "application/json; charset=UTF-8",
        MediaType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8).value());
  }

  @Test
  void constructsCanonicalCustomTypes() {
    assertEquals(
        "application/vnd.example+json", MediaType.of("Application/Vnd.Example+JSON").value());
  }

  @ParameterizedTest
  @MethodSource("invalidNames")
  void rejectsInvalidBareNames(String name) {
    assertThrows(IllegalArgumentException.class, () -> MediaType.of(name));
  }

  @Test
  void replacesCharsetImmutably() {
    var base = MediaType.of("text/plain");
    var utf8 = base.withCharset(Charset.forName("utf8"));
    var latin1 = utf8.withCharset(StandardCharsets.ISO_8859_1);
    assertEquals("text/plain", base.value());
    assertEquals("text/plain; charset=UTF-8", utf8.value());
    assertEquals("text/plain; charset=ISO-8859-1", latin1.value());
  }

  @Test
  void rejectsCharsetNamesRequiringQuotedValues() {
    var charset =
        new Charset("X-Test:Codec", new String[0]) {
          @Override
          public boolean contains(Charset other) {
            return false;
          }

          @Override
          public CharsetDecoder newDecoder() {
            return StandardCharsets.UTF_8.newDecoder();
          }

          @Override
          public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
          }
        };
    var mediaType = MediaType.of("text/plain");
    assertThrows(IllegalArgumentException.class, () -> mediaType.withCharset(charset));
  }

  private static Stream<String> invalidNames() {
    return Stream.of(
        "",
        "text",
        "/plain",
        "text/",
        "text/plain/extra",
        " text/plain",
        "text/plain ",
        "text /plain",
        "text/ plain",
        "text/plain; charset=UTF-8",
        "text/plain,application/json",
        "text/\"plain\"",
        "text/(plain)",
        "text/pl\tain",
        "text/pl\rain",
        "text/pl\nain",
        "text/pl\0ain",
        "text/pl\u007fain",
        "text/pläin",
        "text/١",
        "text/pl\u00a0ain",
        "*/*",
        "text/*",
        "*/json",
        "application/*+json",
        "application/a*b",
        "text/a%b",
        "text/a'b",
        "text/a`b",
        "text/a|b",
        "text/a~b",
        "-text/plain",
        "text/+json",
        "a".repeat(128) + "/plain",
        "text/" + "a".repeat(128));
  }
}
