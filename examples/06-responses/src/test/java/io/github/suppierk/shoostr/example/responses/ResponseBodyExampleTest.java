package io.github.suppierk.shoostr.example.responses;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.testing.TestServer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class ResponseBodyExampleTest {
  @ParameterizedTest
  @ValueSource(ints = {204, 205, 304})
  void sendsEmptyBodylessStatuses(int status) throws Exception {
    try (var app = ResponseBodyExample.application()) {
      app.routes().get("/empty-check", (ignored, response) -> response.status(status));

      try (var test = TestServer.start(app)) {
        var response = test.send(request -> request.path("/empty-check"));
        assertEquals(status, response.statusCode());
        assertArrayEquals(new byte[0], response.body());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {204, 205, 304})
  void rejectsNonemptyBodylessOutputDuringCompletion(int status) throws Exception {
    var staged = new AtomicBoolean();

    try (var app = ResponseBodyExample.application()) {
      app.routes()
          .get(
              "/invalid-check",
              (ignored2, response) -> {
                response.status(status).text("must not escape");
                staged.set(true);
              });

      try (var test = TestServer.start(app)) {
        var response = test.send(request -> request.path("/invalid-check"));
        assertTrue(staged.get());
        assertEquals(500, response.statusCode());
        assertArrayEquals(
            "Internal Server Error".getBytes(StandardCharsets.UTF_8), response.body());
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"0, true", "64, true", "65, false"})
  void enforcesTheFiniteByteLimitBeforeReplacingStagedOutput(int size, boolean accepted)
      throws Exception {
    try (var app = ResponseBodyExample.application()) {
      app.routes()
          .get(
              "/bounded-check",
              (ignored3, response) -> {
                response.text("kept");
                if (accepted) {
                  response.body(MediaType.APPLICATION_OCTET_STREAM, new byte[size]);
                } else {
                  assertThrows(
                      IllegalArgumentException.class,
                      () -> response.body(MediaType.APPLICATION_OCTET_STREAM, new byte[size]));
                }
              });

      try (var test = TestServer.start(app)) {
        var response = test.send(request -> request.path("/bounded-check"));
        assertEquals(200, response.statusCode());
        assertArrayEquals(
            accepted ? new byte[size] : new byte[] {'k', 'e', 'p', 't'}, response.body());
        assertEquals(
            accepted ? "application/octet-stream" : "text/plain; charset=utf-8",
            response.headers().firstValue("Content-Type").orElseThrow());
      }
    }
  }

  @Test
  void copiesFiniteBytesWhileKeepingOutputStaged() throws Exception {
    try (var app = ResponseBodyExample.application()) {
      app.routes()
          .get(
              "/copy-check",
              (ignored4, response) -> {
                var bytes = new byte[] {'o', 'k'};
                response.body(MediaType.TEXT_PLAIN, bytes);
                bytes[0] = 'n';
                assertFalse(response.isCommitted());
              });

      try (var test = TestServer.start(app)) {
        var response = test.send(request -> request.path("/copy-check"));
        assertEquals(200, response.statusCode());
        assertArrayEquals(new byte[] {'o', 'k'}, response.body());
      }
    }
  }

  @Test
  void sendsNoContentWithoutAResponseBody() throws Exception {
    try (var test = TestServer.start(ResponseBodyExample.application())) {
      var response = test.send(request -> request.path("/messages").method("DELETE"));
      assertEquals(204, response.statusCode());
      assertArrayEquals(new byte[0], response.body());
    }
  }

  @Test
  void preservesHeadMetadataWithoutSendingTheRepresentation() throws Exception {
    try (var test = TestServer.start(ResponseBodyExample.application())) {
      var get = test.send(request -> request.path("/message"));
      var head = test.send(request -> request.path("/message").method("HEAD"));
      assertEquals(200, get.statusCode());
      assertArrayEquals(new byte[] {'c', 'a', 'f', (byte) 0xc3, (byte) 0xa9}, get.body());
      assertEquals(200, head.statusCode());
      assertEquals("5", head.headers().firstValue("Content-Length").orElseThrow());
      assertEquals(
          "text/plain; charset=utf-8", head.headers().firstValue("Content-Type").orElseThrow());
      assertArrayEquals(new byte[0], head.body());
    }
  }

  @Test
  void encodesLatin1TextWithMatchingCharsetMetadata() throws Exception {
    try (var test = TestServer.start(ResponseBodyExample.application())) {
      var response = test.send(request -> request.path("/latin1"));
      assertEquals(200, response.statusCode());
      assertEquals(
          "text/plain; charset=ISO-8859-1",
          response.headers().firstValue("Content-Type").orElseThrow());
      assertArrayEquals(new byte[] {'c', 'a', 'f', (byte) 0xe9}, response.body());
    }
  }

  @Test
  void preservesBinaryResponseBytes() throws Exception {
    try (var test = TestServer.start(ResponseBodyExample.application())) {
      var response = test.send(request -> request.path("/bytes"));
      assertEquals(200, response.statusCode());
      assertEquals(
          "application/octet-stream", response.headers().firstValue("Content-Type").orElseThrow());
      assertArrayEquals(new byte[] {0, 1, 127, (byte) 0xff}, response.body());
    }
  }

  @Test
  void sendsCreatedTextAsUtf8() throws Exception {
    try (var test = TestServer.start(ResponseBodyExample.application())) {
      var response = test.send(request -> request.path("/messages").method("POST"));
      assertEquals(201, response.statusCode());
      assertEquals(
          "text/plain; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
      assertArrayEquals(new byte[] {'c', 'a', 'f', (byte) 0xc3, (byte) 0xa9}, response.body());
    }
  }
}
