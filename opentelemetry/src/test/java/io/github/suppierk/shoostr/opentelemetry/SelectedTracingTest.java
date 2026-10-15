package io.github.suppierk.shoostr.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.AuthenticationExtension;
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

  @Test
  void createsServerSpansOnlyForEnabledRoutes() throws Exception {
    app.extensions(tracing);
    app.routes()
        .get(
            "/selected",
            (_, response) ->
                response.text(Boolean.toString(Span.current().getSpanContext().isValid())),
            extensions -> extensions.get(tracing).enabled());
    app.routes()
        .get(
            "/plain",
            (_, response) ->
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

  @Test
  void selectingConfigurationWithoutEnablingItLeavesTheRouteUntraced() throws Exception {
    app.extensions(tracing);
    app.routes()
        .get("/inert", (_, response) -> response.text("ok"), extensions -> extensions.get(tracing));

    try (var test = TestServer.start(app)) {
      assertEquals(200, test.send(request -> request.path("/inert")).statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      assertTrue(exporter.getFinishedSpanItems().isEmpty());
    }
  }

  @Test
  void inheritedAndRepeatedActivationCreatesOneServerSpan() throws Exception {
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
                            (_, response) -> response.text("ok"),
                            extensions -> {
                              var configuration = extensions.get(tracing);
                              assertSame(configuration, extensions.get(tracing));
                              configuration.enabled().enabled();
                            })),
            extensions -> extensions.get(tracing).enabled());

    try (var test = TestServer.start(app)) {
      assertEquals(200, test.send(request -> request.path("/api/v1/orders")).statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      assertEquals(1, exporter.getFinishedSpanItems().size());
      assertEquals("GET /api/v1/orders", exporter.getFinishedSpanItems().getFirst().getName());
    }
  }

  @Test
  void enablingOneChildDoesNotEnableItsSibling() throws Exception {
    app.extensions(tracing);
    app.routes()
        .path(
            "/api",
            group -> {
              group.get(
                  "/selected", (_, response) -> response.text("ok"), e -> e.get(tracing).enabled());
              group.get("/plain", (_, response) -> response.text("ok"));
            },
            e -> e.get(tracing));

    try (var test = TestServer.start(app)) {
      assertEquals(200, test.send(request -> request.path("/api/plain")).statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      assertTrue(exporter.getFinishedSpanItems().isEmpty());
      assertEquals(200, test.send(request -> request.path("/api/selected")).statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      assertEquals(1, exporter.getFinishedSpanItems().size());
    }
  }

  @Test
  void appWideTracingSubsumesLocalActivationOfTheSameInstance() throws Exception {
    app.extensions(tracing.allRequests());
    app.routes()
        .get(
            "/selected",
            (_, response) -> response.text("ok"),
            e -> e.get(tracing).enabled().enabled());

    try (var test = TestServer.start(app)) {
      assertEquals(200, test.send(request -> request.path("/selected")).statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      assertEquals(1, exporter.getFinishedSpanItems().size());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsActivationAfterTheRegistrationCallbackHasFinished(boolean appWide) {
    app.extensions(appWide ? tracing.allRequests() : tracing);
    var retained = new AtomicReference<OpenTelemetryTracing.Configuration>();
    app.routes().get("/selected", (_, _) -> {}, e -> retained.set(e.get(tracing).enabled()));
    var configuration = retained.get();
    assertThrows(IllegalStateException.class, configuration::enabled);
  }

  @Test
  void rejectsSelectionOfAnUninstalledInstance() {
    app.routes()
        .get(
            "/selected",
            (_, _) -> {},
            e -> assertThrows(IllegalArgumentException.class, () -> e.get(tracing)));
  }

  @Test
  void leavesApplicationSpansOnUnselectedRoutesControlledByTheSdk() throws Exception {
    app.extensions(tracing);
    app.routes().get("/selected", (_, _) -> {}, e -> e.get(tracing).enabled());
    app.routes()
        .get(
            "/business",
            (_, response) -> {
              var span = telemetry.getTracer("business").spanBuilder("business").startSpan();

              try (var _ = span.makeCurrent()) {
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

  @Test
  void activatesContextBeforeManagedAuthenticationAndMatchCallbacks() throws Exception {
    var authentication = new ContextAuthentication();
    app.extensions(tracing).authentication(authentication);
    app.onRouteMatched(
        (_, response) ->
            response.setHeader(
                "Observed", Boolean.toString(Span.current().getSpanContext().isValid())));
    app.routes()
        .get(
            "/selected",
            (request, response) -> response.text(request.principal().orElseThrow().getName()),
            e -> {
              e.get(authentication).enabled();
              e.get(tracing).enabled();
            });

    try (var test = TestServer.start(app)) {
      var result =
          test.send(request -> request.path("/selected"), HttpResponse.BodyHandlers.ofString());
      assertEquals("true", result.body());
      assertEquals("true", result.headers().firstValue("Observed").orElseThrow());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/missing", "/selected"})
  void doesNotObserveUnmatchedPathsOrMethodMismatches(String path) throws Exception {
    app.extensions(tracing);
    app.routes().get("/selected", (_, _) -> {}, e -> e.get(tracing).enabled());

    try (var test = TestServer.start(app)) {
      var result = test.send(request -> request.path(path).method("DELETE"));
      assertEquals("/missing".equals(path) ? 404 : 405, result.statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      assertTrue(exporter.getFinishedSpanItems().isEmpty());
    }
  }

  @Test
  void doesNotObserveARequestRejectedBeforeMatching() throws Exception {
    app.extensions(tracing);
    app.onRequestHeaders(
        (_, _) -> {
          throw new IllegalArgumentException("rejected");
        });
    app.routes().get("/selected", (_, _) -> {}, e -> e.get(tracing).enabled());

    try (var test = TestServer.start(app)) {
      assertEquals(500, test.send(request -> request.path("/selected")).statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      assertTrue(exporter.getFinishedSpanItems().isEmpty());
    }
  }

  @Test
  void distinctTracingInstancesEachContributeTheirOwnSpan() throws Exception {
    var second = new OpenTelemetryTracing(telemetry);
    app.extensions(tracing, second);
    app.routes()
        .get(
            "/selected",
            (_, response) -> response.text("ok"),
            e -> {
              e.get(tracing).enabled();
              e.get(second).enabled();
            });

    try (var test = TestServer.start(app)) {
      assertEquals(200, test.send(request -> request.path("/selected")).statusCode());
      assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
      var spans = exporter.getFinishedSpanItems();
      assertEquals(2, spans.size());
      assertTrue(spans.stream().allMatch(span -> span.getKind() == SpanKind.SERVER));
    }
  }

  @Test
  void recordsTheGenerated404WhenAvailabilityRejectsAMatchedEndpoint() throws Exception {
    app.extensions(tracing);
    app.routes()
        .when(
            () -> false,
            routes -> routes.get("/selected", (_, _) -> {}, e -> e.get(tracing).enabled()));

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
}
