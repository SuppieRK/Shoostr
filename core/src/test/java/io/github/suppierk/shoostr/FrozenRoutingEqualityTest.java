package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.http.HttpMethods;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrozenRoutingEqualityTest {
  @TempDir Path directory;

  @Test
  void plainNodeSatisfiesEqualsAndHashCodeContract() throws Exception {
    verifyNode(RadixRoutes.class);
  }

  @Test
  void specialBranchSatisfiesEqualsAndHashCodeContract() throws Exception {
    verifyNode(Class.forName(RadixRoutes.class.getName() + "$SpecialBranchRoutes"));
  }

  @Test
  void catchAllBranchSatisfiesEqualsAndHashCodeContract() throws Exception {
    verifyNode(Class.forName(RadixRoutes.class.getName() + "$CatchAllRoutes"));
  }

  @Test
  void constrainedBranchSatisfiesEqualsAndHashCodeContract() throws Exception {
    verifyNode(Class.forName(RadixRoutes.class.getName() + "$ConstrainedRoutes"));
  }

  @Test
  void independentlyCompiledTreesCompareChildrenAndConstraintValues() {
    Handler handler = (request, response) -> response.text("shared");
    var first = tree(handler, "/items/{id:[0-9]+}", "/files/{*tail}", "/other");
    var second = tree(handler, "/items/{id:[0-9]+}", "/files/{*tail}", "/other");

    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
    assertNotEquals(first, tree(handler, "/items/{id:[a-z]+}", "/files/{*tail}", "/other"));
    assertNotEquals(first, tree(handler, "/items/{id:[0-9]+}", "/files/{*tail}", "/changed"));
    assertNotEquals(
        first,
        tree(
            (request, response) -> response.text("different"),
            "/items/{id:[0-9]+}",
            "/files/{*tail}",
            "/other"));
  }

  @Test
  void concreteNodeKindsRemainUnequalEvenWithMatchingStoredFields()
      throws ReflectiveOperationException {
    Handler handler = (request, response) -> {};
    var special = tree(handler, "/{id:[0-9]+}");
    var fields = new Object[6];
    var names = List.of("edge", "label", "endpoints", "children", "parameter", "staticFiles");
    for (int index = 0; index < names.size(); index++) {
      var field = RadixRoutes.class.getDeclaredField(names.get(index));
      field.setAccessible(true);
      fields[index] = field.get(special);
    }
    var constructor =
        RadixRoutes.class.getDeclaredConstructor(
            String.class,
            char.class,
            Map.class,
            RadixRoutes[].class,
            RadixRoutes.class,
            List.class);
    constructor.setAccessible(true);
    var plain = constructor.newInstance(fields);

    assertNotEquals(plain, special);
    assertNotEquals(special, plain);
  }

  @Test
  void rejectsMissingFrozenNodeCollectionsBeforePublication() throws ReflectiveOperationException {
    var constructor =
        RadixRoutes.class.getDeclaredConstructor(
            String.class,
            char.class,
            Map.class,
            RadixRoutes[].class,
            RadixRoutes.class,
            List.class);
    constructor.setAccessible(true);
    for (int component : new int[] {2, 3, 5}) {
      var values = new Object[] {"", (char) 0, Map.of(), new RadixRoutes[0], null, List.of()};
      values[component] = null;
      var failure =
          assertThrows(InvocationTargetException.class, () -> constructor.newInstance(values));
      assertEquals(NullPointerException.class, failure.getCause().getClass());
    }
  }

  private void verifyNode(Class<?> type) throws Exception {
    var firstEndpoint = RadixRoutes.endpoint(HttpMethods.GET, "/first", (request, response) -> {});
    var secondEndpoint =
        RadixRoutes.endpoint(HttpMethods.POST, "/second", (request, response) -> {});
    var first = RadixRoutes.from(List.of(firstEndpoint));
    var second = RadixRoutes.from(List.of(secondEndpoint));
    var firstDirectory = Files.createDirectory(directory.resolve("first"));
    var secondDirectory = Files.createDirectory(directory.resolve("second"));

    try (var firstMount = new StaticFiles("/first", firstDirectory);
        var secondMount = new StaticFiles("/second", secondDirectory)) {
      var verifier =
          EqualsVerifier.forClass(type)
              .usingGetClass()
              .withPrefabValues(RadixRoutes.class, first, second)
              .withPrefabValues(RadixRoutes.Endpoint.class, firstEndpoint, secondEndpoint)
              .withPrefabValues(StaticFiles.class, firstMount, secondMount)
              .withNonnullFields("endpoints", "children", "staticFiles");
      if ("ConstrainedRoutes".equals(type.getSimpleName())) {
        var constraint = Class.forName(RadixRoutes.class.getName() + "$Constraint");
        verifier =
            verifier
                .withRedefinedSuperclass()
                .withPrefabValuesForField(
                    "constraint",
                    record(constraint, "[0-9]+", 1L, 2L, 1, 10, Map.of(firstEndpoint, 1)),
                    record(constraint, "[a-z]+", 4L, 8L, 2, 20, Map.of(secondEndpoint, 2)))
                .withNonnullFields("endpoints", "children", "staticFiles", "constraint");
      }

      verifier.verify();
    }
  }

  private static RadixRoutes tree(Handler handler, String... paths) {
    return RadixRoutes.from(
        Arrays.stream(paths)
            .map(path -> RadixRoutes.endpoint(HttpMethods.GET, path, handler))
            .toList());
  }

  private static Object record(Class<?> type, Object... values)
      throws ReflectiveOperationException {
    var signature =
        Arrays.stream(type.getRecordComponents())
            .map(RecordComponent::getType)
            .toArray(Class<?>[]::new);
    var constructor = type.getDeclaredConstructor(signature);
    constructor.setAccessible(true);
    return constructor.newInstance(values);
  }
}
