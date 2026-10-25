package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpMethods;
import java.lang.reflect.RecordComponent;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreRecordEqualityTest {
  @TempDir Path directory;

  @Test
  void acceptParametersCompareAllComponents() throws ReflectiveOperationException {
    var type = nested(AcceptNegotiation.class, "Parameters");
    EqualsVerifier.forExamples(
            record(type, Map.of("charset", "utf-8"), 100),
            record(type, Map.of("version", "2"), 200))
        .withNonnullFields("values")
        .verify();
  }

  @Test
  void acceptedRepresentationComparesAllComponents() throws ReflectiveOperationException {
    var type = nested(AcceptNegotiation.class, "Representation");
    EqualsVerifier.forExamples(
            record(type, "text", "plain", Map.of("charset", "utf-8")),
            record(type, "application", "json", Map.of("version", "2")))
        .withNonnullFields("type", "subtype", "parameters")
        .verify();
  }

  @Test
  void acceptRangeComparesAllComponents() throws ReflectiveOperationException {
    var type = nested(AcceptNegotiation.class, "Range");
    EqualsVerifier.forExamples(
            record(type, "text", "plain", Map.of("charset", "utf-8"), 100),
            record(type, "application", "json", Map.of("version", "2"), 200))
        .withNonnullFields("type", "subtype", "parameters")
        .verify();
  }

  @Test
  void byteRangeComparesBothOffsets() throws ReflectiveOperationException {
    var type = nested(Response.class, "ByteRange");
    EqualsVerifier.forExamples(record(type, 1L, 10L), record(type, 2L, 20L)).verify();
  }

  @Test
  void routeSyntaxComparesNormalizedPathAndCaptureMetadata() throws ReflectiveOperationException {
    var type = nested(RadixRoutes.class, "RouteSyntax");
    EqualsVerifier.forExamples(
            record(type, "/first/:id", Map.of("id", 1), 7, 1),
            record(type, "/second/:name", Map.of("name", 2), 8, 2))
        .withNonnullFields("pattern", "parameters")
        .verify();
  }

  @Test
  void endpointComparesAllRegistrationFacts() {
    try (var firstExecutor = Executors.newSingleThreadExecutor();
        var secondExecutor = Executors.newSingleThreadExecutor()) {
      EqualsVerifier.forClass(RadixRoutes.Endpoint.class)
          .withPrefabValues(HttpMethods.class, HttpMethods.GET, HttpMethods.POST)
          .withPrefabValues(
              Csrf.class,
              new Csrf(Set.of("https://first.example")),
              new Csrf(Set.of("https://second.example")))
          .withPrefabValues(ExecutorService.class, firstExecutor, secondExecutor)
          .withNonnullFields("method", "pattern", "routePattern", "handler", "parameters")
          .verify();
    }
  }

  @Test
  void routeConstraintComparesMembershipBoundsAndEndpointRanks()
      throws ReflectiveOperationException {
    var type = nested(RadixRoutes.class, "Constraint");
    var first = endpoint("/first/:id", "id", 1);
    var second = endpoint("/second/:name", "name", 2);
    EqualsVerifier.forExamples(
            record(type, "[0-9]+", 1L, 2L, 1, 10, Map.of(first, 1)),
            record(type, "[a-z]+", 4L, 8L, 2, 20, Map.of(second, 2)))
        .withPrefabValues(RadixRoutes.Endpoint.class, first, second)
        .withNonnullFields("expression", "ranks")
        .verify();
  }

  @Test
  void selectedResourceComparesDescriptorAndMetadata() throws Exception {
    var firstPath = Files.writeString(directory.resolve("first.txt"), "first");
    var secondPath = Files.writeString(directory.resolve("second.txt"), "second");
    var type = nested(StaticFiles.class, "Selected");

    try (var first = Files.newByteChannel(firstPath);
        var second = Files.newByteChannel(secondPath)) {
      EqualsVerifier.forExamples(
              record(type, first, 5L, Instant.EPOCH, "first.txt"),
              record(type, second, 6L, Instant.EPOCH.plusSeconds(1), "second.txt"))
          .withPrefabValues(SeekableByteChannel.class, first, second)
          .withNonnullFields("channel", "lastModified", "name")
          .verify();
    }
  }

  @Test
  void dispatchSnapshotComparesAllCapturedRegistrations() throws ReflectiveOperationException {
    var firstRouter = RadixRoutes.from(List.of(endpoint("/first/:id", "id", 1)));
    var secondRouter = RadixRoutes.from(List.of(endpoint("/second/:name", "name", 2)));
    var type = nested(Shoostr.class, "DispatchConfiguration");
    EqualsVerifier.forClass(type)
        .withPrefabValues(RadixRoutes.class, firstRouter, secondRouter)
        .withNonnullFields(
            "router",
            "errors",
            "statuses",
            "gates",
            "headerHooks",
            "matchedHooks",
            "postRouteHooks",
            "beforeFlushHooks",
            "afterFlushHooks",
            "observers",
            "instrumentation")
        .verify();
  }

  @Test
  void requestSelectionComparesEndpointPreflightAndCors() throws ReflectiveOperationException {
    var first = endpoint("/first/:id", "id", 1);
    var second = endpoint("/second/:name", "name", 2);
    var type = nested(nested(Shoostr.class, "ApplicationHandler"), "Selection");
    EqualsVerifier.forExamples(
            record(
                type, first, false, CorsPolicy.builder().origins("https://first.example").build()),
            record(
                type, second, true, CorsPolicy.builder().origins("https://second.example").build()))
        .withPrefabValues(RadixRoutes.Endpoint.class, first, second)
        .verify();
  }

  @Test
  void extensionSelectionComparesConfigurationContributionAndInclusion()
      throws ReflectiveOperationException {
    var first = new Extensions(Set.of(), null);
    var second = new Extensions(Set.of(), null);
    first.lifecycle().onRouteMatched((request, response) -> {});
    second.lifecycle().afterRouteHandler((request, response) -> {});
    var type = nested(Extensions.class, "Selection");
    EqualsVerifier.forExamples(
            record(type, "first", first, false), record(type, "second", second, true))
        .withPrefabValues(Extensions.class, first, second)
        .withNonnullFields("configuration", "contribution")
        .verify();
  }

  @Test
  void responseStateUsesEnumEquality() throws ClassNotFoundException {
    EqualsVerifier.forClass(nested(Response.class, "State")).verify();
  }

  @Test
  void bodyRepresentationUsesEnumEquality() throws ClassNotFoundException {
    EqualsVerifier.forClass(nested(Request.class, "BodyRepresentation")).verify();
  }

  private static RadixRoutes.Endpoint endpoint(String path, String parameter, int segment) {
    return new RadixRoutes.Endpoint(
        segment == 1 ? HttpMethods.GET : HttpMethods.POST,
        path,
        path,
        (request, response) -> response.text(parameter),
        Map.of(parameter, segment),
        segment + 1,
        segment,
        null);
  }

  private static Class<?> nested(Class<?> owner, String name) throws ClassNotFoundException {
    return Class.forName(owner.getName() + "$" + name);
  }

  private static Object record(Class<?> type, Object... components)
      throws ReflectiveOperationException {
    var signature =
        Arrays.stream(type.getRecordComponents())
            .map(RecordComponent::getType)
            .toArray(Class<?>[]::new);
    var constructor = type.getDeclaredConstructor(signature);
    constructor.setAccessible(true);
    return constructor.newInstance(components);
  }
}
