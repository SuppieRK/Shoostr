package io.github.suppierk.shoostr.example.bodies;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BufferedBodyExampleTest {
  @Test
  void readsLatin1TextWithAnExplicitCharset() throws Exception {

    try (var test = TestServer.start(BufferedBodyExample.application())) {
      var response = sendResponseForReadsLatin1TextWithAnExplicitCharset(test);

      assertEquals(200, response.statusCode());
      assertEquals("café", response.body());
    }
  }

  private HttpResponse<String> sendResponseForReadsLatin1TextWithAnExplicitCharset(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/latin1")
                .method("POST")
                .header("Content-Type", "text/plain; charset=UTF-8")
                .body(new byte[] {'c', 'a', 'f', (byte) 0xe9}),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @CsvSource({"0, 200", "64, 200", "65, 413"})
  void enforcesTheBufferedByteLimit(int size, int status) throws Exception {

    try (var test = TestServer.start(BufferedBodyExample.application())) {
      var response =
          test.send(request -> request.path("/bytes").method("POST").body(new byte[size]));

      assertEquals(status, response.statusCode());

      if (status == 200) {
        assertArrayEquals(new byte[size], response.body());
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"21, 200", "22, 413"})
  void countsUtf8BytesRatherThanCharacters(int characters, int status) throws Exception {

    try (var test = TestServer.start(BufferedBodyExample.application())) {
      var response = sendResponseForCountsUtf8BytesRatherThanCharacters(test, characters);

      assertEquals(status, response.statusCode());
    }
  }

  private HttpResponse<byte[]> sendResponseForCountsUtf8BytesRatherThanCharacters(
      TestServer test, int characters) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/text")
                .method("POST")
                .body("€".repeat(characters).getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void replacesMalformedUtf8InPlainBodyText() throws Exception {

    try (var test = TestServer.start(BufferedBodyExample.application())) {
      var response =
          test.send(
              request -> request.path("/text").method("POST").body(new byte[] {(byte) 0xC3, 0x28}),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      assertEquals("\uFFFD(", response.body());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void rejectsMalformedFormDataAfterBufferedTextAccess(boolean encoded) throws Exception {
    var body =
        encoded
            ? "tag=%FF".getBytes(StandardCharsets.UTF_8)
            : new byte[] {'t', 'a', 'g', '=', (byte) 0xC3, 0x28};

    try (var test = TestServer.start(BufferedBodyExample.application())) {
      var response = sendResponseForRejectsMalformedFormDataAfterBufferedTextAccess(test, body);

      assertEquals(400, response.statusCode());
    }
  }

  private HttpResponse<byte[]> sendResponseForRejectsMalformedFormDataAfterBufferedTextAccess(
      TestServer test, byte[] body) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/form")
                .method("POST")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(body));
  }

  @Test
  void limitsQueryAndFormPairsSeparatelyIncludingRepeatedNames() throws Exception {

    try (var test = TestServer.start(BufferedBodyExample.application())) {
      var four = "tag=one&tag=two&tag=three&tag=four";
      var query = "tag=q1&tag=q2&tag=q3&tag=q4";
      var accepted =
          sendAcceptedRequestForLimitsQueryAndFormPairsSeparatelyIncludingRepeatedNames(
              test, query, four);

      assertEquals(200, accepted.statusCode());
      assertEquals("form=one\nquery=q1\nraw=tag=one&tag=two&tag=three&tag=four", accepted.body());

      assertEquals(
          400,
          test.send(
                  request ->
                      request
                          .path("/form?" + query)
                          .method("POST")
                          .header("Content-Type", "application/x-www-form-urlencoded")
                          .body((four + "&tag=five").getBytes(StandardCharsets.UTF_8)))
              .statusCode());
      assertEquals(
          400,
          test.send(
                  request ->
                      request
                          .path("/form?" + query + "&tag=q5")
                          .method("POST")
                          .header("Content-Type", "application/x-www-form-urlencoded")
                          .body(four.getBytes(StandardCharsets.UTF_8)))
              .statusCode());
    }
  }

  private HttpResponse<String>
      sendAcceptedRequestForLimitsQueryAndFormPairsSeparatelyIncludingRepeatedNames(
          TestServer test, String query, String four) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/form?" + query)
                .method("POST")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(four.getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void returnsIndependentCopiesOfCachedBytes() throws Exception {
    {
      var app = BufferedBodyExample.application();

      try (Closeable appCleanup = app::close) {
        app.routes()
            .post(
                "/copies",
                (request, response) -> {
                  var first = request.bodyBytes();
                  first[0] = 0;

                  assertArrayEquals(new byte[] {'a', 'b', 'c'}, request.bodyBytes());
                  assertEquals("abc", request.bodyText());

                  response.text("checked");
                });

        try (var test = TestServer.start(app)) {
          var response = sendResponseForReturnsIndependentCopiesOfCachedBytes(test);

          assertEquals(200, response.statusCode());
          assertEquals("checked", response.body());
        }
      }
    }
  }

  private HttpResponse<String> sendResponseForReturnsIndependentCopiesOfCachedBytes(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request.path("/copies").method("POST").body("abc".getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsOversizedDeclaredInputEvenWhenTheHandlerDoesNotReadIt() throws Exception {
    {
      var app = BufferedBodyExample.application();

      try (Closeable appCleanup1 = app::close) {
        app.routes().post("/ignored", (ignoredRequest, response) -> response.text("ignored"));

        try (var test = TestServer.start(app)) {
          var accepted =
              test.send(
                  request -> request.path("/ignored").method("POST").body(new byte[64]),
                  HttpResponse.BodyHandlers.ofString());

          assertEquals(200, accepted.statusCode());
          assertEquals("ignored", accepted.body());
          assertEquals(
              413,
              test.send(request -> request.path("/ignored").method("POST").body(new byte[65]))
                  .statusCode());
        }
      }
    }
  }

  @Test
  void rejectsStreamingAndMultipartAccessAfterBufferedAccess() throws Exception {
    {
      var app = BufferedBodyExample.application();

      try (Closeable appCleanup2 = app::close) {
        app.routes()
            .post(
                "/modes",
                (request, response) -> {
                  assertEquals("--x--\r\n", request.bodyText());
                  assertThrows(IllegalStateException.class, request::input);
                  assertThrows(IllegalStateException.class, request::files);
                  assertEquals("--x--\r\n", request.bodyText());

                  response.text("checked");
                });

        try (var test = TestServer.start(app)) {
          var response = sendResponseForRejectsStreamingAndMultipartAccessAfterBufferedAccess(test);

          assertEquals(200, response.statusCode());
          assertEquals("checked", response.body());
        }
      }
    }
  }

  private HttpResponse<String> sendResponseForRejectsStreamingAndMultipartAccessAfterBufferedAccess(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/modes")
                .method("POST")
                .header("Content-Type", "multipart/form-data; boundary=x")
                .body("--x--\r\n".getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void leavesNonmultipartInputAvailableAfterAFileLookup() throws Exception {
    {
      var app = BufferedBodyExample.application();

      try (Closeable appCleanup3 = app::close) {
        app.routes()
            .post(
                "/probe",
                (request, response) -> {
                  assertTrue(request.file("document").isEmpty());
                  assertTrue(request.files().isEmpty());

                  response.text(request.bodyText());
                });

        try (var test = TestServer.start(app)) {
          var response = sendResponseForLeavesNonmultipartInputAvailableAfterAFileLookup(test);

          assertEquals(200, response.statusCode());
          assertEquals("abc", response.body());
        }
      }
    }
  }

  private HttpResponse<String> sendResponseForLeavesNonmultipartInputAvailableAfterAFileLookup(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/probe")
                .method("POST")
                .header("Content-Type", "text/plain")
                .body("abc".getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void sharesCachedInputWithUrlEncodedFormAccess() throws Exception {

    try (var test = TestServer.start(BufferedBodyExample.application())) {
      var response = sendResponseForSharesCachedInputWithUrlEncodedFormAccess(test);

      assertEquals(200, response.statusCode());
      assertEquals("form=one\nquery=query\nraw=tag=one&tag=two", response.body());
    }
  }

  private HttpResponse<String> sendResponseForSharesCachedInputWithUrlEncodedFormAccess(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/form?tag=query")
                .method("POST")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body("tag=one&tag=two".getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void preservesBinaryBodyBytes() throws Exception {

    try (var test = TestServer.start(BufferedBodyExample.application())) {
      var bytes = new byte[] {0, 1, -1, 127, -128};
      var response = test.send(request -> request.path("/bytes").method("POST").body(bytes));

      assertEquals(200, response.statusCode());
      assertArrayEquals(bytes, response.body());
      assertEquals(
          "application/octet-stream", response.headers().firstValue("Content-Type").orElseThrow());
    }
  }

  @Test
  void readsTextAsUtf8RegardlessOfTheDeclaredCharset() throws Exception {

    try (var test = TestServer.start(BufferedBodyExample.application())) {
      var response = sendResponseForReadsTextAsUtf8RegardlessOfTheDeclaredCharset(test);

      assertEquals(200, response.statusCode());
      assertEquals("München", response.body());
    }
  }

  private HttpResponse<String> sendResponseForReadsTextAsUtf8RegardlessOfTheDeclaredCharset(
      TestServer test) throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/text")
                .method("POST")
                .header("Content-Type", "text/plain; charset=ISO-8859-1")
                .body("München".getBytes(StandardCharsets.UTF_8)),
        HttpResponse.BodyHandlers.ofString());
  }
}
