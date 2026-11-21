package io.github.suppierk.shoostr;

import static io.github.suppierk.shoostr.Locks.getUnderLock;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShoostrOwnershipTest {
  @Test
  void reportsOwnedHookCleanupFailureAfterRunningFinalCallbacks() throws Exception {
    var failure = new IllegalStateException("beforeClose failed");
    var finalized = new AtomicInteger();
    var logged = new CompletableFuture<Throwable>();
    var logger = Logger.getLogger(Shoostr.class.getName());
    var capture = shutdownLog(logged);
    logger.addHandler(capture);

    try {
      var app = new Shoostr();
      Thread hook = null;

      try {
        try (Closeable appCleanup = app::close) {
          hook = ownedHook(app);
          app.beforeClose(() -> {
            throw failure;
          });
          app.afterClose(finalized::incrementAndGet);

          invokeOwnedHook(hook);

          var aggregate = logged.get(3, TimeUnit.SECONDS);
          assertEquals(IOException.class, aggregate.getClass());
          assertSame(failure, aggregate.getCause());
          assertEquals(1, finalized.get());
          assertOwnedStateRetired(app);
          assertFalse(Runtime.getRuntime().removeShutdownHook(hook));
        }
      } finally {
        if (hook != null) {
          Runtime.getRuntime().removeShutdownHook(hook);
        }
      }
    } finally {
      logger.removeHandler(capture);
      capture.close();
    }
  }

  private java.util.logging.Handler shutdownLog(CompletableFuture<Throwable> logged) {
    return new java.util.logging.Handler() {
      @Override
      public void publish(LogRecord record) {
        if ("Shoostr shutdown failed".equals(record.getMessage())) {
          logged.complete(record.getThrown());
        }
      }

      @Override
      public void flush() {}

      @Override
      public void close() {}
    };
  }

  @Test
  void rejectsNestedProviderInstallationBeforeClaimingTheNestedProvider() throws Exception {
    var innerInstalls = new AtomicInteger();
    var outerCloses = new AtomicInteger();
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      var outer = nestedInstallingProvider(app, innerInstalls, outerCloses);
      assertThrows(
          IllegalStateException.class,
          () ->
              app.routes()
                  .get(
                      "/outer",
                      (request, response) -> {},
                      lifecycle -> lifecycle.withExtension(outer)));

      assertEquals(0, innerInstalls.get());
      assertEquals(1, outerCloses.get());
      assertOwnedStateRetired(app);
    }
  }

  private RouteExtension<Object> nestedInstallingProvider(
      Shoostr app, AtomicInteger innerInstalls, AtomicInteger outerCloses) {
    return new RouteExtension<>() {
      @Override
      public Object configure(Lifecycle lifecycle, Object inherited) {
        return new Object();
      }

      @Override
      public void install(ApplicationCallbacks callbacks) {
        app.routes()
            .get(
                "/inner",
                (request, response) -> {},
                lifecycle -> lifecycle.withExtension(countingProvider(innerInstalls)));
      }

      @Override
      public void close() {
        outerCloses.incrementAndGet();
      }
    };
  }

  private RouteExtension<Object> countingProvider(AtomicInteger installs) {
    return new RouteExtension<>() {
      @Override
      public Object configure(Lifecycle lifecycle, Object inherited) {
        return new Object();
      }

      @Override
      public void install(ApplicationCallbacks callbacks) {
        installs.incrementAndGet();
      }
    };
  }

  @Test
  void registersItsOwnedHookAtConstructionAndRemovesItAfterExplicitClose() throws Exception {
    var app = new Shoostr();
    Thread hook = null;

    try {
      try (Closeable appCleanup = app::close) {
        hook = ownedHook(app);
        assertRegistered(hook);
        app.close();
        assertFalse(Runtime.getRuntime().removeShutdownHook(hook));
      }
    } finally {
      if (hook != null) {
        Runtime.getRuntime().removeShutdownHook(hook);
      }
    }
  }

  private Thread ownedHook(Shoostr app) throws ReflectiveOperationException {
    return Objects.requireNonNull(
        getUnderLock(
            applicationLock(app),
            () -> {
              var field = Shoostr.class.getDeclaredField("shutdownHook");
              field.setAccessible(true);
              return (Thread) field.get(app);
            }));
  }

  private void assertRegistered(Thread hook) {
    var registered = false;

    try {
      registered = Runtime.getRuntime().removeShutdownHook(hook);
    } finally {
      if (registered) {
        Runtime.getRuntime().addShutdownHook(hook);
      }
    }

    assertTrue(registered);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void ownedHookRunsCleanupBeforeAndAfterStartup(boolean start) throws Exception {
    var closed = new AtomicInteger();
    var nativeServer = new AtomicReference<Server>();
    var app = new Shoostr();
    Thread hook = null;

    try {
      try (Closeable appCleanup = app::close) {
        app.afterClose(closed::incrementAndGet);
        app.modifyServer(server -> {
          nativeServer.set(server);
          ((ServerConnector) server.getConnectors()[0]).setPort(0);
        });
        hook = ownedHook(app);
        if (start) {
          app.start();
        }

        invokeOwnedHook(hook);

        assertEquals(1, closed.get());
        assertThrows(IllegalStateException.class, app::start);
        if (start) {
          assertTrue(nativeServer.get().isStopped());
        }

        assertFalse(Runtime.getRuntime().removeShutdownHook(hook));
        app.close();
        assertEquals(1, closed.get());
      }
    } finally {
      if (hook != null) {
        Runtime.getRuntime().removeShutdownHook(hook);
      }
    }
  }

  @SuppressWarnings("DoNotCall") // Exercise the owned callback without initiating JVM shutdown.
  private void invokeOwnedHook(Thread hook) {
    hook.run();
  }

  @Test
  void releasesCallbackAndConfigurationReferencesWhenClosedBeforeStartup() throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      populateOwnedState(app);
      ownedStateSizes(app).forEach((field, size) -> assertTrue(size > 0, field));
      app.close();

      assertOwnedStateRetired(app);
    }
  }

  private void populateOwnedState(Shoostr app) {
    Handler handler = (ignoredRequest, ignoredResponse) -> {};
    app.exception(
        IllegalArgumentException.class, (ignoredFailure, ignoredRequest, ignoredResponse) -> {});
    app.status(404, handler);
    app.onRequestHeaders(handler)
        .onRouteMatched(handler)
        .beforeRouteHandler(handler)
        .afterRouteHandler(handler)
        .beforeResponseFlush(handler)
        .afterResponseFlush(handler);
    app.afterRequest(ignored -> {}).observe(ignored -> outcome -> {});
    app.modifyServer(ignored -> {}).modifyHttpConfiguration(ignored -> {});
    app.tls(ignored -> {}).sessions(ignored -> {});
    app.beforeClose(() -> {}).afterClose(() -> {});
    app.routes()
        .get(
            "/owned",
            handler,
            lifecycle ->
                lifecycle.withExtension((ignoredLifecycle, ignoredInherited) -> new Object()));
  }

  @Test
  void doesNotRepublishCallbacksWhenInstallationClosesTheApplication() throws Exception {
    var app = new Shoostr();
    var installs = new AtomicInteger();
    var closes = new AtomicInteger();

    try (Closeable appCleanup = app::close) {
      var provider = closingProvider(app, installs, closes);
      assertThrows(
          IllegalStateException.class,
          () ->
              app.routes()
                  .get(
                      "/owned",
                      (request, response) -> {},
                      lifecycle -> lifecycle.withExtension(provider)));

      assertEquals(1, installs.get());
      assertEquals(1, closes.get());
      assertOwnedStateRetired(app);
    }
  }

  private RouteExtension<Object> closingProvider(
      Shoostr app, AtomicInteger installs, AtomicInteger closes) {
    return new RouteExtension<>() {
      @Override
      public Object configure(Lifecycle ignoredLifecycle, Object ignoredInherited) {
        return new Object();
      }

      @Override
      public void install(ApplicationCallbacks callbacks) {
        installs.incrementAndGet();
        Handler handler = (ignoredRequest, ignoredResponse) -> {};
        callbacks
            .onRequestHeaders(handler)
            .onRouteMatched(handler)
            .beforeRouteHandler(handler)
            .afterRouteHandler(handler)
            .beforeResponseFlush(handler)
            .afterResponseFlush(handler);
        callbacks.afterRequest(ignored -> {}).observe(ignored -> outcome -> {});
        assertDoesNotThrow(app::close);
      }

      @Override
      public void close() {
        closes.incrementAndGet();
      }
    };
  }

  private void assertOwnedStateRetired(Shoostr app) throws ReflectiveOperationException {
    var expected = new LinkedHashMap<String, Integer>();
    ownedFields().forEach(field -> expected.put(field, 0));
    assertEquals(expected, ownedStateSizes(app));
  }

  private Map<String, Integer> ownedStateSizes(Shoostr app) throws ReflectiveOperationException {
    return Objects.requireNonNull(
        getUnderLock(applicationLock(app), () -> lockedOwnedStateSizes(app)));
  }

  private Map<String, Integer> lockedOwnedStateSizes(Shoostr app)
      throws ReflectiveOperationException {
    var sizes = new LinkedHashMap<String, Integer>();
    for (var name : ownedFields()) {
      var field = Shoostr.class.getDeclaredField(name);
      field.setAccessible(true);
      var value = field.get(app);
      sizes.put(name, ownedStateSize(value));
    }
    return sizes;
  }

  private int ownedStateSize(Object value) {
    return value instanceof Map<?, ?> map
        ? map.size()
        : value instanceof Collection<?> collection ? collection.size() : value == null ? 0 : 1;
  }

  private List<String> ownedFields() {
    return List.of(
        "exceptionHandlers",
        "statusHandlers",
        "requestHeaderHandlers",
        "routeMatchedHandlers",
        "beforeHandlers",
        "afterRouteHandlers",
        "beforeFlushHandlers",
        "afterFlushHandlers",
        "afterHandlers",
        "observationFactories",
        "serverConfigurations",
        "httpConfigurations",
        "tlsConfiguration",
        "sessionConfiguration",
        "beforeCloseCallbacks",
        "afterCloseCallbacks",
        "extensions");
  }

  private ReentrantLock applicationLock(Shoostr app) throws ReflectiveOperationException {
    var field = Shoostr.class.getDeclaredField("lock");
    field.setAccessible(true);
    return (ReentrantLock) field.get(app);
  }
}
