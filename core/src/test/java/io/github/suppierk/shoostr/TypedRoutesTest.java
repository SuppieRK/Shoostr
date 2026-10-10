package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class TypedRoutesTest {
  @ParameterizedTest(name = "{0}")
  @MethodSource("registrations")
  void registersTypedHttpOverloadsOnExistingRouter(RegistrationCase selected) throws Exception {
    var workerThread = new AtomicReference<Thread>();

    try (var executor =
            Executors.newSingleThreadExecutor(
                task -> {
                  var thread = new Thread(task);
                  workerThread.set(thread);
                  return thread;
                });
        var app = new Shoostr()) {
      TypedHandler handler =
          (request, response) -> {
            if (selected.worker()) {
              assertSame(workerThread.get(), Thread.currentThread());
            } else {
              assertTrue(Thread.currentThread().isVirtual());
            }

            response
                .setHeader("X-Method", request.method())
                .body(MediaType.APPLICATION_JSON, "done");
          };
      var routes = app.codec(new TestCodec()).routes();
      var registration = selected.registration().apply(executor, handler);
      if (selected.pathless()) {
        routes.path("/selected", registration);
      } else {
        registration.accept(routes);
      }

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request -> request.path("/selected").method(selected.method()),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals(selected.method(), result.headers().firstValue("X-Method").orElseThrow());
        assertEquals(
            selected.local() ? "configured" : "",
            result.headers().firstValue("X-Local").orElse(""));
        assertEquals("HEAD".equals(selected.method()) ? "" : "\"done\"", result.body());
        assertEquals("application/json", result.headers().firstValue("Content-Type").orElseThrow());
      }
    }
  }

  private static Stream<RegistrationCase> registrations() {
    return Stream.of(
        new RegistrationCase(
            "get(String path, ExecutorService executor, TypedHandler handler)",
            "GET",
            true,
            false,
            false,
            (executor, handler) -> routes -> routes.get("/selected", executor, handler)),
        new RegistrationCase(
            "get(String path, ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration)",
            "GET",
            true,
            true,
            false,
            (executor, handler) ->
                routes ->
                    routes.get(
                        "/selected",
                        executor,
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "get(ExecutorService executor, TypedHandler handler)",
            "GET",
            true,
            false,
            true,
            (executor, handler) -> routes -> routes.get(executor, handler)),
        new RegistrationCase(
            "get(ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration)",
            "GET",
            true,
            true,
            true,
            (executor, handler) ->
                routes ->
                    routes.get(
                        executor,
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "post(String path, ExecutorService executor, TypedHandler handler)",
            "POST",
            true,
            false,
            false,
            (executor, handler) -> routes -> routes.post("/selected", executor, handler)),
        new RegistrationCase(
            "post(String path, ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration)",
            "POST",
            true,
            true,
            false,
            (executor, handler) ->
                routes ->
                    routes.post(
                        "/selected",
                        executor,
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "post(ExecutorService executor, TypedHandler handler)",
            "POST",
            true,
            false,
            true,
            (executor, handler) -> routes -> routes.post(executor, handler)),
        new RegistrationCase(
            "post(ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration)",
            "POST",
            true,
            true,
            true,
            (executor, handler) ->
                routes ->
                    routes.post(
                        executor,
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "put(String path, ExecutorService executor, TypedHandler handler)",
            "PUT",
            true,
            false,
            false,
            (executor, handler) -> routes -> routes.put("/selected", executor, handler)),
        new RegistrationCase(
            "put(String path, ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration)",
            "PUT",
            true,
            true,
            false,
            (executor, handler) ->
                routes ->
                    routes.put(
                        "/selected",
                        executor,
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "put(ExecutorService executor, TypedHandler handler)",
            "PUT",
            true,
            false,
            true,
            (executor, handler) -> routes -> routes.put(executor, handler)),
        new RegistrationCase(
            "put(ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration)",
            "PUT",
            true,
            true,
            true,
            (executor, handler) ->
                routes ->
                    routes.put(
                        executor,
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "patch(String path, ExecutorService executor, TypedHandler handler)",
            "PATCH",
            true,
            false,
            false,
            (executor, handler) -> routes -> routes.patch("/selected", executor, handler)),
        new RegistrationCase(
            "patch(String path, ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration)",
            "PATCH",
            true,
            true,
            false,
            (executor, handler) ->
                routes ->
                    routes.patch(
                        "/selected",
                        executor,
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "patch(ExecutorService executor, TypedHandler handler)",
            "PATCH",
            true,
            false,
            true,
            (executor, handler) -> routes -> routes.patch(executor, handler)),
        new RegistrationCase(
            "patch(ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration)",
            "PATCH",
            true,
            true,
            true,
            (executor, handler) ->
                routes ->
                    routes.patch(
                        executor,
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "delete(String path, ExecutorService executor, TypedHandler handler)",
            "DELETE",
            true,
            false,
            false,
            (executor, handler) -> routes -> routes.delete("/selected", executor, handler)),
        new RegistrationCase(
            "delete(String path, ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration)",
            "DELETE",
            true,
            true,
            false,
            (executor, handler) ->
                routes ->
                    routes.delete(
                        "/selected",
                        executor,
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "delete(ExecutorService executor, TypedHandler handler)",
            "DELETE",
            true,
            false,
            true,
            (executor, handler) -> routes -> routes.delete(executor, handler)),
        new RegistrationCase(
            "delete(ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration)",
            "DELETE",
            true,
            true,
            true,
            (executor, handler) ->
                routes ->
                    routes.delete(
                        executor,
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "head(String path, ExecutorService executor, TypedHandler handler)",
            "HEAD",
            true,
            false,
            false,
            (executor, handler) -> routes -> routes.head("/selected", executor, handler)),
        new RegistrationCase(
            "head(String path, ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration)",
            "HEAD",
            true,
            true,
            false,
            (executor, handler) ->
                routes ->
                    routes.head(
                        "/selected",
                        executor,
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "head(ExecutorService executor, TypedHandler handler)",
            "HEAD",
            true,
            false,
            true,
            (executor, handler) -> routes -> routes.head(executor, handler)),
        new RegistrationCase(
            "head(ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration)",
            "HEAD",
            true,
            true,
            true,
            (executor, handler) ->
                routes ->
                    routes.head(
                        executor,
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "options(String path, ExecutorService executor, TypedHandler handler)",
            "OPTIONS",
            true,
            false,
            false,
            (executor, handler) -> routes -> routes.options("/selected", executor, handler)),
        new RegistrationCase(
            "options(String path, ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration)",
            "OPTIONS",
            true,
            true,
            false,
            (executor, handler) ->
                routes ->
                    routes.options(
                        "/selected",
                        executor,
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "options(ExecutorService executor, TypedHandler handler)",
            "OPTIONS",
            true,
            false,
            true,
            (executor, handler) -> routes -> routes.options(executor, handler)),
        new RegistrationCase(
            "options(ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration)",
            "OPTIONS",
            true,
            true,
            true,
            (executor, handler) ->
                routes ->
                    routes.options(
                        executor,
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "route(HttpMethods method, String path, ExecutorService executor, TypedHandler handler)",
            "PATCH",
            true,
            false,
            false,
            (executor, handler) ->
                routes -> routes.route(HttpMethods.PATCH, "/selected", executor, handler)),
        new RegistrationCase(
            "route(HttpMethods method, String path, ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration)",
            "PATCH",
            true,
            true,
            false,
            (executor, handler) ->
                routes ->
                    routes.route(
                        HttpMethods.PATCH,
                        "/selected",
                        executor,
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "route(HttpMethods method, ExecutorService executor, TypedHandler handler)",
            "PATCH",
            true,
            false,
            true,
            (executor, handler) -> routes -> routes.route(HttpMethods.PATCH, executor, handler)),
        new RegistrationCase(
            "route(HttpMethods method, ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration)",
            "PATCH",
            true,
            true,
            true,
            (executor, handler) ->
                routes ->
                    routes.route(
                        HttpMethods.PATCH,
                        executor,
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "route(String method, String path, ExecutorService executor, TypedHandler handler)",
            "PATCH",
            true,
            false,
            false,
            (executor, handler) -> routes -> routes.route("PATCH", "/selected", executor, handler)),
        new RegistrationCase(
            "route(String method, String path, ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration)",
            "PATCH",
            true,
            true,
            false,
            (executor, handler) ->
                routes ->
                    routes.route(
                        "PATCH",
                        "/selected",
                        executor,
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "route(String method, ExecutorService executor, TypedHandler handler)",
            "PATCH",
            true,
            false,
            true,
            (executor, handler) -> routes -> routes.route("PATCH", executor, handler)),
        new RegistrationCase(
            "route(String method, ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration)",
            "PATCH",
            true,
            true,
            true,
            (executor, handler) ->
                routes ->
                    routes.route(
                        "PATCH",
                        executor,
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "get(TypedHandler handler, Consumer<Extensions> configuration)",
            "GET",
            false,
            true,
            true,
            (executor, handler) ->
                routes ->
                    routes.get(
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "get(String path, TypedHandler handler, Consumer<Extensions> configuration)",
            "GET",
            false,
            true,
            false,
            (executor, handler) ->
                routes ->
                    routes.get(
                        "/selected",
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "post(TypedHandler handler, Consumer<Extensions> configuration)",
            "POST",
            false,
            true,
            true,
            (executor, handler) ->
                routes ->
                    routes.post(
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "post(String path, TypedHandler handler, Consumer<Extensions> configuration)",
            "POST",
            false,
            true,
            false,
            (executor, handler) ->
                routes ->
                    routes.post(
                        "/selected",
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "put(TypedHandler handler, Consumer<Extensions> configuration)",
            "PUT",
            false,
            true,
            true,
            (executor, handler) ->
                routes ->
                    routes.put(
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "put(String path, TypedHandler handler, Consumer<Extensions> configuration)",
            "PUT",
            false,
            true,
            false,
            (executor, handler) ->
                routes ->
                    routes.put(
                        "/selected",
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "patch(TypedHandler handler, Consumer<Extensions> configuration)",
            "PATCH",
            false,
            true,
            true,
            (executor, handler) ->
                routes ->
                    routes.patch(
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "patch(String path, TypedHandler handler, Consumer<Extensions> configuration)",
            "PATCH",
            false,
            true,
            false,
            (executor, handler) ->
                routes ->
                    routes.patch(
                        "/selected",
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "delete(TypedHandler handler, Consumer<Extensions> configuration)",
            "DELETE",
            false,
            true,
            true,
            (executor, handler) ->
                routes ->
                    routes.delete(
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "delete(String path, TypedHandler handler, Consumer<Extensions> configuration)",
            "DELETE",
            false,
            true,
            false,
            (executor, handler) ->
                routes ->
                    routes.delete(
                        "/selected",
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "head(TypedHandler handler, Consumer<Extensions> configuration)",
            "HEAD",
            false,
            true,
            true,
            (executor, handler) ->
                routes ->
                    routes.head(
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "head(String path, TypedHandler handler, Consumer<Extensions> configuration)",
            "HEAD",
            false,
            true,
            false,
            (executor, handler) ->
                routes ->
                    routes.head(
                        "/selected",
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "options(TypedHandler handler, Consumer<Extensions> configuration)",
            "OPTIONS",
            false,
            true,
            true,
            (executor, handler) ->
                routes ->
                    routes.options(
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "options(String path, TypedHandler handler, Consumer<Extensions> configuration)",
            "OPTIONS",
            false,
            true,
            false,
            (executor, handler) ->
                routes ->
                    routes.options(
                        "/selected",
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "get(TypedHandler handler)",
            "GET",
            false,
            false,
            true,
            (executor, handler) -> routes -> routes.get(handler)),
        new RegistrationCase(
            "get(String path, TypedHandler handler)",
            "GET",
            false,
            false,
            false,
            (executor, handler) -> routes -> routes.get("/selected", handler)),
        new RegistrationCase(
            "post(TypedHandler handler)",
            "POST",
            false,
            false,
            true,
            (executor, handler) -> routes -> routes.post(handler)),
        new RegistrationCase(
            "post(String path, TypedHandler handler)",
            "POST",
            false,
            false,
            false,
            (executor, handler) -> routes -> routes.post("/selected", handler)),
        new RegistrationCase(
            "put(TypedHandler handler)",
            "PUT",
            false,
            false,
            true,
            (executor, handler) -> routes -> routes.put(handler)),
        new RegistrationCase(
            "put(String path, TypedHandler handler)",
            "PUT",
            false,
            false,
            false,
            (executor, handler) -> routes -> routes.put("/selected", handler)),
        new RegistrationCase(
            "patch(TypedHandler handler)",
            "PATCH",
            false,
            false,
            true,
            (executor, handler) -> routes -> routes.patch(handler)),
        new RegistrationCase(
            "patch(String path, TypedHandler handler)",
            "PATCH",
            false,
            false,
            false,
            (executor, handler) -> routes -> routes.patch("/selected", handler)),
        new RegistrationCase(
            "delete(TypedHandler handler)",
            "DELETE",
            false,
            false,
            true,
            (executor, handler) -> routes -> routes.delete(handler)),
        new RegistrationCase(
            "delete(String path, TypedHandler handler)",
            "DELETE",
            false,
            false,
            false,
            (executor, handler) -> routes -> routes.delete("/selected", handler)),
        new RegistrationCase(
            "head(TypedHandler handler)",
            "HEAD",
            false,
            false,
            true,
            (executor, handler) -> routes -> routes.head(handler)),
        new RegistrationCase(
            "head(String path, TypedHandler handler)",
            "HEAD",
            false,
            false,
            false,
            (executor, handler) -> routes -> routes.head("/selected", handler)),
        new RegistrationCase(
            "options(TypedHandler handler)",
            "OPTIONS",
            false,
            false,
            true,
            (executor, handler) -> routes -> routes.options(handler)),
        new RegistrationCase(
            "options(String path, TypedHandler handler)",
            "OPTIONS",
            false,
            false,
            false,
            (executor, handler) -> routes -> routes.options("/selected", handler)),
        new RegistrationCase(
            "route(HttpMethods method, String path, TypedHandler handler)",
            "PATCH",
            false,
            false,
            false,
            (executor, handler) -> routes -> routes.route(HttpMethods.PATCH, "/selected", handler)),
        new RegistrationCase(
            "route(HttpMethods method, String path, TypedHandler handler, Consumer<Extensions> configuration)",
            "PATCH",
            false,
            true,
            false,
            (executor, handler) ->
                routes ->
                    routes.route(
                        HttpMethods.PATCH,
                        "/selected",
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "route(String method, String path, TypedHandler handler)",
            "PATCH",
            false,
            false,
            false,
            (executor, handler) -> routes -> routes.route("PATCH", "/selected", handler)),
        new RegistrationCase(
            "route(String method, String path, TypedHandler handler, Consumer<Extensions> configuration)",
            "PATCH",
            false,
            true,
            false,
            (executor, handler) ->
                routes ->
                    routes.route(
                        "PATCH",
                        "/selected",
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "route(HttpMethods method, TypedHandler handler, Consumer<Extensions> configuration)",
            "PATCH",
            false,
            true,
            true,
            (executor, handler) ->
                routes ->
                    routes.route(
                        HttpMethods.PATCH,
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "route(String method, TypedHandler handler, Consumer<Extensions> configuration)",
            "PATCH",
            false,
            true,
            true,
            (executor, handler) ->
                routes ->
                    routes.route(
                        "PATCH",
                        handler,
                        extensions ->
                            extensions.beforeRouteHandler(
                                (_, response) -> response.setHeader("X-Local", "configured")))),
        new RegistrationCase(
            "route(HttpMethods method, TypedHandler handler)",
            "PATCH",
            false,
            false,
            true,
            (executor, handler) -> routes -> routes.route(HttpMethods.PATCH, handler)),
        new RegistrationCase(
            "route(String method, TypedHandler handler)",
            "PATCH",
            false,
            false,
            true,
            (executor, handler) -> routes -> routes.route("PATCH", handler)));
  }

  @Test
  void inheritsConfiguredPathCallbacksOnTypedEndpoints() throws Exception {
    try (var app = new Shoostr()) {
      app.codec(new TestCodec())
          .routes(
              routes ->
                  routes.path(
                      "/users",
                      child ->
                          child.get(
                              "/{id}",
                              (request, response) ->
                                  response.body(
                                      MediaType.APPLICATION_JSON,
                                      request.pathParam("id").orElseThrow())),
                      extensions ->
                          extensions.beforeRouteHandler(
                              (_, response) -> response.setHeader("X-Group", "users"))));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request -> request.path("/users/alice"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("\"alice\"", result.body());
        assertEquals("users", result.headers().firstValue("X-Group").orElseThrow());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void evaluatesBooleanAvailabilityForTypedHandlers(boolean available) throws Exception {
    try (var app = new Shoostr()) {
      app.codec(new TestCodec())
          .routes()
          .when(
              () -> available,
              routes ->
                  routes.get(
                      "/conditional",
                      (_, response) -> response.body(MediaType.APPLICATION_JSON, "available")));

      try (var test = TestServer.start(app)) {
        var result = test.send(request -> request.path("/conditional"));
        assertEquals(available ? 200 : 404, result.statusCode());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"yes", "no"})
  void evaluatesRequestAvailabilityForTypedHandlers(String enabled) throws Exception {
    try (var app = new Shoostr()) {
      app.codec(new TestCodec())
          .routes()
          .when(
              request -> "yes".equals(request.header("X-Enabled").orElse("")),
              routes ->
                  routes.get(
                      "/conditional",
                      (_, response) -> response.body(MediaType.APPLICATION_JSON, "available")));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/conditional").header("X-Enabled", enabled));
        assertEquals("yes".equals(enabled) ? 200 : 404, result.statusCode());
      }
    }
  }

  @Test
  void preservesTenLevelPathRegistrationLimit() throws Exception {
    try (var app = new Shoostr()) {
      var routes = app.codec(new TestCodec()).routes();
      assertDoesNotThrow(() -> nestedPaths(routes, 10));
      assertThrows(IllegalStateException.class, () -> nestedPaths(routes, 11));
    }
  }

  @Test
  void preservesTenLevelAvailabilityRegistrationLimit() throws Exception {
    try (var app = new Shoostr()) {
      var routes = app.codec(new TestCodec()).routes();
      assertDoesNotThrow(() -> nestedConditions(routes, 10));
      assertThrows(IllegalStateException.class, () -> nestedConditions(routes, 11));
    }
  }

  @Test
  void rejectsCapturedRootDepthEscape() throws Exception {
    try (var app = new Shoostr()) {
      var root = app.codec(new TestCodec()).routes();
      assertThrows(IllegalStateException.class, () -> capturedRootPaths(root, 11));
    }
  }

  @Test
  void rejectsDuplicateBeforeRunningLocalConfiguration() throws Exception {
    var configured = new AtomicInteger();

    try (var app = new Shoostr()) {
      var routes = app.codec(new TestCodec()).routes();
      routes.get(
          "/same", (_, response) -> response.text("first"), _ -> configured.incrementAndGet());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              routes.get(
                  "/same",
                  (_, response) -> response.text("second"),
                  _ -> configured.incrementAndGet()));
      assertEquals(1, configured.get());
    }
  }

  @Test
  void endsRegistrationForEveryViewWhenTypedScopeCloses() throws Exception {
    try (var app = new Shoostr()) {
      var routes = app.codec(new TestCodec()).routes();
      routes.close();
      assertThrows(
          IllegalStateException.class,
          () -> routes.get("/late", (_, response) -> response.text("late")));
      var ordinary = app.routes();
      assertThrows(
          IllegalStateException.class,
          () -> ordinary.get("/late", (_, response) -> response.text("late")));
    }
  }

  @Test
  void endsTypedRegistrationWhenApplicationStarts() throws Exception {
    try (var app = new Shoostr()) {
      var routes = app.codec(new TestCodec()).routes();

      try (var test = TestServer.start(app)) {
        assertThrows(
            IllegalStateException.class,
            () -> routes.get("/late", (_, response) -> response.text("late")));
        assertEquals(404, test.send(request -> request.path("/late")).statusCode());
      }
    }
  }

  private static void nestedPaths(TypedRoutes routes, int remaining) {
    if (remaining > 0) {
      routes.path("/p", child -> nestedPaths(child, remaining - 1));
    }
  }

  private static void nestedConditions(TypedRoutes routes, int remaining) {
    if (remaining > 0) {
      routes.when(() -> true, child -> nestedConditions(child, remaining - 1));
    }
  }

  private static void capturedRootPaths(TypedRoutes root, int remaining) {
    if (remaining > 0) {
      root.path("/p", _ -> capturedRootPaths(root, remaining - 1));
    }
  }

  private record RegistrationCase(
      String name,
      String method,
      boolean worker,
      boolean local,
      boolean pathless,
      BiFunction<ExecutorService, TypedHandler, Consumer<TypedRoutes>> registration) {
    RegistrationCase {
      Objects.requireNonNull(name);
      Objects.requireNonNull(method);
      Objects.requireNonNull(registration);
    }

    @Override
    public String toString() {
      return name;
    }
  }
}
