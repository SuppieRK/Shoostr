package io.github.suppierk.shoostr.example.streaming;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class StreamingExampleTest {
  @Test
  void returnsNotFoundForAnAbsentMountedAsset() throws Exception {
    try (var test = TestServer.start(StreamingExample.application(Options.defaults()))) {
      assertEquals(404, test.send(request -> request.path("/assets/missing.txt")).statusCode());
    }
  }

  @Test
  void refusesTraversalOutsideTheMountedDirectory() throws Exception {
    try (var test = TestServer.start(StreamingExample.application(Options.defaults()))) {
      assertEquals(
          404, test.send(request -> request.path("/assets/../private-example.txt")).statusCode());
    }
  }

  @Test
  void completesAfterSendingBothEventFrames() throws Exception {
    try (var test = TestServer.start(StreamingExample.application(Options.defaults()))) {
      var response =
          test.send(
              request -> request.path("/events").header("Accept", "text/event-stream"),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("data: starting\n\ndata: complete\n\n", response.body()));
    }
  }

  @Test
  void completesTheFiniteDownloadWhenItsHandlerReturns() throws Exception {
    try (var test = TestServer.start(StreamingExample.application(Options.defaults()))) {
      var response =
          test.send(request -> request.path("/download"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("name\nAda\nGrace\n", response.body()),
          () ->
              assertEquals(
                  "text/csv; charset=utf-8",
                  response.headers().firstValue("Content-Type").orElseThrow()));
    }
  }

  @Test
  void servesTheClasspathAssetFromItsExplicitMount() throws Exception {
    try (var test = TestServer.start(StreamingExample.application(Options.defaults()))) {
      var response =
          test.send(
              request -> request.path("/assets/greeting.txt"),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("Hello from a classpath asset!\n", response.body()));
    }
  }
}
