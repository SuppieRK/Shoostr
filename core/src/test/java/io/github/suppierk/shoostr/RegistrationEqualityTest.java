package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpMethods;
import java.io.Closeable;
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

  private static List<Lifecycle> scopes(Shoostr app) {
    var cors = new Cors(CorsPolicy.builder().origins("https://first.example").build());

    var scopes = new ArrayList<Lifecycle>();
    app.routes()
        .get(
            "/first",
            (request, response) -> {},
            extensions -> {
              extensions.withExtension(cors);
              extensions.onRouteMatched(
                  (request, response) -> response.setHeader("X-Scope", "first"));
              scopes.add(extensions);
            })
        .get(
            "/second",
            (request, response) -> {},
            extensions -> {
              extensions
                  .withExtension(cors)
                  .policy(CorsPolicy.builder().origins("https://second.example").build());
              extensions.afterRouteHandler(
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

  @Test
  void lifecycleCollectorRetainsRegistrationIdentity() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        var scopes = scopes(app);
        // Registration collectors have intermediate state, not a published value contract.
        EqualsVerifier.forClass(Lifecycle.class)
            .withPrefabValues(Lifecycle.class, scopes.get(0), scopes.get(1))
            .withPrefabValues(
                CorsPolicy.class,
                CorsPolicy.builder().origins("https://first.example").build(),
                CorsPolicy.builder().origins("https://second.example").build())
            .withPrefabValues(
                Csrf.class,
                Csrf.builder().trustedOrigins(Set.of("https://first.example")).build(),
                Csrf.builder().trustedOrigins(Set.of("https://second.example")).build())
            .withGenericPrefabValues(IdentityHashMap.class, RegistrationEqualityTest::identityMap)
            .withNonnullFields("installed", "selections", "order", "initialize", "routeConsumers")
            .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
            .verify();
      }
    }
  }

  @Test
  void routeBuilderRetainsRegistrationIdentity() throws Exception {
    {
      var first = new Routes(ignored -> {});

      try (Closeable firstCleanup1 = first::disposeRegistration) {
        var second = new Routes(ignored -> {});

        try (Closeable secondCleanup1 = second::disposeRegistration;
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
                  Lifecycle.class,
                  new Lifecycle(Set.of(), ignored -> {}, null),
                  new Lifecycle(Set.of(), ignored -> {}, null))
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
                  Csrf.builder().trustedOrigins(Set.of("https://first.example")).build(),
                  Csrf.builder().trustedOrigins(Set.of("https://second.example")).build())
              .withGenericPrefabValues(IdentityHashMap.class, RegistrationEqualityTest::identityMap)
              .withNonnullFields(
                  "root",
                  "lock",
                  "prefix",
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
    }
  }

  @Test
  void typedRouteBuilderRetainsRegistrationIdentity() throws Exception {
    {
      var first = new Routes(ignored -> {});

      try (Closeable firstCleanup2 = first::disposeRegistration) {
        var second = new Routes(ignored -> {});

        try (Closeable secondCleanup2 = second::disposeRegistration) {
          first.get("/first", (request, response) -> response.text("first"));
          second.get("/second", (request, response) -> response.text("second"));
          EqualsVerifier.forClass(Routes.Typed.class)
              .withPrefabValues(Routes.class, first, second)
              .withPrefabValues(Codec.class, codec("first"), codec("second"))
              .withNonnullFields("routes", "codec")
              .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
              .verify();
        }
      }
    }
  }

  @Test
  void typedApplicationBuilderRetainsRegistrationIdentity() throws Exception {
    {
      var first = new Shoostr();

      try (Closeable firstCleanup = first::close) {
        var second = new Shoostr();

        try (Closeable secondCleanup = second::close) {
          var firstView = new Routes.Typed(first.routes(), codec("first"));
          var secondView = new Routes.Typed(second.routes(), codec("second"));
          EqualsVerifier.forClass(Shoostr.Typed.class)
              .withPrefabValues(Shoostr.class, first, second)
              .withPrefabValues(Routes.Typed.class, firstView, secondView)
              .withNonnullFields("app", "routes")
              .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
              .verify();
        }
      }
    }
  }

  @Test
  void webSocketConfigurationRetainsRegistrationIdentity() {
    var first = new WebSocket().onText(WebSocketConnection::send);
    var second = new WebSocket().onBinary(WebSocketConnection::send);
    EqualsVerifier.forClass(WebSocket.class)
        .withRedefinedSubclass(WebSocket.Typed.class)
        .withPrefabValues(WebSocket.class, first, second)
        .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
        .verify();
  }

  @Test
  void typedWebSocketConfigurationRetainsRegistrationIdentity() {
    EqualsVerifier.forClass(WebSocket.Typed.class)
        .withPrefabValues(Codec.class, codec("first"), codec("second"))
        .withNonnullFields("codec")
        .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
        .verify();
  }
}
