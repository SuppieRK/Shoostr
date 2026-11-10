package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.component.LifeCycle.Listener;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApplicationCloseCallbacksTest {
  private static final String BEFORE_CLOSE = "beforeClose";
  private static final String AFTER_CLOSE = "afterClose";

  @ParameterizedTest
  @ValueSource(strings = {BEFORE_CLOSE, AFTER_CLOSE})
  void returnsTheSameApplicationWhenRegisteringCallbacks(String phase) throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        Function<Runnable, Shoostr> register =
            BEFORE_CLOSE.equals(phase) ? app::beforeClose : app::afterClose;

        assertSame(app, register.apply(() -> {}));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {BEFORE_CLOSE, AFTER_CLOSE})
  void rejectsNullCallbackAtRegistration(String phase) throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {
        Consumer<Runnable> register =
            BEFORE_CLOSE.equals(phase) ? app::beforeClose : app::afterClose;

        assertThrows(NullPointerException.class, () -> register.accept(null));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {BEFORE_CLOSE, AFTER_CLOSE})
  void rejectsCallbackRegistrationAfterStartup(String phase) throws Exception {
    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup2 = app::close) {
        app.start();
        Consumer<Runnable> register =
            BEFORE_CLOSE.equals(phase) ? app::beforeClose : app::afterClose;

        assertThrows(IllegalStateException.class, () -> register.accept(() -> {}));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {BEFORE_CLOSE, AFTER_CLOSE})
  void rejectsCallbackRegistrationAfterClosure(String phase) throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup3 = app::close) {
        app.close();
        Consumer<Runnable> register =
            BEFORE_CLOSE.equals(phase) ? app::beforeClose : app::afterClose;

        assertThrows(IllegalStateException.class, () -> register.accept(() -> {}));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {BEFORE_CLOSE, AFTER_CLOSE})
  void invokesCallbackOnTheClosingThread(String phase) throws Exception {
    var closing = Thread.currentThread();
    var invoked = new AtomicReference<Thread>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup4 = app::close) {
        Consumer<Runnable> register =
            BEFORE_CLOSE.equals(phase) ? app::beforeClose : app::afterClose;
        register.accept(() -> invoked.set(Thread.currentThread()));
      }
    }

    assertSame(closing, invoked.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {BEFORE_CLOSE, AFTER_CLOSE})
  void invokesCallbackOnceDespiteRepeatedClose(String phase) throws Exception {
    var invoked = new AtomicInteger();

    {
      var app = new Shoostr();

      try (Closeable appCleanup5 = app::close) {
        Consumer<Runnable> register =
            BEFORE_CLOSE.equals(phase) ? app::beforeClose : app::afterClose;
        register.accept(invoked::incrementAndGet);
        app.close();
        app.close();
      }
    }

    assertEquals(1, invoked.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {BEFORE_CLOSE, AFTER_CLOSE})
  void invokesEveryRegistrationWhenTheCallbackInstanceIsShared(String phase) throws Exception {
    var invoked = new AtomicInteger();
    Runnable callback = invoked::incrementAndGet;

    {
      var app = new Shoostr();

      try (Closeable appCleanup6 = app::close) {
        Consumer<Runnable> register =
            BEFORE_CLOSE.equals(phase) ? app::beforeClose : app::afterClose;
        register.accept(callback);
        register.accept(callback);
      }
    }

    assertEquals(2, invoked.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {BEFORE_CLOSE, AFTER_CLOSE})
  void ignoresRecursiveCloseFromACallback(String phase) throws Exception {
    var invoked = new AtomicInteger();

    {
      var app = new Shoostr();

      try (Closeable appCleanup7 = app::close) {
        Consumer<Runnable> register =
            BEFORE_CLOSE.equals(phase) ? app::beforeClose : app::afterClose;
        register.accept(
            () -> {
              invoked.incrementAndGet();

              assertDoesNotThrow(app::close);
            });
      }
    }

    assertEquals(1, invoked.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {BEFORE_CLOSE, AFTER_CLOSE})
  void continuesOtherCallbacksAfterOneFails(String phase) throws Exception {
    var disposed = new AtomicBoolean();
    var rejected = new IllegalStateException("Cleanup failed");
    Runnable throwing =
        () -> {
          throw rejected;
        };
    Runnable succeeding = () -> disposed.set(true);

    {
      var app = new Shoostr();

      try (Closeable appCleanup8 = app::close) {
        Consumer<Runnable> register =
            BEFORE_CLOSE.equals(phase) ? app::beforeClose : app::afterClose;
        register.accept(BEFORE_CLOSE.equals(phase) ? throwing : succeeding);
        register.accept(BEFORE_CLOSE.equals(phase) ? succeeding : throwing);

        var failure = assertThrows(IOException.class, app::close);
        assertSame(rejected, failure.getCause());
        assertTrue(disposed.get());
      }
    }
  }

  @Test
  void collectsFailuresFromBothCallbackPhases() throws Exception {
    var before = new IllegalStateException("Stop failed");
    var after = new IllegalArgumentException("Dispose failed");

    {
      var app = new Shoostr();

      try (Closeable appCleanup9 = app::close) {
        app.beforeClose(
            () -> {
              throw before;
            });
        app.afterClose(
            () -> {
              throw after;
            });

        var failure = assertThrows(IOException.class, app::close);
        assertSame(before, failure.getCause());
        assertArrayEquals(new Throwable[] {after}, failure.getSuppressed());
      }
    }
  }

  @Test
  void runsCallbacksWhenExtensionInstallationFails() throws Exception {
    var invoked = new ArrayList<String>();
    var rejected = new IllegalStateException("Install failed");

    {
      var app = new Shoostr();

      try (Closeable appCleanup10 = app::close) {
        app.beforeClose(() -> invoked.add("stop"));
        app.afterClose(() -> invoked.add("dispose"));
        var extension = new FailingInstallation(rejected);

        assertSame(
            rejected,
            assertThrows(IllegalStateException.class, () -> app.withExtension(extension)));
      }
    }

    assertEquals(List.of("stop", "dispose"), invoked);
  }

  @Test
  void disposesApplicationResourcesAfterReentrantOwnershipClaimCleanup() throws Exception {
    var invoked = new ArrayList<String>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup11 = app::close) {
        app.afterClose(() -> invoked.add("registry"));
        app.afterClose(() -> invoked.add("client"));
        var extension =
            new ClaimingExtension(
                () -> assertDoesNotThrow(app::close),
                () -> invoked.add("beforeStop"),
                () -> invoked.add("extension"));

        assertThrows(IllegalStateException.class, () -> app.withExtension(extension));
      }
    }

    assertEquals(List.of("beforeStop", "extension", "client", "registry"), invoked);
  }

  @Test
  void preservesClaimCleanupFailuresBeforeDisposingApplicationResources() throws Exception {
    var invoked = new ArrayList<String>();
    var stopping = new IllegalArgumentException("Signal failed");
    var cleanup = new IllegalStateException("Extension cleanup failed");

    {
      var app = new Shoostr();

      try (Closeable appCleanup12 = app::close) {
        app.afterClose(() -> invoked.add("registry"));
        var extension =
            createExtensionForPreservesClaimCleanupFailuresBeforeDisposingApplicationResources(
                app, invoked, stopping, cleanup);

        var failure = assertThrows(IllegalStateException.class, () -> app.withExtension(extension));
        assertArrayEquals(new Throwable[] {stopping, cleanup}, failure.getSuppressed());
      }
    }

    assertEquals(List.of("beforeStop", "extension", "registry"), invoked);
  }

  private ClaimingExtension
      createExtensionForPreservesClaimCleanupFailuresBeforeDisposingApplicationResources(
          Shoostr app,
          ArrayList<String> invoked,
          IllegalArgumentException stopping,
          IllegalStateException cleanup) {
    return new ClaimingExtension(
        () -> assertDoesNotThrow(app::close),
        () -> {
          invoked.add("beforeStop");
          throw stopping;
        },
        () -> {
          invoked.add("extension");
          throw cleanup;
        });
  }

  @Test
  void keepsRejectedClaimCallerOwnedAfterReentrantClosure() throws Exception {
    var invoked = new ArrayList<String>();
    var rejected = new IllegalArgumentException("Claim failed");

    {
      var app = new Shoostr();

      try (Closeable appCleanup13 = app::close) {
        app.afterClose(() -> invoked.add("registry"));
        app.withExtension(
            new ClaimingExtension(
                () -> {}, () -> invoked.add("beforeStop"), () -> invoked.add("extension")));
        var extension =
            createExtensionForKeepsRejectedClaimCallerOwnedAfterReentrantClosure(
                app, rejected, invoked);

        assertSame(
            rejected,
            assertThrows(IllegalArgumentException.class, () -> app.withExtension(extension)));
      }
    }

    assertEquals(List.of("beforeStop", "extension", "registry"), invoked);
  }

  private ClaimingExtension createExtensionForKeepsRejectedClaimCallerOwnedAfterReentrantClosure(
      Shoostr app, IllegalArgumentException rejected, ArrayList<String> invoked) {
    return new ClaimingExtension(
        () -> {
          assertDoesNotThrow(app::close);

          throw rejected;
        },
        () -> invoked.add("rejected beforeStop"),
        () -> invoked.add("rejected extension"));
  }

  @Test
  void preservesInstallationFailureWhenFinalCallbackAlsoFails() throws Exception {
    var invoked = new AtomicInteger();
    var rejected = new IllegalArgumentException("Install failed");
    var cleanup = new IllegalStateException("Application cleanup failed");

    {
      var app = new Shoostr();

      try (Closeable appCleanup14 = app::close) {
        app.afterClose(invoked::incrementAndGet);
        app.afterClose(
            () -> {
              throw cleanup;
            });

        var extension = new FailingInstallation(rejected);

        var failure =
            assertThrows(IllegalArgumentException.class, () -> app.withExtension(extension));
        assertSame(rejected, failure);
        assertEquals(1, failure.getSuppressed().length);
        var callbackFailure = assertInstanceOf(IOException.class, failure.getSuppressed()[0]);
        assertSame(cleanup, callbackFailure.getCause());
      }
    }

    assertEquals(1, invoked.get());
  }

  @Test
  void disposesApplicationResourcesAfterReentrantInstallationCleanup() throws Exception {
    var invoked = new ArrayList<String>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup15 = app::close) {
        app.afterClose(() -> invoked.add("registry"));
        var extension = new ClosingInstallation(app, invoked);

        assertThrows(IllegalStateException.class, () -> app.withExtension(extension));
      }
    }

    assertEquals(List.of("beforeStop", "extension", "registry"), invoked);
  }

  @Test
  void runsCallbacksWhenListenerStartupFails() throws Exception {
    var invoked = new ArrayList<String>();
    var rejected = new IllegalStateException("Start failed");

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup16 = app::close) {
        app.beforeClose(() -> invoked.add("stop"));
        app.afterClose(() -> invoked.add("dispose"));
        app.modifyServer(
            ignored -> {
              throw rejected;
            });

        assertSame(rejected, assertThrows(IllegalStateException.class, app::start));
      }
    }

    assertEquals(List.of("stop", "dispose"), invoked);
  }

  @Test
  void leavesRegisteredCleanupPendingAfterRejectedCompilation() throws Exception {
    var invoked = new AtomicInteger();

    {
      var app = new Shoostr();

      try (Closeable appCleanup17 = app::close) {
        app.afterClose(invoked::incrementAndGet);
        app.routes(ignoredRoutes -> assertThrows(IllegalStateException.class, app::start));

        assertEquals(0, invoked.get());
      }
    }

    assertEquals(1, invoked.get());
  }

  @Test
  void rejectsRouteRegistrationThroughRetainedScopesWhenCloseBegins() throws Exception {
    var verified = new AtomicBoolean();

    {
      var app = new Shoostr();

      try (Closeable appCleanup18 = app::close) {
        var root = app.routes();
        var child = new AtomicReference<Routes>();
        root.path("/child", child::set);
        var nested = child.get();
        app.beforeClose(
            () -> {
              assertAll(
                  () ->
                      assertThrows(
                          IllegalStateException.class,
                          () ->
                              app.routes(
                                  routes ->
                                      routes.get(
                                          "/late", (ignoredRequest, ignoredResponse) -> {}))),
                  () ->
                      assertThrows(
                          IllegalStateException.class,
                          () -> root.get("/late", (ignoredRequest, ignoredResponse) -> {})),
                  () ->
                      assertThrows(
                          IllegalStateException.class,
                          () -> nested.get("/late", (ignoredRequest, ignoredResponse) -> {})));

              verified.set(true);
            });
      }
    }

    assertTrue(verified.get());
  }

  @Test
  void releasesApplicationResourcesAfterShutdownCallbackFails() throws Exception {
    var disposed = new AtomicBoolean();
    var rejected = new IllegalStateException("Application stop failed");

    {
      var app = new Shoostr();

      try (Closeable appCleanup19 = app::close) {
        app.afterClose(() -> disposed.set(true));
        app.beforeClose(
            () -> {
              throw rejected;
            });

        var failure = assertThrows(IOException.class, app::close);
        assertSame(rejected, failure.getCause());
        assertTrue(disposed.get());
      }
    }
  }

  @Test
  void signalsUnstartedApplicationWorkInRegistrationOrder() throws Exception {
    var signalled = new ArrayList<String>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup20 = app::close) {
        app.beforeClose(() -> signalled.add("producer"));
        app.beforeClose(() -> signalled.add("client"));
      }
    }

    assertEquals(List.of("producer", "client"), signalled);
  }

  @Test
  void closesUnstartedApplicationResourcesInReverseRegistrationOrder() throws Exception {
    var closed = new ArrayList<String>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup21 = app::close) {
        app.afterClose(() -> closed.add("registry"));
        app.afterClose(() -> closed.add("client"));
      }
    }

    assertEquals(List.of("client", "registry"), closed);
  }

  @Test
  void stopsOwnedResourcesAndRunsFinalCallbacksBeforeRethrowingABeforeCloseError()
      throws Exception {
    var rejected = assertionFailure("Stop failed");

    var nativeServer = new AtomicReference<Server>();
    var executor = new AtomicReference<ExecutorService>();
    var extensionClosed = new AtomicBoolean();
    var finalCallback = new AtomicBoolean();

    var configuredOptions = new Options();
    configuredOptions.setVirtualThreads(true);

    {
      var app = new Shoostr(configuredOptions);

      try (Closeable appCleanup22 = app::close;
          var client = HttpClient.newHttpClient()) {
        app.modifyServer(
            server -> {
              nativeServer.set(server);
              ((ServerConnector) server.getConnectors()[0]).setPort(0);
              executor.set(
                  (ExecutorService)
                      ((QueuedThreadPool) server.getThreadPool()).getVirtualThreadsExecutor());
            });
        app.withExtension(
            new ClaimingExtension(() -> {}, () -> {}, () -> extensionClosed.set(true)));
        app.afterClose(() -> finalCallback.set(true));
        app.beforeClose(
            () -> {
              throw rejected;
            });
        app.routes(routes -> routes.get("/ready", (request, response) -> response.text("ready")));
        app.start();

        try {
          var request =
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/ready"))
                  .build();

          assertEquals(
              200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
          assertSame(rejected, assertThrows(AssertionError.class, app::close));
          assertTrue(nativeServer.get().isStopped());
          assertTrue(executor.get().isShutdown());
          assertTrue(extensionClosed.get());
          assertTrue(finalCallback.get());
          assertDoesNotThrow(app::close);
        } finally {
          // Retain cleanup for the failing pre-fix reproduction, whose closed guard prevents retry.
          nativeServer.get().stop();
          executor.get().shutdownNow();
        }
      }
    }
  }

  @Test
  void preservesTheFirstErrorAndAllEarlierOrdinaryFailuresAcrossShutdownPhases() throws Exception {
    var events = new ArrayList<String>();
    var early = new IllegalStateException("Earlier callback failure");

    var primary = assertionFailure("Primary callback error");

    var cleanup = new IllegalArgumentException("Earlier cleanup failure");

    var cleanupError = assertionFailure("Cleanup error");
    var finalError = assertionFailure("Final callback error");

    {
      var app = new Shoostr();

      try (Closeable appCleanup23 = app::close) {
        app.withExtension(
                new ClaimingExtension(
                    () -> {},
                    () -> {},
                    () -> {
                      events.add("cleanup-error");
                      throw cleanupError;
                    }))
            .withExtension(
                new ClaimingExtension(
                    () -> {},
                    () -> {},
                    () -> {
                      events.add("cleanup-runtime");
                      throw cleanup;
                    }));
        app.beforeClose(
            () -> {
              events.add("before-runtime");
              throw early;
            });
        app.beforeClose(
            () -> {
              events.add("before-error");
              throw primary;
            });
        app.beforeClose(() -> events.add("before-skipped"));
        app.afterClose(() -> events.add("after-skipped"));
        app.afterClose(
            () -> {
              events.add("after-error");
              throw finalError;
            });

        assertSame(primary, assertThrows(AssertionError.class, app::close));
        assertEquals(
            List.of(
                "before-runtime",
                "before-error",
                "cleanup-runtime",
                "cleanup-error",
                "after-error"),
            events);
        assertEquals(3, primary.getSuppressed().length);
        assertSame(
            early, assertInstanceOf(IOException.class, primary.getSuppressed()[0]).getCause());
        assertSame(cleanupError, primary.getSuppressed()[1]);
        assertSame(finalError, primary.getSuppressed()[2]);
        assertEquals(1, cleanupError.getSuppressed().length);
        assertSame(
            cleanup,
            assertInstanceOf(IOException.class, cleanupError.getSuppressed()[0]).getCause());
        assertDoesNotThrow(app::close);
      }
    }
  }

  @Test
  void doesNotSuppressTheSameErrorInstanceOntoItselfAcrossCallbackPhases() throws Exception {
    var failure = assertionFailure("Shared failure");

    {
      var app = new Shoostr();

      try (Closeable appCleanup24 = app::close) {
        app.beforeClose(
            () -> {
              throw failure;
            });
        app.afterClose(
            () -> {
              throw failure;
            });

        assertSame(failure, assertThrows(AssertionError.class, app::close));
        assertEquals(0, failure.getSuppressed().length);
      }
    }
  }

  @Test
  void retainsAnOrdinaryAggregateOnlyOnceWhenResourceCleanupThrowsError() throws Exception {
    var ordinary = new IllegalStateException("Before callback failure");

    var failure = assertionFailure("Extension close failed");

    {
      var app = new Shoostr();

      try (Closeable appCleanup25 = app::close) {
        app.beforeClose(
            () -> {
              throw ordinary;
            });
        app.withExtension(
            new ClaimingExtension(
                () -> {},
                () -> {},
                () -> {
                  throw failure;
                }));

        assertSame(failure, assertThrows(AssertionError.class, app::close));
        assertEquals(1, failure.getSuppressed().length);
        assertSame(
            ordinary, assertInstanceOf(IOException.class, failure.getSuppressed()[0]).getCause());
      }
    }
  }

  @Test
  void preservesAFatalShutdownFailureOverAnOrdinaryInstallationFailure() throws Exception {
    var installation = new IllegalArgumentException("Install failed");

    var fatal = assertionFailure("Signalling failed");

    var finalized = new AtomicBoolean();

    {
      var app = new Shoostr();

      try (Closeable appCleanup26 = app::close) {
        app.beforeClose(
            () -> {
              throw fatal;
            });
        app.afterClose(() -> finalized.set(true));
        var extension = new FailingInstallation(installation);

        assertSame(fatal, assertThrows(AssertionError.class, () -> app.withExtension(extension)));
        assertArrayEquals(new Throwable[] {installation}, fatal.getSuppressed());
        assertTrue(finalized.get());
      }
    }
  }

  @Test
  @SuppressWarnings("java:S9357") // Extension has no abstract methods and cannot be a lambda.
  void keepsTheInstallationErrorPrimaryWhenShutdownAndFinalCallbacksAlsoFail() throws Exception {
    var installation = assertionFailure("Install failed");
    var shutdown = assertionFailure("Signalling failed");
    var last = assertionFailure("Final cleanup failed");

    {
      var app = new Shoostr();

      try (Closeable appCleanup27 = app::close) {
        app.beforeClose(
            () -> {
              throw shutdown;
            });
        app.afterClose(
            () -> {
              throw last;
            });
        var extension =
            createExtensionForKeepsTheInstallationErrorPrimaryWhenShutdownAndFinalCallbacksAlsoFail(
                installation);

        assertSame(
            installation, assertThrows(AssertionError.class, () -> app.withExtension(extension)));
        assertArrayEquals(new Throwable[] {shutdown, last}, installation.getSuppressed());
      }
    }
  }

  private ApplicationExtension<Object>
      createExtensionForKeepsTheInstallationErrorPrimaryWhenShutdownAndFinalCallbacksAlsoFail(
          AssertionError installation) {
    return new ApplicationExtension<Object>() {
      @Override
      public Object configure(Lifecycle lifecycle, Object inherited) {
        return this;
      }

      @Override
      public void install(ApplicationCallbacks callbacks) {
        throw installation;
      }
    };
  }

  @Test
  void runsDeferredFinalCallbacksAfterReentrantInstallationCleanupThrowsError() throws Exception {
    var events = new ArrayList<String>();

    var fatal = assertionFailure("Signalling failed");

    {
      var app = new Shoostr();

      try (Closeable appCleanup28 = app::close) {
        app.beforeClose(
            () -> {
              throw fatal;
            });
        app.afterClose(() -> events.add("after"));
        var extension =
            createExtensionForRunsDeferredFinalCallbacksAfterReentrantInstallationCleanupThrowsError(
                app, events);

        assertSame(fatal, assertThrows(AssertionError.class, () -> app.withExtension(extension)));
        assertEquals(List.of("extension", "after"), events);
      }
    }
  }

  private ApplicationExtension<Object>
      createExtensionForRunsDeferredFinalCallbacksAfterReentrantInstallationCleanupThrowsError(
          Shoostr app, ArrayList<String> events) {
    return new ApplicationExtension<Object>() {
      @Override
      public Object configure(Lifecycle lifecycle, Object inherited) {
        return this;
      }

      @Override
      public void install(ApplicationCallbacks callbacks) {
        try {
          app.close();
        } catch (IOException failure) {
          throw new UncheckedIOException(failure);
        }
      }

      @Override
      public void close() {
        events.add("extension");
      }
    };
  }

  @Test
  void leavesBorrowedExecutorsAvailableAfterABeforeCloseError() throws Exception {
    var fatal = assertionFailure("Signalling failed");

    try (var borrowed = Executors.newSingleThreadExecutor()) {
      var app = new Shoostr();

      try (Closeable appCleanup29 = app::close) {
        app.modifyServer(server -> ((ServerConnector) server.getConnectors()[0]).setPort(0));
        app.routes(
            routes ->
                routes.get("/borrowed", borrowed, (request, response) -> response.text("ok")));
        app.beforeClose(
            () -> {
              throw fatal;
            });
        app.start();

        assertSame(fatal, assertThrows(AssertionError.class, app::close));
        assertFalse(borrowed.isShutdown());
        assertEquals("available", borrowed.submit(() -> "available").get(2, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void stopsNativeResourcesAfterOrdinaryAndFatalExtensionSignals() throws Exception {
    var before = assertionFailure("Before callback failed");

    var ordinary = new IllegalStateException("Ordinary signal failed");

    var signal = assertionFailure("Fatal signal failed");

    var server = new AtomicReference<Server>();
    var workers = new AtomicReference<ExecutorService>();
    var finalized = new AtomicBoolean();
    var events = new ArrayList<String>();

    var configuredOptions = new Options();
    configuredOptions.setVirtualThreads(true);

    {
      var app = new Shoostr(configuredOptions);

      try (Closeable appCleanup30 = app::close) {
        app.modifyServer(
            nativeServer -> {
              server.set(nativeServer);
              ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0);
              workers.set(
                  (ExecutorService)
                      ((QueuedThreadPool) nativeServer.getThreadPool())
                          .getVirtualThreadsExecutor());
            });
        app.withExtension(
                new ClaimingExtension(
                    () -> {}, () -> events.add("skipped"), () -> events.add("close-first")))
            .withExtension(
                new ClaimingExtension(
                    () -> {},
                    () -> {
                      events.add("fatal");
                      throw signal;
                    },
                    () -> events.add("close-second")))
            .withExtension(
                new ClaimingExtension(
                    () -> {},
                    () -> {
                      events.add("ordinary");
                      throw ordinary;
                    },
                    () -> events.add("close-third")));
        app.beforeClose(
            () -> {
              throw before;
            });
        app.afterClose(() -> finalized.set(true));
        app.start();

        try {
          assertSame(before, assertThrows(AssertionError.class, app::close));
          assertTrue(server.get().isStopped());
          assertTrue(workers.get().isShutdown());
          assertTrue(finalized.get());
          assertEquals(
              List.of("ordinary", "fatal", "close-third", "close-second", "close-first"), events);
          assertArrayEquals(new Throwable[] {signal}, before.getSuppressed());
          assertEquals(1, signal.getSuppressed().length);
          assertSame(
              ordinary, assertInstanceOf(IOException.class, signal.getSuppressed()[0]).getCause());
        } finally {
          server.get().stop();
          workers.get().shutdownNow();
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void retainsNativeStopAndCleanupErrorsWithoutReplacingAnEarlierCallbackError(boolean beforeFails)
      throws Exception {
    var before = assertionFailure("Before callback failed");
    var stopping = assertionFailure("Native stop failed");
    var cleanup = assertionFailure("Extension cleanup failed");

    var server = new AtomicReference<Server>();
    var finalized = new AtomicBoolean();
    var listener =
        createListenerForRetainsNativeStopAndCleanupErrorsWithoutReplacingAnEarlierCallbackError(
            stopping);

    {
      var app = new Shoostr();

      try (Closeable appCleanup31 = app::close) {
        app.modifyServer(
            nativeServer -> {
              server.set(nativeServer);
              ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0);
              nativeServer.addEventListener(listener);
            });
        app.withExtension(
            new ClaimingExtension(
                () -> {},
                () -> {},
                () -> {
                  throw cleanup;
                }));
        if (beforeFails) {
          app.beforeClose(
              () -> {
                throw before;
              });
        }

        app.afterClose(() -> finalized.set(true));
        app.start();

        try {
          var expected = beforeFails ? before : stopping;

          assertSame(expected, assertThrows(AssertionError.class, app::close));
          assertTrue(finalized.get());
          assertArrayEquals(new Throwable[] {cleanup}, stopping.getSuppressed());

          if (beforeFails) {
            assertArrayEquals(new Throwable[] {stopping}, before.getSuppressed());
          }
        } finally {
          server.get().removeEventListener(listener);
          server.get().stop();
        }
      }
    }
  }

  private Listener
      createListenerForRetainsNativeStopAndCleanupErrorsWithoutReplacingAnEarlierCallbackError(
          AssertionError stopping) {
    return new LifeCycle.Listener() {
      @Override
      public void lifeCycleStopping(LifeCycle lifeCycle) {
        throw stopping;
      }
    };
  }

  private static AssertionError assertionFailure(String message) {
    return assertThrows(AssertionError.class, () -> fail(message));
  }

  private record ClaimingExtension(Runnable claim, Runnable stopping, Runnable cleanup)
      implements ApplicationExtension<Object> {

    private ClaimingExtension {
      Objects.requireNonNull(claim);
      Objects.requireNonNull(stopping);
      Objects.requireNonNull(cleanup);
    }

    @Override
    public Object configure(Lifecycle lifecycle, Object inherited) {
      return this;
    }

    @Override
    public void claimOwnership() {
      claim.run();
    }

    @Override
    public void beforeStop() {
      stopping.run();
    }

    @Override
    public void close() {
      cleanup.run();
    }
  }

  private record FailingInstallation(RuntimeException failure)
      implements ApplicationExtension<Object> {

    private FailingInstallation {
      Objects.requireNonNull(failure);
    }

    @Override
    public Object configure(Lifecycle lifecycle, Object inherited) {
      return this;
    }

    @Override
    public void install(ApplicationCallbacks callbacks) {
      throw failure;
    }
  }

  private record ClosingInstallation(Shoostr app, List<String> invoked)
      implements ApplicationExtension<Object> {

    private ClosingInstallation {
      Objects.requireNonNull(app);
      Objects.requireNonNull(invoked);
    }

    @Override
    public Object configure(Lifecycle lifecycle, Object inherited) {
      return this;
    }

    @Override
    public void install(ApplicationCallbacks callbacks) {
      assertDoesNotThrow(app::close);
    }

    @Override
    public void beforeStop() {
      invoked.add("beforeStop");
    }

    @Override
    public void close() {
      invoked.add("extension");
    }
  }
}
