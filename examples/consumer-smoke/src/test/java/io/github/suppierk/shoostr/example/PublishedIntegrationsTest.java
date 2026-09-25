package io.github.suppierk.shoostr.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.micrometer.MicrometerMetrics;
import io.github.suppierk.shoostr.opentelemetry.OpenTelemetryTracing;
import io.github.suppierk.shoostr.pac4j.Pac4j;
import io.github.suppierk.shoostr.testing.TestServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.http.client.direct.DirectBasicAuthClient;

class PublishedIntegrationsTest {
  @Test
  void publishedPac4jPolicyAuthenticatesAConsumerRequest() throws Exception {
    var provider =
        new DirectBasicAuthClient(
            (context, credentials) -> {
              var profile = new CommonProfile();
              profile.setId("consumer");
              credentials.setUserProfile(profile);
              return Optional.of(credentials);
            });

    try (var client = HttpClient.newHttpClient();
        var server =
            TestServer.start(
                app ->
                    app.routes()
                        .protect(
                            new Pac4j(provider, "Basic"),
                            routes ->
                                routes.get(
                                    "/secure",
                                    (request, response) ->
                                        response.text(
                                            Objects.requireNonNull(request.principal())
                                                .getName()))))) {
      var target = server.baseUri().resolve("secure");
      var missing =
          client.send(
              HttpRequest.newBuilder(target).build(), HttpResponse.BodyHandlers.discarding());
      assertEquals(401, missing.statusCode());
      assertEquals("Basic", missing.headers().firstValue("WWW-Authenticate").orElseThrow());

      var credentials =
          Base64.getEncoder().encodeToString("consumer:secret".getBytes(StandardCharsets.UTF_8));
      var authenticated =
          client.send(
              HttpRequest.newBuilder(target)
                  .header("Authorization", "Basic " + credentials)
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, authenticated.statusCode());
      assertEquals("consumer", authenticated.body());
    }
  }

  @Test
  void publishedMicrometerAdapterRecordsAConsumerRequest() throws Exception {
    var completed = new CountDownLatch(1);
    var registry = new SimpleMeterRegistry();

    try (var client = HttpClient.newHttpClient();
        var server =
            TestServer.start(
                app -> {
                  app.observe(new MicrometerMetrics(registry));
                  app.afterRequest(outcome -> completed.countDown());
                  app.routes().get("/metered", (request, response) -> response.text("ok"));
                })) {
      var response =
          client.send(
              HttpRequest.newBuilder(server.baseUri().resolve("metered")).build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("ok", response.body());
      assertTrue(completed.await(5, TimeUnit.SECONDS));
      var timer = registry.find("http.server.requests").timer();
      assertNotNull(timer);
      assertEquals(1, timer.count());
    } finally {
      registry.close();
    }
  }

  @Test
  void publishedOpenTelemetryAdapterRecordsAServerSpanForAConsumerRequest() throws Exception {
    var completed = new CountDownLatch(1);
    var exporter = InMemorySpanExporter.create();

    try (var provider =
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        var client = HttpClient.newHttpClient();
        var server =
            TestServer.start(
                app -> {
                  var telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
                  app.observe(new OpenTelemetryTracing(telemetry));
                  app.afterRequest(outcome -> completed.countDown());
                  app.routes().get("/traced", (request, response) -> response.text("ok"));
                })) {
      var response =
          client.send(
              HttpRequest.newBuilder(server.baseUri().resolve("traced")).build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("ok", response.body());
      assertTrue(completed.await(5, TimeUnit.SECONDS));
      var spans = exporter.getFinishedSpanItems();
      assertEquals(1, spans.size());
      var span = spans.getFirst();
      assertEquals(SpanKind.SERVER, span.getKind());
      assertEquals("GET /traced", span.getName());
      assertEquals(
          200L, span.getAttributes().get(AttributeKey.longKey("http.response.status_code")));
    }
  }
}
