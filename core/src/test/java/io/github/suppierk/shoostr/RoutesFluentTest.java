package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.suppierk.shoostr.http.HttpMethods;
import java.io.Closeable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RoutesFluentTest {
  private static final Handler HANDLER = (ignoredRequest, ignoredResponse) -> {};
  private static final Consumer<Lifecycle> CONFIGURATION = RoutesFluentTest::configureLifecycle;
  private static final BiFunction<Request, ServerUpgradeResponse, Session.Listener> LISTENER =
      RoutesFluentTest::createListener;
  private static final Consumer<WebSocket> EVENTS = RoutesFluentTest::configureEvents;

  private static void configureLifecycle(Lifecycle lifecycle) {
    lifecycle.beforeRouteHandler(HANDLER);
  }

  private static Session.Listener createListener(
      Request ignored, ServerUpgradeResponse ignoredUpgrade) {
    return new Session.Listener() {};
  }

  private static void configureEvents(WebSocket ignored) {}

  @ParameterizedTest(name = "{0}")
  @MethodSource("registrations")
  void registrationReturnsItsCallingScope(
      String ignoredName, Function<ExecutorService, UnaryOperator<Routes>> registration)
      throws Exception {
    try (var workers = Executors.newSingleThreadExecutor()) {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        var root = app.routes();
        assertSame(
            root,
            root.path(
                "/group", child -> assertSame(child, registration.apply(workers).apply(child))));
      }
    }
  }

  private static Stream<Arguments> registrations() {
    return Stream.of(
            getRegistrations(),
            postRegistrations(),
            putRegistrations(),
            patchRegistrations(),
            deleteRegistrations(),
            headRegistrations(),
            optionsRegistrations(),
            sseRegistrations(),
            routeRegistrations(),
            websocketRegistrations(),
            scopeRegistrations())
        .flatMap(Function.identity());
  }

  private static Stream<Arguments> scopeRegistrations() {
    return Stream.of(
        registration("path", workers -> routes -> routes.path("/selected", ignored -> {})),
        registration(
            "configured path",
            workers -> routes -> routes.path("/selected", ignored -> {}, CONFIGURATION)),
        registration(
            "boolean condition", workers -> routes -> routes.when(() -> true, ignored -> {})),
        registration(
            "request condition",
            workers -> routes -> routes.when(ignoredRequest -> true, ignored -> {})),
        registration(
            "exception",
            workers ->
                routes ->
                    routes.exception(
                        Exception.class, (ignoredFailure, ignoredRequest, ignoredResponse) -> {})));
  }

  private static Stream<Arguments> getRegistrations() {
    return Stream.of(
        registration(
            "get(\"/selected\", HANDLER)", workers -> routes -> routes.get("/selected", HANDLER)),
        registration("get(HANDLER)", workers -> routes -> routes.get(HANDLER)),
        registration(
            "get(\"/selected\", HANDLER, CONFIGURATION)",
            workers -> routes -> routes.get("/selected", HANDLER, CONFIGURATION)),
        registration(
            "get(HANDLER, CONFIGURATION)", workers -> routes -> routes.get(HANDLER, CONFIGURATION)),
        registration(
            "get(\"/selected\", workers, HANDLER)",
            workers -> routes -> routes.get("/selected", workers, HANDLER)),
        registration("get(workers, HANDLER)", workers -> routes -> routes.get(workers, HANDLER)),
        registration(
            "get(\"/selected\", workers, HANDLER, CONFIGURATION)",
            workers -> routes -> routes.get("/selected", workers, HANDLER, CONFIGURATION)),
        registration(
            "get(workers, HANDLER, CONFIGURATION)",
            workers -> routes -> routes.get(workers, HANDLER, CONFIGURATION)));
  }

  private static Stream<Arguments> postRegistrations() {
    return Stream.of(
        registration(
            "post(\"/selected\", HANDLER)", workers -> routes -> routes.post("/selected", HANDLER)),
        registration("post(HANDLER)", workers -> routes -> routes.post(HANDLER)),
        registration(
            "post(\"/selected\", HANDLER, CONFIGURATION)",
            workers -> routes -> routes.post("/selected", HANDLER, CONFIGURATION)),
        registration(
            "post(HANDLER, CONFIGURATION)",
            workers -> routes -> routes.post(HANDLER, CONFIGURATION)),
        registration(
            "post(\"/selected\", workers, HANDLER)",
            workers -> routes -> routes.post("/selected", workers, HANDLER)),
        registration("post(workers, HANDLER)", workers -> routes -> routes.post(workers, HANDLER)),
        registration(
            "post(\"/selected\", workers, HANDLER, CONFIGURATION)",
            workers -> routes -> routes.post("/selected", workers, HANDLER, CONFIGURATION)),
        registration(
            "post(workers, HANDLER, CONFIGURATION)",
            workers -> routes -> routes.post(workers, HANDLER, CONFIGURATION)));
  }

  private static Stream<Arguments> putRegistrations() {
    return Stream.of(
        registration(
            "put(\"/selected\", HANDLER)", workers -> routes -> routes.put("/selected", HANDLER)),
        registration("put(HANDLER)", workers -> routes -> routes.put(HANDLER)),
        registration(
            "put(\"/selected\", HANDLER, CONFIGURATION)",
            workers -> routes -> routes.put("/selected", HANDLER, CONFIGURATION)),
        registration(
            "put(HANDLER, CONFIGURATION)", workers -> routes -> routes.put(HANDLER, CONFIGURATION)),
        registration(
            "put(\"/selected\", workers, HANDLER)",
            workers -> routes -> routes.put("/selected", workers, HANDLER)),
        registration("put(workers, HANDLER)", workers -> routes -> routes.put(workers, HANDLER)),
        registration(
            "put(\"/selected\", workers, HANDLER, CONFIGURATION)",
            workers -> routes -> routes.put("/selected", workers, HANDLER, CONFIGURATION)),
        registration(
            "put(workers, HANDLER, CONFIGURATION)",
            workers -> routes -> routes.put(workers, HANDLER, CONFIGURATION)));
  }

  private static Stream<Arguments> patchRegistrations() {
    return Stream.of(
        registration(
            "patch(\"/selected\", HANDLER)",
            workers -> routes -> routes.patch("/selected", HANDLER)),
        registration("patch(HANDLER)", workers -> routes -> routes.patch(HANDLER)),
        registration(
            "patch(\"/selected\", HANDLER, CONFIGURATION)",
            workers -> routes -> routes.patch("/selected", HANDLER, CONFIGURATION)),
        registration(
            "patch(HANDLER, CONFIGURATION)",
            workers -> routes -> routes.patch(HANDLER, CONFIGURATION)),
        registration(
            "patch(\"/selected\", workers, HANDLER)",
            workers -> routes -> routes.patch("/selected", workers, HANDLER)),
        registration(
            "patch(workers, HANDLER)", workers -> routes -> routes.patch(workers, HANDLER)),
        registration(
            "patch(\"/selected\", workers, HANDLER, CONFIGURATION)",
            workers -> routes -> routes.patch("/selected", workers, HANDLER, CONFIGURATION)),
        registration(
            "patch(workers, HANDLER, CONFIGURATION)",
            workers -> routes -> routes.patch(workers, HANDLER, CONFIGURATION)));
  }

  private static Stream<Arguments> deleteRegistrations() {
    return Stream.of(
        registration(
            "delete(\"/selected\", HANDLER)",
            workers -> routes -> routes.delete("/selected", HANDLER)),
        registration("delete(HANDLER)", workers -> routes -> routes.delete(HANDLER)),
        registration(
            "delete(\"/selected\", HANDLER, CONFIGURATION)",
            workers -> routes -> routes.delete("/selected", HANDLER, CONFIGURATION)),
        registration(
            "delete(HANDLER, CONFIGURATION)",
            workers -> routes -> routes.delete(HANDLER, CONFIGURATION)),
        registration(
            "delete(\"/selected\", workers, HANDLER)",
            workers -> routes -> routes.delete("/selected", workers, HANDLER)),
        registration(
            "delete(workers, HANDLER)", workers -> routes -> routes.delete(workers, HANDLER)),
        registration(
            "delete(\"/selected\", workers, HANDLER, CONFIGURATION)",
            workers -> routes -> routes.delete("/selected", workers, HANDLER, CONFIGURATION)),
        registration(
            "delete(workers, HANDLER, CONFIGURATION)",
            workers -> routes -> routes.delete(workers, HANDLER, CONFIGURATION)));
  }

  private static Stream<Arguments> headRegistrations() {
    return Stream.of(
        registration(
            "head(\"/selected\", HANDLER)", workers -> routes -> routes.head("/selected", HANDLER)),
        registration("head(HANDLER)", workers -> routes -> routes.head(HANDLER)),
        registration(
            "head(\"/selected\", HANDLER, CONFIGURATION)",
            workers -> routes -> routes.head("/selected", HANDLER, CONFIGURATION)),
        registration(
            "head(HANDLER, CONFIGURATION)",
            workers -> routes -> routes.head(HANDLER, CONFIGURATION)),
        registration(
            "head(\"/selected\", workers, HANDLER)",
            workers -> routes -> routes.head("/selected", workers, HANDLER)),
        registration("head(workers, HANDLER)", workers -> routes -> routes.head(workers, HANDLER)),
        registration(
            "head(\"/selected\", workers, HANDLER, CONFIGURATION)",
            workers -> routes -> routes.head("/selected", workers, HANDLER, CONFIGURATION)),
        registration(
            "head(workers, HANDLER, CONFIGURATION)",
            workers -> routes -> routes.head(workers, HANDLER, CONFIGURATION)));
  }

  private static Stream<Arguments> optionsRegistrations() {
    return Stream.of(
        registration(
            "options(\"/selected\", HANDLER)",
            workers -> routes -> routes.options("/selected", HANDLER)),
        registration("options(HANDLER)", workers -> routes -> routes.options(HANDLER)),
        registration(
            "options(\"/selected\", HANDLER, CONFIGURATION)",
            workers -> routes -> routes.options("/selected", HANDLER, CONFIGURATION)),
        registration(
            "options(HANDLER, CONFIGURATION)",
            workers -> routes -> routes.options(HANDLER, CONFIGURATION)),
        registration(
            "options(\"/selected\", workers, HANDLER)",
            workers -> routes -> routes.options("/selected", workers, HANDLER)),
        registration(
            "options(workers, HANDLER)", workers -> routes -> routes.options(workers, HANDLER)),
        registration(
            "options(\"/selected\", workers, HANDLER, CONFIGURATION)",
            workers -> routes -> routes.options("/selected", workers, HANDLER, CONFIGURATION)),
        registration(
            "options(workers, HANDLER, CONFIGURATION)",
            workers -> routes -> routes.options(workers, HANDLER, CONFIGURATION)));
  }

  private static Stream<Arguments> sseRegistrations() {
    return Stream.of(
        registration(
            "sse(\"/selected\", HANDLER)", workers -> routes -> routes.sse("/selected", HANDLER)),
        registration("sse(HANDLER)", workers -> routes -> routes.sse(HANDLER)),
        registration(
            "sse(\"/selected\", HANDLER, CONFIGURATION)",
            workers -> routes -> routes.sse("/selected", HANDLER, CONFIGURATION)),
        registration(
            "sse(HANDLER, CONFIGURATION)", workers -> routes -> routes.sse(HANDLER, CONFIGURATION)),
        registration(
            "sse(\"/selected\", workers, HANDLER)",
            workers -> routes -> routes.sse("/selected", workers, HANDLER)),
        registration("sse(workers, HANDLER)", workers -> routes -> routes.sse(workers, HANDLER)),
        registration(
            "sse(\"/selected\", workers, HANDLER, CONFIGURATION)",
            workers -> routes -> routes.sse("/selected", workers, HANDLER, CONFIGURATION)),
        registration(
            "sse(workers, HANDLER, CONFIGURATION)",
            workers -> routes -> routes.sse(workers, HANDLER, CONFIGURATION)));
  }

  private static Stream<Arguments> routeRegistrations() {
    return Stream.of(
        registration(
            "route(HttpMethods.GET, \"/selected\", HANDLER)",
            workers -> routes -> routes.route(HttpMethods.GET, "/selected", HANDLER)),
        registration(
            "route(HttpMethods.GET, \"/selected\", HANDLER, CONFIGURATION)",
            workers ->
                routes -> routes.route(HttpMethods.GET, "/selected", HANDLER, CONFIGURATION)),
        registration(
            "route(HttpMethods.GET, HANDLER)",
            workers -> routes -> routes.route(HttpMethods.GET, HANDLER)),
        registration(
            "route(HttpMethods.GET, HANDLER, CONFIGURATION)",
            workers -> routes -> routes.route(HttpMethods.GET, HANDLER, CONFIGURATION)),
        registration(
            "route(HttpMethods.GET, \"/selected\", workers, HANDLER)",
            workers -> routes -> routes.route(HttpMethods.GET, "/selected", workers, HANDLER)),
        registration(
            "route(HttpMethods.GET, \"/selected\", workers, HANDLER, CONFIGURATION)",
            workers ->
                routes ->
                    routes.route(HttpMethods.GET, "/selected", workers, HANDLER, CONFIGURATION)),
        registration(
            "route(HttpMethods.GET, workers, HANDLER)",
            workers -> routes -> routes.route(HttpMethods.GET, workers, HANDLER)),
        registration(
            "route(HttpMethods.GET, workers, HANDLER, CONFIGURATION)",
            workers -> routes -> routes.route(HttpMethods.GET, workers, HANDLER, CONFIGURATION)),
        registration(
            "route(\"GET\", \"/selected\", HANDLER)",
            workers -> routes -> routes.route("GET", "/selected", HANDLER)),
        registration(
            "route(\"GET\", \"/selected\", HANDLER, CONFIGURATION)",
            workers -> routes -> routes.route("GET", "/selected", HANDLER, CONFIGURATION)),
        registration("route(\"GET\", HANDLER)", workers -> routes -> routes.route("GET", HANDLER)),
        registration(
            "route(\"GET\", HANDLER, CONFIGURATION)",
            workers -> routes -> routes.route("GET", HANDLER, CONFIGURATION)),
        registration(
            "route(\"GET\", \"/selected\", workers, HANDLER)",
            workers -> routes -> routes.route("GET", "/selected", workers, HANDLER)),
        registration(
            "route(\"GET\", \"/selected\", workers, HANDLER, CONFIGURATION)",
            workers -> routes -> routes.route("GET", "/selected", workers, HANDLER, CONFIGURATION)),
        registration(
            "route(\"GET\", workers, HANDLER)",
            workers -> routes -> routes.route("GET", workers, HANDLER)),
        registration(
            "route(\"GET\", workers, HANDLER, CONFIGURATION)",
            workers -> routes -> routes.route("GET", workers, HANDLER, CONFIGURATION)));
  }

  private static Stream<Arguments> websocketRegistrations() {
    return Stream.of(
        registration(
            "websocket(\"/selected\", LISTENER)",
            workers -> routes -> routes.websocket("/selected", LISTENER)),
        registration(
            "websocket(\"/selected\", LISTENER, CONFIGURATION)",
            workers -> routes -> routes.websocket("/selected", LISTENER, CONFIGURATION)),
        registration("websocket(LISTENER)", workers -> routes -> routes.websocket(LISTENER)),
        registration(
            "websocket(LISTENER, CONFIGURATION)",
            workers -> routes -> routes.websocket(LISTENER, CONFIGURATION)),
        registration(
            "websocket(\"/selected\", workers, LISTENER)",
            workers -> routes -> routes.websocket("/selected", workers, LISTENER)),
        registration(
            "websocket(\"/selected\", workers, LISTENER, CONFIGURATION)",
            workers -> routes -> routes.websocket("/selected", workers, LISTENER, CONFIGURATION)),
        registration(
            "websocket(workers, LISTENER)",
            workers -> routes -> routes.websocket(workers, LISTENER)),
        registration(
            "websocket(workers, LISTENER, CONFIGURATION)",
            workers -> routes -> routes.websocket(workers, LISTENER, CONFIGURATION)),
        registration(
            "websocket(\"/selected\", EVENTS)",
            workers -> routes -> routes.websocket("/selected", EVENTS)),
        registration(
            "websocket(\"/selected\", EVENTS, CONFIGURATION)",
            workers -> routes -> routes.websocket("/selected", EVENTS, CONFIGURATION)),
        registration("websocket(EVENTS)", workers -> routes -> routes.websocket(EVENTS)),
        registration(
            "websocket(EVENTS, CONFIGURATION)",
            workers -> routes -> routes.websocket(EVENTS, CONFIGURATION)),
        registration(
            "websocket(\"/selected\", workers, EVENTS)",
            workers -> routes -> routes.websocket("/selected", workers, EVENTS)),
        registration(
            "websocket(\"/selected\", workers, EVENTS, CONFIGURATION)",
            workers -> routes -> routes.websocket("/selected", workers, EVENTS, CONFIGURATION)),
        registration(
            "websocket(workers, EVENTS)", workers -> routes -> routes.websocket(workers, EVENTS)),
        registration(
            "websocket(workers, EVENTS, CONFIGURATION)",
            workers -> routes -> routes.websocket(workers, EVENTS, CONFIGURATION)));
  }

  private static Arguments registration(
      String name, Function<ExecutorService, UnaryOperator<Routes>> operation) {
    return Arguments.of(name, operation);
  }
}
