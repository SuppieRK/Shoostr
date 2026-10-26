package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.ApplicationCallbacks;
import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Handler;
import io.github.suppierk.shoostr.RequestObservation;
import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
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
        extension(
            callbacks -> {
              retained.set(callbacks);
              if (fail) {
                throw new IllegalArgumentException("setup failed");
              }
            });

    try (var app = new Shoostr()) {
      if (fail) {
        assertThrows(IllegalArgumentException.class, () -> app.extensions(extension));
      } else {
        app.extensions(extension);
      }

      var callbacks = retained.get();
      for (var register : registrations()) {
        assertThrows(IllegalStateException.class, () -> register.accept(callbacks));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsRetainedRegistrationFromAnotherThread(boolean fail) throws Exception {
    var retained = new AtomicReference<ApplicationCallbacks>();
    var extension =
        extension(
            callbacks -> {
              retained.set(callbacks);
              if (fail) {
                throw new IllegalArgumentException("setup failed");
              }
            });

    try (var app = new Shoostr();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      if (fail) {
        assertThrows(IllegalArgumentException.class, () -> app.extensions(extension));
      } else {
        app.extensions(extension);
      }

      var callbacks = retained.get();
      executor
          .submit(
              () ->
                  assertThrows(
                      IllegalStateException.class,
                      () -> callbacks.beforeRouteHandler((ignored, ignored2) -> {})))
          .get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void rejectsNullCallbacksWhileInstallationIsOpen() throws Exception {
    var extension =
        extension(
            c -> {
              assertThrows(NullPointerException.class, () -> c.onRequestHeaders(null));
              assertThrows(NullPointerException.class, () -> c.onRouteMatched(null));
              assertThrows(NullPointerException.class, () -> c.beforeRouteHandler(null));
              assertThrows(NullPointerException.class, () -> c.afterRouteHandler(null));
              assertThrows(NullPointerException.class, () -> c.beforeResponseFlush(null));
              assertThrows(NullPointerException.class, () -> c.afterResponseFlush(null));
              assertThrows(NullPointerException.class, () -> c.afterRequest(null));
              assertThrows(NullPointerException.class, () -> c.observe(null));
            });

    try (var app = new Shoostr()) {
      app.extensions(extension);
    }
  }

  @Test
  void installsHeaderCallbacksEagerlyInWrittenOrder() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    try (var app = new Shoostr()) {
      app.onRequestHeaders((ignored3, ignored4) -> events.add("before"));
      app.extensions(
          extension(
              c -> assertSame(c, c.onRequestHeaders((ignored5, ignored6) -> events.add("first")))),
          extension(c -> c.onRequestHeaders((ignored7, ignored8) -> events.add("second"))));
      app.onRequestHeaders((ignored9, ignored10) -> events.add("after"));
      app.afterRequest(ignored11 -> completed.countDown());
      app.routes().get("/", (ignored12, response) -> response.text("ok"));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "ok",
            test.send(request -> request.path("/"), HttpResponse.BodyHandlers.ofString()).body());
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("before", "first", "second", "after"), List.copyOf(events));
      }
    }
  }

  @Test
  void installsMatchedCallbacksEagerlyInWrittenOrder() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    try (var app = new Shoostr()) {
      app.onRouteMatched((ignored13, ignored14) -> events.add("before"));
      app.extensions(
          extension(
              c -> assertSame(c, c.onRouteMatched((ignored15, ignored16) -> events.add("first")))),
          extension(c -> c.onRouteMatched((ignored17, ignored18) -> events.add("second"))));
      app.onRouteMatched((ignored19, ignored20) -> events.add("after"));
      app.afterRequest(ignored21 -> completed.countDown());
      app.routes().get("/", (ignored22, response) -> response.text("ok"));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "ok",
            test.send(request -> request.path("/"), HttpResponse.BodyHandlers.ofString()).body());
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("before", "first", "second", "after"), List.copyOf(events));
      }
    }
  }

  @Test
  void installsBeforeHandlerCallbacksEagerlyInWrittenOrder() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    try (var app = new Shoostr()) {
      app.beforeRouteHandler((ignored23, ignored24) -> events.add("before"));
      app.extensions(
          extension(
              c ->
                  assertSame(
                      c, c.beforeRouteHandler((ignored25, ignored26) -> events.add("first")))),
          extension(c -> c.beforeRouteHandler((ignored27, ignored28) -> events.add("second"))));
      app.beforeRouteHandler((ignored29, ignored30) -> events.add("after"));
      app.afterRequest(ignored31 -> completed.countDown());
      app.routes().get("/", (ignored32, response) -> response.text("ok"));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "ok",
            test.send(request -> request.path("/"), HttpResponse.BodyHandlers.ofString()).body());
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("before", "first", "second", "after"), List.copyOf(events));
      }
    }
  }

  @Test
  void installsAfterHandlerCallbacksEagerlyInWrittenOrder() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    try (var app = new Shoostr()) {
      app.afterRouteHandler((ignored33, ignored34) -> events.add("before"));
      app.extensions(
          extension(
              c ->
                  assertSame(
                      c, c.afterRouteHandler((ignored35, ignored36) -> events.add("first")))),
          extension(c -> c.afterRouteHandler((ignored37, ignored38) -> events.add("second"))));
      app.afterRouteHandler((ignored39, ignored40) -> events.add("after"));
      app.afterRequest(ignored41 -> completed.countDown());
      app.routes().get("/", (ignored42, response) -> response.text("ok"));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "ok",
            test.send(request -> request.path("/"), HttpResponse.BodyHandlers.ofString()).body());
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("before", "first", "second", "after"), List.copyOf(events));
      }
    }
  }

  @Test
  void installsBeforeFlushCallbacksEagerlyInWrittenOrder() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    try (var app = new Shoostr()) {
      app.beforeResponseFlush((ignored43, ignored44) -> events.add("before"));
      app.extensions(
          extension(
              c ->
                  assertSame(
                      c, c.beforeResponseFlush((ignored45, ignored46) -> events.add("first")))),
          extension(c -> c.beforeResponseFlush((ignored47, ignored48) -> events.add("second"))));
      app.beforeResponseFlush((ignored49, ignored50) -> events.add("after"));
      app.afterRequest(ignored51 -> completed.countDown());
      app.routes().get("/", (ignored52, response) -> response.text("ok"));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "ok",
            test.send(request -> request.path("/"), HttpResponse.BodyHandlers.ofString()).body());
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("before", "first", "second", "after"), List.copyOf(events));
      }
    }
  }

  @Test
  void installsAfterFlushCallbacksEagerlyInWrittenOrder() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    try (var app = new Shoostr()) {
      app.afterResponseFlush((ignored53, ignored54) -> events.add("before"));
      app.extensions(
          extension(
              c ->
                  assertSame(
                      c, c.afterResponseFlush((ignored55, ignored56) -> events.add("first")))),
          extension(c -> c.afterResponseFlush((ignored57, ignored58) -> events.add("second"))));
      app.afterResponseFlush((ignored59, ignored60) -> events.add("after"));
      app.afterRequest(ignored61 -> completed.countDown());
      app.routes().get("/", (ignored62, response) -> response.text("ok"));

      try (var test = TestServer.start(app)) {
        assertEquals(
            "ok",
            test.send(request -> request.path("/"), HttpResponse.BodyHandlers.ofString()).body());
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("before", "first", "second", "after"), List.copyOf(events));
      }
    }
  }

  @Test
  void isolatesTerminalCollectorFailuresAndContinuesLaterObservers() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    try (var app = new Shoostr()) {
      app.extensions(
          extension(
              c ->
                  c.afterRequest(
                      ignored63 -> {
                        throw new IllegalStateException("observer failed");
                      })),
          extension(c -> c.afterRequest(ignored64 -> events.add("extension"))));
      app.afterRequest(
          ignored65 -> {
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

  @Test
  void observesUnmatchedRequestsAndClosesObservationScopes() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    try (var app = new Shoostr()) {
      app.extensions(
          extension(
              c ->
                  c.observe(
                      ignored66 -> {
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
                      })));

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

  @SuppressWarnings("java:S9357") // Extension has no abstract methods and cannot be a lambda.
  private static Extension<Void> extension(Consumer<ApplicationCallbacks> installation) {
    return new Extension<>() {
      @Override
      public void install(ApplicationCallbacks callbacks) {
        installation.accept(callbacks);
      }
    };
  }

  private static List<Consumer<ApplicationCallbacks>> registrations() {
    Handler handler = (ignored67, ignored68) -> {};
    return List.of(
        c -> c.onRequestHeaders(handler),
        c -> c.onRouteMatched(handler),
        c -> c.beforeRouteHandler(handler),
        c -> c.afterRouteHandler(handler),
        c -> c.beforeResponseFlush(handler),
        c -> c.afterResponseFlush(handler),
        c -> c.afterRequest(ignored69 -> {}),
        c -> c.observe(ignored70 -> ignored71 -> {}));
  }
}
