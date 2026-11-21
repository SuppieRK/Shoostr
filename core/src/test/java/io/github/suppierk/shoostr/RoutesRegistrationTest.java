package io.github.suppierk.shoostr;

import static io.github.suppierk.shoostr.Locks.getUnderLock;
import static io.github.suppierk.shoostr.Locks.runUnderLock;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class RoutesRegistrationTest {
  @TempDir Path directory;

  @Test
  void appliesTheSameScopedExceptionMappingToEverySiblingEndpoint() throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.routes().path("/group", this::throwingSiblings);

      try (var test = TestServer.start(app)) {
        for (var path : List.of("/group/first", "/group/second")) {
          var response =
              test.send(request -> request.path(path), HttpResponse.BodyHandlers.ofString());

          assertEquals(409, response.statusCode());
          assertEquals("scoped", response.body());
        }
      }
    }
  }

  private void throwingSiblings(Routes routes) {
    routes.exception(
        IllegalArgumentException.class,
        (ignoredFailure, ignoredRequest, response) -> response.status(409).text("scoped"));
    Handler reject =
        (ignoredRequest, ignoredResponse) -> {
          throw new IllegalArgumentException("private");
        };
    routes.get("/first", reject).get("/second", reject);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void freezesTheOuterFailedCollectorBeforeReleasingItsRouteShape(boolean fatal) throws Exception {
    var retained = new AtomicReference<Lifecycle>();
    Throwable failure =
        fatal
            ? new LinkageError("configuration failed")
            : new IllegalStateException("configuration failed");
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      var routes = app.routes();
      var actual = failedRouteRegistration(routes, retained, failure);

      assertSame(failure, actual);
      var closedScope = retained.get();

      assertThrows(IllegalStateException.class, () -> closedScope.afterRequest(ignored -> {}));
      routes.get("/retry", (request, response) -> response.text("ready"));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "ready",
            test.send(request -> request.path("/retry"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  private Throwable failedRouteRegistration(
      Routes routes, AtomicReference<Lifecycle> retained, Throwable failure) {
    return assertThrows(
        failure.getClass(),
        () ->
            routes.get(
                "/retry",
                (request, response) -> response.text("wrong"),
                lifecycle -> {
                  retained.set(lifecycle);
                  throw configurationFailure(failure);
                }));
  }

  private RuntimeException configurationFailure(Throwable failure) {
    if (failure instanceof Error fatal) {
      throw fatal;
    }

    return (RuntimeException) failure;
  }

  @Test
  void rejectsRouteRegistrationUntilTheDefaultsCallbackFinishes() throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      var routes = app.routes();
      app.withExtension(
          provider(),
          ignored ->
              assertThrows(
                  IllegalStateException.class,
                  () -> routes.get("/ready", (request, response) -> response.text("wrong"))));
      routes.get("/ready", (request, response) -> response.text("ready"));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "ready",
            test.send(request -> request.path("/ready"), HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void rejectsDefaultsWhoseCallbackClosesTheApplication() throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      var extension = provider();

      assertThrows(
          IllegalStateException.class,
          () -> app.withExtension(extension, ignored -> assertDoesNotThrow(app::close)));
      assertThrows(IllegalStateException.class, app::start);
    }
  }

  @Test
  void closedDefaultsRejectTheSameProviderBeforeCheckingDuplicateActivation() throws Exception {
    var app = new Shoostr();
    var extension = provider();

    try (Closeable appCleanup = app::close) {
      app.withExtension(extension);
      app.close();

      assertThrows(IllegalStateException.class, () -> app.withExtension(extension));
    }
  }

  @Test
  void closedRegistrationRejectsStartupWhileCloseWaitsForTheApplicationLock() throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));
      var lock = applicationLock(app);
      var closing = pendingClose(app);
      var closer = Thread.ofPlatform().unstarted(closing);

      try {
        runUnderLock(
            lock,
            () -> {
              closer.start();
              await().atMost(Duration.ofSeconds(5)).until(() -> lock.hasQueuedThread(closer));
              assertThrows(IllegalStateException.class, app::start);
            });
        closing.get(5, TimeUnit.SECONDS);
      } finally {
        closer.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(closer.isAlive());
      }
    }
  }

  private ReentrantLock applicationLock(Shoostr app) throws ReflectiveOperationException {
    var field = Shoostr.class.getDeclaredField("lock");
    field.setAccessible(true);
    return (ReentrantLock) field.get(app);
  }

  private FutureTask<Void> pendingClose(Shoostr app) {
    return new FutureTask<>(() -> {
      app.close();
      return null;
    });
  }

  @Test
  void rejectsAnEmptyConditionalRegistrationAfterCloseWithoutInvokingItsCallback()
      throws Exception {
    var app = new Shoostr();
    var routes = app.routes();
    var invoked = new AtomicBoolean();
    app.close();

    assertThrows(
        IllegalStateException.class, () -> routes.when(() -> true, ignored -> invoked.set(true)));
    assertFalse(invoked.get());
  }

  @Test
  void staticMountingEndsTheApplicationDefaultsPhase() throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.routes().staticFiles("/assets", directory);
      var extension = provider();

      assertThrows(IllegalStateException.class, () -> app.withExtension(extension));
    }
  }

  private ApplicationExtension<Object> provider() {
    return (ignoredLifecycle, ignoredInherited) -> new Object();
  }

  @Test
  @SuppressWarnings("NullAway")
  void closedRegistrationWinsOverInvalidEndpointPaths() throws Exception {
    var app = new Shoostr();
    var routes = app.routes();
    app.close();

    assertThrows(
        IllegalStateException.class,
        () -> routes.get((String) null, (ignoredRequest, ignoredResponse) -> {}));
    assertThrows(
        IllegalStateException.class,
        () ->
            routes.websocket(
                (String) null, (ignoredRequest, ignoredResponse) -> new Session.Listener() {}));
    assertThrows(
        IllegalStateException.class, () -> routes.websocket((String) null, ignoredEvents -> {}));
  }

  @Test
  void rejectsHttpOutputStagedAfterManagedWebSocketAdmission() throws Exception {
    var opened = new AtomicInteger();
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.afterRouteHandler((request, response) -> response.text("unexpected HTTP output"));
      app.exception(
          IllegalStateException.class,
          (ignoredFailure, ignoredRequest, response) ->
              response.status(409).text("invalid upgrade"));
      app.routes()
          .websocket("/socket", events -> events.onOpen(ignored -> opened.incrementAndGet()));

      try (var test = TestServer.start(app);
          var client = HttpClient.newHttpClient()) {
        var uri = new URI("ws", test.baseUri().getRawAuthority(), "/socket", null, null);
        var failure = rejectedHandshake(client, uri);
        var handshake = assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());

        assertEquals(409, handshake.getResponse().statusCode());
        assertEquals(0, opened.get());
      }
    }
  }

  private ExecutionException rejectedHandshake(HttpClient client, URI uri) {
    var opening = client.newWebSocketBuilder().buildAsync(uri, new Listener() {});
    var completed = opening.thenAccept(WebSocket::abort);

    try {
      return assertThrows(ExecutionException.class, () -> completed.get(3, TimeUnit.SECONDS));
    } finally {
      opening.cancel(true);
    }
  }

  @Test
  void configuresManagedWebSocketExtensionsSynchronouslyExactlyOnce() throws Exception {
    var caller = Thread.currentThread();
    var configured = new AtomicInteger();
    var retained = new AtomicReference<Lifecycle>();
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      var routes = app.routes();
      assertSame(
          routes,
          routes.websocket(
              "/socket",
              ignoredEvents -> {},
              lifecycle -> {
                assertSame(caller, Thread.currentThread());
                configured.incrementAndGet();
                retained.set(lifecycle);
              }));

      assertEquals(1, configured.get());
      var closedScope = retained.get();

      assertThrows(IllegalStateException.class, () -> closedScope.afterRequest(ignored -> {}));
    }
  }

  @Test
  void reportsStaticRequestsWithOnlyLocalTerminalObservation() throws Exception {
    Files.writeString(directory.resolve("file.txt"), "mounted");
    var terminal = new CompletableFuture<RequestOutcome>();
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.routes()
          .path(
              "/assets",
              scope -> scope.staticFiles("", directory),
              lifecycle -> lifecycle.afterRequest(terminal::complete));

      try (var test = TestServer.start(app)) {
        var response =
            test.send(
                request -> request.path("/assets/file.txt"), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("mounted", response.body());
        var outcome = terminal.get(3, TimeUnit.SECONDS);
        assertEquals(200, outcome.statusCode());
        assertEquals("/assets", outcome.routePattern());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void releasesRegistrationStateAfterStartupOrCloseBeforeStartup(boolean start) throws Exception {
    Files.writeString(directory.resolve("file.txt"), "mounted");
    var notified = new AtomicInteger();
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      registerOwnedState(app, notified);
      var routes = app.routes();
      registrationSizes(routes).forEach((field, size) -> assertTrue(size > 0, field));

      if (start) {
        try (var test = TestServer.start(app)) {
          assertEquals(
              "ready",
              test.send(request -> request.path("/ready"), HttpResponse.BodyHandlers.ofString())
                  .body());
          registrationSizes(routes).forEach((field, size) -> assertEquals(0, size, field));
          assertEquals(1, notified.get());
        }
      } else {
        app.close();
        assertEquals(0, notified.get());
      }

      registrationSizes(routes).forEach((field, size) -> assertEquals(0, size, field));
    }
  }

  private void registerOwnedState(Shoostr app, AtomicInteger notified) {
    app.withExtension((lifecycle, ignoredInherited) -> {
      lifecycle.onRoute(ignored -> notified.incrementAndGet());
      return new Object();
    });
    app.routes()
        .get("/ready", (request, response) -> response.text("ready"))
        .websocket("/socket", ignored -> {})
        .staticFiles("/assets", directory);
  }

  private Map<String, Integer> registrationSizes(Routes routes)
      throws ReflectiveOperationException {
    var field = Routes.class.getDeclaredField("lock");
    field.setAccessible(true);
    var lock = (ReentrantLock) field.get(routes);
    return Objects.requireNonNull(getUnderLock(lock, () -> lockedRegistrationSizes(routes)));
  }

  private Map<String, Integer> lockedRegistrationSizes(Routes routes)
      throws ReflectiveOperationException {
    var sizes = new LinkedHashMap<String, Integer>();
    for (var name : registrationFields()) {
      var field = Routes.class.getDeclaredField(name);
      field.setAccessible(true);
      var value = field.get(routes);
      sizes.put(name, value instanceof Map<?, ?> map ? map.size() : ((Collection<?>) value).size());
    }
    return sizes;
  }

  private List<String> registrationFields() {
    return List.of(
        "registrations",
        "websocketRegistrations",
        "staticFiles",
        "endpointScopes",
        "staticScopes",
        "routeKeys",
        "websocketRouteKeys",
        "installed",
        "notifications");
  }
}
