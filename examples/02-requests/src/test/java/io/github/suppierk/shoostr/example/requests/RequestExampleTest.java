package io.github.suppierk.shoostr.example.requests;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class RequestExampleTest {
  @Test
  void passesTheDecodedExplicitQueryNameToTheDependency() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> "Welcome " + name))) {
      var response =
          test.send(
              request -> request.path("/welcome?name=Ada%20Lovelace"),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("Welcome Ada Lovelace", response.body()));
    }
  }

  @Test
  void returnsEmptySearchTextWhenNoTagsAreSupplied() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      var response =
          test.send(request -> request.path("/search"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()), () -> assertEquals("", response.body()));
    }
  }

  @Test
  void rejectsABlankExplicitQueryNameRatherThanApplyingTheDefault() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      assertEquals(400, test.send(request -> request.path("/welcome?name=%20")).statusCode());
    }
  }

  @Test
  void usesWorldWhenTheOptionalQueryNameIsAbsent() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> "Welcome " + name))) {
      var response =
          test.send(request -> request.path("/welcome"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("Welcome World", response.body()));
    }
  }

  @Test
  void preservesRepeatedQueryValuesInOrder() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> name))) {
      var response =
          test.send(
              request -> request.path("/search?tag=java&tag=HTTP%20API"),
              HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("java,HTTP API", response.body()));
    }
  }

  @Test
  void usesTheDependencySuppliedBeforeStartingTheFixture() throws Exception {
    try (var test = TestServer.start(RequestExample.application(name -> "Test " + name))) {
      var response =
          test.send(
              request -> request.path("/greetings/Ada"), HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode()),
          () -> assertEquals("Test Ada", response.body()));
    }
  }
}
