package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpStatusCodes;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class ResponseMetadataTest {
  @TempDir Path temporaryDirectory;
  private Shoostr app;
  private HttpClient client;

  @BeforeEach
  void prepare() {
    app = new Shoostr(Options.defaults().withPort(0));
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  @AfterEach
  void close() throws Exception {
    try {
      client.close();
    } finally {
      app.close();
    }
  }

  @Test
  void inspectsStagedStatusAndFirstHeader() throws Exception {
    app.routes()
        .get(
            "/metadata",
            (request, response) -> {
              assertEquals(200, response.status());
              assertNull(response.header("Absent"));
              response.status(202).header("X-Value", "first");
              assertEquals(202, response.status());
              assertEquals("first", response.header("x-value"));
              response.text("ok");
            });
    app.start();
    var result = send("/metadata");
    assertEquals(202, result.statusCode());
    assertEquals("first", result.headers().firstValue("X-Value").orElseThrow());
    assertEquals("ok", result.body());
  }

  @Test
  void appendsHeadersAndReturnsImmutableSnapshots() throws Exception {
    app.routes()
        .get(
            "/headers",
            (request, response) -> {
              response
                  .addHeader("Set-Cookie", "first=1; Path=/")
                  .addHeader("set-cookie", "second=2; Path=/");
              var snapshot = response.headerMap();
              assertEquals(
                  List.of("first=1; Path=/", "second=2; Path=/"), snapshot.get("SET-COOKIE"));
              assertEquals(List.of(), response.headers("Absent"));
              assertThrows(UnsupportedOperationException.class, snapshot::clear);
              assertThrows(
                  UnsupportedOperationException.class,
                  () -> response.headers("Set-Cookie").add("x"));
              response.addHeader("X-Values", "a,b").addHeader("X-Values", "c");
              assertNull(snapshot.get("X-Values"));
              assertEquals(List.of("a,b", "c"), response.headers("x-values"));
              response.text("ok");
            });
    app.start();
    var result = send("/headers");
    assertEquals(200, result.statusCode());
    assertEquals(
        List.of("first=1; Path=/", "second=2; Path=/"), result.headers().allValues("Set-Cookie"));
    assertEquals(List.of("a,b", "c"), result.headers().allValues("X-Values"));
  }

  @Test
  void replacesAndRemovesAllFieldValues() throws Exception {
    app.routes()
        .get(
            "/remove",
            (request, response) -> {
              response
                  .addHeader("X-Value", "one")
                  .addHeader("x-value", "two")
                  .header("X-VALUE", "replacement")
                  .header("X-Keep", "kept");
              assertEquals(List.of("replacement"), response.headers("x-value"));
              response.removeHeader("x-VaLuE").removeHeader("Absent");
              assertNull(response.header("X-Value"));
              assertEquals(List.of(), response.headers("X-Value"));
              assertNull(response.headerMap().get("X-Value"));
              response.text("ok");
            });
    app.start();
    var result = send("/remove");
    assertEquals(200, result.statusCode());
    assertEquals(List.of(), result.headers().allValues("X-Value"));
    assertEquals("kept", result.headers().firstValue("X-Keep").orElseThrow());
  }

  @Test
  void rejectsInvalidFieldValuesAtomically() throws Exception {
    app.routes()
        .get(
            "/invalid",
            (request, response) -> {
              response.header("X-Value", "kept");
              for (var invalid :
                  List.of("bad\rvalue", "bad\nvalue", "bad" + (char) 0, "bad" + (char) 127)) {
                assertThrows(
                    IllegalArgumentException.class, () -> response.header("X-Value", invalid));
                assertThrows(
                    IllegalArgumentException.class, () -> response.addHeader("X-Value", invalid));
                assertEquals(List.of("kept"), response.headers("X-Value"));
              }
              response.text("ok");
            });
    app.start();
    var result = send("/invalid");
    assertEquals(200, result.statusCode());
    assertEquals(List.of("kept"), result.headers().allValues("X-Value"));
  }

  @Test
  @SuppressWarnings("NullAway") // Null header inputs must fail before mutating the response.
  void protectsHeaderNamesAndFraming() throws Exception {
    app.routes()
        .get(
            "/framing",
            (request, response) -> {
              response.header("X-Value", "kept");
              for (var name :
                  List.of(
                      "",
                      "bad name",
                      "bad:name",
                      "bad\r\nname",
                      "content-length",
                      "TRANSFER-ENCODING")) {
                assertThrows(IllegalArgumentException.class, () -> response.header(name, "x"));
                assertThrows(IllegalArgumentException.class, () -> response.addHeader(name, "x"));
                assertThrows(IllegalArgumentException.class, () -> response.removeHeader(name));
              }
              assertThrows(NullPointerException.class, () -> response.addHeader(null, "x"));
              assertThrows(NullPointerException.class, () -> response.removeHeader(null));
              assertThrows(NullPointerException.class, () -> response.header("X-Value", null));
              assertThrows(NullPointerException.class, () -> response.addHeader("X-Value", null));
              response.header("X-Tab", "a\tb").text("ok");
              assertEquals("a\tb", response.header("X-Tab"));
            });
    app.start();
    var result = send("/framing");
    assertEquals(200, result.statusCode());
    assertEquals("2", result.headers().firstValue("Content-Length").orElseThrow());
    assertEquals("a b", result.headers().firstValue("X-Tab").orElseThrow());
    assertEquals("kept", result.headers().firstValue("X-Value").orElseThrow());
  }

  @Test
  void confinesMetadataToTheLiveOwnerThread() throws Exception {
    var retained = new AtomicReference<Response>();
    app.routes()
        .get(
            "/stream-metadata",
            (request, response) -> {
              retained.set(response);
              response.status(202).header("X-Value", "kept");
              assertFalse(response.isCommitted());

              try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor
                    .submit(
                        () -> {
                          assertThrows(IllegalStateException.class, response::status);
                          assertThrows(
                              IllegalStateException.class, () -> response.header("X-Value"));
                          assertThrows(
                              IllegalStateException.class, () -> response.headers("X-Value"));
                          assertThrows(IllegalStateException.class, response::headerMap);
                          assertThrows(IllegalStateException.class, response::isCommitted);
                          assertThrows(
                              IllegalStateException.class,
                              () -> response.addHeader("X-Value", "bad"));
                          assertThrows(
                              IllegalStateException.class, () -> response.removeHeader("X-Value"));
                        })
                    .get(3, TimeUnit.SECONDS);
              }

              var stream = response.startStream("text/plain");
              assertTrue(response.isCommitted());
              assertEquals(202, response.status());
              assertEquals("kept", response.header("X-Value"));
              assertEquals(List.of("kept"), response.headers("X-Value"));
              assertEquals(List.of("kept"), response.headerMap().get("x-value"));
              assertThrows(IllegalStateException.class, () -> response.addHeader("X-Value", "bad"));
              assertThrows(IllegalStateException.class, () -> response.removeHeader("X-Value"));
              stream.write("streamed");
            });
    app.start();
    var result = send("/stream-metadata");
    assertEquals(202, result.statusCode());
    assertEquals("streamed", result.body());
    assertThrows(IllegalStateException.class, () -> retained.get().status());
    assertThrows(IllegalStateException.class, () -> retained.get().header("X-Value"));
    assertThrows(IllegalStateException.class, () -> retained.get().headers("X-Value"));
    assertThrows(IllegalStateException.class, () -> retained.get().headerMap());
    assertThrows(IllegalStateException.class, () -> retained.get().isCommitted());
  }

  @Test
  void stagesRedirectUntilHandlerReturns() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    app.routes()
        .get(
            "/redirect",
            (request, response) -> {
              response
                  .text("obsolete")
                  .redirect("/next?value=%2F#top")
                  .header("X-After", "continued");
              assertFalse(response.isCommitted());
              assertEquals(302, response.status());
              entered.countDown();
              assertTrue(release.await(3, TimeUnit.SECONDS));
            });
    app.start();
    var future =
        client.sendAsync(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/redirect"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    try {
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      assertFalse(future.isDone());
    } finally {
      release.countDown();
    }

    var result = future.get(3, TimeUnit.SECONDS);
    assertEquals(302, result.statusCode());
    assertEquals("/next?value=%2F#top", result.headers().firstValue("Location").orElseThrow());
    assertEquals("continued", result.headers().firstValue("X-After").orElseThrow());
    assertEquals(List.of(), result.headers().allValues("Content-Type"));
    assertEquals("", result.body());
  }

  @Test
  void redirectAfterFileDiscardsTheSelectedRepresentation() throws Exception {
    var file = Files.writeString(temporaryDirectory.resolve("report.txt"), "file contents");
    app.routes()
        .get("/report", (request, response) -> response.file(file, "text/plain").redirect("/next"));
    app.start();

    var result = send("/report");

    assertEquals(302, result.statusCode());
    assertEquals("/next", result.headers().firstValue("Location").orElseThrow());
    assertEquals("", result.body());
    assertEquals(List.of(), result.headers().allValues("Content-Range"));
    assertEquals(List.of(), result.headers().allValues("ETag"));
    assertEquals(List.of(), result.headers().allValues("Accept-Ranges"));
    assertEquals(List.of(), result.headers().allValues("Content-Type"));
  }

  @Test
  void supportsExplicitRedirectStatuses() throws Exception {
    var codes =
        List.of(
            HttpStatusCodes.MULTIPLE_CHOICES,
            HttpStatusCodes.MOVED_PERMANENTLY,
            HttpStatusCodes.FOUND,
            HttpStatusCodes.SEE_OTHER,
            HttpStatusCodes.TEMPORARY_REDIRECT,
            HttpStatusCodes.PERMANENT_REDIRECT);
    for (var code : codes) {
      app.routes()
          .get(
              "/redirect-" + code.value(),
              (request, response) -> response.redirect("../next", code));
    }
    app.start();
    for (var code : codes) {
      var result = send("/redirect-" + code.value());
      assertEquals(code.value(), result.statusCode());
      assertEquals("../next", result.headers().firstValue("Location").orElseThrow());
      assertEquals("", result.body());
    }
  }

  @Test
  @SuppressWarnings("NullAway") // Null redirect inputs are rejected by the response API.
  void validatesRedirectBeforeChangingOutput() throws Exception {
    app.routes()
        .get(
            "/invalid-redirect",
            (request, response) -> {
              response.status(202).header("Location", "/original").text("kept");
              for (var location :
                  List.of(
                      "",
                      "bad target",
                      "/bad%zz",
                      "/bad\r\nLocation: /other",
                      "https:///missing-host",
                      "https://user:pass@example.test/",
                      "//user@example.test/",
                      "https://example.test:65536/")) {
                assertThrows(IllegalArgumentException.class, () -> response.redirect(location));
                assertEquals(202, response.status());
                assertEquals("/original", response.header("Location"));
              }
              for (var code :
                  List.of(
                      HttpStatusCodes.OK,
                      HttpStatusCodes.NOT_MODIFIED,
                      HttpStatusCodes.BAD_REQUEST,
                      HttpStatusCodes.INTERNAL_SERVER_ERROR)) {
                assertThrows(
                    IllegalArgumentException.class, () -> response.redirect("/valid", code));
              }
              assertThrows(NullPointerException.class, () -> response.redirect(null));
              assertThrows(NullPointerException.class, () -> response.redirect("/valid", null));
            });
    app.start();
    var result = send("/invalid-redirect");
    assertEquals(202, result.statusCode());
    assertEquals("/original", result.headers().firstValue("Location").orElseThrow());
    assertEquals(
        "text/plain; charset=utf-8", result.headers().firstValue("Content-Type").orElseThrow());
    assertEquals("kept", result.body());
  }

  @Test
  void preservesValidRedirectReferences() throws Exception {
    String[][] destinations = {
      {"/", "/"},
      {"next", "next"},
      {"?q=1", "?q=1"},
      {"#section", "#section"},
      {"//example.test/a", "//example.test/a"},
      {"https://example.test/a?x=%2f", "https://example.test/a?x=%2f"},
      {"mailto:help@example.test", "mailto:help@example.test"},
      {"/café", "/caf%C3%A9"}
    };
    for (int index = 0; index < destinations.length; index++) {
      var destination = destinations[index][0];
      app.routes()
          .get("/destination-" + index, (request, response) -> response.redirect(destination));
    }
    app.start();
    for (int index = 0; index < destinations.length; index++) {
      var result = send("/destination-" + index);
      assertEquals(302, result.statusCode());
      assertEquals(destinations[index][1], result.headers().firstValue("Location").orElseThrow());
    }
  }

  @Test
  void preservesRedirectLifecycleOnStreamingAndFailure() throws Exception {
    app.routes()
        .get(
            "/stream-redirect",
            (request, response) -> {
              var stream = response.startStream("text/plain");
              assertThrows(IllegalStateException.class, () -> response.redirect("/late"));
              assertNull(response.header("Location"));
              stream.write("original");
            });
    app.routes()
        .get(
            "/failed-redirect",
            (request, response) -> {
              response.redirect("/never");
              throw new IllegalArgumentException("private details");
            });
    app.routes().head("/head-redirect", (request, response) -> response.redirect("/next"));
    app.start();
    var streamed = send("/stream-redirect");
    assertEquals(200, streamed.statusCode());
    assertEquals("original", streamed.body());
    assertEquals(List.of(), streamed.headers().allValues("Location"));
    var failed = send("/failed-redirect");
    assertEquals(500, failed.statusCode());
    assertEquals("Internal Server Error", failed.body());
    assertEquals(List.of(), failed.headers().allValues("Location"));
    var head =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/head-redirect"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(302, head.statusCode());
    assertEquals("/next", head.headers().firstValue("Location").orElseThrow());
    assertEquals("", head.body());
  }

  private HttpResponse<String> send(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
            .timeout(Duration.ofSeconds(3))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
