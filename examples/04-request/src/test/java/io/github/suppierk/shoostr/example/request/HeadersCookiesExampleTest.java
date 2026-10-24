package io.github.suppierk.shoostr.example.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class HeadersCookiesExampleTest {
  @Test
  void distinguishesEmptyValuesFromMissingFields() throws Exception {
    try (var test = TestServer.start(HeadersCookiesExample.application())) {
      var missing =
          test.send(request -> request.path("/preferences"), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, missing.statusCode());
      assertEquals(
          "header=(missing)\nheaders=\nheaderMap=\ncookie=(missing)\ncookies=\ncookieMap=\notherCookie=(missing)",
          missing.body());

      var empty =
          test.send(
              request ->
                  request.path("/preferences").header("X-Label", "").header("Cookie", "theme="),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, empty.statusCode());
      assertEquals(
          "header=\nheaders=\nheaderMap=\ncookie=\ncookies=\ncookieMap=\notherCookie=(missing)",
          empty.body());
    }
  }

  @Test
  void exposesImmutableHeaderAndCookieCollections() throws Exception {
    try (var app = HeadersCookiesExample.application()) {
      app.routes()
          .get(
              "/snapshots",
              (request, response) -> {
                var headers = request.headerMap();
                var cookies = request.cookieMap();
                assertEquals(List.of("one,two", "three"), headers.get("X-LABEL"));
                assertEquals(List.of("light", "dark"), cookies.get("theme"));
                assertThrows(UnsupportedOperationException.class, headers::clear);
                assertThrows(
                    UnsupportedOperationException.class, () -> headers.get("x-label").add("bad"));
                assertThrows(
                    UnsupportedOperationException.class, () -> request.headers("X-Label").clear());
                assertThrows(UnsupportedOperationException.class, cookies::clear);
                assertThrows(
                    UnsupportedOperationException.class, () -> cookies.get("theme").add("bad"));
                assertThrows(
                    UnsupportedOperationException.class, () -> request.cookies("theme").clear());
                response.text("checked");
              });

      try (var test = TestServer.start(app)) {
        var response =
            test.send(
                request ->
                    request
                        .path("/snapshots")
                        .header("X-Label", "one,two")
                        .header("x-label", "three")
                        .header("Cookie", "theme=light; theme=dark"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("checked", response.body());
      }
    }
  }

  @Test
  void preservesRepeatedFieldsCommaTextAndCookieSpelling() throws Exception {
    try (var test = TestServer.start(HeadersCookiesExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/preferences")
                      .header("X-Label", "first,second")
                      .header("x-label", "third")
                      .header("Cookie", "theme=a+b%2F; theme=dark; Theme=upper"),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      assertEquals(
          "header=first,second\nheaders=first,second|third\nheaderMap=first,second|third"
              + "\ncookie=a+b%2F\ncookies=a+b%2F|dark\ncookieMap=a+b%2F|dark\notherCookie=upper",
          response.body());
    }
  }
}
