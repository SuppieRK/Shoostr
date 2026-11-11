package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Lifecycle;
import io.github.suppierk.shoostr.Routes;
import io.github.suppierk.shoostr.Shoostr;
import java.io.Closeable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;

class RegistrationDepthTest {
  private static void paths(Routes routes, int remaining, AtomicInteger entered) {
    if (remaining == 0) {
      return;
    }

    routes.path(
        "/level",
        child -> {
          entered.incrementAndGet();
          paths(child, remaining - 1, entered);
        });
  }

  private static void conditions(Routes routes, int remaining, AtomicInteger entered) {
    if (remaining == 0) {
      return;
    }

    routes.when(
        () -> true,
        child -> {
          entered.incrementAndGet();
          conditions(child, remaining - 1, entered);
        });
  }

  private static void nested(Routes routes, int remaining, boolean mixed, Consumer<Routes> leaf) {
    if (remaining == 0) {
      leaf.accept(routes);
    } else if (mixed && remaining % 2 == 0) {
      routes.when(() -> true, child -> nested(child, remaining - 1, true, leaf));
    } else {
      routes.path("/", child -> nested(child, remaining - 1, mixed, leaf));
    }
  }

  private static void capturedRoot(Routes root, int remaining, AtomicInteger entered) {
    if (remaining > 0) {
      root.path(
          "/",
          ignoredRoutes -> {
            entered.incrementAndGet();
            capturedRoot(root, remaining - 1, entered);
          });
    }
  }

  @Test
  void acceptsTenPathScopesAndAllowsEndpointRegistrationAtTheLimit() throws Exception {
    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup = app::close) {
        nested(
            app.routes(),
            10,
            false,
            routes -> routes.get("/leaf", (ignored, ignoredResponse) -> {}));
        app.start();

        assertTrue(app.port() > 0);
      }
    }
  }

  @Test
  void acceptsTenWhenScopes() throws Exception {
    var entered = new AtomicInteger();

    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {
        conditions(app.routes(), 10, entered);

        assertEquals(10, entered.get());
      }
    }
  }

  @Test
  void countsPathAndWhenScopesTogether() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup2 = app::close) {
        nested(
            app.routes(),
            10,
            true,
            routes ->
                assertThrows(
                    IllegalStateException.class,
                    () -> routes.when(() -> true, ignoredRoutes -> {})));
      }
    }
  }

  @Test
  void countsSameThreadGroupingThroughCapturedRoot() throws Exception {
    var entered = new AtomicInteger();

    {
      var app = new Shoostr();

      try (Closeable appCleanup3 = app::close) {
        var routes = app.routes();

        assertThrows(IllegalStateException.class, () -> capturedRoot(routes, 11, entered));
        assertEquals(10, entered.get());
      }
    }
  }

  @Test
  void retainsTheDepthOfAnEscapedChildScope() throws Exception {
    var retained = new AtomicReference<Routes>();

    {
      var app = new Shoostr();

      try (Closeable appCleanup4 = app::close) {
        nested(app.routes(), 10, false, retained::set);
        var child = retained.get();

        assertThrows(IllegalStateException.class, () -> child.path("/child", ignoredRoutes -> {}));
      }
    }
  }

  @Test
  void restoresGroupingDepthAfterRegistrationException() throws Exception {
    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup5 = app::close) {
        var routes = app.routes();

        assertThrows(
            IllegalArgumentException.class,
            () ->
                nested(
                    routes,
                    10,
                    true,
                    ignoredRoutes -> {
                      throw new IllegalArgumentException("registration failed");
                    }));

        nested(
            routes,
            10,
            true,
            child -> child.get("/recovered", (ignoredRequest, ignoredResponse) -> {}));
        app.start();

        assertTrue(app.port() > 0);
      }
    }
  }

  @Test
  @SuppressWarnings("java:S9357")
  // Extension has no abstract methods and cannot be a lambda.
  void rejectsExcessDepthBeforeConfigurationOrInheritedProvidersRun() throws Exception {
    var configured = new AtomicInteger();
    var userCalls = new AtomicInteger();
    var provider = countingProvider(configured);

    {
      var app =
          new Shoostr()
              .modifyServer(
                  nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));

      try (Closeable appCleanup6 = app::close) {
        app.routes()
            .path(
                "/",
                child ->
                    nested(
                        child,
                        9,
                        false,
                        limit -> {
                          assertEquals(10, configured.get());
                          assertThrows(
                              IllegalStateException.class,
                              () ->
                                  limit.path(
                                      "/blocked",
                                      ignoredRoutes -> userCalls.incrementAndGet(),
                                      ignoredLifecycle -> userCalls.incrementAndGet()));
                          assertEquals(10, configured.get());
                        }),
                lifecycle -> lifecycle.withExtension(provider));

        assertEquals(0, userCalls.get());

        app.routes().get("/recovered", (ignoredRequest, ignoredResponse) -> {});
        app.start();

        assertTrue(app.port() > 0);
      }
    }
  }

  private TestExtension<Object> countingProvider(AtomicInteger configured) {
    return new TestExtension<>() {
      @Override
      public Object configure(Lifecycle endpoint, Object inherited) {
        configured.incrementAndGet();

        return new Object();
      }
    };
  }

  @Test
  void keepsConcurrentRegistrationThreadsDepthIndependent() throws Exception {
    var reached = new CountDownLatch(2);

    {
      var app = new Shoostr();

      try (Closeable appCleanup7 = app::close;
          var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        Runnable registration = nestedRegistration(app, reached);
        var first = executor.submit(registration);
        var second = executor.submit(registration);
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
      }
    }
  }

  private Runnable nestedRegistration(Shoostr app, CountDownLatch reached) {
    return () ->
        nested(
            app.routes(),
            10,
            true,
            ignoredRoutes -> {
              reached.countDown();

              assertDoesNotThrow(() -> assertTrue(reached.await(5, TimeUnit.SECONDS)));
            });
  }

  @Test
  void rejectsTheEleventhWhenScopeBeforeItsCallbackRuns() throws Exception {
    var entered = new AtomicInteger();

    {
      var app = new Shoostr();

      try (Closeable appCleanup8 = app::close) {
        var routes = app.routes();

        assertThrows(IllegalStateException.class, () -> conditions(routes, 11, entered));
        assertEquals(10, entered.get());
      }
    }
  }

  @Test
  void rejectsTheEleventhPathScopeBeforeItsCallbackRuns() throws Exception {
    var entered = new AtomicInteger();

    {
      var app = new Shoostr();

      try (Closeable appCleanup9 = app::close) {
        var routes = app.routes();

        assertThrows(IllegalStateException.class, () -> paths(routes, 11, entered));
        assertEquals(10, entered.get());
      }
    }
  }
}
