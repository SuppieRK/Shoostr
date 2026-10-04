package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Extensions;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Routes;
import io.github.suppierk.shoostr.Shoostr;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class RegistrationDepthTest {
  @Test
  void acceptsTenPathScopesAndAllowsEndpointRegistrationAtTheLimit() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      nested(app.routes(), 10, false, routes -> routes.get("/leaf", (_, _) -> {}));
      app.start();
      assertTrue(app.port() > 0);
    }
  }

  @Test
  void acceptsTenWhenScopes() throws Exception {
    var entered = new AtomicInteger();

    try (var app = new Shoostr()) {
      conditions(app.routes(), 10, entered);
      assertEquals(10, entered.get());
    }
  }

  @Test
  void countsPathAndWhenScopesTogether() throws Exception {
    try (var app = new Shoostr()) {
      nested(
          app.routes(),
          10,
          true,
          routes ->
              assertThrows(IllegalStateException.class, () -> routes.when(() -> true, _ -> {})));
    }
  }

  @Test
  void countsSameThreadGroupingThroughACapturedRoot() throws Exception {
    var entered = new AtomicInteger();

    try (var app = new Shoostr()) {
      assertThrows(IllegalStateException.class, () -> capturedRoot(app.routes(), 11, entered));
      assertEquals(10, entered.get());
    }
  }

  @Test
  void retainsTheDepthOfAnEscapedChildScope() throws Exception {
    var retained = new AtomicReference<Routes>();

    try (var app = new Shoostr()) {
      nested(app.routes(), 10, false, retained::set);
      assertThrows(IllegalStateException.class, () -> retained.get().path("/child", _ -> {}));
    }
  }

  @Test
  void restoresGroupingDepthAfterARegistrationException() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              nested(
                  app.routes(),
                  10,
                  true,
                  _ -> {
                    throw new IllegalArgumentException("registration failed");
                  }));
      nested(app.routes(), 10, true, routes -> routes.get("/recovered", (_, _) -> {}));
      app.start();
      assertTrue(app.port() > 0);
    }
  }

  @Test
  void rejectsExcessDepthBeforeConfigurationOrInheritedProvidersRun() throws Exception {
    var configured = new AtomicInteger();
    var userCalls = new AtomicInteger();
    var provider =
        new Extension<Object>() {
          @Override
          public Object configure(Extensions endpoint, Object inherited) {
            configured.incrementAndGet();
            return new Object();
          }
        };

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.extensions(provider)
          .routes()
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
                                    _ -> userCalls.incrementAndGet(),
                                    _ -> userCalls.incrementAndGet()));
                        assertEquals(10, configured.get());
                      }),
              e -> e.get(provider));
      assertEquals(0, userCalls.get());
      app.routes().get("/recovered", (_, _) -> {});
      app.start();
      assertTrue(app.port() > 0);
    }
  }

  @Test
  void keepsConcurrentRegistrationThreadsDepthIndependent() throws Exception {
    var reached = new CountDownLatch(2);

    try (var app = new Shoostr();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Runnable registration =
          () ->
              nested(
                  app.routes(),
                  10,
                  true,
                  _ -> {
                    reached.countDown();

                    try {
                      assertTrue(reached.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                      Thread.currentThread().interrupt();
                      fail("Registration thread was interrupted", failure);
                    }
                  });
      var first = executor.submit(registration);
      var second = executor.submit(registration);
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void rejectsTheEleventhWhenScopeBeforeItsCallbackRuns() throws Exception {
    var entered = new AtomicInteger();

    try (var app = new Shoostr()) {
      assertThrows(IllegalStateException.class, () -> conditions(app.routes(), 11, entered));
      assertEquals(10, entered.get());
    }
  }

  @Test
  void rejectsTheEleventhPathScopeBeforeItsCallbackRuns() throws Exception {
    var entered = new AtomicInteger();

    try (var app = new Shoostr()) {
      assertThrows(IllegalStateException.class, () -> paths(app.routes(), 11, entered));
      assertEquals(10, entered.get());
    }
  }

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
          _ -> {
            entered.incrementAndGet();
            capturedRoot(root, remaining - 1, entered);
          });
    }
  }
}
