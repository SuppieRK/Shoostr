package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ShoostrRegistrationTest {
  private static Stream<Arguments> registrations() {
    return Stream.of(
        Arguments.of("beforeRouteHandler", "Route gates"),
        Arguments.of("onRequestHeaders", "Request-header hooks"),
        Arguments.of("onRouteMatched", "Route-match hooks"),
        Arguments.of("afterRouteHandler", "Post-route hooks"),
        Arguments.of("beforeResponseFlush", "Before-flush hooks"),
        Arguments.of("afterResponseFlush", "After-flush hooks"),
        Arguments.of("observe", "Instrumentation"),
        Arguments.of("afterRequest", "Completion observers"),
        Arguments.of("beforeClose", "Shutdown callbacks"),
        Arguments.of("afterClose", "Cleanup callbacks"));
  }

  @ParameterizedTest
  @MethodSource("registrations")
  void registersFluentlyWithoutInvokingCallbacksAndRejectsNullWhileOpen(
      String phase, String ignoredMessage) throws Exception {
    var app = new Shoostr();
    var invoked = new AtomicInteger();

    try (Closeable appCleanup = app::close) {
      assertSame(app, register(app, phase, invoked::incrementAndGet));
      assertEquals(0, invoked.get());
      assertThrows(NullPointerException.class, () -> register(app, phase, null));
    }
  }

  @ParameterizedTest
  @MethodSource("registrations")
  void rejectsNullCallbackAfterStartupBeforeValidatingIt(String phase, String message)
      throws Exception {
    var options = new Options();
    options.setHost("127.0.0.1");
    options.setPort(0);
    var app = new Shoostr(options);

    try (Closeable appCleanup = app::close) {
      app.start();
      var failure = assertThrows(IllegalStateException.class, () -> register(app, phase, null));

      assertEquals(message + " must be registered before startup", failure.getMessage());
    }
  }

  @ParameterizedTest
  @MethodSource("registrations")
  void rejectsNullCallbackAfterCloseBeforeStartupBeforeValidatingIt(String phase, String message)
      throws Exception {
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.close();
      var failure = assertThrows(IllegalStateException.class, () -> register(app, phase, null));

      assertEquals(message + " must be registered before startup", failure.getMessage());
    }
  }

  private Shoostr register(Shoostr app, String phase, Runnable callback) {
    Handler handler = callback == null ? null : (request, response) -> callback.run();
    Consumer<RequestOutcome> observer = callback == null ? null : outcome -> callback.run();
    Function<Request, RequestObservation> factory =
        callback == null ? null : request -> beginObservation(callback);

    return switch (phase) {
      case "beforeRouteHandler" -> app.beforeRouteHandler(handler);
      case "onRequestHeaders" -> app.onRequestHeaders(handler);
      case "onRouteMatched" -> app.onRouteMatched(handler);
      case "afterRouteHandler" -> app.afterRouteHandler(handler);
      case "beforeResponseFlush" -> app.beforeResponseFlush(handler);
      case "afterResponseFlush" -> app.afterResponseFlush(handler);
      case "observe" -> app.observe(factory);
      case "afterRequest" -> app.afterRequest(observer);
      case "beforeClose" -> app.beforeClose(callback);
      case "afterClose" -> app.afterClose(callback);
      default -> throw new IllegalArgumentException("Unknown callback phase: " + phase);
    };
  }

  private RequestObservation beginObservation(Runnable callback) {
    callback.run();

    return outcome -> {};
  }
}
