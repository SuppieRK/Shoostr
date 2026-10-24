package io.github.suppierk.shoostr.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.AuthenticationExtension;
import io.github.suppierk.shoostr.Extensions;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
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
  private final Shoostr app;
  private final LinkedBlockingQueue<RequestOutcome> outcomes;

  SelectedTracingTest() {
    exporter = InMemorySpanExporter.create();
    telemetry =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build())
            .build();
    tracing = new OpenTelemetryTracing(telemetry);
    app = new Shoostr();
    outcomes = new LinkedBlockingQueue<>();
    app.afterRequest(outcomes::add);
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
    withExecutionMode(app, virtualApplication);
    app.extensions(tracing);
    app.routes()
        .get(
            "/selected",
            (ignored, response) ->
                response.text(Boolean.toString(Span.current().getSpanContext().isValid())),
            extensions -> extensions.with(tracing));
    app.routes()
        .get(
            "/plain",
            (ignored2, response) ->
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
    withExecutionMode(app, virtualApplication);
    app.extensions(tracing).configureExtensionsDefaults(e -> e.with(tracing));
    app.routes()
        .get(
            "/inert",
            (ignored3, response) -> response.text("ok"),
            extensions -> extensions.without(tracing));

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
    withExecutionMode(app, virtualApplication);
    app.extensions(tracing);
    app.routes()
        .path(
            "/api",
            group ->
                group.path(
                    "/v1",
                    nested ->
                        nested.get(
                            "/orders",
                            (ignored4, response) -> response.text("ok"),
                            extensions -> {
                              var configuration = extensions.with(tracing);
                              assertSame(configuration, extensions.with(tracing));
                            })),
            extensions -> extensions.with(tracing));

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
    withExecutionMode(app, virtualApplication);
    app.extensions(tracing);
    app.routes()
        .path(
            "/api",
            group -> {
              group.get(
                  "/selected", (ignored5, response) -> response.text("ok"), e -> e.with(tracing));
              group.get("/plain", (ignored6, response) -> response.text("ok"));
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
  void appWideTracingSurvivesScopedExclusionWithoutCreatingDuplicateSpans(
      boolean virtualApplication) throws Exception {
    withExecutionMode(app, virtualApplication);
    app.extensions(tracing.allRequests());
    app.routes()
        .get(
            "/selected",
            (ignored7, response) -> response.text("ok"),
            e -> {
              e.with(tracing);
              e.without(tracing);
            });

    try (var test = TestServer.start(app)) {
      assertEquals(200, test.send(request -> request.path("/selected")).statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      assertEquals(1, exporter.getFinishedSpanItems().size());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsSelectionAfterTheRegistrationCallbackHasFinished(boolean appWide) {
    app.extensions(appWide ? tracing.allRequests() : tracing);
    var retained = new AtomicReference<Extensions>();
    app.routes()
        .get(
            "/selected",
            (ignored8, ignored9) -> {},
            e -> {
              e.with(tracing);
              retained.set(e);
            });
    var configuration = retained.get();
    assertThrows(IllegalStateException.class, () -> configuration.with(tracing));
  }

  @Test
  void rejectsSelectionOfAnUninstalledInstance() {
    app.routes()
        .get(
            "/selected",
            (ignored10, ignored11) -> {},
            e -> assertThrows(IllegalArgumentException.class, () -> e.with(tracing)));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void leavesApplicationSpansOnUnselectedRoutesControlledByTheSdk(boolean virtualApplication)
      throws Exception {
    withExecutionMode(app, virtualApplication);
    app.extensions(tracing);
    app.routes().get("/selected", (ignored12, ignored13) -> {}, e -> e.with(tracing));
    app.routes()
        .get(
            "/business",
            (ignored14, response) -> {
              var span = telemetry.getTracer("business").spanBuilder("business").startSpan();

              try (var ignored15 = span.makeCurrent()) {
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
    withExecutionMode(app, virtualApplication);
    var authentication = new ContextAuthentication();
    app.extensions(tracing).authentication(authentication);
    app.onRouteMatched(
        (ignored16, response) ->
            response.setHeader(
                "Observed", Boolean.toString(Span.current().getSpanContext().isValid())));
    app.routes()
        .get(
            "/selected",
            (request, response) -> response.text(request.principal().orElseThrow().getName()),
            e -> {
              e.with(authentication);
              e.with(tracing);
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
    withExecutionMode(app, virtualApplication);
    app.extensions(tracing);
    app.routes().get("/selected", (ignored17, ignored18) -> {}, e -> e.with(tracing));

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
    withExecutionMode(app, virtualApplication);
    app.extensions(tracing);
    app.onRequestHeaders(
        (ignored19, ignored20) -> {
          throw new IllegalArgumentException("rejected");
        });
    app.routes().get("/selected", (ignored21, ignored22) -> {}, e -> e.with(tracing));

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
    withExecutionMode(app, virtualApplication);
    var second = new OpenTelemetryTracing(telemetry);
    app.extensions(tracing, second);
    app.routes()
        .get(
            "/selected",
            (ignored23, response) -> response.text("ok"),
            e -> {
              e.with(tracing);
              e.with(second);
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
    withExecutionMode(app, virtualApplication);
    app.extensions(tracing);
    app.routes()
        .when(
            () -> false,
            routes -> routes.get("/selected", (ignored24, ignored25) -> {}, e -> e.with(tracing)));

    try (var test = TestServer.start(app)) {
      assertEquals(404, test.send(request -> request.path("/selected")).statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      var spans = exporter.getFinishedSpanItems();
      assertEquals(1, spans.size());
      assertEquals("GET /selected", spans.getFirst().getName());
      assertEquals(
          404L,
          spans
              .getFirst()
              .getAttributes()
              .get(io.opentelemetry.api.common.AttributeKey.longKey("http.response.status_code")));
    }
  }

  private static final class ContextAuthentication extends AuthenticationExtension {
    @Override
    public void handle(Request request, Response response) {
      var observed = Boolean.toString(Span.current().getSpanContext().isValid());
      request.principal(() -> observed);
    }
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
