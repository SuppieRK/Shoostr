package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpMethods;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;

class RouteInfoTest {
  @Test
  void satisfiesEqualsAndHashCodeContract() {
    var first = new RouteInfo(HttpMethods.GET, "/first", false);
    var second = new RouteInfo(HttpMethods.POST, "/second", true);

    EqualsVerifier.forExamples(first, second).withNonnullFields("method", "pathTemplate").verify();
  }
}
