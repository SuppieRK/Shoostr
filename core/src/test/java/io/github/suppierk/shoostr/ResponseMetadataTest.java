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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
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
            (_, response) -> {
              assertEquals(200, response.status());
              assertNull(response.setHeader("Absent"));
              response.status(202).setHeader("X-Value", "first");
              assertEquals(202, response.status());
              assertEquals("first", response.setHeader("x-value"));
              response.text("ok");
            });
    app.start();
    var result =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/metadata"))
                .timeout(Duration.ofSeconds(3))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(202, result.statusCode());
    assertEquals("first", result.headers().firstValue("X-Value").orElseThrow());
    assertEquals("ok", result.body());
  }

  @Test
  @SuppressWarnings("NullAway") // Deliberately verifies rejection of an invalid public API input.
  void rejectsNullTextWithoutChangingStagedResponse() throws Exception {
    app.routes()
        .get(
            "/null-text",
            (_, response) -> {
              response
                  .status(202)
                  .body("application/json", "\"kept\"".getBytes(StandardCharsets.UTF_8));
              assertThrows(NullPointerException.class, () -> response.text(null));
            });
    app.start();
    var result =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/null-text"))
                .timeout(Duration.ofSeconds(3))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(202, result.statusCode());
    assertEquals("application/json", result.headers().firstValue("Content-Type").orElseThrow());
    assertEquals("\"kept\"", result.body());
  }

  @Test
  void appendsHeadersAndReturnsImmutableSnapshots() throws Exception {
    app.routes()
        .get(
            "/headers",
            (_, response) -> {
              response
                  .addHeader("Set-Cookie", "first=1; Path=/")
                  .addHeader("set-cookie", "second=2; Path=/");
              var snapshot = response.headerMap();
              assertEquals(
                  List.of("first=1; Path=/", "second=2; Path=/"), snapshot.get("SET-COOKIE"));
              assertEquals(List.of(), response.headers("Absent"));
              assertThrows(UnsupportedOperationException.class, snapshot::clear);
              var snapshotCookieHeaders = Objects.requireNonNull(snapshot.get("SET-COOKIE"));
              assertThrows(
                  UnsupportedOperationException.class, () -> snapshotCookieHeaders.add("x"));
              var cookieHeaders = response.headers("Set-Cookie");
              assertThrows(UnsupportedOperationException.class, () -> cookieHeaders.add("x"));
              response.addHeader("X-Values", "a,b").addHeader("X-Values", "c");
              assertNull(snapshot.get("X-Values"));
              assertEquals(List.of("a,b", "c"), response.headers("x-values"));
              response.text("ok");
            });
    app.start();
    var result =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/headers"))
                .timeout(Duration.ofSeconds(3))
                .build(),
            HttpResponse.BodyHandlers.ofString());
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
            (_, response) -> {
              response
                  .addHeader("X-Value", "one")
                  .addHeader("x-value", "two")
                  .setHeader("X-VALUE", "replacement")
                  .setHeader("X-Keep", "kept");
              assertEquals(List.of("replacement"), response.headers("x-value"));
              response.removeHeader("x-VaLuE").removeHeader("Absent");
              assertNull(response.setHeader("X-Value"));
              assertEquals(List.of(), response.headers("X-Value"));
              assertNull(response.headerMap().get("X-Value"));
              response.text("ok");
            });
    app.start();
    var result =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/remove"))
                .timeout(Duration.ofSeconds(3))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, result.statusCode());
    assertEquals(List.of(), result.headers().allValues("X-Value"));
    assertEquals("kept", result.headers().firstValue("X-Keep").orElseThrow());
  }

  @Test
  void rejectsInvalidFieldValuesAtomically() throws Exception {
    app.routes()
        .get(
            "/invalid",
            (_, response) -> {
              response.setHeader("X-Value", "kept");
              for (var invalid :
                  List.of("bad\rvalue", "bad\nvalue", "bad" + (char) 0, "bad" + (char) 127)) {
                assertThrows(
                    IllegalArgumentException.class, () -> response.setHeader("X-Value", invalid));
                assertThrows(
                    IllegalArgumentException.class, () -> response.addHeader("X-Value", invalid));
                assertEquals(List.of("kept"), response.headers("X-Value"));
              }
              response.text("ok");
            });
    app.start();
    var result =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/invalid"))
                .timeout(Duration.ofSeconds(3))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, result.statusCode());
    assertEquals(List.of("kept"), result.headers().allValues("X-Value"));
  }

  @Test
  @SuppressWarnings("NullAway") // Null header inputs must fail before mutating the response.
  void protectsHeaderNamesAndFraming() throws Exception {
    app.routes()
        .get(
            "/framing",
            (_, response) -> {
              response.setHeader("X-Value", "kept");
              for (var name :
                  List.of(
                      "",
                      "bad name",
                      "bad:name",
                      "bad\r\nname",
                      "content-length",
                      "TRANSFER-ENCODING")) {
                assertThrows(IllegalArgumentException.class, () -> response.setHeader(name, "x"));
                assertThrows(IllegalArgumentException.class, () -> response.addHeader(name, "x"));
                assertThrows(IllegalArgumentException.class, () -> response.removeHeader(name));
              }
              assertThrows(NullPointerException.class, () -> response.addHeader(null, "x"));
              assertThrows(NullPointerException.class, () -> response.removeHeader(null));
              assertThrows(NullPointerException.class, () -> response.setHeader("X-Value", null));
              assertThrows(NullPointerException.class, () -> response.addHeader("X-Value", null));
              response.setHeader("X-Tab", "a\tb").text("ok");
              assertEquals("a\tb", response.setHeader("X-Tab"));
            });
    app.start();
    var result =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/framing"))
                .timeout(Duration.ofSeconds(3))
                .build(),
            HttpResponse.BodyHandlers.ofString());
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
            (_, response) -> {
              retained.set(response);
              response.status(202).setHeader("X-Value", "kept");
              assertFalse(response.isCommitted());

              try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor
                    .submit(
                        () -> {
                          assertThrows(IllegalStateException.class, response::status);
                          assertThrows(
                              IllegalStateException.class, () -> response.setHeader("X-Value"));
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
              assertEquals("kept", response.setHeader("X-Value"));
              assertEquals(List.of("kept"), response.headers("X-Value"));
              assertEquals(List.of("kept"), response.headerMap().get("x-value"));
              assertThrows(IllegalStateException.class, () -> response.addHeader("X-Value", "bad"));
              assertThrows(IllegalStateException.class, () -> response.removeHeader("X-Value"));
              stream.write("streamed");
            });
    app.start();
    var result =
        client.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + app.port() + "/stream-metadata"))
                .timeout(Duration.ofSeconds(3))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(202, result.statusCode());
    assertEquals("streamed", result.body());
    var closedResponse = retained.get();
    assertThrows(IllegalStateException.class, closedResponse::status);
    assertThrows(IllegalStateException.class, () -> closedResponse.setHeader("X-Value"));
    assertThrows(IllegalStateException.class, () -> closedResponse.headers("X-Value"));
    assertThrows(IllegalStateException.class, closedResponse::headerMap);
    assertThrows(IllegalStateException.class, closedResponse::isCommitted);
  }

  @Test
  void stagesRedirectUntilHandlerReturns() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    app.routes()
        .get(
            "/redirect",
            (_, response) -> {
              response
                  .text("obsolete")
                  .redirect("/next?value=%2F#top")
                  .setHeader("X-After", "continued");
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
        .get("/report", (_, response) -> response.file(file, "text/plain").redirect("/next"));
    app.start();

    var result =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/report"))
                .timeout(Duration.ofSeconds(3))
                .build(),
            HttpResponse.BodyHandlers.ofString());

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
          .get("/redirect-" + code.value(), (_, response) -> response.redirect("../next", code));
    }
    app.start();
    for (var code : codes) {
      var result =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + app.port() + "/redirect-" + code.value()))
                  .timeout(Duration.ofSeconds(3))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
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
            (_, response) -> {
              response.status(202).setHeader("Location", "/original").text("kept");
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
                assertEquals("/original", response.setHeader("Location"));
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
    var result =
        client.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + app.port() + "/invalid-redirect"))
                .timeout(Duration.ofSeconds(3))
                .build(),
            HttpResponse.BodyHandlers.ofString());
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
      app.routes().get("/destination-" + index, (_, response) -> response.redirect(destination));
    }
    app.start();
    for (int index = 0; index < destinations.length; index++) {
      var result =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + app.port() + "/destination-" + index))
                  .timeout(Duration.ofSeconds(3))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(302, result.statusCode());
      assertEquals(destinations[index][1], result.headers().firstValue("Location").orElseThrow());
    }
  }

  @Test
  void preservesRedirectLifecycleOnStreamingAndFailure() throws Exception {
    app.routes()
        .get(
            "/stream-redirect",
            (_, response) -> {
              var stream = response.startStream("text/plain");
              assertThrows(IllegalStateException.class, () -> response.redirect("/late"));
              assertNull(response.setHeader("Location"));
              stream.write("original");
            });
    app.routes()
        .get(
            "/failed-redirect",
            (_, response) -> {
              response.redirect("/never");
              throw new IllegalArgumentException("private details");
            });
    app.routes().head("/head-redirect", (_, response) -> response.redirect("/next"));
    app.start();
    var streamed =
        client.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + app.port() + "/stream-redirect"))
                .timeout(Duration.ofSeconds(3))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, streamed.statusCode());
    assertEquals("original", streamed.body());
    assertEquals(List.of(), streamed.headers().allValues("Location"));
    var failed =
        client.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + app.port() + "/failed-redirect"))
                .timeout(Duration.ofSeconds(3))
                .build(),
            HttpResponse.BodyHandlers.ofString());
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
}
