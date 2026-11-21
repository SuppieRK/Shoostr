package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.exceptions.AuthorizationDeniedException;
import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class AuthorizationChallengeTest {
  private static final String CHALLENGE = "Bearer error=\"insufficient_scope\", scope=\"write\"";
  private static final Handler DENY =
      (ignored, ignoredResponse) -> {
        throw new AuthorizationDeniedException(CHALLENGE);
      };

  @ParameterizedTest
  @ValueSource(strings = {"builtin", "app", "scope"})
  void preservesTheRequiredChallengeWithBuiltInOrCustomBodyRendering(String renderer)
      throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup = app::close) {
        if ("app".equals(renderer)) {
          app.exception(
              ForbiddenException.class,
              (ignoredFailure, ignoredRequest, response) -> response.text("custom"));
        } else if ("scope".equals(renderer)) {
          app.routes()
              .exception(
                  Exception.class,
                  (ignoredFailure, ignoredRequest, response) -> response.text("custom"));
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
  }

  @Test
  void restoresTheRequiredChallengeWhenCustomRendererReplacesIt() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup1 = app::close) {
        app.exception(
            ForbiddenException.class,
            (ignoredFailure, ignoredRequest, response) ->
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
  }

  @Test
  void preservesTheChallengeWhenCustomRendererStartsStream() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup2 = app::close) {
        app.routes()
            .exception(
                AuthorizationDeniedException.class,
                (ignoredFailure, ignoredRequest, response) ->
                    response.startStream("text/plain").write("streamed denial"))
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
  }

  @Test
  void doesNotAddTheDenialChallengeWhenTheRendererDeliberatelyChangesStatus() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup3 = app::close) {
        app.exception(
            ForbiddenException.class,
            (ignoredFailure, ignoredRequest, response) -> response.status(404));
        app.routes().get("/denied", DENY);

        try (var test = TestServer.start(app)) {
          var result = test.send(request -> request.path("/denied"));

          assertEquals(404, result.statusCode());
          assertFalse(
              result.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE.value()).isPresent());
        }
      }
    }
  }

  @Test
  void doesNotInventChallengeForPlainForbiddenException() throws Exception {
    {
      var app = new Shoostr();

      try (Closeable appCleanup4 = app::close) {
        app.routes()
            .get(
                "/denied",
                (ignoredRequest, ignoredResponse) -> {
                  throw new ForbiddenException();
                });

        try (var test = TestServer.start(app)) {
          var result = test.send(request -> request.path("/denied"));

          assertEquals(403, result.statusCode());
          assertFalse(
              result.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE.value()).isPresent());
        }
      }
    }
  }
}
