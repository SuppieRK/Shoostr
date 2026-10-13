package io.github.suppierk.shoostr.example.postgres;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PostgresExampleTest {
  @Test
  void returnsNotFoundWhenTheLookupHasNoSuchNote() throws Exception {
    try (var test =
        TestServer.start(
            PostgresExample.application(text -> new Note(41, text), _ -> Optional.empty()))) {
      assertEquals(404, test.send(request -> request.path("/notes/41")).statusCode());
    }
  }

  @Test
  void rejectsBlankTextBeforeCreation() throws Exception {
    try (var test =
        TestServer.start(
            PostgresExample.application(text -> new Note(41, text), _ -> Optional.empty()))) {
      assertEquals(
          400,
          test.send(
                  request ->
                      request
                          .path("/notes")
                          .method(HttpMethods.POST)
                          .body(" ".getBytes(StandardCharsets.UTF_8)))
              .statusCode());
    }
  }

  @Test
  void rejectsAnIdentityBeyondTheLongRange() throws Exception {
    try (var test =
        TestServer.start(
            PostgresExample.application(text -> new Note(41, text), _ -> Optional.empty()))) {
      assertEquals(
          400, test.send(request -> request.path("/notes/9223372036854775808")).statusCode());
    }
  }

  @Test
  void returnsTheNoteFromTheSuppliedLookupFunction() throws Exception {
    try (var test =
        TestServer.start(
            PostgresExample.application(
                text -> new Note(41, text),
                identity -> Optional.of(new Note(identity, "Stored note"))))) {
      var response =
          test.send(request -> request.path("/notes/41"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("Stored note", response.body()));
    }
  }

  @Test
  void returnsTheIdentityFromTheSuppliedCreationFunction() throws Exception {
    try (var test =
        TestServer.start(
            PostgresExample.application(text -> new Note(41, text), _ -> Optional.empty()))) {
      var response =
          test.send(
              request ->
                  request
                      .path("/notes")
                      .method(HttpMethods.POST)
                      .body("Read the docs".getBytes(StandardCharsets.UTF_8)),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(201, response.statusCode()),
          () -> assertEquals("/notes/41", response.headers().firstValue("Location").orElseThrow()),
          () -> assertEquals("Read the docs", response.body()));
    }
  }
}
