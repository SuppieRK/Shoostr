package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.testing.TestServer;
import java.lang.reflect.Modifier;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class TypedCodecTest {
  @Test
  void decodesConcreteRequestAndEncodesFiniteResponse() throws Exception {
    var codec = new GreetingCodec();

    try (var app = new Shoostr()) {
      app.codec(codec)
          .routes()
          .post(
              "/greeting",
              (request, response) -> {
                var greeting = request.body(Greeting.class);
                response
                    .status(201)
                    .body(MediaType.APPLICATION_JSON, new Greeting("Hello " + greeting.name()));
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/greeting")
                        .method("POST")
                        .body("Alice".getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, result.statusCode());
        assertEquals("{\"name\":\"Hello Alice\"}", result.body());
        assertEquals("application/json", result.headers().firstValue("Content-Type").orElseThrow());
      }
    }
  }

  @Test
  void permitsOnlyFrameworkOwnedFinalTypedSubclasses() {
    assertTrue(Request.class.isSealed());
    assertTrue(Response.class.isSealed());
    assertArrayEquals(new Class<?>[] {TypedRequest.class}, Request.class.getPermittedSubclasses());
    assertArrayEquals(
        new Class<?>[] {TypedResponse.class}, Response.class.getPermittedSubclasses());
    assertTrue(Modifier.isFinal(TypedRequest.class.getModifiers()));
    assertTrue(Modifier.isFinal(TypedResponse.class.getModifiers()));
    assertEquals(0, TypedRequest.class.getConstructors().length);
    assertEquals(0, TypedResponse.class.getConstructors().length);
  }

  @Test
  void leavesOrdinaryHandlerTypesAndMethodsUnchangedWithoutCodec() throws Exception {
    assertThrows(NoSuchMethodException.class, () -> Request.class.getMethod("body", Class.class));
    assertThrows(
        NoSuchMethodException.class,
        () -> Response.class.getMethod("body", MediaType.class, Object.class));

    try (var app = new Shoostr()) {
      app.routes()
          .get(
              "/ordinary",
              (request, response) -> {
                assertEquals(Request.class, request.getClass());
                assertEquals(Response.class, response.getClass());
                response.text("ordinary");
              });

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/ordinary")).statusCode());
      }
    }
  }

  @Test
  void rejectsSecondCodecConfiguration() throws Exception {
    try (var app = new Shoostr()) {
      app.codec(new TestCodec());
      var second = new TestCodec();
      assertThrows(IllegalStateException.class, () -> app.codec(second));
    }
  }

  @Test
  void rejectsCodecConfigurationAfterStartup() throws Exception {
    try (var app = new Shoostr();
        var test = TestServer.start(app)) {
      var codec = new TestCodec();
      assertThrows(IllegalStateException.class, () -> app.codec(codec));
      assertEquals(404, test.send(request -> request.path("/missing")).statusCode());
    }
  }

  @Test
  void rejectsCodecConfigurationAfterClosure() throws Exception {
    var app = new Shoostr();
    app.close();
    var codec = new TestCodec();
    assertThrows(IllegalStateException.class, () -> app.codec(codec));
  }

  @Test
  @SuppressWarnings("NullAway") // Deliberate invalid input at the public configuration interface.
  void rejectsNullCodecWithoutConsumingConfiguration() throws Exception {
    try (var app = new Shoostr()) {
      assertThrows(NullPointerException.class, () -> app.codec(null));
      app.codec(new TestCodec()).routes().get("/valid", (_, response) -> response.text("valid"));

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/valid")).statusCode());
      }
    }
  }

  @Test
  void neverClosesCallerOwnedCodec() throws Exception {
    var codec = new OwnedCodec();
    var app = new Shoostr();
    app.codec(codec);
    app.close();
    app.close();
    assertEquals(0, codec.closes);
    codec.close();
    assertEquals(1, codec.closes);
  }

  private record Greeting(String name) {
    Greeting {
      Objects.requireNonNull(name);
    }
  }

  private static class OwnedCodec extends TestCodec implements AutoCloseable {
    private int closes;

    @Override
    public void close() {
      closes++;
    }
  }

  private static class GreetingCodec implements Codec {
    @Override
    public <T> T read(byte[] source, Class<T> type) throws Exception {
      return type.cast(new Greeting(new String(source, StandardCharsets.UTF_8)));
    }

    @Override
    public byte[] write(Object value) throws Exception {
      var greeting = (Greeting) value;
      return ("{\"name\":\"" + greeting.name() + "\"}").getBytes(StandardCharsets.UTF_8);
    }
  }
}
