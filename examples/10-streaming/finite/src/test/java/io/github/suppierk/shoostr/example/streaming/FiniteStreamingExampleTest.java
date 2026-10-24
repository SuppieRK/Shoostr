package io.github.suppierk.shoostr.example.streaming;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.FilterInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class FiniteStreamingExampleTest {
  @TempDir Path directory;

  @Test
  void sendsEveryChunkAndFinishesWhenTheHandlerReturns() throws Exception {
    var report = Files.writeString(directory.resolve("report.csv"), "name\nAda\nGrace\n");

    try (var test = TestServer.start(FiniteStreamingExample.application(report))) {
      var response =
          test.send(request -> request.path("/chunks"), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("name\nAda\nGrace\n", response.body());
      assertEquals(
          "text/csv; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
    }
  }

  @Test
  void transfersAnInputLargerThanTheFiniteResponseLimitWithoutTruncation() throws Exception {
    var bytes = "A".repeat(1_048_600).getBytes(StandardCharsets.US_ASCII);
    var report = Files.write(directory.resolve("report.csv"), bytes);

    try (var test = TestServer.start(FiniteStreamingExample.application(report))) {
      var response = test.send(request -> request.path("/input"));
      assertEquals(200, response.statusCode());
      assertArrayEquals(bytes, response.body());
      assertEquals(
          "text/csv; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
    }
  }

  @Test
  void closesTheSuppliedInputBeforeTheRequestCompletes() throws Exception {
    var report = Files.writeString(directory.resolve("report.csv"), "name\nAda\n");
    var closed = new AtomicBoolean();
    var app = FiniteStreamingExample.application(report);
    app.routes()
        .get(
            "/tracked-input",
            (ignored, response) ->
                response.input(
                    new FilterInputStream(Files.newInputStream(report)) {
                      @Override
                      public void close() throws IOException {
                        super.close();
                        closed.set(true);
                      }
                    },
                    "text/csv; charset=utf-8"));

    try (var test = TestServer.start(app)) {
      var response =
          test.send(
              request -> request.path("/tracked-input"), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("name\nAda\n", response.body());
      assertTrue(closed.get());
    }
  }

  @Test
  @Timeout(15)
  void exposesAFlushedChunkBeforeTheHandlerFinishes() throws Exception {
    var report = Files.writeString(directory.resolve("report.csv"), "unused");
    var release = new CountDownLatch(1);
    var finished = new AtomicBoolean();
    var app = FiniteStreamingExample.application(report);
    app.routes()
        .get(
            "/flush-boundary",
            (ignored2, response) -> {
              try {
                var output = response.startStream("text/csv; charset=utf-8");
                output.write("name\n").flush();
                if (!release.await(5, TimeUnit.SECONDS)) {
                  throw new IOException("Client did not release the feed");
                }

                output.write("Ada\n").write("Grace\n");
              } finally {
                finished.set(true);
              }
            });

    try (var test = TestServer.start(app);
        var client = HttpClient.newHttpClient()) {
      try {
        var response =
            client.send(
                HttpRequest.newBuilder(test.baseUri().resolve("flush-boundary"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());

        try (var body = response.body()) {
          assertEquals("name\n", new String(body.readNBytes(5), StandardCharsets.UTF_8));
          assertFalse(finished.get());
          release.countDown();
          assertEquals("Ada\nGrace\n", new String(body.readAllBytes(), StandardCharsets.UTF_8));
        }
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  @Timeout(15)
  void closesAFailingInputAndAbortsOutputAfterTheFirstBufferWasSent() throws Exception {
    var report = Files.writeString(directory.resolve("report.csv"), "A".repeat(16_384));
    var closed = new AtomicBoolean();
    var app = FiniteStreamingExample.application(report);
    app.routes()
        .get(
            "/broken-input",
            (ignored3, response) -> {
              var readOnce = new AtomicBoolean();
              response.input(
                  new FilterInputStream(Files.newInputStream(report)) {
                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                      if (readOnce.getAndSet(true)) {
                        throw new IOException("Private source failure");
                      }

                      return super.read(buffer, offset, length);
                    }

                    @Override
                    public void close() throws IOException {
                      super.close();
                      closed.set(true);
                    }
                  },
                  "text/csv; charset=utf-8");
            });

    try (var test = TestServer.start(app)) {
      var response =
          test.send(
              request -> request.path("/broken-input"), HttpResponse.BodyHandlers.ofInputStream());
      assertEquals(200, response.statusCode());

      try (var body = response.body()) {
        assertThrows(IOException.class, body::readAllBytes);
      }

      assertTrue(closed.get());
    }
  }
}
