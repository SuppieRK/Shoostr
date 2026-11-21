package io.github.suppierk.shoostr;

import static io.github.suppierk.shoostr.Locks.getUnderLock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShoostrFatalCleanupTest {
  @TempDir Path directory;

  @Test
  void fatalExtensionCloseReleasesTheOwnedProviderReference() throws Exception {
    var fatal = new LinkageError("extension close failed");
    var closeOrder = new ArrayList<String>();
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.afterClose(() -> closeOrder.add("afterClose"));
      app.routes()
          .get(
              "/healthy",
              (request, response) -> {},
              lifecycle ->
                  lifecycle.withExtension(trackedProvider(() -> closeOrder.add("healthy"))));
      app.routes()
          .get(
              "/owned",
              (request, response) -> {},
              lifecycle ->
                  lifecycle.withExtension(trackedProvider(() -> failClose(fatal, closeOrder))));
      var fields = List.of("extensions");
      ownedSizes(app, fields).forEach((field, size) -> assertTrue(size > 0, field));

      assertSame(fatal, assertThrows(LinkageError.class, app::close));
      assertEquals(List.of("fatal", "afterClose"), closeOrder);

      assertRetired(app, fields);
      app.close();
    }
  }

  private void failClose(Error failure, List<String> closeOrder) {
    closeOrder.add("fatal");
    throw failure;
  }

  private RouteExtension<Object> trackedProvider(Runnable close) {
    return new RouteExtension<>() {
      @Override
      public Object configure(Lifecycle lifecycle, Object inherited) {
        return new Object();
      }

      @Override
      public void close() {
        close.run();
      }
    };
  }

  @Test
  void fatalPendingSourceCloseReleasesTransportConfigurationReferences() throws Exception {
    var fatal = new LinkageError("native close failed");

    try (var source = ownedSource(fatal)) {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        app.modifyServer(ignored -> {}).modifyHttpConfiguration(ignored -> {});
        app.tls(ignored -> {}).sessions(ignored -> {});
        registerSource(app.routes(), source);
        var fields = transportConfigurationFields();
        ownedSizes(app, fields).forEach((field, size) -> assertTrue(size > 0, field));

        assertSame(fatal, assertThrows(LinkageError.class, app::close));

        assertRetired(app, fields);
        app.close();
      }
    }
  }

  private List<String> transportConfigurationFields() {
    return List.of(
        "serverConfigurations", "httpConfigurations", "tlsConfiguration", "sessionConfiguration");
  }

  private StaticFiles ownedSource(Error fatal) {
    return new StaticFiles(
        "/assets", directory, StaticOptions.defaults(), () -> new FailingFactory(fatal));
  }

  private void assertRetired(Shoostr app, List<String> fields) throws ReflectiveOperationException {
    var expected = new LinkedHashMap<String, Integer>();
    fields.forEach(field -> expected.put(field, 0));
    assertEquals(expected, ownedSizes(app, fields));
  }

  private void registerSource(Routes routes, StaticFiles source) throws Exception {
    var registration = Routes.class.getDeclaredMethod("staticSource", String.class, Function.class);
    registration.setAccessible(true);
    registration.invoke(routes, "/assets", (Function<String, StaticFiles>) ignored -> source);
  }

  private Map<String, Integer> ownedSizes(Shoostr app, List<String> fields)
      throws ReflectiveOperationException {
    var lockField = Shoostr.class.getDeclaredField("lock");
    lockField.setAccessible(true);
    var lock = (ReentrantLock) lockField.get(app);
    return Objects.requireNonNull(getUnderLock(lock, () -> lockedOwnedSizes(app, fields)));
  }

  private Map<String, Integer> lockedOwnedSizes(Shoostr app, List<String> fields)
      throws ReflectiveOperationException {
    var result = new LinkedHashMap<String, Integer>();
    for (var name : fields) {
      var field = Shoostr.class.getDeclaredField(name);
      field.setAccessible(true);
      var value = field.get(app);
      result.put(name, value instanceof Collection<?> list ? list.size() : value == null ? 0 : 1);
    }
    return result;
  }

  private static final class FailingFactory implements ResourceFactory.Closeable {
    private final Error failure;
    private final ResourceFactory.Closeable delegate;
    private boolean closed;

    private FailingFactory(Error failure) {
      this.failure = Objects.requireNonNull(failure);
      this.delegate = ResourceFactory.closeable();
      this.closed = false;
    }

    @Override
    public Resource newResource(URI uri) {
      return delegate.newResource(uri);
    }

    @Override
    public void close() {
      if (!closed) {
        closed = true;
        delegate.close();
        throw failure;
      }
    }
  }
}
