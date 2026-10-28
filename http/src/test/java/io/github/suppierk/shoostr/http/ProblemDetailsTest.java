package io.github.suppierk.shoostr.http;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProblemDetailsTest {
  @Test
  void satisfiesEqualsAndHashCodeContract() {
    EqualsVerifier.forClass(ProblemDetails.class).withNonnullFields("extensions").verify();
  }

  @Test
  void equalContentsHaveEqualHashesRegardlessOfExtensionOrder() {
    var first =
        ProblemDetails.builder()
            .type(URI.create("/problems/invalid-order"))
            .title("Invalid order")
            .status(422)
            .detail("Quantity must be positive")
            .instance(URI.create("/orders/123"))
            .extension("errors", List.of(Map.of("field", "quantity", "reason", "positive")))
            .extension("trace", null)
            .build();
    var second =
        ProblemDetails.builder()
            .type(URI.create("/problems/invalid-order"))
            .title("Invalid order")
            .status(422)
            .detail("Quantity must be positive")
            .instance(URI.create("/orders/123"))
            .extension("trace", null)
            .extension("errors", List.of(Map.of("reason", "positive", "field", "quantity")))
            .build();

    assertNotSame(first, second);
    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
  }

  @Test
  void omitsEveryUnsetStandardMember() {
    var problem = ProblemDetails.builder().build();

    assertTrue(problem.toMap().isEmpty());
  }

  @Test
  void projectsSuppliedStandardMembersWithoutEncodingTheirText() {
    var problem =
        ProblemDetails.builder()
            .type(URI.create("https://example.com/problems/invalid-order"))
            .title("Invalid order")
            .status(422)
            .detail("Quantity must be positive.\n\"数量\"")
            .instance(URI.create("/orders/123"))
            .build();

    assertEquals(
        Map.of(
            "type", "https://example.com/problems/invalid-order",
            "title", "Invalid order",
            "status", 422,
            "detail", "Quantity must be positive.\n\"数量\"",
            "instance", "/orders/123"),
        problem.toMap());
  }

  @Test
  void snapshotsNestedContainersWhenAnExtensionIsSupplied() {
    var field = new LinkedHashMap<String, Object>();
    field.put("field", "quantity");
    var errors = new ArrayList<>(List.of(field));
    var builder = ProblemDetails.builder().extension("errors", errors);

    field.put("field", "changed");
    errors.clear();

    assertEquals(Map.of("errors", List.of(Map.of("field", "quantity"))), builder.build().toMap());
  }

  @ParameterizedTest
  @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 99, 600, Integer.MAX_VALUE})
  void rejectsOutOfRangeStatusBeforeBuilding(int status) {
    assertThrows(IllegalArgumentException.class, () -> ProblemDetails.builder().status(status));
  }

  @ParameterizedTest
  @ValueSource(strings = {"type", "title", "status", "detail", "instance"})
  void rejectsCustomMembersThatReplaceStandardMembers(String name) {
    var builder = ProblemDetails.builder();

    assertThrows(IllegalArgumentException.class, () -> builder.extension(name, "replacement"));
  }

  @Test
  void rejectsCyclicListValues() {
    var cyclic = new ArrayList<>();
    var child = new ArrayList<>();
    cyclic.add(child);
    child.add(cyclic);
    var builder = ProblemDetails.builder();

    assertThrows(IllegalArgumentException.class, () -> builder.extension("errors", cyclic));
  }

  @Test
  void exposesAbsentStandardMembersAsNull() {
    var problem = ProblemDetails.builder().build();

    assertAll(
        () -> assertNull(problem.type()),
        () -> assertNull(problem.title()),
        () -> assertNull(problem.status()),
        () -> assertNull(problem.detail()),
        () -> assertNull(problem.instance()));
  }

  @Test
  void exposesSuppliedStandardMembersWithoutChangingTheirTypes() {
    var problem =
        ProblemDetails.builder()
            .type(URI.create("/problems/invalid-order"))
            .title("Invalid order")
            .status(422)
            .detail("Quantity must be positive.")
            .instance(URI.create("urn:order:123"))
            .build();

    assertAll(
        () -> assertEquals(URI.create("/problems/invalid-order"), problem.type()),
        () -> assertEquals("Invalid order", problem.title()),
        () -> assertEquals(422, problem.status()),
        () -> assertEquals("Quantity must be positive.", problem.detail()),
        () -> assertEquals(URI.create("urn:order:123"), problem.instance()));
  }

  @Test
  void exposesUnmodifiableExtensionEntries() {
    var problem = ProblemDetails.builder().extension("balance", 30).build();
    var extensions = problem.extensions();

    assertEquals(Map.of("balance", 30), extensions);
    assertThrows(UnsupportedOperationException.class, extensions::clear);
  }

  @Test
  void returnsAnEmptyExtensionMapWhenNoCustomMembersWereSupplied() {
    assertTrue(ProblemDetails.builder().build().extensions().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {100, 200, 302, 422, 500, 599})
  void acceptsStatusesThroughoutTheHttpRange(int status) {
    assertEquals(Map.of("status", status), ProblemDetails.builder().status(status).build().toMap());
  }

  @Test
  void keepsThePreviousStatusWhenItsReplacementIsInvalid() {
    var builder = ProblemDetails.builder().status(422);

    assertThrows(IllegalArgumentException.class, () -> builder.status(600));
    assertEquals(Map.of("status", 422), builder.build().toMap());
  }

  @Test
  void projectsRelativeUnicodeUriReferencesAsAsciiStrings() {
    var problem =
        ProblemDetails.builder()
            .type(URI.create("/problems/café"))
            .instance(URI.create("/orders/数量"))
            .build();

    assertEquals(
        Map.of("type", "/problems/caf%C3%A9", "instance", "/orders/%E6%95%B0%E9%87%8F"),
        problem.toMap());
  }

  @Test
  void retainsEmptySuppliedStandardStrings() {
    var problem =
        ProblemDetails.builder()
            .type(URI.create(""))
            .title("")
            .detail("")
            .instance(URI.create(""))
            .build();

    assertEquals(Map.of("type", "", "title", "", "detail", "", "instance", ""), problem.toMap());
  }

  @ParameterizedTest
  @NullSource
  void preservesThePreviousTypeWhenItsReplacementIsNull(URI type) {
    var builder = ProblemDetails.builder().type(URI.create("/problems/invalid-order"));

    assertThrows(NullPointerException.class, () -> builder.type(type));
    assertEquals(URI.create("/problems/invalid-order"), builder.build().type());
  }

  @ParameterizedTest
  @NullSource
  void preservesThePreviousTitleWhenItsReplacementIsNull(String title) {
    var builder = ProblemDetails.builder().title("Invalid order");

    assertThrows(NullPointerException.class, () -> builder.title(title));
    assertEquals("Invalid order", builder.build().title());
  }

  @ParameterizedTest
  @NullSource
  void preservesThePreviousDetailWhenItsReplacementIsNull(String detail) {
    var builder = ProblemDetails.builder().detail("Quantity must be positive.");

    assertThrows(NullPointerException.class, () -> builder.detail(detail));
    assertEquals("Quantity must be positive.", builder.build().detail());
  }

  @ParameterizedTest
  @NullSource
  void preservesThePreviousInstanceWhenItsReplacementIsNull(URI instance) {
    var builder = ProblemDetails.builder().instance(URI.create("/orders/123"));

    assertThrows(NullPointerException.class, () -> builder.instance(instance));
    assertEquals(URI.create("/orders/123"), builder.build().instance());
  }

  @ParameterizedTest
  @NullSource
  void rejectsNullCustomMemberNames(String name) {
    var builder = ProblemDetails.builder();

    assertThrows(NullPointerException.class, () -> builder.extension(name, "value"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Status", "x", ""})
  void acceptsCustomNamesWithoutImposingAdditionalNamingRules(String name) {
    assertEquals(
        Map.of(name, true), ProblemDetails.builder().extension(name, true).build().toMap());
  }

  @Test
  void retainsNullCustomMembersRatherThanOmittingThem() {
    var problem = ProblemDetails.builder().extension("missing", null).build();

    assertEquals(Collections.singletonMap("missing", null), problem.toMap());
  }

  @Test
  void preservesNullValuesInsideCopiedMaps() {
    var problem =
        ProblemDetails.builder()
            .extension("context", Collections.singletonMap("missing", null))
            .build();

    assertEquals(Map.of("context", Collections.singletonMap("missing", null)), problem.toMap());
  }

  @Test
  void preservesNullElementsInsideCopiedLists() {
    var problem =
        ProblemDetails.builder().extension("errors", Arrays.asList("quantity", null)).build();

    assertEquals(Map.of("errors", Arrays.asList("quantity", null)), problem.toMap());
  }

  @Test
  void preventsMutationOfCopiedNestedMapsThroughTheProjection() {
    var problem =
        ProblemDetails.builder().extension("context", Map.of("field", "quantity")).build();
    var context = (Map<?, ?>) problem.toMap().get("context");

    assertNotNull(context);
    assertThrows(UnsupportedOperationException.class, context::clear);
  }

  @Test
  void preventsMutationOfCopiedNestedListsThroughTheExtensionAccessor() {
    var problem = ProblemDetails.builder().extension("errors", List.of("quantity")).build();
    var errors = (List<?>) problem.extensions().get("errors");

    assertNotNull(errors);
    assertThrows(UnsupportedOperationException.class, errors::clear);
  }

  @Test
  void snapshotsMapsContainingNestedLists() {
    var fields = new ArrayList<>(List.of("quantity"));
    var context = new LinkedHashMap<String, Object>();
    context.put("fields", fields);
    var problem = ProblemDetails.builder().extension("context", context).build();

    fields.clear();
    context.clear();

    assertEquals(Map.of("context", Map.of("fields", List.of("quantity"))), problem.toMap());
  }

  @Test
  void allowsSharedAcyclicContainers() {
    var shared = new ArrayList<>(List.of("quantity"));
    var problem =
        ProblemDetails.builder()
            .extension("context", Map.of("first", shared, "second", shared))
            .build();

    shared.clear();

    assertEquals(
        Map.of("context", Map.of("first", List.of("quantity"), "second", List.of("quantity"))),
        problem.toMap());
  }

  @Test
  void rejectsCyclicMapValues() {
    var cyclic = new LinkedHashMap<String, Object>();
    var child = new LinkedHashMap<String, Object>();
    cyclic.put("child", child);
    child.put("parent", cyclic);
    var builder = ProblemDetails.builder();

    assertThrows(IllegalArgumentException.class, () -> builder.extension("context", cyclic));
  }

  @Test
  void rejectsCyclesCrossingMapAndListValues() {
    var map = new LinkedHashMap<String, Object>();
    var list = new ArrayList<>();
    map.put("errors", list);
    list.add(map);
    var builder = ProblemDetails.builder();

    assertThrows(IllegalArgumentException.class, () -> builder.extension("context", map));
  }

  @Test
  void keepsThePreviousExtensionWhenItsReplacementContainsACycle() {
    var builder = ProblemDetails.builder().extension("errors", List.of("quantity"));
    var cyclic = new ArrayList<>();
    var child = new ArrayList<>();
    cyclic.add(child);
    child.add(cyclic);

    assertThrows(IllegalArgumentException.class, () -> builder.extension("errors", cyclic));
    assertEquals(Map.of("errors", List.of("quantity")), builder.build().toMap());
  }

  @Test
  void retainsArbitraryApplicationObjectsByReference() {
    var context = new StringBuilder("before");
    var problem = ProblemDetails.builder().extension("context", List.of(context)).build();
    context.append(" after");
    var retained = (List<?>) problem.extensions().get("context");

    assertNotNull(retained);
    assertSame(context, retained.getFirst());
  }

  @Test
  void leavesNestedMapKeysCallerOwned() {
    var key = new StringBuilder("quantity");
    var problem = ProblemDetails.builder().extension("context", Map.of(key, "positive")).build();
    var context = (Map<?, ?>) problem.extensions().get("context");

    assertNotNull(context);
    assertSame(key, context.keySet().iterator().next());
  }

  @Test
  void replacesRepeatedCustomMembers() {
    var problem =
        ProblemDetails.builder().extension("balance", 30).extension("balance", 20).build();

    assertEquals(Map.of("balance", 20), problem.toMap());
  }

  @Test
  void reusingTheBuilderDoesNotChangeEarlierStandardMembers() {
    var builder = ProblemDetails.builder().title("First");
    var first = builder.build();
    var second = builder.title("Second").build();

    assertEquals(Map.of("title", "First"), first.toMap());
    assertEquals(Map.of("title", "Second"), second.toMap());
  }

  @Test
  void reusingTheBuilderDoesNotChangeEarlierExtensionEntries() {
    var builder = ProblemDetails.builder().extension("balance", 30);
    var first = builder.build();
    var second = builder.extension("balance", 20).extension("currency", "EUR").build();

    assertEquals(Map.of("balance", 30), first.toMap());
    assertEquals(Map.of("balance", 20, "currency", "EUR"), second.toMap());
  }

  @Test
  void returnsIndependentMutableTopLevelProjections() {
    var problem = ProblemDetails.builder().status(422).extension("balance", 30).build();
    var document = problem.toMap();
    document.put("status", 500);
    document.remove("balance");
    document.put("extra", true);

    assertEquals(Map.of("status", 422, "balance", 30), problem.toMap());
  }

  @Test
  void rejectsMapCopiesThatWouldCollapseDistinctKeys() {
    var firstKey = new ArrayList<>(List.of("field"));
    var secondKey = new ArrayList<>(List.of("field"));
    var context = new IdentityHashMap<>();
    context.put(firstKey, "first");
    context.put(secondKey, "second");
    var builder = ProblemDetails.builder();

    assertThrows(IllegalArgumentException.class, () -> builder.extension("context", context));
  }

  @Test
  void keepsThePreviousExtensionWhenItsReplacementWouldLoseMapEntries() {
    var firstKey = new ArrayList<>(List.of("field"));
    var secondKey = new ArrayList<>(List.of("field"));
    var context = new IdentityHashMap<>();
    context.put(firstKey, "first");
    context.put(secondKey, "second");
    var builder = ProblemDetails.builder().extension("context", Map.of("field", "quantity"));

    assertThrows(IllegalArgumentException.class, () -> builder.extension("context", context));
    assertEquals(Map.of("context", Map.of("field", "quantity")), builder.build().toMap());
  }

  @Test
  void copiesMapsWithNonstandardEqualityWhenTheirEntriesRemainDistinct() {
    var firstKey = new Object();
    var secondKey = new Object();
    var context = new IdentityHashMap<>();
    context.put(firstKey, "first value");
    context.put(secondKey, "second value");
    var problem = ProblemDetails.builder().extension("context", context).build();
    var copied = (Map<?, ?>) problem.extensions().get("context");

    assertNotNull(copied);
    assertEquals(2, copied.size());
    assertEquals("first value", copied.get(firstKey));
    assertEquals("second value", copied.get(secondKey));
  }
}
