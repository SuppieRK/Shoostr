package io.github.suppierk.shoostr.example.sse;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SseExampleTest {
  @Test
  void sendsTheSuppliedReportAsAResultEvent() throws Exception {
    try (var test = TestServer.start(SseExample.application(() -> "Ready"))) {
      var response =
          test.send(
              request ->
                  request.path("/reports").header(HttpHeaders.ACCEPT.value(), "text/event-stream"),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () ->
              assertEquals(
                  "event: progress\ndata: starting\n\nevent: result\ndata: Ready\n\n",
                  response.body()));
    }
  }

  @Test
  void sendsASafeFailureEventAfterStreamingHasStarted() throws Exception {
    try (var test = TestServer.start(SseExample.application())) {
      var response =
          test.send(
              request ->
                  request.path("/reports").header(HttpHeaders.ACCEPT.value(), "text/event-stream"),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () ->
              assertEquals(
                  "event: progress\ndata: starting\n\nevent: failure\ndata: Report unavailable\n\n",
                  response.body()),
          () ->
              assertEquals(
                  "text/event-stream; charset=utf-8",
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }

  @Test
  void handlesAFailureBeforeStreamingAsAnHttpError() throws Exception {
    try (var test = TestServer.start(SseExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/unavailable")
                      .header(HttpHeaders.ACCEPT.value(), "text/event-stream"),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(500, response.statusCode()),
          () -> assertEquals("Stream unavailable", response.body()),
          () ->
              assertEquals(
                  "text/plain; charset=utf-8",
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }

  @Test
  void stopsReconnectionAfterTheFinalReplayEntry() throws Exception {
    try (var test = TestServer.start(SseExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/replay")
                      .header(HttpHeaders.ACCEPT.value(), "text/event-stream")
                      .header(HttpHeaders.LAST_EVENT_ID.value(), "3"));
      assertAll(
          () -> assertEquals(204, response.statusCode()),
          () -> assertEquals(0, response.body().length));
    }
  }

  @Test
  void resumesAfterTheClientsLastEventId() throws Exception {
    try (var test = TestServer.start(SseExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/replay")
                      .header(HttpHeaders.ACCEPT.value(), "text/event-stream")
                      .header(HttpHeaders.LAST_EVENT_ID.value(), "1"),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () ->
              assertEquals(
                  "id: 2\nretry: 1000\ndata: Entry 2\n\nid: 3\nretry: 1000\ndata: Entry 3\n\n",
                  response.body()));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc", "-1", "4", "2147483648"})
  void rejectsInvalidReplayCursorsBeforeStartingAStream(String cursor) throws Exception {
    try (var test = TestServer.start(SseExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/replay")
                      .header(HttpHeaders.ACCEPT.value(), "text/event-stream")
                      .header(HttpHeaders.LAST_EVENT_ID.value(), cursor),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(400, response.statusCode()),
          () -> assertEquals("Bad Request", response.body()),
          () ->
              assertEquals(
                  "text/plain; charset=utf-8",
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }

  @Test
  void replaysAllThreeEntriesWithoutALastEventId() throws Exception {
    try (var test = TestServer.start(SseExample.application())) {
      var response =
          test.send(
              request ->
                  request.path("/replay").header(HttpHeaders.ACCEPT.value(), "text/event-stream"),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () ->
              assertEquals(
                  "id: 1\nretry: 1000\ndata: Entry 1\n\nid: 2\nretry: 1000\ndata: Entry 2\n\nid: 3\nretry: 1000\ndata: Entry 3\n\n",
                  response.body()));
    }
  }

  @Test
  void sendsTheNamedProgressFeedWithItsMetadata() throws Exception {
    try (var test = TestServer.start(SseExample.application())) {
      var response =
          test.send(
              request ->
                  request.path("/progress").header(HttpHeaders.ACCEPT.value(), "text/event-stream"),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () ->
              assertEquals(
                  ": connection ready\n\n:\n\nevent: progress\nid: 1\nretry: 2000\ndata: Preparing\ndata: Waiting\n\nevent: done\nid: 2\ndata: Complete\n\n",
                  response.body()));
    }
  }

  @Test
  void completesAfterSendingBothUnnamedEvents() throws Exception {
    try (var test = TestServer.start(SseExample.application())) {
      var response =
          test.send(
              request ->
                  request.path("/events").header(HttpHeaders.ACCEPT.value(), "text/event-stream"),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("data: starting\n\ndata: complete\n\n", response.body()),
          () ->
              assertEquals(
                  "text/event-stream; charset=utf-8",
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }

  @Test
  void servesThePlainTextEndpointList() throws Exception {
    try (var test = TestServer.start(SseExample.application())) {
      var response = test.send(request -> request.path("/"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () ->
              assertEquals(
                  "SSE endpoints: /events, /progress, /replay, /unavailable, /reports",
                  response.body()),
          () ->
              assertEquals(
                  "text/plain; charset=utf-8",
                  response.headers().firstValue(HttpHeaders.CONTENT_TYPE.value()).orElseThrow()));
    }
  }
}
