package io.github.suppierk.shoostr.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Shoostr;
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
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OpenTelemetryTracingTest {
  @Test
  void noOpTracingDoesNotChangeRequestHandlingOrInstallCurrentSpans() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.observe(new OpenTelemetryTracing(OpenTelemetry.noop()));
      app.routes()
          .get(
              "/ok",
              (request, response) ->
                  response.text(Boolean.toString(Span.current().getSpanContext().isValid())));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/ok"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("false", result.body());
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
        var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      var telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
      app.observe(new OpenTelemetryTracing(telemetry));
      app.afterRequest(
          outcome -> {
            assertFalse(Span.current().getSpanContext().isValid());
            outcomes.add(outcome);
          });
      app.routes()
          .get(
              "/failure",
              (request, response) -> {
                throw new IllegalStateException("private");
              });
      app.routes().get("/transport", (request, response) -> response.text("ok"));
      app.afterResponseFlush(
          (request, response) -> {
            if ("/transport".equals(request.routePattern())) {
              throw new IOException("private");
            }
          });
      app.start();
      for (String path : new String[] {"/missing?secret=value", "/failure", "/transport"}) {
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
                .header("Authorization", "Bearer private")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());
        assertTrue(outcomes.poll(5, TimeUnit.SECONDS) != null);
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
      for (var span : spans) {
        assertFalse(span.getAttributes().toString().contains("private"));
        assertFalse(span.getAttributes().toString().contains("secret"));
        assertTrue(span.getEvents().isEmpty());
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
        var client = HttpClient.newHttpClient()) {
      var telemetry =
          OpenTelemetrySdk.builder()
              .setTracerProvider(provider)
              .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
              .build();
      app.observe(new OpenTelemetryTracing(telemetry));
      app.afterRequest(outcomes::add);
      app.routes()
          .get(
              "/context",
              (request, response) -> {
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
      app.start();
      var requests = new ArrayList<CompletableFuture<HttpResponse<String>>>();
      for (int index = 1; index <= 20; index++) {
        String traceId = "%032x".formatted(index);
        requests.add(
            client.sendAsync(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/context"))
                    .header("traceparent", "00-" + traceId + "-0123456789abcdef-01")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()));
      }
      for (int index = 1; index <= 20; index++) {
        assertTrue(
            requests
                .get(index - 1)
                .get(5, TimeUnit.SECONDS)
                .body()
                .startsWith("00-%032x-".formatted(index)));
        assertTrue(outcomes.poll(5, TimeUnit.SECONDS) != null);
      }
      assertEquals(20, exporter.getFinishedSpanItems().size());
      assertEquals(
          20,
          exporter.getFinishedSpanItems().stream()
              .map(span -> span.getTraceId())
              .distinct()
              .count());
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
        var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      var telemetry =
          OpenTelemetrySdk.builder()
              .setTracerProvider(provider)
              .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
              .build();
      app.observe(new OpenTelemetryTracing(telemetry));
      app.afterRequest(outcome -> completed.complete(null));
      app.routes()
          .get(
              "/orders/{id}",
              (request, response) -> {
                assertTrue(Span.current().getSpanContext().isValid());
                var child = telemetry.getTracer("test").spanBuilder("child").startSpan();
                child.end();
                response.text(Span.current().getSpanContext().getSpanId());
              });
      app.start();
      var response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + app.port() + "/orders/secret"))
                  .header("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01")
                  .GET()
                  .build(),
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
