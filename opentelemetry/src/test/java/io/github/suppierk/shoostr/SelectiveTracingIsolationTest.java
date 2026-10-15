package io.github.suppierk.shoostr;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.opentelemetry.OpenTelemetryTracing;
import io.github.suppierk.shoostr.testing.TestServer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SelectiveTracingIsolationTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preservesGlobalSpanWithSelectiveTracingInstalledInEitherOrder(boolean globalFirst)
      throws Exception {
    var exporter = InMemorySpanExporter.create();
    var completed = new CompletableFuture<Void>();

    try (var provider =
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        var workers = new ForkJoinPool(1);
        var app = new Shoostr(Options.defaults().withPort(0))) {
      var telemetry =
          OpenTelemetrySdk.builder()
              .setTracerProvider(provider)
              .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
              .build();
      var global = new OpenTelemetryTracing(telemetry).allRequests();
      var selective = new OpenTelemetryTracing(telemetry);
      app.extensions(globalFirst ? global : selective, globalFirst ? selective : global)
          .afterRequest(_ -> completed.complete(null));
      app.routes()
          .get(
              "/unselected",
              workers,
              (_, response) -> response.text(Span.current().getSpanContext().getTraceId()));

      try (var test = TestServer.start(app)) {
        var response =
            test.send(
                request ->
                    request
                        .path("/unselected")
                        .header(
                            "traceparent",
                            "00-11111111111111111111111111111111-0123456789012345-01"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("11111111111111111111111111111111", response.body());
        completed.get(5, TimeUnit.SECONDS);
        assertEquals(1, exporter.getFinishedSpanItems().size());
        assertFalse(
            workers
                .submit(() -> Span.current().getSpanContext().isValid())
                .get(5, TimeUnit.SECONDS));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void doesNotInheritAnotherRequestTraceWhileHelpingAnUnselectedRequest(boolean applicationChild)
      throws Exception {
    var entered = new CountDownLatch(1);
    var allowHelping = new CountDownLatch(1);
    var completed = new CountDownLatch(2);
    var outerThread = new CompletableFuture<Thread>();
    var innerThread = new CompletableFuture<Thread>();
    var outerSpan = new CompletableFuture<String>();
    var exporter = InMemorySpanExporter.create();

    try (var provider =
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        var workers = new ForkJoinPool(1);
        var drivers = Executors.newVirtualThreadPerTaskExecutor();
        var app = new Shoostr(Options.defaults().withPort(0))) {
      var telemetry =
          OpenTelemetrySdk.builder()
              .setTracerProvider(provider)
              .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
              .build();
      var tracing = new OpenTelemetryTracing(telemetry);
      app.extensions(tracing).afterRequest(_ -> completed.countDown());
      app.routes()
          .get(
              "/outer",
              workers,
              (_, response) -> {
                var child =
                    applicationChild
                        ? telemetry.getTracer("test").spanBuilder("outer-business").startSpan()
                        : null;
                var current = child == null ? Span.current() : child;

                try (var _ = current.makeCurrent()) {
                  outerThread.complete(Thread.currentThread());
                  outerSpan.complete(current.getSpanContext().getSpanId());
                  entered.countDown();
                  if (!allowHelping.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Helping was not enabled");
                  }

                  assertTrue(workers.awaitQuiescence(5, TimeUnit.SECONDS));
                  response.text(Span.current().getSpanContext().getSpanId());
                } finally {
                  if (child != null) {
                    child.end();
                  }
                }
              },
              e -> e.get(tracing).enabled())
          .get(
              "/inner",
              workers,
              (_, response) -> {
                innerThread.complete(Thread.currentThread());
                var inherited = Span.current().getSpanContext();
                var business =
                    telemetry.getTracer("test").spanBuilder("inner-business").startSpan();
                business.end();
                response.text(inherited.isValid() ? inherited.getTraceId() : "none");
              });

      try (var test = TestServer.start(app)) {
        var outer =
            drivers.submit(
                () ->
                    test.send(
                        request ->
                            request
                                .path("/outer")
                                .header(
                                    "traceparent",
                                    "00-11111111111111111111111111111111-0123456789012345-01"),
                        HttpResponse.BodyHandlers.ofString()));

        try {
          assertTrue(entered.await(5, TimeUnit.SECONDS));
          var inner =
              drivers.submit(
                  () ->
                      test.send(
                          request -> request.path("/inner"), HttpResponse.BodyHandlers.ofString()));
          await().atMost(Duration.ofSeconds(5)).until(() -> workers.getQueuedSubmissionCount() > 0);
          allowHelping.countDown();
          var innerResponse = inner.get(5, TimeUnit.SECONDS);
          var outerResponse = outer.get(5, TimeUnit.SECONDS);
          assertTrue(completed.await(5, TimeUnit.SECONDS));
          assertSame(outerThread.get(5, TimeUnit.SECONDS), innerThread.get(5, TimeUnit.SECONDS));
          assertEquals(outerSpan.get(5, TimeUnit.SECONDS), outerResponse.body());
          assertFalse(
              workers
                  .submit(() -> Span.current().getSpanContext().isValid())
                  .get(5, TimeUnit.SECONDS));
          var spans = exporter.getFinishedSpanItems();
          assertEquals(1, spans.stream().filter(span -> span.getKind() == SpanKind.SERVER).count());
          var business =
              spans.stream()
                  .filter(span -> "inner-business".equals(span.getName()))
                  .findFirst()
                  .orElseThrow();
          assertAll(
              () -> assertEquals("none", innerResponse.body()),
              () -> assertFalse(business.getParentSpanContext().isValid()),
              () -> assertNotEquals("11111111111111111111111111111111", business.getTraceId()));
        } finally {
          allowHelping.countDown();
        }
      }
    }
  }
}
