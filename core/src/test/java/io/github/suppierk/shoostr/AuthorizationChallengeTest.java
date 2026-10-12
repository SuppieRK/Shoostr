package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.exceptions.AuthorizationDeniedException;
import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class AuthorizationChallengeTest {
  private static final String CHALLENGE = "Bearer error=\"insufficient_scope\", scope=\"write\"";
  private static final Handler DENY =
      (_, _) -> {
        throw new AuthorizationDeniedException(CHALLENGE);
      };

  @ParameterizedTest
  @ValueSource(strings = {"builtin", "app", "scope"})
  void preservesTheRequiredChallengeWithBuiltInOrCustomBodyRendering(String renderer)
      throws Exception {
    try (var app = new Shoostr()) {
      if ("app".equals(renderer)) {
        app.exception(ForbiddenException.class, (_, _, response) -> response.text("custom"));
      } else if ("scope".equals(renderer)) {
        app.routes().exception(Exception.class, (_, _, response) -> response.text("custom"));
      }

      app.routes().get("/denied", DENY);

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/denied"), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, result.statusCode());
        assertEquals(
            CHALLENGE,
            result.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE.value()).orElseThrow());
        if (!"builtin".equals(renderer)) {
          assertEquals("custom", result.body());
        }
      }
    }
  }

  @Test
  void restoresTheRequiredChallengeWhenACustomRendererReplacesIt() throws Exception {
    try (var app = new Shoostr()) {
      app.exception(
          ForbiddenException.class,
          (_, _, response) ->
              response
                  .setHeader(HttpHeaders.WWW_AUTHENTICATE.value(), "Basic realm=\"wrong\"")
                  .text("custom"));
      app.routes().get("/denied", DENY);

      try (var test = TestServer.start(app)) {
        var result = test.send(request -> request.path("/denied"));
        assertEquals(
            CHALLENGE,
            result.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE.value()).orElseThrow());
      }
    }
  }

  @Test
  void preservesTheChallengeWhenACustomRendererStartsAStream() throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .exception(
              AuthorizationDeniedException.class,
              (_, _, response) -> response.startStream("text/plain").write("streamed denial"))
          .get("/denied", DENY);

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/denied"), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, result.statusCode());
        assertEquals(
            CHALLENGE,
            result.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE.value()).orElseThrow());
        assertEquals("streamed denial", result.body());
      }
    }
  }

  @Test
  void doesNotAddTheDenialChallengeWhenTheRendererDeliberatelyChangesStatus() throws Exception {
    try (var app = new Shoostr()) {
      app.exception(ForbiddenException.class, (_, _, response) -> response.status(404));
      app.routes().get("/denied", DENY);

      try (var test = TestServer.start(app)) {
        var result = test.send(request -> request.path("/denied"));
        assertEquals(404, result.statusCode());
        assertFalse(result.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE.value()).isPresent());
      }
    }
  }

  @Test
  void doesNotInventAChallengeForAPlainForbiddenException() throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .get(
              "/denied",
              (_, _) -> {
                throw new ForbiddenException();
              });

      try (var test = TestServer.start(app)) {
        var result = test.send(request -> request.path("/denied"));
        assertEquals(403, result.statusCode());
        assertFalse(result.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE.value()).isPresent());
      }
    }
  }
}
