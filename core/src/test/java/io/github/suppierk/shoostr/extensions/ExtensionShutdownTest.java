package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.ApplicationCallbacks;
import io.github.suppierk.shoostr.ApplicationExtension;
import io.github.suppierk.shoostr.Lifecycle;
import io.github.suppierk.shoostr.RequestObservation;
import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.Test;

class ExtensionShutdownTest {
  private static ApplicationExtension<Object> capability(String name, List<String> events) {
    return new ApplicationExtension<Object>() {
      @Override
      public Object configure(Lifecycle lifecycle, Object inherited) {
        return this;
      }

      @Override
      public void beforeStop() {
        events.add("before-" + name);
      }

      @Override
      public void close() {
        events.add("close-" + name);
      }
    };
  }

  @Test
  void keepsOwnedResourcesUsableForDrainingHandlersAndTheirObservations() throws Exception {
    var entered = new CountDownLatch(1);
    var signalled = new CountDownLatch(1);
    var stopping = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var scopeUsedResource = new CountDownLatch(1);
    var completionUsedResource = new CountDownLatch(1);
    var disposals = new AtomicInteger();

    try (var resource = Executors.newSingleThreadExecutor();
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        app.withExtension(
            new ApplicationExtension<Object>() {
              @Override
              public Object configure(Lifecycle lifecycle, Object inherited) {
                return this;
              }

              @Override
              public void install(ApplicationCallbacks callbacks) {
                callbacks.observe(ignored ->
                    new RequestObservation() {
                      @Override
                      public void close() {
                        resource.execute(scopeUsedResource::countDown);
                      }

                      @Override
                      public void complete(RequestOutcome outcome) {
                        resource.execute(completionUsedResource::countDown);
                      }
                    });
              }

              @Override
              public void beforeStop() {
                signalled.countDown();
              }

              @Override
              public void close() {
                disposals.incrementAndGet();
                resource.close();
              }
            });
        app.modifyServer(server ->
            server.addEventListener(
                new LifeCycle.Listener() {
                  @Override
                  public void lifeCycleStopping(LifeCycle event) {
                    stopping.countDown();
                  }
                }));
        app.routes()
            .get(
                "/drain",
                (ignoredRequest, response) -> {
                  entered.countDown();

                  assertTrue(release.await(5, TimeUnit.SECONDS));

                  response.body(
                      "text/plain",
                      resource
                          .submit(() -> "resource available")
                          .get(5, TimeUnit.SECONDS)
                          .getBytes(StandardCharsets.UTF_8));
                });

        try (var test = TestServer.start(app)) {
          var request = workers.submit(() -> test.send(builder -> builder.path("/drain")));

          try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            final var closing = submitApplicationClose(workers, app);

            assertTrue(signalled.await(5, TimeUnit.SECONDS));
            assertTrue(stopping.await(5, TimeUnit.SECONDS));
            assertEquals(0, disposals.get());

            release.countDown();
            var response = request.get(5, TimeUnit.SECONDS);

            assertEquals(200, response.statusCode());
            assertEquals("resource available", new String(response.body(), StandardCharsets.UTF_8));

            closing.get(5, TimeUnit.SECONDS);

            assertTrue(scopeUsedResource.await(5, TimeUnit.SECONDS));
            assertTrue(completionUsedResource.await(5, TimeUnit.SECONDS));
            assertEquals(1, disposals.get());
            assertTrue(resource.isShutdown());
          } finally {
            release.countDown();
          }
        }
      }
    }
  }

  private Future<Object> submitApplicationClose(ExecutorService workers, Shoostr app) {
    return workers.submit(() -> {
      app.close();

      return null;
    });
  }

  @Test
  void signalsExtensionsBeforeNativeStopAndDisposesThemAfterward() throws Exception {
    var events = new CopyOnWriteArrayList<String>();
    var app =
        new Shoostr()
            .modifyServer(
                nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));
    app.withExtension(capability("first", events)).withExtension(capability("second", events));
    app.modifyServer(server ->
        server.addEventListener(
            new LifeCycle.Listener() {
              @Override
              public void lifeCycleStopping(LifeCycle event) {
                events.add("server");
              }
            }));

    try (Closeable appCleanup1 = app::close) {
      app.start();
      app.close();

      assertEquals(
          List.of("before-second", "before-first", "server", "close-second", "close-first"),
          events);
    }
  }

  @Test
  void continuesNativeStopAndCleanupAfterOneShutdownSignalThrows() throws Exception {
    var events = new CopyOnWriteArrayList<String>();
    var rejection = new IllegalStateException("signal failed");
    ApplicationExtension<Object> broken = signalFailingProvider(events, rejection);

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup2 = app::close) {
        app.withExtension(capability("first", events)).withExtension(broken);
        app.modifyServer(server ->
            server.addEventListener(
                new LifeCycle.Listener() {
                  @Override
                  public void lifeCycleStopping(LifeCycle event) {
                    events.add("server");
                  }
                }));
        app.start();

        var failure = assertThrows(IOException.class, app::close);
        assertSame(rejection, failure.getCause());
        assertEquals(
            List.of("before-broken", "before-first", "server", "close-broken", "close-first"),
            events);
      }
    }
  }

  private ApplicationExtension<Object> signalFailingProvider(
      CopyOnWriteArrayList<String> events, IllegalStateException rejection) {
    return new ApplicationExtension<Object>() {
      @Override
      public Object configure(Lifecycle lifecycle, Object inherited) {
        return this;
      }

      @Override
      public void beforeStop() {
        events.add("before-broken");
        throw rejection;
      }

      @Override
      public void close() {
        events.add("close-broken");
      }
    };
  }

  @Test
  void signalsClaimedProvidersAfterSetupFailsWithoutClaimingLaterOnes() throws Exception {
    var events = new CopyOnWriteArrayList<String>();
    var setupFailure = new IllegalArgumentException("setup failed");
    ApplicationExtension<Object> broken = setupFailingProvider(setupFailure, events);

    {
      var app = new Shoostr();

      try (Closeable appCleanup3 = app::close) {
        var first = capability("first", events);
        var last = capability("last", events);

        assertSame(
            setupFailure,
            assertThrows(
                IllegalArgumentException.class,
                () -> app.withExtension(first).withExtension(broken).withExtension(last)));
        assertEquals(
            List.of("before-broken", "before-first", "close-broken", "close-first"), events);
      }
    }
  }

  private ApplicationExtension<Object> setupFailingProvider(
      IllegalArgumentException setupFailure, CopyOnWriteArrayList<String> events) {
    return new ApplicationExtension<Object>() {
      @Override
      public Object configure(Lifecycle lifecycle, Object inherited) {
        return this;
      }

      @Override
      public void install(ApplicationCallbacks callbacks) {
        throw setupFailure;
      }

      @Override
      public void beforeStop() {
        events.add("before-broken");
      }

      @Override
      public void close() {
        events.add("close-broken");
      }
    };
  }

  @Test
  void signalsProvidersAfterStartupPreparationFails() throws Exception {
    var events = new CopyOnWriteArrayList<String>();
    ApplicationExtension<Object> broken = preparationFailingProvider(events);

    {
      var app = new Shoostr();

      try (Closeable appCleanup4 = app::close) {
        app.withExtension(broken);

        assertThrows(IllegalArgumentException.class, app::start);
        assertEquals(List.of("before", "close"), events);
      }
    }
  }

  private ApplicationExtension<Object> preparationFailingProvider(
      CopyOnWriteArrayList<String> events) {
    return new ApplicationExtension<Object>() {
      @Override
      public Object configure(Lifecycle lifecycle, Object inherited) {
        return this;
      }

      @Override
      public void beforeStart() {
        throw new IllegalArgumentException("startup failed");
      }

      @Override
      public void beforeStop() {
        events.add("before");
      }

      @Override
      public void close() {
        events.add("close");
      }
    };
  }

  @Test
  void signalsSuccessfullyClaimedProviderEvenIfItsClaimClosesTheApp() throws Exception {
    var events = new CopyOnWriteArrayList<String>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup5 = app::close) {
        ApplicationExtension<Object> capability = closingClaimProvider(app, events);

        assertThrows(IllegalStateException.class, () -> app.withExtension(capability));
        assertEquals(List.of("before", "close"), events);
      }
    }
  }

  private ApplicationExtension<Object> closingClaimProvider(
      Shoostr app, CopyOnWriteArrayList<String> events) {
    return new ApplicationExtension<Object>() {
      @Override
      public Object configure(Lifecycle lifecycle, Object inherited) {
        return this;
      }

      @Override
      public void claimOwnership() {
        assertDoesNotThrow(app::close);
      }

      @Override
      public void beforeStop() {
        events.add("before");
      }

      @Override
      public void close() {
        events.add("close");
      }
    };
  }

  @Test
  void reentrantCloseDoesNotSignalTheProviderTwice() throws Exception {
    var signals = new AtomicInteger();
    var disposals = new AtomicInteger();

    {
      var app = new Shoostr();

      try (Closeable appCleanup6 = app::close) {
        app.withExtension(
            new ApplicationExtension<Object>() {
              @Override
              public Object configure(Lifecycle lifecycle, Object inherited) {
                return this;
              }

              @Override
              public void beforeStop() {
                signals.incrementAndGet();

                assertDoesNotThrow(app::close);
              }

              @Override
              public void close() {
                disposals.incrementAndGet();
              }
            });
        app.close();
        app.close();

        assertEquals(1, signals.get());
        assertEquals(1, disposals.get());
      }
    }
  }

  @Test
  void concurrentCloseDoesNotSignalTheProviderTwice() throws Exception {
    var signals = new AtomicInteger();
    var entered = new CountDownLatch(1);
    var secondAttempt = new CountDownLatch(1);
    var release = new CountDownLatch(1);

    {
      var app = new Shoostr();

      try (Closeable appCleanup7 = app::close;
          var workers = Executors.newVirtualThreadPerTaskExecutor()) {
        class WaitingShutdown implements ApplicationExtension<Object> {
          @Override
          public Object configure(Lifecycle lifecycle, Object inherited) {
            return this;
          }

          @Override
          public void beforeStop() {
            signals.incrementAndGet();
            entered.countDown();

            try {
              assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(error);
            }
          }
        }

        app.withExtension(new WaitingShutdown());
        var first = submitFirstClose(workers, app);

        try {
          assertTrue(entered.await(5, TimeUnit.SECONDS));

          final var second = submitSecondClose(workers, secondAttempt, app);

          assertTrue(secondAttempt.await(5, TimeUnit.SECONDS));

          release.countDown();
          first.get(5, TimeUnit.SECONDS);
          second.get(5, TimeUnit.SECONDS);

          assertEquals(1, signals.get());
        } finally {
          release.countDown();
        }
      }
    }
  }

  private Future<Object> submitFirstClose(ExecutorService workers, Shoostr app) {
    return workers.submit(() -> {
      app.close();

      return null;
    });
  }

  private Future<Object> submitSecondClose(
      ExecutorService workers, CountDownLatch secondAttempt, Shoostr app) {
    return workers.submit(() -> {
      secondAttempt.countDown();
      app.close();

      return null;
    });
  }
}
