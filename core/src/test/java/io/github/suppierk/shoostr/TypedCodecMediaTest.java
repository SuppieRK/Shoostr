package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class TypedCodecMediaTest {
  @ParameterizedTest
  @ValueSource(strings = {"application/json", "text/plain"})
  void servesEncodedJsonOrExplicitTextBytesFromSameTypedEndpoint(String accept) throws Exception {
    try (var app = new Shoostr()) {
      app.codec(new TestCodec())
          .routes()
          .get(
              "/representation",
              (_, response) -> {
                var selected = response.negotiate(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN);
                if (MediaType.APPLICATION_JSON.equals(selected)) {
                  response.body(selected, "hello");
                } else {
                  response.body(selected, "hello".getBytes(StandardCharsets.UTF_8));
                }
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request -> request.path("/representation").header("Accept", accept),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("application/json".equals(accept) ? "\"hello\"" : "hello", result.body());
        assertEquals(accept, result.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("Accept", result.headers().firstValue("Vary").orElseThrow());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"string", "mediaType", "text"})
  void bypassesCodecForExplicitBytesAndText(String operation) throws Exception {
    var writes = new AtomicInteger();
    var codec =
        new TestCodec() {
          @Override
          public byte[] write(Object value) throws Exception {
            writes.incrementAndGet();
            return super.write(value);
          }
        };
    var bytes = "plain".getBytes(StandardCharsets.UTF_8);

    try (var app = new Shoostr()) {
      app.codec(codec)
          .routes()
          .get(
              "/raw",
              (_, response) -> {
                switch (operation) {
                  case "string" -> response.body("text/plain", bytes);
                  case "mediaType" -> response.body(MediaType.TEXT_PLAIN, bytes);
                  case "text" -> response.text("plain");
                  default -> throw new IllegalArgumentException(operation);
                }
              });

      try (var test = TestServer.start(app)) {
        var result = test.send(request -> request.path("/raw"));
        assertEquals(200, result.statusCode());
        assertArrayEquals(bytes, result.body());
        assertEquals(
            "text".equals(operation) ? "text/plain; charset=utf-8" : "text/plain",
            result.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(0, writes.get());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void explicitlyReplacesExistingMediaTypeForCodecOutput(boolean typedMedia) throws Exception {
    var writes = new AtomicInteger();
    var codec =
        new TestCodec() {
          @Override
          public byte[] write(Object value) throws Exception {
            writes.incrementAndGet();
            return super.write(value);
          }
        };

    try (var app = new Shoostr()) {
      app.codec(codec)
          .routes()
          .get(
              "/encoded",
              (_, response) -> {
                response.text("old");
                if (typedMedia) {
                  response.body(MediaType.APPLICATION_JSON, "Alice");
                } else {
                  response.body("application/json", "Alice");
                }
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/encoded"), HttpResponse.BodyHandlers.ofString());
        assertEquals("\"Alice\"", result.body());
        assertEquals("application/json", result.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(1, writes.get());
      }
    }
  }

  @Test
  void labelsEncodedBytesWithoutTranscodingForExplicitCharset() throws Exception {
    try (var app = new Shoostr()) {
      app.codec(new TestCodec())
          .routes()
          .get(
              "/charset",
              (_, response) ->
                  response.body(
                      MediaType.TEXT_PLAIN.withCharset(StandardCharsets.ISO_8859_1), "é"));

      try (var test = TestServer.start(app)) {
        var result = test.send(request -> request.path("/charset"));
        assertArrayEquals("\"é\"".getBytes(StandardCharsets.UTF_8), result.body());
        assertEquals(
            "text/plain; charset=ISO-8859-1",
            result.headers().firstValue("Content-Type").orElseThrow());
      }
    }
  }

  @Test
  @SuppressWarnings("NullAway") // Invalid inputs must be rejected before user conversion.
  void validatesAvailableInputsBeforeCallingCodec() throws Exception {
    var codec =
        new TestCodec() {
          @Override
          public <T> T read(byte[] source, Class<T> type) {
            return fail("Invalid class reached codec");
          }

          @Override
          public byte[] write(Object value) {
            return fail("Invalid response reached codec");
          }
        };

    try (var app = new Shoostr()) {
      app.codec(codec)
          .routes()
          .get(
              "/invalid",
              (request, response) -> {
                assertThrows(NullPointerException.class, () -> request.body((Class<String>) null));
                assertThrows(
                    NullPointerException.class, () -> response.body((String) null, "Alice"));
                assertThrows(
                    NullPointerException.class, () -> response.body((MediaType) null, "Alice"));
                assertThrows(
                    NullPointerException.class,
                    () -> response.body("application/json", (Object) null));
                assertThrows(
                    IllegalArgumentException.class,
                    () -> response.body("application/json\r\nInjected: value", "Alice"));
                response.text("valid");
              });

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/invalid")).statusCode());
      }
    }
  }

  @Test
  void preservesTypedOutputAfterFluentHeaderConfiguration() throws Exception {
    try (var app = new Shoostr()) {
      app.codec(new TestCodec())
          .routes()
          .post(
              "/encoded",
              (request, response) -> {
                assertSame(request, request.attribute("name", "Alice"));
                response.status(201).setHeader("X-Name", "Alice").body("application/json", "Alice");
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request -> request.path("/encoded").method("POST"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, result.statusCode());
        assertEquals("\"Alice\"", result.body());
        assertEquals("Alice", result.headers().firstValue("X-Name").orElseThrow());
      }
    }
  }

  @Test
  void retainsTypedReturnForEveryResponseFluentMethod() throws Exception {
    var checked = 0;
    for (var method : Response.class.getMethods()) {
      if (method.getReturnType() == Response.class) {
        assertSame(
            TypedResponse.class,
            TypedResponse.class
                .getMethod(method.getName(), method.getParameterTypes())
                .getReturnType(),
            method.toString());
        checked++;
      }
    }
    assertTrue(checked > 0);
  }

  @Test
  void retainsTypedReturnForEveryRequestFluentMethod() throws Exception {
    var checked = 0;
    for (var method : Request.class.getMethods()) {
      if (method.getReturnType() == Request.class) {
        assertSame(
            TypedRequest.class,
            TypedRequest.class
                .getMethod(method.getName(), method.getParameterTypes())
                .getReturnType(),
            method.toString());
        checked++;
      }
    }
    assertTrue(checked > 0);
  }
}
