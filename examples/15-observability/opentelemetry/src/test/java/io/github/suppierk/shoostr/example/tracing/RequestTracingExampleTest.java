package io.github.suppierk.shoostr.example.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.net.http.HttpResponse;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequestTracingExampleTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void continuesARemoteParentAndCreatesAChildOnTheSelectedWorker(boolean virtualApplication)
      throws Exception {
    var exporter = InMemorySpanExporter.create();
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();

    try (var telemetry = telemetry(exporter);
        var workers = Executors.newSingleThreadExecutor();
        var test =
            TestServer.start(
                withExecutionMode(
                        RequestTracingExample.application(telemetry, workers), virtualApplication)
                    .afterRequest(outcomes::offer))) {
      var response =
          test.send(
              request ->
                  request
                      .path("/api/items/42")
                      .header(
                          "traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("item 42", response.body());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      var spans = exporter.getFinishedSpanItems();
      assertEquals(2, spans.size());
      var server =
          spans.stream()
              .filter(span -> span.getKind() == SpanKind.SERVER)
              .findFirst()
              .orElseThrow();
      var child =
          spans.stream()
              .filter(span -> "load-item".equals(span.getName()))
              .findFirst()
              .orElseThrow();
      assertEquals("GET /api/items/{id}", server.getName());
      assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", server.getTraceId());
      assertEquals("00f067aa0ba902b7", server.getParentSpanId());
      assertEquals(server.getSpanId(), child.getParentSpanId());
      assertEquals(server.getTraceId(), child.getTraceId());
      assertEquals(
          "/api/items/{id}", server.getAttributes().get(AttributeKey.stringKey("http.route")));
      assertEquals(
          200L, server.getAttributes().get(AttributeKey.longKey("http.response.status_code")));
      assertFalse(
          workers.submit(() -> Span.current().getSpanContext().isValid()).get(5, TimeUnit.SECONDS));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void leavesUnselectedRequestsUntracedAndKeepsBorrowedResourcesOpen(boolean virtualApplication)
      throws Exception {
    var exporter = InMemorySpanExporter.create();
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();

    try (var telemetry = telemetry(exporter);
        var workers = Executors.newSingleThreadExecutor()) {
      try (var test =
          TestServer.start(
              withExecutionMode(
                      RequestTracingExample.application(telemetry, workers), virtualApplication)
                  .afterRequest(outcomes::offer))) {
        var response =
            test.send(request -> request.path("/health"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("traced=false", response.body());
        assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
        assertTrue(exporter.getFinishedSpanItems().isEmpty());
      }

      assertFalse(workers.isShutdown());
      assertFalse(
          workers.submit(() -> Span.current().getSpanContext().isValid()).get(5, TimeUnit.SECONDS));
      var span = telemetry.getTracer("application").spanBuilder("still available").startSpan();
      span.end();
      assertEquals(1, exporter.getFinishedSpanItems().size());
      assertEquals("still available", exporter.getFinishedSpanItems().getFirst().getName());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void honorsAnUnsampledRemoteParentWithoutChangingTheResponse(boolean virtualApplication)
      throws Exception {
    var exporter = InMemorySpanExporter.create();
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();

    try (var telemetry = telemetry(exporter);
        var workers = Executors.newSingleThreadExecutor();
        var test =
            TestServer.start(
                withExecutionMode(
                        RequestTracingExample.application(telemetry, workers), virtualApplication)
                    .afterRequest(outcomes::offer))) {
      var response =
          test.send(
              request ->
                  request
                      .path("/api/items/42")
                      .header(
                          "traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("item 42", response.body());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      assertTrue(exporter.getFinishedSpanItems().isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void recordsAServerFailureWithoutLeakingTheExceptionMessage(boolean virtualApplication)
      throws Exception {
    var exporter = InMemorySpanExporter.create();
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();

    try (var telemetry = telemetry(exporter);
        var workers = Executors.newSingleThreadExecutor();
        var app =
            withExecutionMode(
                    RequestTracingExample.application(telemetry, workers), virtualApplication)
                .beforeRouteHandler(
                    (ignored, ignored2) -> {
                      throw new IllegalStateException("private failure");
                    })
                .afterRequest(outcomes::offer);
        var test = TestServer.start(app)) {
      assertEquals(
          500,
          test.send(
                  request ->
                      request
                          .path("/api/items/42?secret=value")
                          .header("Authorization", "Bearer private"))
              .statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      assertEquals(1, exporter.getFinishedSpanItems().size());
      var server = exporter.getFinishedSpanItems().getFirst();
      assertEquals(SpanKind.SERVER, server.getKind());
      assertEquals(StatusCode.ERROR, server.getStatus().getStatusCode());
      assertEquals(
          500L, server.getAttributes().get(AttributeKey.longKey("http.response.status_code")));
      assertEquals("500", server.getAttributes().get(AttributeKey.stringKey("error.type")));
      assertTrue(server.getEvents().isEmpty());
      assertFalse(server.getAttributes().toString().contains("private"));
      assertFalse(server.getAttributes().toString().contains("secret"));
      assertFalse(
          workers.submit(() -> Span.current().getSpanContext().isValid()).get(5, TimeUnit.SECONDS));
    }
  }

  private static OpenTelemetrySdk telemetry(InMemorySpanExporter exporter) {
    return OpenTelemetrySdk.builder()
        .setTracerProvider(
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build())
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build();
  }

  private static Shoostr withExecutionMode(Shoostr app, boolean virtualApplication) {
    if (virtualApplication) {
      app.virtualThreads();
    }

    return app.onRequestHeaders(
        (request, response) ->
            assertEquals(virtualApplication, Thread.currentThread().isVirtual()));
  }
}
