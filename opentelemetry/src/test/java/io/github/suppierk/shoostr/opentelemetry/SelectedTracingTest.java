package io.github.suppierk.shoostr.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.AuthenticationExtension;
import io.github.suppierk.shoostr.Lifecycle;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.OpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SelectedTracingTest {
  private final InMemorySpanExporter exporter;
  private final OpenTelemetrySdk telemetry;
  private final OpenTelemetryTracing tracing;
  private Shoostr app;
  private final LinkedBlockingQueue<RequestOutcome> outcomes;

  SelectedTracingTest() {
    this.exporter = InMemorySpanExporter.create();
    this.telemetry = createTelemetry(exporter);
    this.tracing = new OpenTelemetryTracing(telemetry);
    this.app = new Shoostr();
    this.outcomes = new LinkedBlockingQueue<>();
    app.afterRequest(outcomes::add);
  }

  private OpenTelemetrySdk createTelemetry(InMemorySpanExporter exporter) {
    return telemetryProperties(exporter).build();
  }

  private OpenTelemetrySdkBuilder telemetryProperties(InMemorySpanExporter exporter) {
    return OpenTelemetrySdk.builder().setTracerProvider(createTracerProvider(exporter));
  }

  private SdkTracerProvider createTracerProvider(InMemorySpanExporter exporter) {
    return tracerProviderProperties(exporter).build();
  }

  private SdkTracerProviderBuilder tracerProviderProperties(InMemorySpanExporter exporter) {
    return SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
  }

  @AfterEach
  void close() throws IOException {
    try {
      app.close();
    } finally {
      telemetry.close();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void createsServerSpansOnlyForSelectedRoutes(boolean virtualApplication) throws Exception {
    withExecutionMode(virtualApplication);

    app.routes()
        .get(
            "/selected",
            (ignored, response) ->
                response.text(Boolean.toString(Span.current().getSpanContext().isValid())),
            extensions -> extensions.withExtension(tracing));
    app.routes()
        .get(
            "/plain",
            (ignoredRequest, response) ->
                response.text(Boolean.toString(Span.current().getSpanContext().isValid())));

    try (var test = TestServer.start(app)) {
      var selected =
          test.send(request -> request.path("/selected"), HttpResponse.BodyHandlers.ofString());

      assertEquals("true", selected.body());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));

      var plain =
          test.send(request -> request.path("/plain"), HttpResponse.BodyHandlers.ofString());

      assertEquals("false", plain.body());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      assertEquals(1, exporter.getFinishedSpanItems().size());
      assertEquals("GET /selected", exporter.getFinishedSpanItems().getFirst().getName());
      assertFalse(Span.current().getSpanContext().isValid());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void excludingDefaultTracingLeavesTheRouteUntraced(boolean virtualApplication) throws Exception {
    withExecutionMode(virtualApplication);
    app.withExtension(tracing);
    app.routes()
        .get(
            "/inert",
            (ignoredRequest, response) -> response.text("ok"),
            extensions -> extensions.withoutExtension(tracing));

    try (var test = TestServer.start(app)) {
      assertEquals(200, test.send(request -> request.path("/inert")).statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      assertTrue(exporter.getFinishedSpanItems().isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void inheritedAndRepeatedActivationCreatesOneServerSpan(boolean virtualApplication)
      throws Exception {
    withExecutionMode(virtualApplication);

    app.routes()
        .path(
            "/api",
            group ->
                group.path(
                    "/v1",
                    nested ->
                        nested.get(
                            "/orders",
                            (ignoredRequest, response) -> response.text("ok"),
                            extensions -> {
                              var configuration = extensions.withExtension(tracing);

                              assertSame(configuration, extensions.withExtension(tracing));
                            })),
            extensions -> extensions.withExtension(tracing));

    try (var test = TestServer.start(app)) {
      assertEquals(200, test.send(request -> request.path("/api/v1/orders")).statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      assertEquals(1, exporter.getFinishedSpanItems().size());
      assertEquals("GET /api/v1/orders", exporter.getFinishedSpanItems().getFirst().getName());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void enablingOneChildDoesNotEnableItsSibling(boolean virtualApplication) throws Exception {
    withExecutionMode(virtualApplication);

    app.routes()
        .path(
            "/api",
            group -> {
              group.get(
                  "/selected",
                  (ignoredRequest, response) -> response.text("ok"),
                  lifecycle -> lifecycle.withExtension(tracing));
              group.get("/plain", (ignoredRequest, response) -> response.text("ok"));
            });

    try (var test = TestServer.start(app)) {
      assertEquals(200, test.send(request -> request.path("/api/plain")).statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      assertTrue(exporter.getFinishedSpanItems().isEmpty());
      assertEquals(200, test.send(request -> request.path("/api/selected")).statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      assertEquals(1, exporter.getFinishedSpanItems().size());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void applicationDefaultsAllowScopedExclusionAndLeaveMissesUntraced(boolean virtualApplication)
      throws Exception {
    withExecutionMode(virtualApplication);
    app.withExtension(tracing);
    app.routes()
        .get(
            "/selected",
            (ignoredRequest, response) -> response.text("ok"),
            lifecycle -> {
              lifecycle.withExtension(tracing);
              lifecycle.withoutExtension(tracing);
            });

    try (var test = TestServer.start(app)) {
      assertEquals(200, test.send(request -> request.path("/selected")).statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      assertEquals(404, test.send(request -> request.path("/missing")).statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      assertTrue(exporter.getFinishedSpanItems().isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsSelectionAfterTheRegistrationCallbackHasFinished(boolean appWide) {
    if (appWide) {
      app.withExtension(tracing);
    }

    var retained = new AtomicReference<Lifecycle>();
    app.routes()
        .get(
            "/selected",
            (ignoredRequest, ignoredResponse) -> {},
            lifecycle -> {
              lifecycle.withExtension(tracing);
              retained.set(lifecycle);
            });
    var configuration = retained.get();

    assertThrows(IllegalStateException.class, () -> configuration.withExtension(tracing));
  }

  @Test
  void selectingAnInstanceNeedsNoPriorApplicationRegistration() throws Exception {
    app.routes()
        .get(
            "/selected",
            (request, response) -> response.text("ready"),
            lifecycle -> lifecycle.withExtension(tracing));

    try (var server = TestServer.start(app)) {
      assertEquals(200, server.send(request -> request.path("/selected")).statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      assertEquals(1, exporter.getFinishedSpanItems().size());
      assertEquals("GET /selected", exporter.getFinishedSpanItems().getFirst().getName());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void leavesApplicationSpansOnUnselectedRoutesControlledByTheSdk(boolean virtualApplication)
      throws Exception {
    withExecutionMode(virtualApplication);

    app.routes()
        .get(
            "/selected",
            (ignoredRequest, ignoredResponse) -> {},
            lifecycle -> lifecycle.withExtension(tracing));
    app.routes()
        .get(
            "/business",
            (ignoredRequest, response) -> {
              var span = telemetry.getTracer("business").spanBuilder("business").startSpan();

              try (var ignoredScope = span.makeCurrent()) {
                response.text(Span.current().getSpanContext().getTraceId());
              } finally {
                span.end();
              }
            });

    try (var test = TestServer.start(app)) {
      assertEquals(200, test.send(request -> request.path("/business")).statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));

      var spans = exporter.getFinishedSpanItems();

      assertEquals(1, spans.size());
      assertEquals("business", spans.getFirst().getName());
      assertEquals(SpanKind.INTERNAL, spans.getFirst().getKind());
      assertFalse(spans.getFirst().getParentSpanContext().isValid());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void activatesContextBeforeManagedAuthenticationAndMatchCallbacks(boolean virtualApplication)
      throws Exception {
    withExecutionMode(virtualApplication);
    var authentication = new ContextAuthentication();

    app.onRouteMatched(
        (ignoredRequest, response) ->
            response.setHeader(
                "Observed", Boolean.toString(Span.current().getSpanContext().isValid())));
    app.routes()
        .get(
            "/selected",
            (request, response) -> response.text(request.principal().orElseThrow().getName()),
            lifecycle -> {
              lifecycle.withExtension(authentication);
              lifecycle.withExtension(tracing);
            });

    try (var test = TestServer.start(app)) {
      var result =
          test.send(request -> request.path("/selected"), HttpResponse.BodyHandlers.ofString());

      assertEquals("true", result.body());
      assertEquals("true", result.headers().firstValue("Observed").orElseThrow());
    }
  }

  @ParameterizedTest
  @CsvSource({"/missing,false", "/missing,true", "/selected,false", "/selected,true"})
  void doesNotObserveUnmatchedPathsOrMethodMismatches(String path, boolean virtualApplication)
      throws Exception {
    withExecutionMode(virtualApplication);

    app.routes()
        .get(
            "/selected",
            (ignoredRequest, ignoredResponse) -> {},
            lifecycle -> lifecycle.withExtension(tracing));

    try (var test = TestServer.start(app)) {
      var result = test.send(request -> request.path(path).method("DELETE"));

      assertEquals("/missing".equals(path) ? 404 : 405, result.statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      assertTrue(exporter.getFinishedSpanItems().isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void doesNotObserveARequestRejectedBeforeMatching(boolean virtualApplication) throws Exception {
    withExecutionMode(virtualApplication);

    app.onRequestHeaders(
        (ignoredRequest, ignoredResponse) -> {
          throw new IllegalArgumentException("rejected");
        });
    app.routes()
        .get(
            "/selected",
            (ignoredRequest, ignoredResponse) -> {},
            lifecycle -> lifecycle.withExtension(tracing));

    try (var test = TestServer.start(app)) {
      assertEquals(500, test.send(request -> request.path("/selected")).statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      assertTrue(exporter.getFinishedSpanItems().isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void distinctTracingInstancesEachContributeTheirOwnSpan(boolean virtualApplication)
      throws Exception {
    withExecutionMode(virtualApplication);
    var second = new OpenTelemetryTracing(telemetry);

    app.routes()
        .get(
            "/selected",
            (ignoredRequest, response) -> response.text("ok"),
            lifecycle -> {
              lifecycle.withExtension(tracing);
              lifecycle.withExtension(second);
            });

    try (var test = TestServer.start(app)) {
      assertEquals(200, test.send(request -> request.path("/selected")).statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));

      var spans = exporter.getFinishedSpanItems();

      assertEquals(2, spans.size());
      assertTrue(spans.stream().allMatch(span -> span.getKind() == SpanKind.SERVER));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void recordsTheGenerated404WhenAvailabilityRejectsAMatchedEndpoint(boolean virtualApplication)
      throws Exception {
    withExecutionMode(virtualApplication);

    app.routes()
        .when(
            () -> false,
            routes ->
                routes.get(
                    "/selected",
                    (ignoredRequest, ignoredResponse) -> {},
                    lifecycle -> lifecycle.withExtension(tracing)));

    try (var test = TestServer.start(app)) {
      assertEquals(404, test.send(request -> request.path("/selected")).statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));

      var spans = exporter.getFinishedSpanItems();

      assertEquals(1, spans.size());
      assertEquals("GET /selected", spans.getFirst().getName());
      assertEquals(
          404L,
          spans.getFirst().getAttributes().get(AttributeKey.longKey("http.response.status_code")));
    }
  }

  private void withExecutionMode(boolean virtualApplication) throws IOException {
    app.close();

    var options = new Options();
    options.setVirtualThreads(virtualApplication);

    app =
        new Shoostr(options)
            .afterRequest(outcomes::add)
            .onRequestHeaders(
                (request, response) ->
                    assertEquals(virtualApplication, Thread.currentThread().isVirtual()));
  }

  private static final class ContextAuthentication extends AuthenticationExtension {
    @Override
    public void handle(Request request, Response response) {
      var observed = Boolean.toString(Span.current().getSpanContext().isValid());
      request.principal(() -> observed);
    }
  }
}
