package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Test;

class EndpointTracingTest {
  @Test
  void preservesServerSpanAndRemoteParentAcrossPlatformHandoff() throws Exception {
    var exporter = InMemorySpanExporter.create();
    var completed = new CompletableFuture<Void>();
    var transport = new QueuedThreadPool(16, 8);
    transport.setReservedThreads(0);
    var workers = Executors.newFixedThreadPool(1);

    try (var provider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()) {
      var telemetry =
          OpenTelemetrySdk.builder()
              .setTracerProvider(provider)
              .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
              .build();
      Handler endpoint =
          (_, response) -> {
            var child = telemetry.getTracer("test").spanBuilder("child").startSpan();
            child.end();
            response.text(Span.current().getSpanContext().getTraceId());
          };
      var execution = new ExecutionSettings(transport, true, workers, Set.of("/trace"));

      try (var app = new Shoostr(Options.defaults().withPort(0), execution)) {
        app.extensions(new OpenTelemetryTracing(telemetry).allRequests());
        app.afterRequest(_ -> completed.complete(null));
        app.routes().get("/trace", endpoint);

        try (var test = TestServer.start(app)) {
          var response =
              test.send(
                  request ->
                      request
                          .path("/trace")
                          .header(
                              "traceparent",
                              "00-12345678901234567890123456789012-0123456789012345-01"),
                  HttpResponse.BodyHandlers.ofString());
          assertEquals("12345678901234567890123456789012", response.body());
          completed.get(5, TimeUnit.SECONDS);
          var spans = exporter.getFinishedSpanItems();
          assertEquals(2, spans.size());
          var server =
              spans.stream()
                  .filter(span -> span.getKind() == SpanKind.SERVER)
                  .findFirst()
                  .orElseThrow();
          var child =
              spans.stream()
                  .filter(span -> "child".equals(span.getName()))
                  .findFirst()
                  .orElseThrow();
          assertEquals("0123456789012345", server.getParentSpanId());
          assertEquals(server.getSpanId(), child.getParentSpanId());
          assertEquals(server.getTraceId(), child.getTraceId());
          assertFalse(
              workers
                  .submit(() -> Span.current().getSpanContext().isValid())
                  .get(5, TimeUnit.SECONDS));
        }
      }
    }
  }
}
