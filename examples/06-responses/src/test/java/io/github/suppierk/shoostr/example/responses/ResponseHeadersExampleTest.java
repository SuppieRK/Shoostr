package io.github.suppierk.shoostr.example.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class ResponseHeadersExampleTest {
  @Test
  void keepsCaseInsensitiveSnapshotsStableAndDeeplyImmutable() throws Exception {
    try (var app = ResponseHeadersExample.application()) {
      app.routes()
          .get(
              "/snapshots",
              (ignored, response) -> {
                assertNull(response.header("Missing"));
                assertEquals(List.of(), response.headers("Missing"));
                response.addHeader("X-Values", "a,b").addHeader("x-values", "c");
                var values = response.headers("X-VALUES");
                var snapshot = response.headerMap();
                var nested = Objects.requireNonNull(snapshot.get("x-values"));
                assertEquals(List.of("a,b", "c"), nested);
                assertThrows(UnsupportedOperationException.class, () -> values.add("changed"));
                assertThrows(UnsupportedOperationException.class, () -> nested.add("changed"));
                assertThrows(UnsupportedOperationException.class, snapshot::clear);
                response.addHeader("X-Values", "d");
                assertEquals(List.of("a,b", "c"), values);
                assertEquals(List.of("a,b", "c"), snapshot.get("X-VALUES"));
                assertEquals(List.of("a,b", "c", "d"), response.headers("x-values"));
                response.text("checked");
              });

      try (var test = TestServer.start(app)) {
        var response =
            test.send(request -> request.path("/snapshots"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("checked", response.body());
        assertEquals(List.of("a,b", "c", "d"), response.headers().allValues("X-Values"));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"Content-Length", "Transfer-Encoding"})
  void leavesFramingToTheFramework(String name) throws Exception {
    try (var app = ResponseHeadersExample.application()) {
      app.routes()
          .get(
              "/framing",
              (ignored2, response) -> {
                assertThrows(IllegalArgumentException.class, () -> response.setHeader(name, "0"));
                assertThrows(IllegalArgumentException.class, () -> response.addHeader(name, "0"));
                assertThrows(IllegalArgumentException.class, () -> response.removeHeader(name));
                response.text("checked");
              });

      try (var test = TestServer.start(app)) {
        var response =
            test.send(request -> request.path("/framing"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("checked", response.body());
        assertEquals("7", response.headers().firstValue("Content-Length").orElseThrow());
        assertTrue(response.headers().firstValue("Transfer-Encoding").isEmpty());
      }
    }
  }

  @Test
  void sendsRepeatedFieldsAndReadsTheOriginalSnapshot() throws Exception {
    try (var test = TestServer.start(ResponseHeadersExample.application())) {
      var response =
          test.send(request -> request.path("/headers"), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals(
          List.of(
              "</next>; rel=\"next\", </last>; rel=\"last\"",
              "</previous>; rel=\"prev\"",
              "</first>; rel=\"first\""),
          response.headers().allValues("Link"));
      assertEquals("ready", response.headers().firstValue("X-Mode").orElseThrow());
      assertTrue(response.headers().firstValue("X-Temporary").isEmpty());
      assertEquals(
          """
          first=</next>; rel="next", </last>; rel="last"
          links=</next>; rel="next", </last>; rel="last"|</previous>; rel="prev"|</first>; rel="first"
          mode=ready
          snapshot-mode=draft
          snapshot-links=2\
          """,
          response.body());
    }
  }
}
