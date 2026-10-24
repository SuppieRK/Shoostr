package io.github.suppierk.shoostr.example.json;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.testing.TestServer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class NegotiationExampleTest {
  @ParameterizedTest
  @MethodSource("acceptedRepresentations")
  void sendsBytesMatchingTheSelectedRepresentation(String accept, String mediaType, String body)
      throws Exception {
    try (var test = TestServer.start(NegotiationExample.application())) {
      var response = test.send(request -> request.path("/greeting").header("Accept", accept));
      assertEquals(200, response.statusCode());
      assertEquals(mediaType, response.headers().firstValue("Content-Type").orElseThrow());
      assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), response.body());
      assertEquals(List.of("Accept"), response.headers().allValues("Vary"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"application/xml", "", "application/json;q=0, text/plain;q=0"})
  void rejectsAnExplicitPreferenceThatAcceptsNeitherRepresentation(String accept) throws Exception {
    try (var test = TestServer.start(NegotiationExample.application())) {
      var response = test.send(request -> request.path("/greeting").header("Accept", accept));
      assertEquals(406, response.statusCode());
      assertArrayEquals("Not Acceptable".getBytes(StandardCharsets.UTF_8), response.body());
      assertEquals(List.of("Accept"), response.headers().allValues("Vary"));
    }
  }

  @Test
  void rejectsMalformedAcceptBeforeAddingVary() throws Exception {
    try (var test = TestServer.start(NegotiationExample.application())) {
      var response =
          test.send(request -> request.path("/greeting").header("Accept", "text/plain;q=broken"));
      assertEquals(400, response.statusCode());
      assertArrayEquals("Bad Request".getBytes(StandardCharsets.UTF_8), response.body());
      assertEquals(List.of(), response.headers().allValues("Vary"));
    }
  }

  @ParameterizedTest
  @CsvSource({"application/json, 200", "application/xml, 406"})
  void preservesAnExistingVaryFieldOnSuccessAndRejection(String accept, int status)
      throws Exception {
    try (var app = NegotiationExample.application()) {
      app.onRequestHeaders((ignored, response) -> response.addHeader("Vary", "Origin"));

      try (var test = TestServer.start(app)) {
        var response = test.send(request -> request.path("/greeting").header("Accept", accept));
        assertEquals(status, response.statusCode());
        assertEquals(List.of("Origin", "Accept"), response.headers().allValues("Vary"));
      }
    }
  }

  @Test
  void selectsTheFirstRepresentationWhenAcceptIsAbsent() throws Exception {
    try (var test = TestServer.start(NegotiationExample.application())) {
      var response = test.send(request -> request.path("/greeting"));
      assertEquals(200, response.statusCode());
      assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow());
      assertArrayEquals("{\"message\":\"café\"}".getBytes(StandardCharsets.UTF_8), response.body());
      assertEquals(List.of("Accept"), response.headers().allValues("Vary"));
    }
  }

  private static Stream<Arguments> acceptedRepresentations() {
    return Stream.of(
        Arguments.of("application/json", "application/json", "{\"message\":\"café\"}"),
        Arguments.of("text/plain", "text/plain; charset=UTF-8", "café"),
        Arguments.of(
            "application/json;q=0.2, text/plain;q=0.9", "text/plain; charset=UTF-8", "café"),
        Arguments.of("*/*", "application/json", "{\"message\":\"café\"}"),
        Arguments.of("text/*", "text/plain; charset=UTF-8", "café"),
        Arguments.of(
            "text/plain;q=0.5, application/json;q=0.5",
            "application/json",
            "{\"message\":\"café\"}"),
        Arguments.of("application/json;q=0, */*;q=1", "text/plain; charset=UTF-8", "café"));
  }
}
