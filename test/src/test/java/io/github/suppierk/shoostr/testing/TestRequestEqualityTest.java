package io.github.suppierk.shoostr.testing;

import java.net.URI;
import java.net.http.HttpRequest;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.junit.jupiter.api.Test;

class TestRequestEqualityTest {
  @Test
  void retainsCallbackScopedBuilderIdentity() {
    EqualsVerifier.forClass(TestRequest.class)
        .withPrefabValues(
            HttpRequest.Builder.class,
            HttpRequest.newBuilder(URI.create("http://127.0.0.1/first"))
                .POST(HttpRequest.BodyPublishers.ofString("first")),
            HttpRequest.newBuilder(URI.create("http://127.0.0.1/second"))
                .PUT(HttpRequest.BodyPublishers.ofString("second")))
        .withPrefabValues(
            HttpRequest.BodyPublisher.class,
            HttpRequest.BodyPublishers.ofString("first"),
            HttpRequest.BodyPublishers.ofString("second"))
        .withNonnullFields("baseUri", "builder", "method", "body")
        .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
        .verify();
  }
}
