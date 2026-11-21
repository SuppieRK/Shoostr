package io.github.suppierk.shoostr;

import static io.github.suppierk.shoostr.Locks.getUnderLock;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RoutesResourceCleanupTest {
  @TempDir Path directory;

  @Test
  void sourceCloseFailuresPreserveOrderSuppressionAndFinalCleanup() throws Exception {
    var closeOrder = new ArrayList<String>();
    var firstFailure = new IllegalStateException("first source close failed");
    var lastFailure = new IllegalStateException("last source close failed");
    var first = new AtomicReference<StaticFiles>();
    var healthy = new AtomicReference<StaticFiles>();
    var last = new AtomicReference<StaticFiles>();
    var app = new Shoostr();

    try (Closeable firstCleanup = () -> closeAcquiredSource(first);
        Closeable healthyCleanup = () -> closeAcquiredSource(healthy);
        Closeable lastCleanup = () -> closeAcquiredSource(last);
        Closeable appCleanup = app::close) {
      app.afterClose(() -> closeOrder.add("afterClose"));
      registerOwnedState(app);
      var routes = app.routes();
      registerSource(routes, "/first", failingSource("first", closeOrder, firstFailure, first));
      registerSource(routes, "/healthy", source("healthy", closeOrder, () -> {}, healthy));
      registerSource(routes, "/last", failingSource("last", closeOrder, lastFailure, last));
      assertRegistrationStatePopulated(routes);

      var failure = assertThrows(IOException.class, app::close);

      assertSame(firstFailure, failure.getCause());
      assertArrayEquals(new Throwable[] {lastFailure}, firstFailure.getSuppressed());
      assertEquals(List.of("first", "healthy", "last", "afterClose"), closeOrder);
      assertRegistrationStateRetired(routes);
      app.close();
      assertEquals(List.of("first", "healthy", "last", "afterClose"), closeOrder);
      assertRegistrationStateRetired(routes);
    }
  }

  @Test
  void fatalSourceCloseRetiresRegistrationStateAndPreservesTheOriginalError() throws Exception {
    var fatal = new LinkageError("native close failed");
    var closeOrder = new ArrayList<String>();
    var acquired = new AtomicReference<StaticFiles>();
    var app = new Shoostr();

    try (Closeable sourceCleanup = () -> closeAcquiredSource(acquired);
        Closeable appCleanup = app::close) {
      app.afterClose(() -> closeOrder.add("afterClose"));
      registerOwnedState(app);
      var routes = app.routes();
      registerSource(routes, "/fatal", failingSource("fatal", closeOrder, fatal, acquired));
      assertRegistrationStatePopulated(routes);

      assertSame(fatal, assertThrows(LinkageError.class, app::close));
      assertEquals(List.of("fatal", "afterClose"), closeOrder);
      assertRegistrationStateRetired(routes);
      app.close();
      assertEquals(List.of("fatal", "afterClose"), closeOrder);
      assertRegistrationStateRetired(routes);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void failedInheritedConfigurationClosesItsSourceAndSuppressesCleanupFailure(boolean fatal)
      throws Exception {
    Throwable primary =
        fatal
            ? new LinkageError("configuration failed")
            : new IllegalArgumentException("configuration failed");
    var cleanupFailure = new IllegalStateException("source close failed");
    var closeOrder = new ArrayList<String>();
    var acquired = new AtomicReference<StaticFiles>();
    var app = new Shoostr();

    try (Closeable sourceCleanup = () -> closeAcquiredSource(acquired);
        Closeable appCleanup = app::close) {
      var provider = failingInheritedProvider(primary);
      var source = failingSource("unpublished", closeOrder, cleanupFailure, acquired);
      app.routes()
          .path(
              "/assets",
              routes -> {
                var failure =
                    assertThrows(
                        primary.getClass(), () -> registerSource(routes, "/source", source));

                assertSame(primary, failure);
                assertArrayEquals(new Throwable[] {cleanupFailure}, failure.getSuppressed());
                assertEquals(List.of("unpublished"), closeOrder);
              },
              lifecycle -> lifecycle.withExtension(provider));
    }
  }

  private RouteExtension<Object> failingInheritedProvider(Throwable failure) {
    return (ignoredLifecycle, inherited) -> {
      if (inherited != null) {
        throw configurationFailure(failure);
      }

      return new Object();
    };
  }

  private RuntimeException configurationFailure(Throwable failure) {
    if (failure instanceof Error fatal) {
      throw fatal;
    }

    return (RuntimeException) failure;
  }

  @Test
  void reentrantClosureRejectsAndClosesTheUnpublishedSource() throws Exception {
    var closeOrder = new ArrayList<String>();
    var acquired = new AtomicReference<StaticFiles>();
    var app = new Shoostr();

    try (Closeable sourceCleanup = () -> closeAcquiredSource(acquired);
        Closeable appCleanup = app::close) {
      var provider = closingInheritedProvider(app);
      var source = source("unpublished", closeOrder, () -> {}, acquired);
      app.routes()
          .path(
              "/assets",
              routes -> {
                assertThrows(
                    IllegalStateException.class, () -> registerSource(routes, "/source", source));
                assertEquals(List.of("unpublished"), closeOrder);
              },
              lifecycle -> lifecycle.withExtension(provider));
    }
  }

  private RouteExtension<Object> closingInheritedProvider(Shoostr app) {
    return (ignoredLifecycle, inherited) -> {
      if (inherited != null) {
        assertDoesNotThrow(app::close);
      }

      return new Object();
    };
  }

  private Function<String, StaticFiles> failingSource(
      String name,
      List<String> closeOrder,
      Throwable failure,
      AtomicReference<StaticFiles> acquired) {
    return source(
        name,
        closeOrder,
        () -> {
          throw configurationFailure(failure);
        },
        acquired);
  }

  private Function<String, StaticFiles> source(
      String name,
      List<String> closeOrder,
      Runnable afterClose,
      AtomicReference<StaticFiles> acquired) {
    return mount -> {
      var files = ownedSource(mount, name, closeOrder, afterClose);
      acquired.set(files);
      return files;
    };
  }

  private StaticFiles ownedSource(
      String mount, String name, List<String> closeOrder, Runnable afterClose) {
    return new StaticFiles(
        mount,
        directory,
        StaticOptions.defaults(),
        () -> new CloseTrackingFactory(name, closeOrder, afterClose));
  }

  private void closeAcquiredSource(AtomicReference<StaticFiles> acquired) {
    var files = acquired.get();
    if (files != null) {
      files.close();
    }
  }

  private Routes registerSource(Routes routes, String mount, Function<String, StaticFiles> source)
      throws ReflectiveOperationException {
    // Inject only the native factory choice; registration and ownership stay in Routes.
    var registration = Routes.class.getDeclaredMethod("staticSource", String.class, Function.class);
    registration.setAccessible(true);

    try {
      return (Routes) registration.invoke(routes, mount, source);
    } catch (InvocationTargetException wrapper) {
      var cause = wrapper.getCause();
      if (cause instanceof Error fatal) {
        throw fatal;
      }

      if (cause instanceof RuntimeException failure) {
        throw failure;
      }

      throw wrapper;
    }
  }

  private void registerOwnedState(Shoostr app) {
    app.withExtension((lifecycle, ignoredInherited) -> {
      lifecycle.onRoute(ignored -> {});
      return new Object();
    });
    app.routes()
        .get("/ready", (request, response) -> response.text("ready"))
        .websocket("/socket", ignored -> {});
  }

  private void assertRegistrationStatePopulated(Routes routes) throws ReflectiveOperationException {
    registrationSizes(routes).forEach((field, size) -> assertTrue(size > 0, field));
  }

  private void assertRegistrationStateRetired(Routes routes) throws ReflectiveOperationException {
    var expected = new LinkedHashMap<String, Integer>();
    registrationFields().forEach(field -> expected.put(field, 0));
    assertEquals(expected, registrationSizes(routes));
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

  private static final class CloseTrackingFactory implements ResourceFactory.Closeable {
    private final String name;
    private final List<String> closeOrder;
    private final Runnable afterClose;
    private final ResourceFactory.Closeable delegate;
    private boolean closed;

    private CloseTrackingFactory(String name, List<String> closeOrder, Runnable afterClose) {
      this.name = Objects.requireNonNull(name);
      this.closeOrder = Objects.requireNonNull(closeOrder);
      this.afterClose = Objects.requireNonNull(afterClose);
      this.delegate = ResourceFactory.closeable();
      this.closed = false;
    }

    @Override
    public Resource newResource(URI uri) {
      return delegate.newResource(uri);
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }

      closed = true;
      delegate.close();
      closeOrder.add(name);
      afterClose.run();
    }
  }
}
