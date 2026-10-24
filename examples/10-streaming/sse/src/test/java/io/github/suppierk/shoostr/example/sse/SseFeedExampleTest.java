package io.github.suppierk.shoostr.example.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SseFeedExampleTest {
  @Test
  void sendsDataCommentsHeartbeatAndNamedEventFieldsAsCompleteFrames() throws Exception {
    try (var test = TestServer.start(SseFeedExample.application())) {
      var response =
          test.send(
              request -> request.path("/events").header("Accept", "text/event-stream"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals(
          "text/event-stream; charset=utf-8",
          response.headers().firstValue("Content-Type").orElseThrow());
      assertEquals(
          """
          data: starting

          : ready

          :

          event: progress
          id: 1
          retry: 2000
          data: Preparing
          data: Waiting

          event: done
          id: 2
          data: Complete

          """,
          response.body());
    }
  }

  @Test
  void resumesAfterTheLastEventIdSentByTheClient() throws Exception {
    try (var test = TestServer.start(SseFeedExample.application())) {
      var response =
          test.send(
              request ->
                  request
                      .path("/replay")
                      .header("Last-Event-ID", "1")
                      .header("Accept", "text/event-stream"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals(
          """
          id: 2
          retry: 1000
          data: Entry 2

          id: 3
          retry: 1000
          data: Entry 3

          """,
          response.body());
    }
  }

  @Test
  void sendsTheWholeReplayFeedWhenNoCursorWasSupplied() throws Exception {
    try (var test = TestServer.start(SseFeedExample.application())) {
      var response =
          test.send(request -> request.path("/replay"), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals(
          """
          id: 1
          retry: 1000
          data: Entry 1

          id: 2
          retry: 1000
          data: Entry 2

          id: 3
          retry: 1000
          data: Entry 3

          """,
          response.body());
    }
  }

  @Test
  void returnsNoContentWithoutStartingAStreamAfterTheFinalEntry() throws Exception {
    try (var test = TestServer.start(SseFeedExample.application())) {
      var response = test.send(request -> request.path("/replay").header("Last-Event-ID", "3"));
      assertEquals(204, response.statusCode());
      assertEquals(0, response.body().length);
      assertTrue(response.headers().firstValue("Content-Type").isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc", "-1", "4", "2147483648", ""})
  void rejectsInvalidReplayCursorsBeforeCommittingAnEventStream(String cursor) throws Exception {
    try (var test = TestServer.start(SseFeedExample.application())) {
      var response =
          test.send(
              request -> request.path("/replay").header("Last-Event-ID", cursor),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(400, response.statusCode());
      assertEquals("Bad Request", response.body());
      assertEquals(
          "text/plain; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
    }
  }
}
