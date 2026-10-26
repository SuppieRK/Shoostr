package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class ResponseHeaderGetterTest {
  @Test
  @SuppressWarnings("NullAway")
  void inheritsHeaderInspectionInTypedResponses() throws Exception {
    try (var app = new Shoostr()) {
      app.codec(new TestCodec())
          .routes()
          .get(
              "/headers",
              (request, response) -> {
                assertNull(response.header("Missing"));
                assertThrows(NullPointerException.class, () -> response.header(null));
                response.status(202).setHeader("X-Value", "typed");
                assertEquals("typed", response.header("x-value"));
                response.text("checked");
              });

      try (var test = TestServer.start(app)) {
        var response =
            test.send(request -> request.path("/headers"), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, response.statusCode());
        assertEquals("checked", response.body());
        assertEquals("typed", response.headers().firstValue("X-Value").orElseThrow());
      }
    }
  }

  @Test
  void doesNotExposeAGetterUnderTheSetterName() {
    assertThrows(
        NoSuchMethodException.class, () -> Response.class.getMethod("setHeader", String.class));
  }

  @Test
  void readsTheFirstHeaderWithoutChangingItsValues() throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .get(
              "/headers",
              (request, response) -> {
                assertNull(response.header("Missing"));
                response.setHeader("X-Value", "first").addHeader("X-Value", "second");
                assertEquals("first", response.header("x-value"));
                response.text("checked");
              });

      try (var test = TestServer.start(app)) {
        var response =
            test.send(request -> request.path("/headers"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("checked", response.body());
        assertEquals(List.of("first", "second"), response.headers().allValues("X-Value"));
      }
    }
  }
}
