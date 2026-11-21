package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CsrfBuilderTest {
  @Test
  void retainsEveryAdditionalTrustedOrigin() {
    var csrf =
        Csrf.builder()
            .trustedOrigins(
                "https://first.example", "https://second.example", "https://third.example")
            .build();

    assertEquals(
        Set.of("https://first.example", "https://second.example", "https://third.example"),
        csrf.getTrustedOrigins());
  }

  @Test
  void rejectsNullAdditionalOrigin() {
    String[] additional = {"https://second.example", null};

    assertThrows(
        NullPointerException.class,
        () -> Csrf.builder().trustedOrigins("https://first.example", additional));
  }

  @Test
  void treatsNullAdditionalArrayAsNoAdditionalOrigins() {
    String[] additional = null;
    var csrf = Csrf.builder().trustedOrigins("https://first.example", additional).build();

    assertEquals(Set.of("https://first.example"), csrf.getTrustedOrigins());
  }
}
