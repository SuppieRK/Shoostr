package io.github.suppierk.shoostr.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OpenTelemetryTracingTest {
  @Test
  void bareInstallationDoesNotCreateSpansOrOwnTheProvider() throws Exception {
    var exporter = InMemorySpanExporter.create();

    try (var provider =
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        var app = new Shoostr(Options.defaults().withPort(0))) {
      var tracing =
          new OpenTelemetryTracing(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
      app.extensions(tracing);
      assertThrows(IllegalStateException.class, tracing::allRequests);
      app.routes()
          .get(
              "/",
              (_, response) ->
                  response.text(Boolean.toString(Span.current().getSpanContext().isValid())));

      try (var test = TestServer.start(app)) {
        var result = test.send(request -> request.path("/"), HttpResponse.BodyHandlers.ofString());
        assertEquals("false", result.body());
        assertTrue(exporter.getFinishedSpanItems().isEmpty());
        test.close();
        var span = provider.get("borrowed").spanBuilder("still usable").startSpan();
        span.end();
        assertEquals(1, exporter.getFinishedSpanItems().size());
      }
    }
  }

  @Test
  void recordsExplicitServerErrorResponsesWithoutApplicationFailures() throws Exception {
    var exporter = InMemorySpanExporter.create();
    var completed = new CompletableFuture<Void>();

    try (var provider =
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        var app = new Shoostr(Options.defaults().withPort(0))) {
      app.extensions(
          new OpenTelemetryTracing(OpenTelemetrySdk.builder().setTracerProvider(provider).build())
              .allRequests());
      app.afterRequest(_ -> completed.complete(null));
      app.routes().get("/explicit", (_, response) -> response.status(500).text("explicit"));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/explicit"), HttpResponse.BodyHandlers.discarding());
        assertEquals(500, result.statusCode());
        completed.get(5, TimeUnit.SECONDS);
        assertEquals(1, exporter.getFinishedSpanItems().size());
        var span = exporter.getFinishedSpanItems().getFirst();
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals("500", span.getAttributes().get(AttributeKey.stringKey("error.type")));
      }
    }
  }

  @Test
  void noOpTracingDoesNotChangeRequestHandlingOrInstallCurrentSpans() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.extensions(new OpenTelemetryTracing(OpenTelemetry.noop()).allRequests());
      app.routes()
          .get(
              "/ok",
              (_, response) ->
                  response.text(Boolean.toString(Span.current().getSpanContext().isValid())));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/ok"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("false", result.body());
      }
    }
  }

  @Test
  void recordsMissesAndTerminalFailuresWithoutSensitiveAttributesAndRestoresContext()
      throws Exception {
    var exporter = InMemorySpanExporter.create();
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();

    try (var provider =
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        var app = new Shoostr(Options.defaults().withPort(0))) {
      var telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
      app.extensions(new OpenTelemetryTracing(telemetry).allRequests());
      app.afterRequest(
          outcome -> {
            assertFalse(Span.current().getSpanContext().isValid());
            outcomes.add(outcome);
          });
      app.routes()
          .get(
              "/failure",
              (_, _) -> {
                throw new IllegalStateException("private");
              });
      app.routes().get("/transport", (_, response) -> response.text("ok"));
      app.afterResponseFlush(
          (request, _) -> {
            if (request.routePattern().filter("/transport"::equals).isPresent()) {
              throw new IOException("private");
            }
          });

      try (var test = TestServer.start(app)) {
        for (String path : new String[] {"/missing?secret=value", "/failure", "/transport"}) {
          test.send(
              request -> request.path(path).header("Authorization", "Bearer private"),
              HttpResponse.BodyHandlers.discarding());
          assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
        }
        var spans = exporter.getFinishedSpanItems();
        assertEquals(3, spans.size());
        var missing =
            spans.stream().filter(span -> "GET".equals(span.getName())).findFirst().orElseThrow();
        assertEquals(StatusCode.UNSET, missing.getStatus().getStatusCode());
        assertEquals(
            404L, missing.getAttributes().get(AttributeKey.longKey("http.response.status_code")));
        assertEquals(
            2,
            spans.stream()
                .filter(span -> span.getStatus().getStatusCode() == StatusCode.ERROR)
                .count());
        var transport =
            spans.stream()
                .filter(span -> "GET /transport".equals(span.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals(
            "transport", transport.getAttributes().get(AttributeKey.stringKey("error.type")));
        for (var span : spans) {
          assertFalse(span.getAttributes().toString().contains("private"));
          assertFalse(span.getAttributes().toString().contains("secret"));
          assertTrue(span.getEvents().isEmpty());
        }
      }
    }
  }

  @Test
  void isolatesConcurrentRemoteContextsAndSupportsExplicitDownstreamPropagation() throws Exception {
    var exporter = InMemorySpanExporter.create();
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();

    try (var provider =
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        var app = new Shoostr(Options.defaults().withPort(0));
        var testRequests = Executors.newVirtualThreadPerTaskExecutor()) {
      var telemetry =
          OpenTelemetrySdk.builder()
              .setTracerProvider(provider)
              .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
              .build();
      app.extensions(new OpenTelemetryTracing(telemetry).allRequests());
      app.afterRequest(outcomes::add);
      app.routes()
          .get(
              "/context",
              (_, response) -> {
                var headers = new HashMap<String, String>();
                telemetry
                    .getPropagators()
                    .getTextMapPropagator()
                    .inject(
                        Context.current(),
                        headers,
                        (carrier, key, value) -> {
                          if (carrier != null) {
                            carrier.put(key, value);
                          }
                        });
                response.text(Objects.requireNonNull(headers.get("traceparent")));
              });

      try (var test = TestServer.start(app)) {
        var requests = new ArrayList<Future<HttpResponse<String>>>();
        for (int index = 1; index <= 20; index++) {
          String traceId = "%032x".formatted(index);
          requests.add(
              testRequests.submit(
                  () ->
                      test.send(
                          request ->
                              request
                                  .path("/context")
                                  .header("traceparent", "00-" + traceId + "-0123456789abcdef-01"),
                          HttpResponse.BodyHandlers.ofString())));
        }
        for (int index = 1; index <= 20; index++) {
          assertTrue(
              requests
                  .get(index - 1)
                  .get(5, TimeUnit.SECONDS)
                  .body()
                  .startsWith("00-%032x-".formatted(index)));
          assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
        }
        assertEquals(20, exporter.getFinishedSpanItems().size());
        assertEquals(
            20,
            exporter.getFinishedSpanItems().stream().map(SpanData::getTraceId).distinct().count());
      }
    }
  }

  @Test
  void extractsTheRemoteParentAndScopesAChildSpanUntilTerminalCompletion() throws Exception {
    var exporter = InMemorySpanExporter.create();
    var completed = new CompletableFuture<Void>();

    try (var provider =
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        var app = new Shoostr(Options.defaults().withPort(0))) {
      var telemetry =
          OpenTelemetrySdk.builder()
              .setTracerProvider(provider)
              .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
              .build();
      app.extensions(new OpenTelemetryTracing(telemetry).allRequests());
      app.afterRequest(_ -> completed.complete(null));
      app.routes()
          .get(
              "/orders/{id}",
              (_, response) -> {
                assertTrue(Span.current().getSpanContext().isValid());
                var child = telemetry.getTracer("test").spanBuilder("child").startSpan();
                child.end();
                response.text(Span.current().getSpanContext().getSpanId());
              });

      try (var test = TestServer.start(app)) {
        var response =
            test.send(
                request ->
                    request
                        .path("/orders/secret")
                        .header(
                            "traceparent",
                            "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"),
                HttpResponse.BodyHandlers.ofString());
        completed.get(5, TimeUnit.SECONDS);
        var spans = exporter.getFinishedSpanItems();
        assertEquals(2, spans.size());
        var server =
            spans.stream()
                .filter(span -> span.getKind() == SpanKind.SERVER)
                .findFirst()
                .orElseThrow();
        var child =
            spans.stream().filter(span -> "child".equals(span.getName())).findFirst().orElseThrow();
        assertEquals("GET /orders/{id}", server.getName());
        assertEquals("0123456789abcdef0123456789abcdef", server.getTraceId());
        assertEquals("0123456789abcdef", server.getParentSpanId());
        assertEquals(server.getSpanId(), child.getParentSpanId());
        assertEquals(server.getSpanId(), response.body());
      }
    }
  }
}
