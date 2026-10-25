package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import nl.jqno.equalsverifier.api.SingleTypeEqualsVerifierApi;
import org.eclipse.jetty.compression.server.CompressionHandler;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.content.ResourceHttpContent;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExchangeEqualityTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preservesExchangeAndStreamIdentityThroughReadWriteFlushAndCompletion(boolean typed)
      throws Exception {
    var exchanges = new ArrayList<Map.Entry<Request, Response>>();
    var outputs = new ArrayList<Response.Stream>();
    var events = new ArrayList<Response.EventStream>();
    var hashes = new HashMap<Object, Integer>();
    var completed = new LinkedBlockingQueue<RequestOutcome>();

    try (var app = new Shoostr().afterRequest(completed::add)) {
      if (typed) {
        app.codec(codec("configured"));
      }

      app.routes()
          .post(
              "/exchange",
              (request, response) -> {
                exchanges.add(Map.entry(request, response));
                hashes.put(request, request.hashCode());
                hashes.put(response, response.hashCode());
                assertEquals("payload", request.bodyText());
                request.attribute("processed", true);
                var output = response.startStream("text/plain");
                outputs.add(output);
                hashes.put(output, output.hashCode());
                output.write("done").flush();
              })
          .get(
              "/events",
              (request, response) -> {
                var output = response.startEventStream();
                events.add(output);
                hashes.put(output, output.hashCode());
                output.send("hello").heartbeat();
              });

      try (var server = TestServer.start(app)) {
        for (int index = 0; index < 2; index++) {
          var response =
              server.send(
                  request ->
                      request
                          .path("/exchange")
                          .method("POST")
                          .body("payload".getBytes(StandardCharsets.UTF_8)),
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(200, response.statusCode());
          assertEquals("done", response.body());
          assertNotNull(completed.poll(3, TimeUnit.SECONDS));
          var eventResponse =
              server.send(request -> request.path("/events"), HttpResponse.BodyHandlers.ofString());
          assertEquals(200, eventResponse.statusCode());
          assertTrue(eventResponse.body().contains("data: hello"));
          assertNotNull(completed.poll(3, TimeUnit.SECONDS));
        }
        assertNotEquals(exchanges.get(0).getKey(), exchanges.get(1).getKey());
        assertNotEquals(exchanges.get(0).getValue(), exchanges.get(1).getValue());
        assertNotEquals(outputs.get(0), outputs.get(1));
        assertNotEquals(events.get(0), events.get(1));

        if (typed) {
          verifier(TypedRequest.class, exchanges, outputs).verify();
          verifier(TypedResponse.class, exchanges, outputs).verify();
          verifier(TypedEventStream.class, exchanges, outputs).verify();
        } else {
          verifier(Request.class, exchanges, outputs)
              .withRedefinedSubclass(TypedRequest.class)
              .verify();
          verifier(Response.class, exchanges, outputs)
              .withRedefinedSubclass(TypedResponse.class)
              .verify();
          verifier(Response.EventStream.class, exchanges, outputs)
              .withRedefinedSubclass(TypedEventStream.class)
              .verify();
        }

        verifier(Response.Stream.class, exchanges, outputs).verify();
        verifier(Class.forName(Response.class.getName() + "$FlushCompletion"), exchanges, outputs)
            .verify();
      }
    }

    // A completed operation remains the same key after native output and app cleanup.
    var retained = new HashSet<>(hashes.keySet());
    for (var entry : hashes.entrySet()) {
      assertEquals(entry.getKey(), entry.getKey());
      assertEquals(entry.getValue().intValue(), entry.getKey().hashCode());
      assertTrue(retained.remove(entry.getKey()));
    }
    assertTrue(retained.isEmpty());
  }

  private <T> SingleTypeEqualsVerifierApi<T> verifier(
      Class<T> type, List<Map.Entry<Request, Response>> exchanges, List<Response.Stream> outputs)
      throws IOException {
    return EqualsVerifier.forClass(type)
        .withPrefabValues(Request.class, exchanges.get(0).getKey(), exchanges.get(1).getKey())
        .withPrefabValues(Response.class, exchanges.get(0).getValue(), exchanges.get(1).getValue())
        .withPrefabValues(Response.Stream.class, outputs.get(0), outputs.get(1))
        .withPrefabValues(
            org.eclipse.jetty.server.Request.class,
            exchanges.get(0).getKey().nativeRequest(),
            exchanges.get(1).getKey().nativeRequest())
        .withPrefabValues(
            org.eclipse.jetty.server.Response.class,
            exchanges.get(0).getValue().nativeResponse(),
            exchanges.get(1).getValue().nativeResponse())
        .withPrefabValues(
            RadixRoutes.Endpoint.class,
            RadixRoutes.endpoint(HttpMethods.GET, "/first", (request, response) -> {}),
            RadixRoutes.endpoint(HttpMethods.POST, "/second", (request, response) -> {}))
        .withPrefabValues(
            CompressionHandler.class, new CompressionHandler(), new CompressionHandler())
        .withPrefabValues(
            ResourceHttpContent.class,
            new ResourceHttpContent(
                ResourceFactory.root()
                    .newResource(Files.writeString(directory.resolve("first.txt"), "first")),
                "text/plain"),
            new ResourceHttpContent(
                ResourceFactory.root()
                    .newResource(Files.writeString(directory.resolve("second.txt"), "second")),
                "text/plain"))
        .withPrefabValues(Codec.class, codec("first"), codec("second"))
        .withPrefabValues(MultiPart.Part.class, part("first"), part("second"))
        .withPrefabValues(
            Upload.class, new Upload(part("first"), () -> {}), new Upload(part("second"), () -> {}))
        .withPrefabValues(
            Thread.class, new Thread(() -> {}, "first"), new Thread(() -> {}, "second"))
        .withPrefabValues(
            Throwable.class,
            new IllegalStateException("first"),
            new IllegalStateException("second"))
        .withPrefabValues(
            InputStream.class,
            new ByteArrayInputStream(new byte[] {1}),
            new ByteArrayInputStream(new byte[] {2}))
        .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED);
  }

  private static Codec codec(String marker) {
    return new Codec() {
      @Override
      public <T> T read(byte[] source, Class<T> type) {
        return type.cast(new String(source, StandardCharsets.UTF_8));
      }

      @Override
      public byte[] write(Object value) {
        return (marker + value).getBytes(StandardCharsets.UTF_8);
      }
    };
  }

  private static MultiPart.Part part(String marker) {
    return new MultiPart.ByteBufferPart(
        marker,
        marker + ".txt",
        HttpFields.EMPTY,
        ByteBuffer.wrap(marker.getBytes(StandardCharsets.UTF_8)));
  }
}
