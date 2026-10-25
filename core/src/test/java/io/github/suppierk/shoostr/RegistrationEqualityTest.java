package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpMethods;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegistrationEqualityTest {
  @TempDir Path directory;

  @Test
  void extensionCollectorRetainsRegistrationIdentity() throws Exception {
    try (var app = new Shoostr()) {
      var scopes = scopes(app);
      // Registration collectors have intermediate state, not a published value contract.
      EqualsVerifier.forClass(Extensions.class)
          .withPrefabValues(Extensions.class, scopes.get(0), scopes.get(1))
          .withPrefabValues(
              Csrf.class,
              new Csrf(Set.of("https://first.example")),
              new Csrf(Set.of("https://second.example")))
          .withGenericPrefabValues(IdentityHashMap.class, RegistrationEqualityTest::identityMap)
          .withPrefabValues(
              Lifecycle.class, new Lifecycle(scopes.get(0)), new Lifecycle(scopes.get(1)))
          .withNonnullFields("installed", "selections", "order", "lifecycle", "routeConsumers")
          .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
          .verify();
    }
  }

  @Test
  void lifecycleCollectorRetainsRegistrationIdentity() throws Exception {
    try (var app = new Shoostr()) {
      var scopes = scopes(app);
      EqualsVerifier.forClass(Lifecycle.class)
          .withPrefabValues(Extensions.class, scopes.get(0), scopes.get(1))
          .withNonnullFields(
              "owner",
              "matched",
              "before",
              "after",
              "beforeFlush",
              "afterFlush",
              "observers",
              "instrumentation",
              "statuses")
          .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
          .verify();
    }
  }

  @Test
  void routeBuilderRetainsRegistrationIdentity() throws Exception {
    try (var first = new Routes();
        var second = new Routes();
        var firstMount =
            new StaticFiles("/first", Files.createDirectory(directory.resolve("first")));
        var secondMount =
            new StaticFiles("/second", Files.createDirectory(directory.resolve("second")))) {
      first.get("/first", (request, response) -> response.text("first"));
      second.get("/second", (request, response) -> response.text("second"));
      EqualsVerifier.forClass(Routes.class)
          .withPrefabValues(Routes.class, first, second)
          .withPrefabValues(
              RadixRoutes.Endpoint.class,
              new RadixRoutes.Endpoint(
                  HttpMethods.GET,
                  "/first",
                  "/first",
                  (request, response) -> {},
                  Map.of(),
                  -1,
                  0,
                  null),
              new RadixRoutes.Endpoint(
                  HttpMethods.POST,
                  "/second",
                  "/second",
                  (request, response) -> {},
                  Map.of(),
                  -1,
                  0,
                  null))
          .withPrefabValues(StaticFiles.class, firstMount, secondMount)
          .withPrefabValues(
              Extensions.class, new Extensions(Set.of(), null), new Extensions(Set.of(), null))
          .withPrefabValues(
              RadixRoutes.class,
              RadixRoutes.from(List.of()),
              RadixRoutes.from(
                  List.of(
                      new RadixRoutes.Endpoint(
                          HttpMethods.GET,
                          "/first",
                          "/first",
                          (request, response) -> {},
                          Map.of(),
                          -1,
                          0,
                          null))))
          .withPrefabValues(
              Csrf.class,
              new Csrf(Set.of("https://first.example")),
              new Csrf(Set.of("https://second.example")))
          .withGenericPrefabValues(IdentityHashMap.class, RegistrationEqualityTest::identityMap)
          .withPrefabValues(
              ThreadLocal.class, ThreadLocal.withInitial(() -> 1), ThreadLocal.withInitial(() -> 2))
          .withNonnullFields(
              "root",
              "lock",
              "prefix",
              "activeGroupDepth",
              "conditions",
              "installed",
              "notifications",
              "registrations",
              "websocketRegistrations",
              "staticFiles",
              "errors",
              "endpointScopes",
              "staticScopes",
              "routeKeys",
              "websocketRouteKeys")
          .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
          .verify();
    }
  }

  @Test
  void typedRouteBuilderRetainsRegistrationIdentity() {
    try (var first = new Routes();
        var second = new Routes()) {
      first.get("/first", (request, response) -> response.text("first"));
      second.get("/second", (request, response) -> response.text("second"));
      EqualsVerifier.forClass(TypedRoutes.class)
          .withPrefabValues(Routes.class, first, second)
          .withPrefabValues(Codec.class, codec("first"), codec("second"))
          .withNonnullFields("routes", "codec")
          .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
          .verify();
    }
  }

  @Test
  void typedApplicationBuilderRetainsRegistrationIdentity() throws Exception {
    try (var first = new Shoostr();
        var second = new Shoostr()) {
      var firstView = new TypedRoutes(first.routes(), codec("first"));
      var secondView = new TypedRoutes(second.routes(), codec("second"));
      EqualsVerifier.forClass(TypedShoostr.class)
          .withPrefabValues(Shoostr.class, first, second)
          .withPrefabValues(TypedRoutes.class, firstView, secondView)
          .withNonnullFields("app", "routes")
          .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
          .verify();
    }
  }

  @Test
  void webSocketConfigurationRetainsRegistrationIdentity() {
    var first = new WebSocket().onText((connection, message) -> connection.send(message));
    var second = new WebSocket().onBinary((connection, message) -> connection.send(message));
    EqualsVerifier.forClass(WebSocket.class)
        .withRedefinedSubclass(TypedWebSocket.class)
        .withPrefabValues(WebSocket.class, first, second)
        .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
        .verify();
  }

  @Test
  void typedWebSocketConfigurationRetainsRegistrationIdentity() {
    EqualsVerifier.forClass(TypedWebSocket.class)
        .withPrefabValues(Codec.class, codec("first"), codec("second"))
        .withNonnullFields("codec")
        .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
        .verify();
  }

  private static List<Extensions> scopes(Shoostr app) {
    var cors = new Cors(CorsPolicy.builder().origins("https://first.example").build());
    app.extensions(cors);
    var scopes = new ArrayList<Extensions>();
    app.routes()
        .get(
            "/first",
            (request, response) -> {},
            extensions -> {
              extensions.with(cors);
              extensions
                  .lifecycle()
                  .onRouteMatched((request, response) -> response.setHeader("X-Scope", "first"));
              scopes.add(extensions);
            })
        .get(
            "/second",
            (request, response) -> {},
            extensions -> {
              extensions
                  .with(cors)
                  .policy(CorsPolicy.builder().origins("https://second.example").build());
              extensions
                  .lifecycle()
                  .afterRouteHandler(
                      (request, response) -> response.setHeader("X-Scope", "second"));
              scopes.add(extensions);
            });
    return scopes;
  }

  private static <K, V> IdentityHashMap<K, V> identityMap(K key, V value) {
    var result = new IdentityHashMap<K, V>();
    result.put(key, value);
    return result;
  }

  private static Codec codec(String marker) {
    return new Codec() {
      @Override
      public <T> T read(byte[] source, Class<T> type) {
        return type.cast(marker);
      }

      @Override
      public byte[] write(Object value) {
        return (marker + value).getBytes(StandardCharsets.UTF_8);
      }
    };
  }
}
