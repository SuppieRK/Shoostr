package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.http.Cookie;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ResponseTypedFluentTest {
  @ParameterizedTest
  @CsvSource({
    "headers, 200, ''",
    "input, 204, ''",
    "file, 200, download",
    "attachment, 200, download",
    "compression, 200, ''",
    "cookie-name, 200, ''",
    "cookie, 200, ''",
    "remove-cookie-name, 200, ''",
    "remove-cookie-path, 200, ''",
    "remove-cookie, 200, ''",
    "redirect, 302, ''",
    "redirect-status, 307, ''"
  })
  void retainsTypedResponseForHeaderConfigurationAfterEveryFluentOperation(
      String operation, int status, String body, @TempDir Path directory) throws Exception {
    var file = Files.writeString(directory.resolve("report.txt"), "download");
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.codec(new TestCodec())
          .routes()
          .get(
              "/fluent",
              (ignoredRequest, response) ->
                  fluentResponse(response, operation, file).setHeader("X-Chain", "ok"));

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/fluent"), HttpResponse.BodyHandlers.ofString());

        assertEquals(status, result.statusCode());
        assertEquals(body, result.body());
        assertEquals("ok", result.headers().firstValue("X-Chain").orElseThrow());
      }
    }
  }

  private Response.Typed fluentResponse(Response.Typed response, String operation, Path file)
      throws IOException {
    return switch (operation) {
      case "headers" -> response.addHeader("X-Remove", "value").removeHeader("X-Remove");
      case "input" ->
          response
              .status(204)
              .input(
                  new ByteArrayInputStream("download".getBytes(StandardCharsets.UTF_8)),
                  "text/plain");
      case "file" -> response.file(file, "text/plain");
      case "attachment" -> response.attachment(file, "text/plain", "report.txt");
      case "compression" -> response.disableCompression();
      case "cookie-name" -> response.cookie("token", "value");
      case "cookie" -> response.cookie(new Cookie("token", "value"));
      case "remove-cookie-name" -> response.removeCookie("token");
      case "remove-cookie-path" -> response.removeCookie("token", "/", null);
      case "remove-cookie" -> response.removeCookie(new Cookie("token", "value"));
      case "redirect" -> response.redirect("/next");
      case "redirect-status" -> response.redirect("/next", HttpStatusCodes.TEMPORARY_REDIRECT);
      default -> throw new IllegalArgumentException(operation);
    };
  }
}
