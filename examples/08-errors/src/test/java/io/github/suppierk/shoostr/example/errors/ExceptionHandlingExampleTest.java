package io.github.suppierk.shoostr.example.errors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Timeout(15)
class ExceptionHandlingExampleTest {
  @Test
  void fallsBackSafelyWhenAScopedRendererFailsWithoutTryingTheApplicationRenderer()
      throws Exception {
    try (var app = ExceptionHandlingExample.application()) {
      app.routes()
          .path(
              "/renderer-failure",
              group -> {
                group.exception(
                    IllegalStateException.class,
                    (ignored, ignored2, response) -> {
                      response
                          .setHeader("X-Renderer-Diagnostic", "private")
                          .text("Private renderer output");
                      throw new IOException("Private renderer diagnostics");
                    });
                group.get(
                    (ignored3, ignored4) -> {
                      throw new IllegalStateException("Private original diagnostics");
                    });
              });

      try (var test = TestServer.start(app)) {
        var response =
            test.send(
                request -> request.path("/renderer-failure"), HttpResponse.BodyHandlers.ofString());
        assertEquals(500, response.statusCode());
        assertEquals("Internal Server Error", response.body());
        assertTrue(response.headers().firstValue("X-Renderer-Diagnostic").isEmpty());
      }
    }
  }

  @Test
  void abortsCommittedOutputWithoutInvokingAnErrorRenderer() throws Exception {
    var release = new CountDownLatch(1);
    var rendererCalls = new AtomicInteger();

    try (var app = ExceptionHandlingExample.application()) {
      app.routes()
          .exception(
              IOException.class,
              (ignored5, ignored6, response) -> {
                rendererCalls.incrementAndGet();
                response.text("Must not run after commitment");
              });
      app.routes()
          .get(
              "/committed",
              (ignored7, response) -> {
                var stream = response.startStream("text/plain");
                stream.write("visible");
                stream.flush();
                if (!release.await(5, TimeUnit.SECONDS)) {
                  throw new IOException("Reader did not release the handler");
                }

                stream.write("Private buffered output");
                throw new IOException("Private transport diagnostics");
              });

      try (var test = TestServer.start(app)) {
        try {
          var response =
              test.send(
                  request -> request.path("/committed").timeout(Duration.ofSeconds(5)),
                  HttpResponse.BodyHandlers.ofInputStream());
          assertEquals(200, response.statusCode());

          try (var input = response.body()) {
            assertEquals("visible", new String(input.readNBytes(7), StandardCharsets.UTF_8));
            release.countDown();
            var tail = new ByteArrayOutputStream();
            assertThrows(
                IOException.class,
                () -> {
                  int value;
                  while ((value = input.read()) != -1) {
                    tail.write(value);
                  }
                });
            assertEquals("", tail.toString(StandardCharsets.UTF_8));
          }

          assertEquals(0, rendererCalls.get());
        } finally {
          release.countDown();
        }
      }
    }
  }

  @ParameterizedTest
  @CsvSource({
    "/recover?value=bad, 400, Use an integer",
    "/recover?value=42, 200, 42",
    "/number?value=42, 200, 42",
    "/input?name=Ada, 200, Ada"
  })
  void recoversLocallyOrReturnsValidInput(String path, int status, String body) throws Exception {
    try (var test = TestServer.start(ExceptionHandlingExample.application())) {
      var response = test.send(request -> request.path(path), HttpResponse.BodyHandlers.ofString());
      assertEquals(status, response.statusCode());
      assertEquals(body, response.body());
    }
  }

  @ParameterizedTest
  @CsvSource({
    "/builtin, 400, Bad Request",
    "/unhandled, 500, Internal Server Error",
    "/cause, 500, Internal Server Error"
  })
  void usesSafeDefaultsWithoutUnwrappingTheCause(String path, int status, String body)
      throws Exception {
    try (var test = TestServer.start(ExceptionHandlingExample.application())) {
      var response = test.send(request -> request.path(path), HttpResponse.BodyHandlers.ofString());
      assertEquals(status, response.statusCode());
      assertEquals(body, response.body());
      assertEquals(
          "text/plain; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
    }
  }

  @ParameterizedTest
  @CsvSource({
    "/input, 400, Invalid input",
    "/number?value=nope, 422, Integer required",
    "/group/input, 409, Group input rejected",
    "/group/number?value=nope, 422, Integer required",
    "/inherited/input, 400, Invalid input"
  })
  void selectsSpecificInheritedAndOverriddenScopeRenderers(String path, int status, String body)
      throws Exception {
    try (var test = TestServer.start(ExceptionHandlingExample.application())) {
      var response = test.send(request -> request.path(path), HttpResponse.BodyHandlers.ofString());
      assertEquals(status, response.statusCode());
      assertEquals(body, response.body());
    }
  }

  @Test
  void rendersAnApplicationFailureWithoutPrivateStagedOutput() throws Exception {
    try (var test = TestServer.start(ExceptionHandlingExample.application())) {
      var response =
          test.send(request -> request.path("/checkout"), HttpResponse.BodyHandlers.ofString());
      assertEquals(500, response.statusCode());
      assertEquals("Request failed", response.body());
      assertEquals(
          "text/plain; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
      assertTrue(response.headers().firstValue("X-Diagnostic").isEmpty());
    }
  }
}
