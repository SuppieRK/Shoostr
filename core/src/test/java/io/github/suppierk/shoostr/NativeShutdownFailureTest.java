package io.github.suppierk.shoostr;

import static io.github.suppierk.shoostr.Locks.getUnderLock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NativeShutdownFailureTest {
  @ParameterizedTest
  @CsvSource({
    "false,false,false,true",
    "false,false,true,true",
    "false,true,false,true",
    "false,true,true,true",
    "true,false,false,true",
    "true,false,true,true",
    "true,true,false,true",
    "true,true,true,true",
    "false,true,true,false",
  })
  void preservesFailuresAndCleanupOrderAcrossNativeAndExtensionShutdown(
      boolean fatalNative, boolean fatalCleanup, boolean fatalSignal, boolean stopFails)
      throws Exception {
    Throwable stopFailure =
        fatalNative
            ? new LinkageError("native stop failed")
            : new IOException("native stop failed");
    Throwable cleanupFailure =
        fatalCleanup ? new LinkageError("extension failed") : new IOException("extension failed");
    var signalFailure = new LinkageError("extension signal failed");
    var order = new ArrayList<String>();
    var stops = new AtomicInteger();
    var nativeServer = new AtomicReference<Server>();
    var app = new Shoostr();
    Thread hook = null;

    try {
      try (Closeable appCleanup = app::close) {
        hook = ownedHook(app);
        app.afterClose(() -> order.add("afterClose"));
        app.modifyServer(server -> {
          nativeServer.set(server);
          ((ServerConnector) server.getConnectors()[0]).setPort(0);
          server.addBean(failingStop(stopFailure, order, stops, stopFails), true);
        });
        app.routes()
            .get(
                "/owned",
                (request, response) -> response.text("ok"),
                lifecycle ->
                    lifecycle.withExtension(
                        failingProvider(fatalSignal, signalFailure, cleanupFailure, order)));
        app.start();

        var failure = assertThrows(Throwable.class, app::close);
        assertFalse(Runtime.getRuntime().removeShutdownHook(hook));

        if (fatalSignal) {
          assertSame(signalFailure, failure);
        } else if (fatalNative) {
          assertSame(stopFailure, failure);
        } else if (fatalCleanup) {
          assertSame(cleanupFailure, failure);
        } else {
          assertEquals(IOException.class, failure.getClass());
          assertSame(stopFailure, failure.getCause());
        }

        if (stopFails) {
          assertTrue(retainsFailure(failure, stopFailure));
        }

        assertTrue(retainsFailure(failure, cleanupFailure));
        assertEquals(List.of("signal", "native", "extension", "afterClose"), order);
        assertEquals(1, stops.get());
        app.close();
        assertEquals(List.of("signal", "native", "extension", "afterClose"), order);
      }
    } finally {
      try {
        var server = nativeServer.get();
        if (server != null) {
          try {
            server.stop();
          } finally {
            server.destroy();
          }
        }
      } finally {
        if (hook != null) {
          Runtime.getRuntime().removeShutdownHook(hook);
        }
      }
    }
  }

  private Thread ownedHook(Shoostr app) throws ReflectiveOperationException {
    var lockField = Shoostr.class.getDeclaredField("lock");
    lockField.setAccessible(true);
    var lock = (ReentrantLock) lockField.get(app);
    return Objects.requireNonNull(
        getUnderLock(
            lock,
            () -> {
              var field = Shoostr.class.getDeclaredField("shutdownHook");
              field.setAccessible(true);
              return (Thread) field.get(app);
            }));
  }

  private AbstractLifeCycle failingStop(
      Throwable failure, List<String> order, AtomicInteger stops, boolean stopFails) {
    return new AbstractLifeCycle() {
      @Override
      protected void doStop() throws Exception {
        if (stops.incrementAndGet() == 1) {
          order.add("native");
          if (!stopFails) {
            return;
          }

          if (failure instanceof Error fatal) {
            throw fatal;
          }

          throw (Exception) failure;
        }
      }
    };
  }

  private RouteExtension<Object> failingProvider(
      boolean fatalSignal, Error signalFailure, Throwable cleanupFailure, List<String> order) {
    return new RouteExtension<>() {
      @Override
      public Object configure(Lifecycle lifecycle, Object inherited) {
        return new Object();
      }

      @Override
      public void beforeStop() {
        order.add("signal");
        if (fatalSignal) {
          throw signalFailure;
        }
      }

      @Override
      public void close() throws IOException {
        order.add("extension");
        if (cleanupFailure instanceof Error fatal) {
          throw fatal;
        }

        throw (IOException) cleanupFailure;
      }
    };
  }

  @SuppressWarnings(
      "ReferenceEquality") // The shutdown contract retains the actual failure objects.
  private boolean retainsFailure(Throwable aggregate, Throwable expected) {
    var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    var pending = new ArrayDeque<Throwable>();
    pending.add(aggregate);

    while (!pending.isEmpty()) {
      var failure = pending.removeFirst();
      if (failure == expected) {
        return true;
      }

      if (!visited.add(failure)) {
        continue;
      }

      if (failure.getCause() != null) {
        pending.add(failure.getCause());
      }

      pending.addAll(List.of(failure.getSuppressed()));
    }

    return false;
  }
}
