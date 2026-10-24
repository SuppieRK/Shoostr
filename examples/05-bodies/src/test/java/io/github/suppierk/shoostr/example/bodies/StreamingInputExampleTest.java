package io.github.suppierk.shoostr.example.bodies;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StreamingInputExampleTest {
  @Test
  void calculatesTheChecksumOfEmptyInput() throws Exception {
    try (var test = TestServer.start(StreamingInputExample.application())) {
      var response =
          test.send(
              request -> request.path("/checksum").method("POST"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals(
          "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", response.body());
    }
  }

  @Test
  void processesInputLargerThanTheTransferBuffer() throws Exception {
    try (var test = TestServer.start(StreamingInputExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/checksum")
                      .method("POST")
                      .body("abc".repeat(4096).getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals(
          "7834ab64afb43e3f7a58712a73251d9258453c373cccc3c6bd8154ab767fc06a", response.body());
    }
  }

  @ParameterizedTest
  @CsvSource({
    "1048576, false, 200",
    "1048577, false, 413",
    "1048576, true, 200",
    "1048577, true, 413"
  })
  void enforcesTheLimitForFixedLengthAndChunkedInput(int size, boolean chunked, int status)
      throws Exception {
    var bytes = new byte[size];
    var publisher =
        chunked
            ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))
            : HttpRequest.BodyPublishers.ofByteArray(bytes);

    try (var test = TestServer.start(StreamingInputExample.application());
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
      var request =
          HttpRequest.newBuilder(test.baseUri().resolve("/checksum"))
              .timeout(Duration.ofSeconds(5))
              .POST(publisher)
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(status, response.statusCode());
      if (status == 200) {
        assertEquals(
            "30e14955ebf1352266dc2ff8067e68104607e750abb9d3b36582b8af909fcb58", response.body());
      }
    }
  }

  @Test
  void preservesStreamPositionAndRejectsBufferedAccess() throws Exception {
    try (var app = StreamingInputExample.application()) {
      app.routes()
          .post(
              "/position",
              (request, response) -> {
                var input = request.input();
                assertEquals('a', input.read());
                assertSame(input, request.input());
                assertThrows(IllegalStateException.class, request::bodyBytes);
                assertThrows(IllegalStateException.class, request::bodyText);
                assertThrows(IllegalStateException.class, request::formParamMap);
                assertArrayEquals(new byte[] {'b', 'c'}, request.input().readNBytes(2));
                assertEquals(-1, input.read());
                response.text("checked");
              });

      try (var test = TestServer.start(app)) {
        var response =
            test.send(
                request ->
                    request
                        .path("/position")
                        .method("POST")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .body("abc".getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("checked", response.body());
      }
    }
  }

  @Test
  void rejectsMultipartParsingAfterStreamingAccess() throws Exception {
    try (var app = StreamingInputExample.application()) {
      app.routes()
          .post(
              "/modes",
              (request, response) -> {
                var input = request.input();
                assertThrows(IllegalStateException.class, request::files);
                assertArrayEquals(
                    "--x--\r\n".getBytes(StandardCharsets.UTF_8), input.readNBytes(7));
                response.text("checked");
              });

      try (var test = TestServer.start(app)) {
        var response =
            test.send(
                request ->
                    request
                        .path("/modes")
                        .method("POST")
                        .header("Content-Type", "multipart/form-data; boundary=x")
                        .body("--x--\r\n".getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("checked", response.body());
      }
    }
  }

  @Test
  void calculatesTheSha256ChecksumOfTheRequestBytes() throws Exception {
    try (var test = TestServer.start(StreamingInputExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/checksum")
                      .method("POST")
                      .body("abc".getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      assertEquals(
          "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", response.body());
    }
  }
}
