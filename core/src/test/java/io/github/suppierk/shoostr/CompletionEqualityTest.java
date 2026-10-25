package io.github.suppierk.shoostr;

import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.junit.jupiter.api.Test;

class CompletionEqualityTest {
  @Test
  void completionOwnerRetainsInstanceEquality() throws ClassNotFoundException {
    var type = Class.forName(Shoostr.class.getName() + "$Completion");
    EqualsVerifier.forClass(type)
        .withPrefabValues(
            Throwable.class,
            new IllegalStateException("first"),
            new IllegalStateException("second"))
        .withNonnullFields("localObservers", "scopes")
        .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
        .verify();
  }
}
