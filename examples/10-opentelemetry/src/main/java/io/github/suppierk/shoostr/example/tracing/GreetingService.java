package io.github.suppierk.shoostr.example.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import java.util.Objects;

/** Demonstrates a traced operation and context propagation through ordinary Java calls. */
final class GreetingService {
  private final Tracer tracer;

  /**
   * Borrows a tracer from the application-owned SDK.
   *
   * @param tracer application instrumentation
   */
  GreetingService(Tracer tracer) {
    this.tracer = Objects.requireNonNull(tracer);
  }

  /**
   * Adds one operation beneath the current request span, without passing trace identifiers.
   *
   * @param name greeting recipient
   * @return greeting text
   */
  String greet(String name) {
    // The SDK uses the current SERVER span as the parent; creating a span does not activate it.
    var span = tracer.spanBuilder("greeting").setSpanKind(SpanKind.INTERNAL).startSpan();
    // Activate the child for nested calls; closing this scope restores the SERVER span.

    try (var _ = span.makeCurrent()) {
      return formatGreeting(name);
    } finally {
      // Closing a context scope does not finish its span; always end the operation separately.
      span.end();
    }
  }

  /**
   * Uses the current child span without receiving a tracer, span or propagation headers.
   *
   * @param name greeting recipient
   * @return greeting text
   */
  private String formatGreeting(String name) {
    Span.current().addEvent("Greeting formatted");
    return "Hello, " + name + "!";
  }
}
