package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.http.ForwardedHeaders;
import java.net.InetAddress;
import java.util.function.Predicate;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;

class TrustedProxyEqualityTest {
  @Test
  void satisfiesEqualsAndHashCodeContract() {
    EqualsVerifier.forClass(TrustedProxy.class).withNonnullFields("trusted", "headers").verify();
  }

  @Test
  void matchingTrustPredicateAndHeaderFamilyProduceEqualValues() {
    Predicate<InetAddress> trusted = InetAddress::isLoopbackAddress;
    var first = new TrustedProxy(trusted, ForwardedHeaders.RFC7239);
    var second = new TrustedProxy(trusted, ForwardedHeaders.RFC7239);

    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
    assertNotEquals(
        first, new TrustedProxy(InetAddress::isSiteLocalAddress, ForwardedHeaders.RFC7239));
    assertNotEquals(first, new TrustedProxy(trusted, ForwardedHeaders.X_FORWARDED));
  }

  @Test
  void rejectsNullConfigurationBeforeUse() {
    assertThrows(
        NullPointerException.class, () -> new TrustedProxy(null, ForwardedHeaders.RFC7239));
    assertThrows(
        NullPointerException.class, () -> new TrustedProxy(InetAddress::isLoopbackAddress, null));
  }
}
