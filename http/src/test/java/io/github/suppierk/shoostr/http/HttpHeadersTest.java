package io.github.suppierk.shoostr.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class HttpHeadersTest {
  private static final HttpHeaders TENANT = HttpHeaders.of("X-Tenant");

  private static final Set<String> OBSOLETED =
      Set.of(
          "ACCESS_CONTROL",
          "C_EXT",
          "C_MAN",
          "C_OPT",
          "C_PEP",
          "CONTENT_BASE",
          "CONTENT_MD5",
          "CONTENT_SCRIPT_TYPE",
          "CONTENT_STYLE_TYPE",
          "CONTENT_VERSION",
          "COOKIE2",
          "DEFAULT_STYLE",
          "DERIVED_FROM",
          "DIGEST",
          "EXT",
          "GETPROFILE",
          "HTTP2_SETTINGS",
          "MAN",
          "METHOD_CHECK",
          "METHOD_CHECK_EXPIRES",
          "OPT",
          "P3P",
          "PEP",
          "PEP_INFO",
          "PICS_LABEL",
          "PROFILEOBJECT",
          "PROTOCOL",
          "PROTOCOL_REQUEST",
          "PROXY_FEATURES",
          "PROXY_INSTRUCTION",
          "PUBLIC",
          "REFERER_ROOT",
          "SAFE",
          "SECURITY_SCHEME",
          "SET_COOKIE2",
          "SETPROFILE",
          "URI",
          "WANT_DIGEST",
          "WARNING");

  @Test
  void retainsTheRegisteredBuiltInInventoryAndWireSpelling() throws IllegalAccessException {
    var names = new HashSet<String>();
    for (var field : HttpHeaders.class.getFields()) {
      if (field.getType() == HttpHeaders.class && Modifier.isStatic(field.getModifiers())) {
        var header = (HttpHeaders) field.get(null);
        var value = header.value();
        assertTrue(names.add(value.toLowerCase(Locale.ROOT)), value);
        for (String spelling :
            new String[] {value, value.toLowerCase(Locale.ROOT), value.toUpperCase(Locale.ROOT)}) {
          assertSame(header, HttpHeaders.httpHeader(spelling).orElseThrow());
          assertTrue(header.equalsIgnoreCase(spelling), spelling);
        }
      }
    }

    assertEquals(258, names.size());
    assertEquals("Content-Type", HttpHeaders.CONTENT_TYPE.value());
  }

  @Test
  void constructsCustomHeadersWithoutRegisteringThem() {
    assertEquals("X-Tenant", TENANT.value());
    assertTrue(TENANT.equalsIgnoreCase("x-tenant"));
    assertTrue(HttpHeaders.httpHeader("X-Tenant").isEmpty());
  }

  @Test
  void usesCaseInsensitiveEqualityAndHashing() {
    var first = TENANT;
    var second = HttpHeaders.of("x-tenant");

    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
    assertTrue(Set.of(first).contains(second));
  }

  @Test
  void recognizesBuiltInNamesRegardlessOfCase() {
    assertSame(HttpHeaders.CONTENT_TYPE, HttpHeaders.httpHeader("cOnTeNt-TyPe").orElseThrow());
    assertTrue(HttpHeaders.CONTENT_TYPE.equalsIgnoreCase("cOnTeNt-TyPe"));
    assertFalse(HttpHeaders.CONTENT_TYPE.equalsIgnoreCase("Content-Length"));
  }

  @Test
  void lookupDoesNotDependOnDefaultLocale() {
    var originalLocale = Locale.getDefault();

    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertSame(HttpHeaders.IF_MATCH, HttpHeaders.httpHeader("IF-MATCH").orElseThrow());
      assertTrue(HttpHeaders.IF_MATCH.equalsIgnoreCase("if-match"));
    } finally {
      Locale.setDefault(originalLocale);
    }
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "CONTENT_TYPE",
        " Content-Type",
        "Content-Type ",
        "Content-Type\r\n",
        ":method",
        "LİNК",
        "LinK"
      })
  void keepsUnknownHeaderLookupNonthrowing(String value) {
    assertTrue(HttpHeaders.httpHeader(value).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", " Content-Type", "Content Type", "Content:Type", "Content-Type\r\n", "LİNК"})
  void rejectsInvalidHeaderNames(String value) {
    assertThrows(IllegalArgumentException.class, () -> HttpHeaders.of(value));
  }

  @Test
  @SuppressWarnings("NullAway")
  void rejectsNullHeaderNames() {
    assertThrows(NullPointerException.class, () -> HttpHeaders.of(null));
  }

  @Test
  void marksExactlyTheObsoletedBuiltInConstantsDeprecated() {
    for (var field : HttpHeaders.class.getFields()) {
      if (field.getType() == HttpHeaders.class && Modifier.isStatic(field.getModifiers())) {
        var deprecated = field.isAnnotationPresent(Deprecated.class);
        assertEquals(OBSOLETED.contains(field.getName()), deprecated, field.getName());
      }
    }
  }
}
