package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.http.HttpMethods;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RouteInfoTest {
  @ParameterizedTest
  @ValueSource(strings = {"", "relative", "relative/path"})
  void rejectsTemplatesWithoutAnAbsolutePath(String template) {
    assertThrows(
        IllegalArgumentException.class, () -> new RouteInfo(HttpMethods.GET, template, false));
  }

  @Test
  void satisfiesEqualsAndHashCodeContract() {
    var first = new RouteInfo(HttpMethods.GET, "/first", false);
    var second = new RouteInfo(HttpMethods.POST, "/second", true);

    EqualsVerifier.forExamples(first, second).withNonnullFields("method", "pathTemplate").verify();
  }
}
