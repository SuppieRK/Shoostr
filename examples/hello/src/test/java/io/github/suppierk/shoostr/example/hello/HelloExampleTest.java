package io.github.suppierk.shoostr.example.hello;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class HelloExampleTest {
  @Test
  void servesTheDocumentedHelloResponse() throws Exception {
    try (var test = TestServer.start(HelloExample.application(Options.defaults()))) {
      var response =
          test.send(request -> request.path("/hello"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("Hello, Shoostr!", response.body()),
          () ->
              assertEquals(
                  "text/plain; charset=utf-8",
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }
}
