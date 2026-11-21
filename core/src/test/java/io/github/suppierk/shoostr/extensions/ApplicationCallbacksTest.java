package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.ApplicationCallbacks;
import io.github.suppierk.shoostr.ApplicationExtension;
import io.github.suppierk.shoostr.Handler;
import io.github.suppierk.shoostr.Lifecycle;
import io.github.suppierk.shoostr.RequestObservation;
import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApplicationCallbacksTest {
  @SuppressWarnings("java:S9357") // Extension has no abstract methods and cannot be a lambda.
  private static ApplicationExtension<Object> extension(
      Consumer<ApplicationCallbacks> installation) {
    return new ApplicationExtension<Object>() {
      @Override
      public Object configure(Lifecycle lifecycle, Object inherited) {
        return this;
      }

      @Override
      public void install(ApplicationCallbacks callbacks) {
        installation.accept(callbacks);
      }
    };
  }

  private static List<Consumer<ApplicationCallbacks>> registrations() {
    Handler handler = (ignoredRequest, ignoredResponse) -> {};

    return List.of(
        c -> c.onRequestHeaders(handler),
        c -> c.onRouteMatched(handler),
        c -> c.beforeRouteHandler(handler),
        c -> c.afterRouteHandler(handler),
        c -> c.beforeResponseFlush(handler),
        c -> c.afterResponseFlush(handler),
        c -> c.afterRequest(ignoredRequestOutcome -> {}),
        c -> c.observe(ignoredRequest -> ignoredRequestOutcome -> {}));
  }

  @Test
  void exposesOnlyTheEightApprovedRegistrationMethods() {
    var methods =
        Arrays.stream(ApplicationCallbacks.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .collect(Collectors.toSet());

    assertEquals(8, methods.size());
    assertEquals(
        Set.of(
            "onRequestHeaders",
            "onRouteMatched",
            "beforeRouteHandler",
            "afterRouteHandler",
            "beforeResponseFlush",
            "afterResponseFlush",
            "afterRequest",
            "observe"),
        methods.stream().map(Method::getName).collect(Collectors.toSet()));
    assertEquals(0, ApplicationCallbacks.class.getConstructors().length);
    assertTrue(
        methods.stream().allMatch(method -> method.getReturnType() == ApplicationCallbacks.class));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsRetainedRegistrationAfterInstallationFinishes(boolean fail) throws Exception {
    var retained = new AtomicReference<ApplicationCallbacks>();
    var extension =
        createExtensionForRejectsRetainedRegistrationAfterInstallationFinishes(retained, fail);

    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        if (fail) {
          assertThrows(IllegalArgumentException.class, () -> app.withExtension(extension));
        } else {
          app.withExtension(extension);
        }

        var callbacks = retained.get();
        for (var register : registrations()) {
          assertThrows(IllegalStateException.class, () -> register.accept(callbacks));
        }
      }
    }
  }

  private ApplicationExtension<Object>
      createExtensionForRejectsRetainedRegistrationAfterInstallationFinishes(
          AtomicReference<ApplicationCallbacks> retained, boolean fail) {
    return extension(callbacks -> {
      retained.set(callbacks);
      if (fail) {
        throw new IllegalArgumentException("setup failed");
      }
    });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsRetainedRegistrationFromAnotherThread(boolean fail) throws Exception {
    var retained = new AtomicReference<ApplicationCallbacks>();
    var extension = createExtensionForRejectsRetainedRegistrationFromAnotherThread(retained, fail);

    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close;
          var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        if (fail) {
          assertThrows(IllegalArgumentException.class, () -> app.withExtension(extension));
        } else {
          app.withExtension(extension);
        }

        var callbacks = retained.get();
        executor
            .submit(() ->
                assertThrows(
                    IllegalStateException.class,
                    () -> callbacks.beforeRouteHandler((ignored, ignoredResponse) -> {})))
            .get(5, TimeUnit.SECONDS);
      }
    }
  }

  private ApplicationExtension<Object>
      createExtensionForRejectsRetainedRegistrationFromAnotherThread(
          AtomicReference<ApplicationCallbacks> retained, boolean fail) {
    return extension(callbacks -> {
      retained.set(callbacks);
      if (fail) {
        throw new IllegalArgumentException("setup failed");
      }
    });
  }

  @Test
  void rejectsNullCallbacksWhileInstallationIsOpen() throws Exception {
    var extension = createExtensionForRejectsNullCallbacksWhileInstallationIsOpen();

    {
      var app = new Shoostr();

      try (Closeable appCleanup2 = app::close) {
        app.withExtension(extension);
      }
    }
  }

  private ApplicationExtension<Object>
      createExtensionForRejectsNullCallbacksWhileInstallationIsOpen() {
    return extension(c -> {
      assertThrows(NullPointerException.class, () -> c.onRequestHeaders(null));
      assertThrows(NullPointerException.class, () -> c.onRouteMatched(null));
      assertThrows(NullPointerException.class, () -> c.beforeRouteHandler(null));
      assertThrows(NullPointerException.class, () -> c.afterRouteHandler(null));
      assertThrows(NullPointerException.class, () -> c.beforeResponseFlush(null));
      assertThrows(NullPointerException.class, () -> c.afterResponseFlush(null));
      assertThrows(NullPointerException.class, () -> c.afterRequest(null));
      assertThrows(NullPointerException.class, () -> c.observe(null));
    });
  }

  @Test
  void installsHeaderCallbacksEagerlyInWrittenOrder() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    {
      var app = new Shoostr();

      try (Closeable appCleanup3 = app::close) {
        app.onRequestHeaders((ignoredRequest, ignoredResponse) -> events.add("before"));
        app.withExtension(
                extension(c ->
                    assertSame(
                        c,
                        c.onRequestHeaders(
                            (ignoredRequest, ignoredResponse) -> events.add("first")))))
            .withExtension(
                extension(c ->
                    c.onRequestHeaders((ignoredRequest, ignoredResponse) -> events.add("second"))));
        app.onRequestHeaders((ignoredRequest, ignoredResponse) -> events.add("after"));
        app.afterRequest(ignoredRequestOutcome -> completed.countDown());
        app.routes().get("/", (ignoredRequest, response) -> response.text("ok"));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "ok",
              test.send(request -> request.path("/"), HttpResponse.BodyHandlers.ofString()).body());
          assertTrue(completed.await(5, TimeUnit.SECONDS));
          assertEquals(List.of("before", "first", "second", "after"), List.copyOf(events));
        }
      }
    }
  }

  @Test
  void installsMatchedCallbacksEagerlyInWrittenOrder() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    {
      var app = new Shoostr();

      try (Closeable appCleanup4 = app::close) {
        app.onRouteMatched((ignoredRequest, ignoredResponse) -> events.add("before"));
        app.withExtension(
                extension(c ->
                    assertSame(
                        c,
                        c.onRouteMatched(
                            (ignoredRequest, ignoredResponse) -> events.add("first")))))
            .withExtension(
                extension(c ->
                    c.onRouteMatched((ignoredRequest, ignoredResponse) -> events.add("second"))));
        app.onRouteMatched((ignoredRequest, ignoredResponse) -> events.add("after"));
        app.afterRequest(ignoredRequestOutcome -> completed.countDown());
        app.routes().get("/", (ignoredRequest, response) -> response.text("ok"));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "ok",
              test.send(request -> request.path("/"), HttpResponse.BodyHandlers.ofString()).body());
          assertTrue(completed.await(5, TimeUnit.SECONDS));
          assertEquals(List.of("before", "first", "second", "after"), List.copyOf(events));
        }
      }
    }
  }

  @Test
  void installsBeforeHandlerCallbacksEagerlyInWrittenOrder() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    {
      var app = new Shoostr();

      try (Closeable appCleanup5 = app::close) {
        app.beforeRouteHandler((ignoredRequest, ignoredResponse) -> events.add("before"));
        app.withExtension(
                extension(c ->
                    assertSame(
                        c,
                        c.beforeRouteHandler(
                            (ignoredRequest, ignoredResponse) -> events.add("first")))))
            .withExtension(
                extension(c ->
                    c.beforeRouteHandler(
                        (ignoredRequest, ignoredResponse) -> events.add("second"))));
        app.beforeRouteHandler((ignoredRequest, ignoredResponse) -> events.add("after"));
        app.afterRequest(ignoredRequestOutcome -> completed.countDown());
        app.routes().get("/", (ignoredRequest, response) -> response.text("ok"));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "ok",
              test.send(request -> request.path("/"), HttpResponse.BodyHandlers.ofString()).body());
          assertTrue(completed.await(5, TimeUnit.SECONDS));
          assertEquals(List.of("before", "first", "second", "after"), List.copyOf(events));
        }
      }
    }
  }

  @Test
  void installsAfterHandlerCallbacksEagerlyInWrittenOrder() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    {
      var app = new Shoostr();

      try (Closeable appCleanup6 = app::close) {
        app.afterRouteHandler((ignoredRequest, ignoredResponse) -> events.add("before"));
        app.withExtension(
                extension(c ->
                    assertSame(
                        c,
                        c.afterRouteHandler(
                            (ignoredRequest, ignoredResponse) -> events.add("first")))))
            .withExtension(
                extension(c ->
                    c.afterRouteHandler(
                        (ignoredRequest, ignoredResponse) -> events.add("second"))));
        app.afterRouteHandler((ignoredRequest, ignoredResponse) -> events.add("after"));
        app.afterRequest(ignoredRequestOutcome -> completed.countDown());
        app.routes().get("/", (ignoredRequest, response) -> response.text("ok"));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "ok",
              test.send(request -> request.path("/"), HttpResponse.BodyHandlers.ofString()).body());
          assertTrue(completed.await(5, TimeUnit.SECONDS));
          assertEquals(List.of("before", "first", "second", "after"), List.copyOf(events));
        }
      }
    }
  }

  @Test
  void installsBeforeFlushCallbacksEagerlyInWrittenOrder() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    {
      var app = new Shoostr();

      try (Closeable appCleanup7 = app::close) {
        app.beforeResponseFlush((ignoredRequest, ignoredResponse) -> events.add("before"));
        app.withExtension(
                extension(c ->
                    assertSame(
                        c,
                        c.beforeResponseFlush(
                            (ignoredRequest, ignoredResponse) -> events.add("first")))))
            .withExtension(
                extension(c ->
                    c.beforeResponseFlush(
                        (ignoredRequest, ignoredResponse) -> events.add("second"))));
        app.beforeResponseFlush((ignoredRequest, ignoredResponse) -> events.add("after"));
        app.afterRequest(ignoredRequestOutcome -> completed.countDown());
        app.routes().get("/", (ignoredRequest, response) -> response.text("ok"));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "ok",
              test.send(request -> request.path("/"), HttpResponse.BodyHandlers.ofString()).body());
          assertTrue(completed.await(5, TimeUnit.SECONDS));
          assertEquals(List.of("before", "first", "second", "after"), List.copyOf(events));
        }
      }
    }
  }

  @Test
  void installsAfterFlushCallbacksEagerlyInWrittenOrder() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    {
      var app = new Shoostr();

      try (Closeable appCleanup8 = app::close) {
        app.afterResponseFlush((ignoredRequest, ignoredResponse) -> events.add("before"));
        app.withExtension(
                extension(c ->
                    assertSame(
                        c,
                        c.afterResponseFlush(
                            (ignoredRequest, ignoredResponse) -> events.add("first")))))
            .withExtension(
                extension(c ->
                    c.afterResponseFlush(
                        (ignoredRequest, ignoredResponse) -> events.add("second"))));
        app.afterResponseFlush((ignoredRequest, ignoredResponse) -> events.add("after"));
        app.afterRequest(ignoredRequestOutcome -> completed.countDown());
        app.routes().get("/", (ignoredRequest, response) -> response.text("ok"));

        try (var test = TestServer.start(app)) {
          assertEquals(
              "ok",
              test.send(request -> request.path("/"), HttpResponse.BodyHandlers.ofString()).body());
          assertTrue(completed.await(5, TimeUnit.SECONDS));
          assertEquals(List.of("before", "first", "second", "after"), List.copyOf(events));
        }
      }
    }
  }

  @Test
  void isolatesTerminalCollectorFailuresAndContinuesLaterObservers() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    {
      var app = new Shoostr();

      try (Closeable appCleanup9 = app::close) {
        app.withExtension(
                extension(c ->
                    c.afterRequest(ignoredRequestOutcome -> {
                      throw new IllegalStateException("observer failed");
                    })))
            .withExtension(
                extension(c ->
                    assertSame(
                        c, c.afterRequest(ignoredRequestOutcome -> events.add("extension")))));
        app.afterRequest(ignoredRequestOutcome -> {
          events.add("app");
          completed.countDown();
        });

        try (var test = TestServer.start(app)) {
          assertEquals(
              404,
              test.send(request -> request.path("/missing"), HttpResponse.BodyHandlers.discarding())
                  .statusCode());
          assertTrue(completed.await(5, TimeUnit.SECONDS));
          assertEquals(List.of("extension", "app"), List.copyOf(events));
        }
      }
    }
  }

  @Test
  void observesUnmatchedRequestsAndClosesObservationScopes() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    {
      var app = new Shoostr();

      try (Closeable appCleanup10 = app::close) {
        app.withExtension(
            extension(c ->
                assertSame(
                    c,
                    c.observe(ignoredRequest -> {
                      events.add("begin");

                      return new RequestObservation() {
                        @Override
                        public void close() {
                          events.add("close");
                        }

                        @Override
                        public void complete(RequestOutcome outcome) {
                          events.add("complete-" + outcome.statusCode());
                          completed.countDown();
                        }
                      };
                    }))));

        try (var test = TestServer.start(app)) {
          assertEquals(
              404,
              test.send(request -> request.path("/missing"), HttpResponse.BodyHandlers.discarding())
                  .statusCode());
          assertTrue(completed.await(5, TimeUnit.SECONDS));
          assertEquals(List.of("begin", "close", "complete-404"), List.copyOf(events));
        }
      }
    }
  }
}
