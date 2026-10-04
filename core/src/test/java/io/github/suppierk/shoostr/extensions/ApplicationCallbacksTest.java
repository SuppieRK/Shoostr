package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.ApplicationCallbacks;
import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Handler;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.RequestObservation;
import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Shoostr;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
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
        methods.stream().map(java.lang.reflect.Method::getName).collect(Collectors.toSet()));
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
                      () -> callbacks.beforeRouteHandler((_, _) -> {})))
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

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.onRequestHeaders((_, _) -> events.add("before"));
      app.extensions(
          extension(c -> assertSame(c, c.onRequestHeaders((_, _) -> events.add("first")))),
          extension(c -> c.onRequestHeaders((_, _) -> events.add("second"))));
      app.onRequestHeaders((_, _) -> events.add("after"));
      app.afterRequest(_ -> completed.countDown());
      app.routes().get("/", (_, response) -> response.text("ok"));
      app.start();
      assertEquals(
          "ok",
          client
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertTrue(completed.await(5, TimeUnit.SECONDS));
      assertEquals(List.of("before", "first", "second", "after"), List.copyOf(events));
    }
  }

  @Test
  void installsMatchedCallbacksEagerlyInWrittenOrder() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.onRouteMatched((_, _) -> events.add("before"));
      app.extensions(
          extension(c -> assertSame(c, c.onRouteMatched((_, _) -> events.add("first")))),
          extension(c -> c.onRouteMatched((_, _) -> events.add("second"))));
      app.onRouteMatched((_, _) -> events.add("after"));
      app.afterRequest(_ -> completed.countDown());
      app.routes().get("/", (_, response) -> response.text("ok"));
      app.start();
      assertEquals(
          "ok",
          client
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertTrue(completed.await(5, TimeUnit.SECONDS));
      assertEquals(List.of("before", "first", "second", "after"), List.copyOf(events));
    }
  }

  @Test
  void installsBeforeHandlerCallbacksEagerlyInWrittenOrder() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.beforeRouteHandler((_, _) -> events.add("before"));
      app.extensions(
          extension(c -> assertSame(c, c.beforeRouteHandler((_, _) -> events.add("first")))),
          extension(c -> c.beforeRouteHandler((_, _) -> events.add("second"))));
      app.beforeRouteHandler((_, _) -> events.add("after"));
      app.afterRequest(_ -> completed.countDown());
      app.routes().get("/", (_, response) -> response.text("ok"));
      app.start();
      assertEquals(
          "ok",
          client
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertTrue(completed.await(5, TimeUnit.SECONDS));
      assertEquals(List.of("before", "first", "second", "after"), List.copyOf(events));
    }
  }

  @Test
  void installsAfterHandlerCallbacksEagerlyInWrittenOrder() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.afterRouteHandler((_, _) -> events.add("before"));
      app.extensions(
          extension(c -> assertSame(c, c.afterRouteHandler((_, _) -> events.add("first")))),
          extension(c -> c.afterRouteHandler((_, _) -> events.add("second"))));
      app.afterRouteHandler((_, _) -> events.add("after"));
      app.afterRequest(_ -> completed.countDown());
      app.routes().get("/", (_, response) -> response.text("ok"));
      app.start();
      assertEquals(
          "ok",
          client
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertTrue(completed.await(5, TimeUnit.SECONDS));
      assertEquals(List.of("before", "first", "second", "after"), List.copyOf(events));
    }
  }

  @Test
  void installsBeforeFlushCallbacksEagerlyInWrittenOrder() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.beforeResponseFlush((_, _) -> events.add("before"));
      app.extensions(
          extension(c -> assertSame(c, c.beforeResponseFlush((_, _) -> events.add("first")))),
          extension(c -> c.beforeResponseFlush((_, _) -> events.add("second"))));
      app.beforeResponseFlush((_, _) -> events.add("after"));
      app.afterRequest(_ -> completed.countDown());
      app.routes().get("/", (_, response) -> response.text("ok"));
      app.start();
      assertEquals(
          "ok",
          client
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertTrue(completed.await(5, TimeUnit.SECONDS));
      assertEquals(List.of("before", "first", "second", "after"), List.copyOf(events));
    }
  }

  @Test
  void installsAfterFlushCallbacksEagerlyInWrittenOrder() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.afterResponseFlush((_, _) -> events.add("before"));
      app.extensions(
          extension(c -> assertSame(c, c.afterResponseFlush((_, _) -> events.add("first")))),
          extension(c -> c.afterResponseFlush((_, _) -> events.add("second"))));
      app.afterResponseFlush((_, _) -> events.add("after"));
      app.afterRequest(_ -> completed.countDown());
      app.routes().get("/", (_, response) -> response.text("ok"));
      app.start();
      assertEquals(
          "ok",
          client
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertTrue(completed.await(5, TimeUnit.SECONDS));
      assertEquals(List.of("before", "first", "second", "after"), List.copyOf(events));
    }
  }

  @Test
  void isolatesTerminalCollectorFailuresAndContinuesLaterObservers() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.extensions(
          extension(
              c ->
                  c.afterRequest(
                      _ -> {
                        throw new IllegalStateException("observer failed");
                      })),
          extension(c -> c.afterRequest(_ -> events.add("extension"))));
      app.afterRequest(
          _ -> {
            events.add("app");
            completed.countDown();
          });
      app.start();
      assertEquals(
          404,
          client
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/missing"))
                      .build(),
                  HttpResponse.BodyHandlers.discarding())
              .statusCode());
      assertTrue(completed.await(5, TimeUnit.SECONDS));
      assertEquals(List.of("extension", "app"), List.copyOf(events));
    }
  }

  @Test
  void observesUnmatchedRequestsAndClosesObservationScopes() throws Exception {
    var events = new LinkedBlockingQueue<String>();
    var completed = new CountDownLatch(1);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.extensions(
          extension(
              c ->
                  c.observe(
                      _ -> {
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
      app.start();
      assertEquals(
          404,
          client
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/missing"))
                      .build(),
                  HttpResponse.BodyHandlers.discarding())
              .statusCode());
      assertTrue(completed.await(5, TimeUnit.SECONDS));
      assertEquals(List.of("begin", "close", "complete-404"), List.copyOf(events));
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
    Handler handler = (_, _) -> {};
    return List.of(
        c -> c.onRequestHeaders(handler),
        c -> c.onRouteMatched(handler),
        c -> c.beforeRouteHandler(handler),
        c -> c.afterRouteHandler(handler),
        c -> c.beforeResponseFlush(handler),
        c -> c.afterResponseFlush(handler),
        c -> c.afterRequest(_ -> {}),
        c -> c.observe(_ -> _ -> {}));
  }
}
