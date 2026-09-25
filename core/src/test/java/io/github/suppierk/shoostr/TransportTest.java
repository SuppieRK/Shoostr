package io.github.suppierk.shoostr;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.exceptions.NotFoundException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.eclipse.jetty.compression.gzip.GzipCompression;
import org.eclipse.jetty.compression.server.CompressionConfig;
import org.eclipse.jetty.compression.server.CompressionHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.GracefulHandler;
import org.eclipse.jetty.session.DefaultSessionCache;
import org.eclipse.jetty.session.SessionHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(20)
class TransportTest {
  private static final String KEYSTORE_RESOURCE = "/localhost-test.p12";
  private static final String UNSELECTED_ENCODING = "unselected";
  private static final char[] PASSWORD = "changeit".toCharArray();
  @TempDir private Path temporary;

  @Test
  void tlsServesAnHttpsClientAndReportsDirectSecureMetadata() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = secureClient(HttpClient.Version.HTTP_1_1)) {
      enableTls(app);
      app.routes()
          .get(
              "/secure",
              (request, response) -> {
                assertTrue(request.isSecure());
                assertEquals("https", request.scheme());
                response.text("secure");
              });
      app.start();

      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("https://localhost:" + app.port() + "/secure"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals("secure", result.body());
      assertEquals(HttpClient.Version.HTTP_1_1, result.version());
    }
  }

  @Test
  void http2NegotiatesOverTlsAndRunsConcurrentStreamsOnOneConnection() throws Exception {
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = secureClient(HttpClient.Version.HTTP_2)) {
      enableTls(app);
      app.http2();
      app.routes()
          .get(
              "/parallel/{id}",
              (request, response) -> {
                if ("warmup".equals(request.pathParam("id"))) {
                  response.text("warm");
                  return;
                }

                entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
                response.text(
                    request.pathParam("id")
                        + ":"
                        + Objects.requireNonNull((InetSocketAddress) request.remoteAddress())
                            .getPort());
              });
      app.start();
      var base = "https://localhost:" + app.port() + "/parallel/";
      assertEquals(
          HttpClient.Version.HTTP_2,
          client
              .send(
                  HttpRequest.newBuilder(URI.create(base + "warmup")).build(),
                  HttpResponse.BodyHandlers.ofString())
              .version());

      try {
        var first =
            client.sendAsync(
                HttpRequest.newBuilder(URI.create(base + "first")).build(),
                HttpResponse.BodyHandlers.ofString());
        var second =
            client.sendAsync(
                HttpRequest.newBuilder(URI.create(base + "second")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        release.countDown();

        var firstResult = first.get(5, TimeUnit.SECONDS);
        var secondResult = second.get(5, TimeUnit.SECONDS);
        assertEquals(HttpClient.Version.HTTP_2, firstResult.version());
        assertEquals(HttpClient.Version.HTTP_2, secondResult.version());
        assertEquals(200, firstResult.statusCode());
        assertEquals(200, secondResult.statusCode());
        assertEquals(firstResult.body().split(":", -1)[1], secondResult.body().split(":", -1)[1]);
      } finally {
        release.countDown();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void closeDrainsHttpsHttp2InteractionsAndRejectsNewStreams(boolean streaming) throws Exception {
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var admissions = new AtomicInteger();
    var nativeServer = new AtomicReference<Server>();
    var tlsContext = new AtomicReference<SslContextFactory.Server>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = secureClient(HttpClient.Version.HTTP_2)) {
      enableTls(app, tlsContext);
      app.http2().modifyServer(nativeServer::set);
      app.routes().get("/warmup", (request, response) -> response.text("ready"));
      app.routes()
          .get(
              "/hold/{id}",
              (request, response) -> {
                admissions.incrementAndGet();
                var stream = streaming ? response.startStream("text/plain") : null;
                if (stream != null) {
                  stream.write("first-");
                  stream.flush();
                }

                entered.countDown();
                release.await();
                if (stream != null) {
                  stream.write(request.pathParam("id"));
                } else {
                  response.text("complete-" + request.pathParam("id"));
                }
              });
      app.start();
      var base = "https://localhost:" + app.port();
      assertEquals(
          HttpClient.Version.HTTP_2,
          client
              .send(
                  HttpRequest.newBuilder(URI.create(base + "/warmup")).build(),
                  HttpResponse.BodyHandlers.ofString())
              .version());
      var first =
          client.sendAsync(
              HttpRequest.newBuilder(URI.create(base + "/hold/one")).build(),
              HttpResponse.BodyHandlers.ofString());
      var second =
          client.sendAsync(
              HttpRequest.newBuilder(URI.create(base + "/hold/two")).build(),
              HttpResponse.BodyHandlers.ofString());
      CompletableFuture<Void> closing = null;

      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var graceful = nativeServer.get().getDescendant(GracefulHandler.class);
        closing =
            CompletableFuture.runAsync(
                () -> {
                  try {
                    app.close();
                  } catch (IOException failure) {
                    throw new CompletionException(failure);
                  }
                });
        await()
            .pollInterval(Duration.ofMillis(10))
            .atMost(Duration.ofSeconds(3))
            .until(graceful::isShutdown);
        assertFalse(closing.isDone());
        var late =
            client.sendAsync(
                HttpRequest.newBuilder(URI.create(base + "/hold/late")).build(),
                HttpResponse.BodyHandlers.discarding());

        try {
          assertEquals(503, late.get(3, TimeUnit.SECONDS).statusCode());
        } catch (ExecutionException failure) {
          assertInstanceOf(IOException.class, failure.getCause());
        }

        assertEquals(2, admissions.get());
        release.countDown();
        assertEquals(
            streaming ? "first-one" : "complete-one", first.get(3, TimeUnit.SECONDS).body());
        assertEquals(
            streaming ? "first-two" : "complete-two", second.get(3, TimeUnit.SECONDS).body());
        closing.get(3, TimeUnit.SECONDS);
        assertTrue(nativeServer.get().isStopped());
        assertTrue(((ServerConnector) nativeServer.get().getConnectors()[0]).isStopped());
        assertTrue(tlsContext.get().isStopped());
        assertTrue(
            ((ExecutorService)
                    ((QueuedThreadPool) nativeServer.get().getThreadPool())
                        .getVirtualThreadsExecutor())
                .isShutdown());
      } finally {
        release.countDown();
        if (closing != null) {
          closing.get(6, TimeUnit.SECONDS);
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void forcedHttpsHttp2StopInterruptsBlockedWorkAndReleasesTheOwnedExecutor(boolean streaming)
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var observed = new CountDownLatch(1);
    var outcome = new AtomicReference<RequestOutcome>();
    var nativeServer = new AtomicReference<Server>();
    var sessionHandler = new AtomicReference<SessionHandler>();
    var tlsContext = new AtomicReference<SslContextFactory.Server>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = secureClient(HttpClient.Version.HTTP_2)) {
      enableTls(app, tlsContext);
      app.afterRequest(
          result -> {
            if ("/blocked".equals(result.routePattern())) {
              outcome.set(result);
              observed.countDown();
            }
          });
      app.http2()
          .sessions(sessionHandler::set)
          .modifyServer(
              server -> {
                nativeServer.set(server);
                server.setStopTimeout(100);
                ((ServerConnector) server.getConnectors()[0]).setShutdownIdleTimeout(0);
              });
      app.routes()
          .get(
              "/session",
              (request, response) ->
                  response.text(Objects.requireNonNull(request.session(true)).getId()));
      app.routes()
          .get(
              "/blocked",
              (request, response) -> {
                if (streaming) {
                  var stream = response.startStream("text/plain");
                  stream.write("prefix");
                  stream.flush();
                }

                entered.countDown();
                while (release.getCount() != 0) {
                  try {
                    release.await();
                  } catch (InterruptedException failure) {
                    interrupted.countDown();
                  }
                }

                if (!streaming) {
                  response.text("late");
                }
              });
      app.start();
      var base = "https://localhost:" + app.port();
      assertEquals(
          HttpClient.Version.HTTP_2,
          client
              .send(
                  HttpRequest.newBuilder(URI.create(base + "/session")).build(),
                  HttpResponse.BodyHandlers.ofString())
              .version());
      assertEquals(
          1, ((DefaultSessionCache) sessionHandler.get().getSessionCache()).getSessionsCurrent());
      var pending =
          client.sendAsync(
              HttpRequest.newBuilder(URI.create(base + "/blocked")).build(),
              HttpResponse.BodyHandlers.ofString());

      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var closing =
            CompletableFuture.runAsync(
                () -> {
                  try {
                    app.close();
                  } catch (IOException failure) {
                    throw new CompletionException(failure);
                  }
                });
        var stopFailure = closing.handle((_, failure) -> failure).get(3, TimeUnit.SECONDS);
        assertInstanceOf(IOException.class, stopFailure.getCause());
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
        assertTrue(((ServerConnector) nativeServer.get().getConnectors()[0]).isStopped());
        assertTrue(tlsContext.get().isStopped());
        assertTrue(sessionHandler.get().isStopped());
        assertEquals(
            0, ((DefaultSessionCache) sessionHandler.get().getSessionCache()).getSessionsCurrent());
        assertTrue(
            ((ExecutorService)
                    ((QueuedThreadPool) nativeServer.get().getThreadPool())
                        .getVirtualThreadsExecutor())
                .isShutdown());
      } finally {
        release.countDown();
      }

      assertTrue(observed.await(3, TimeUnit.SECONDS));
      assertEquals("/blocked", outcome.get().routePattern());
      assertTrue(outcome.get().transportFailure() != null);
    }
  }

  @Test
  void clearTextHttp2CloseDrainsWorkAndClosesTheSessionCache() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var sessionHandler = new AtomicReference<SessionHandler>();
    var nativeServer = new AtomicReference<Server>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build()) {
      app.http2().sessions(sessionHandler::set).modifyServer(nativeServer::set);
      app.routes()
          .get(
              "/session",
              (request, response) ->
                  response.text(Objects.requireNonNull(request.session(true)).getId()));
      app.routes()
          .get(
              "/hold",
              (request, response) -> {
                entered.countDown();
                release.await();
                response.text("complete");
              });
      app.start();
      var base = "http://127.0.0.1:" + app.port();
      var created =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/session")).build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(HttpClient.Version.HTTP_2, created.version());
      assertEquals(
          1, ((DefaultSessionCache) sessionHandler.get().getSessionCache()).getSessionsCurrent());
      var pending =
          client.sendAsync(
              HttpRequest.newBuilder(URI.create(base + "/hold")).build(),
              HttpResponse.BodyHandlers.ofString());
      CompletableFuture<Void> closing = null;

      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        closing =
            CompletableFuture.runAsync(
                () -> {
                  try {
                    app.close();
                  } catch (IOException failure) {
                    throw new CompletionException(failure);
                  }
                });
        var graceful = nativeServer.get().getDescendant(GracefulHandler.class);
        await()
            .pollInterval(Duration.ofMillis(10))
            .atMost(Duration.ofSeconds(3))
            .until(graceful::isShutdown);
        assertFalse(closing.isDone());
        release.countDown();
        assertEquals("complete", pending.get(3, TimeUnit.SECONDS).body());
        closing.get(3, TimeUnit.SECONDS);
        assertTrue(nativeServer.get().isStopped());
        assertTrue(sessionHandler.get().isStopped());
        assertEquals(
            0, ((DefaultSessionCache) sessionHandler.get().getSessionCache()).getSessionsCurrent());
      } finally {
        release.countDown();
        if (closing != null) {
          closing.get(6, TimeUnit.SECONDS);
        }
      }
    }
  }

  @Test
  void responseCompressionIsOptInAndVariesOnAcceptEncoding() throws Exception {
    var payload = "compressible-body-".repeat(100);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.compression();
      app.routes().get("/data", (request, response) -> response.text(payload));
      app.start();
      var uri = URI.create("http://127.0.0.1:" + app.port() + "/data");

      var encoded =
          client.send(
              HttpRequest.newBuilder(uri).header("Accept-Encoding", "gzip").build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, encoded.statusCode());
      assertEquals("gzip", encoded.headers().firstValue("Content-Encoding").orElseThrow());
      assertTrue(encoded.headers().allValues("Vary").contains("Accept-Encoding"));

      try (var gzip = new GZIPInputStream(new ByteArrayInputStream(encoded.body()))) {
        assertEquals(payload, new String(gzip.readAllBytes(), StandardCharsets.UTF_8));
      }

      var gzipOnly =
          client.send(
              HttpRequest.newBuilder(uri)
                  .header("Accept-Encoding", "gzip;q=1, identity;q=0")
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, gzipOnly.statusCode());
      assertEquals("gzip", gzipOnly.headers().firstValue("Content-Encoding").orElseThrow());

      var plain =
          client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(payload, plain.body());
      assertTrue(plain.headers().firstValue("Content-Encoding").isEmpty());
      assertTrue(plain.headers().allValues("Vary").contains("Accept-Encoding"));

      var excluded =
          client.send(
              HttpRequest.newBuilder(uri).header("Accept-Encoding", "gzip;q=0").build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(payload, excluded.body());
      assertTrue(excluded.headers().firstValue("Content-Encoding").isEmpty());

      var rejectedWithWildcard =
          client.send(
              HttpRequest.newBuilder(uri).header("Accept-Encoding", "gzip;q=0, *;q=1").build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(payload, rejectedWithWildcard.body());
      assertEquals(
          "identity", rejectedWithWildcard.headers().firstValue("Content-Encoding").orElseThrow());
    }
  }

  @Test
  void compressionReturnsEmptyNotAcceptableWhenGzipAndIdentityAreRejected() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.compression();
      app.routes().get("/finite", (request, response) -> response.text("body"));
      app.routes().head("/finite", (request, response) -> response.text("body"));
      app.routes()
          .get(
              "/stream",
              (request, response) -> {
                var stream = response.startStream("text/plain");
                stream.write("body");
                stream.flush();
              });
      app.routes().get("/empty", (request, response) -> response.status(204));
      app.routes()
          .get("/opt-out", (request, response) -> response.disableCompression().text("body"));
      app.routes()
          .get(
              "/custom",
              (request, response) ->
                  response
                      .header("Content-Encoding", "br")
                      .body("application/octet-stream", new byte[] {1}));
      app.start();
      var base = "http://127.0.0.1:" + app.port();

      for (var path : List.of("/finite", "/stream")) {
        var result =
            client.send(
                HttpRequest.newBuilder(URI.create(base + path))
                    .header("Accept-Encoding", "gzip;q=0, *;q=1, identity;q=0")
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(406, result.statusCode());
        assertEquals(0, result.body().length);
        assertTrue(result.headers().allValues("Vary").contains("Accept-Encoding"));
      }

      var empty =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/empty"))
                  .header("Accept-Encoding", "gzip;q=0, *;q=1, identity;q=0")
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(204, empty.statusCode());

      var head =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/finite"))
                  .header("Accept-Encoding", "gzip;q=0, *;q=1, identity;q=0")
                  .method("HEAD", HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(406, head.statusCode());
      assertEquals(0, head.body().length);
      assertTrue(head.headers().allValues("Vary").contains("Accept-Encoding"));

      var custom =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/custom"))
                  .header("Accept-Encoding", "gzip;q=0, *;q=1, identity;q=0")
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, custom.statusCode());
      assertEquals("br", custom.headers().firstValue("Content-Encoding").orElseThrow());

      var optOut =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/opt-out"))
                  .header("Accept-Encoding", "gzip;q=1, identity;q=0")
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(406, optOut.statusCode());
      assertEquals(0, optOut.body().length);
      assertTrue(optOut.headers().allValues("Vary").contains("Accept-Encoding"));
    }
  }

  @Test
  void compressionRejectsRangedFileWhenIdentityIsForbidden() throws Exception {
    var file = Files.writeString(temporary.resolve("range-rejected.txt"), "0123456789".repeat(300));

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.compression();
      app.routes().get("/file", (request, response) -> response.file(file, "text/plain"));
      app.start();

      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/file"))
                  .header("Accept-Encoding", "gzip;q=1, identity;q=0")
                  .header("Range", "bytes=0-1999")
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(406, result.statusCode());
      assertEquals(0, result.body().length);
      assertTrue(result.headers().allValues("Vary").contains("Accept-Encoding"));
      assertTrue(result.headers().firstValue("Content-Range").isEmpty());
    }
  }

  @Test
  void compressionRejectsUnacceptableExceptionHandlerRecovery() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.compression();
      app.exception(
          NotFoundException.class,
          (failure, request, response) -> response.status(200).text("recovered"));
      app.routes()
          .get(
              "/failure",
              (request, response) -> {
                throw new NotFoundException();
              });
      app.start();

      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/failure"))
                  .header("Accept-Encoding", "gzip;q=0, *;q=1, identity;q=0")
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(406, result.statusCode());
      assertEquals(0, result.body().length);
      assertTrue(result.headers().allValues("Vary").contains("Accept-Encoding"));
    }
  }

  @Test
  void compressionCanBeDisabledPerResponseAndHonorsAnExcludedEncoding() throws Exception {
    var payload = "uncompressed-".repeat(100);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.compression();
      app.routes()
          .get("/opt-out", (request, response) -> response.disableCompression().text(payload));
      app.start();
      var uri = URI.create("http://127.0.0.1:" + app.port() + "/opt-out");

      var result =
          client.send(
              HttpRequest.newBuilder(uri).header("Accept-Encoding", "gzip").build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(payload, result.body());
      assertEquals("identity", result.headers().firstValue("Content-Encoding").orElseThrow());
      assertTrue(result.headers().allValues("Vary").contains("Accept-Encoding"));

      var denied =
          client.send(
              HttpRequest.newBuilder(uri).header("Accept-Encoding", "gzip;q=0").build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(payload, denied.body());
      assertEquals("identity", denied.headers().firstValue("Content-Encoding").orElseThrow());
    }
  }

  @Test
  void compressionPreservesHeadBodylessAndPartialFileResponses() throws Exception {
    var content = "0123456789".repeat(300);
    var file = Files.writeString(temporary.resolve("range.txt"), content);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.compression();
      app.routes().get("/file", (request, response) -> response.file(file, "text/plain"));
      app.routes().head("/file", (request, response) -> response.file(file, "text/plain"));
      app.routes().get("/empty", (request, response) -> response.status(204));
      app.routes().head("/empty", (request, response) -> response.status(204));
      app.start();
      var base = "http://127.0.0.1:" + app.port();

      var partial =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/file"))
                  .header("Accept-Encoding", "gzip")
                  .header("Range", "bytes=0-1999")
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(206, partial.statusCode());
      assertEquals(
          "bytes 0-1999/3000", partial.headers().firstValue("Content-Range").orElseThrow());
      assertEquals(content.substring(0, 2000), new String(partial.body(), StandardCharsets.UTF_8));
      assertFalse(
          partial.headers().firstValue("Content-Encoding").filter("gzip"::equals).isPresent());

      var head =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/file"))
                  .header("Accept-Encoding", "gzip")
                  .method("HEAD", HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, head.statusCode());
      assertEquals(0, head.body().length);
      assertTrue(
          head.headers().firstValue("Content-Length").isEmpty(), head.headers().map().toString());
      assertTrue(head.headers().firstValue("Content-Encoding").isEmpty());
      assertTrue(head.headers().allValues("Vary").contains("Accept-Encoding"));

      var empty =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/empty"))
                  .header("Accept-Encoding", "gzip")
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(204, empty.statusCode());
      assertEquals(0, empty.body().length);
      assertTrue(empty.headers().firstValue("Content-Encoding").isEmpty());

      var emptyHead =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/empty"))
                  .header("Accept-Encoding", "gzip")
                  .method("HEAD", HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(204, emptyHead.statusCode());
      assertEquals(0, emptyHead.body().length);
      assertTrue(emptyHead.headers().firstValue("Transfer-Encoding").isEmpty());
      assertTrue(emptyHead.headers().firstValue("Content-Length").isEmpty());
    }
  }

  @Test
  void acceptsHeadWhenTheMatchingGetFileCanBeCompressedAndIdentityIsForbidden() throws Exception {
    var content = "compressible-file-".repeat(300);
    var file = Files.writeString(temporary.resolve("head-gzip.txt"), content);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.compression();
      app.routes().get("/head-gzip", (request, response) -> response.file(file, "text/plain"));
      app.routes().head("/head-gzip", (request, response) -> response.file(file, "text/plain"));
      app.start();
      var uri = URI.create("http://127.0.0.1:" + app.port() + "/head-gzip");
      var get =
          client.send(
              HttpRequest.newBuilder(uri)
                  .header("Accept-Encoding", "gzip;q=1, identity;q=0")
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, get.statusCode());
      assertEquals("gzip", get.headers().firstValue("Content-Encoding").orElseThrow());

      try (var gzip = new GZIPInputStream(new ByteArrayInputStream(get.body()))) {
        assertEquals(content, new String(gzip.readAllBytes(), StandardCharsets.UTF_8));
      }

      var head =
          client.send(
              HttpRequest.newBuilder(uri)
                  .header("Accept-Encoding", "gzip;q=1, identity;q=0")
                  .method("HEAD", HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, head.statusCode());
      assertEquals(0, head.body().length);
      assertTrue(head.headers().allValues("Vary").contains("Accept-Encoding"));
      assertTrue(head.headers().firstValue("Content-Length").isEmpty());
    }
  }

  @Test
  void invalidTlsKeyStoreCleansUpTheOwnedServerAndPreventsRestart() throws Exception {
    var nativeServer = new AtomicReference<Server>();
    var sessionHandler = new AtomicReference<SessionHandler>();
    var tlsContext = new AtomicReference<SslContextFactory.Server>();

    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      app.modifyServer(nativeServer::set);
      app.sessions(sessionHandler::set);
      var keyStore = keyStoreFile();
      app.tls(
          tls -> {
            tlsContext.set(tls);
            tls.setKeyStorePath(keyStore.toString());
            tls.setKeyStorePassword("wrong-password");
          });
      app.routes().get("/", (request, response) -> response.text("never"));

      assertThrows(Exception.class, app::start);
      assertThrows(IllegalStateException.class, app::start);
      assertThrows(IllegalStateException.class, app::port);
      assertTrue(nativeServer.get().isStopped());
      assertTrue(sessionHandler.get().isStopped());
      assertTrue(tlsContext.get().isStopped());
      assertTrue(((ServerConnector) nativeServer.get().getConnectors()[0]).isStopped());
      assertTrue(
          ((ExecutorService)
                  ((QueuedThreadPool) nativeServer.get().getThreadPool())
                      .getVirtualThreadsExecutor())
              .isShutdown());
    }
  }

  @Test
  void encryptedHttp2PreservesDirectAndTrustedProxyMetadata() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = secureClient(HttpClient.Version.HTTP_2)) {
      enableTls(app);
      app.http2().trustedProxies(InetAddress::isLoopbackAddress);
      app.routes()
          .get(
              "/metadata",
              (request, response) -> {
                assertTrue(request.isSecure());
                assertEquals("https", request.scheme());
                assertEquals("HTTP/2.0", request.protocol());
                assertTrue(request.isForwarded());
                assertEquals("https://public.example/metadata?raw=%2B", request.effectiveUrl());
                assertEquals("raw=%2B", request.queryString());
                response.text("metadata");
              });
      app.start();

      var result =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("https://localhost:" + app.port() + "/metadata?raw=%2B"))
                  .header("Forwarded", "for=203.0.113.7;host=public.example;proto=https")
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals(HttpClient.Version.HTTP_2, result.version());
      assertEquals("metadata", result.body());
    }
  }

  @Test
  void clearTextHttp2KeepsPhysicalSecuritySeparateFromForwardedHttps() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build()) {
      app.http2().trustedProxies(InetAddress::isLoopbackAddress);
      app.routes()
          .get(
              "/metadata",
              (request, response) -> {
                assertFalse(request.isSecure());
                assertEquals("http", request.scheme());
                assertEquals("HTTP/2.0", request.protocol());
                assertTrue(request.isForwarded());
                assertEquals("https://public.example/metadata?raw=%2B", request.effectiveUrl());
                response.text("h2c");
              });
      app.start();

      var result =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + app.port() + "/metadata?raw=%2B"))
                  .header("Forwarded", "for=203.0.113.7;host=public.example;proto=https")
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals(HttpClient.Version.HTTP_2, result.version());
      assertEquals("h2c", result.body());
    }
  }

  @Test
  void gzipCompressionWorksOnEncryptedHttp2AndLeavesHeadBodyless() throws Exception {
    var payload = "over-http2-".repeat(100);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = secureClient(HttpClient.Version.HTTP_2)) {
      enableTls(app);
      app.http2().compression();
      app.routes().get("/data", (request, response) -> response.text(payload));
      app.routes().head("/data", (request, response) -> response.text(payload));
      app.start();
      var uri = URI.create("https://localhost:" + app.port() + "/data");

      var encoded =
          client.send(
              HttpRequest.newBuilder(uri).header("Accept-Encoding", "gzip").build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(HttpClient.Version.HTTP_2, encoded.version());
      assertEquals("gzip", encoded.headers().firstValue("Content-Encoding").orElseThrow());

      try (var gzip = new GZIPInputStream(new ByteArrayInputStream(encoded.body()))) {
        assertEquals(payload, new String(gzip.readAllBytes(), StandardCharsets.UTF_8));
      }

      var head =
          client.send(
              HttpRequest.newBuilder(uri)
                  .header("Accept-Encoding", "gzip")
                  .method("HEAD", HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(HttpClient.Version.HTTP_2, head.version());
      assertEquals(0, head.body().length);
      assertTrue(head.headers().firstValue("Content-Length").isEmpty());
      assertTrue(head.headers().allValues("Vary").contains("Accept-Encoding"));
    }
  }

  @Test
  void responseCompressionDoesNotDecodeRequestBodies() throws Exception {
    var payload = "application-owned-input-".repeat(40).getBytes(StandardCharsets.UTF_8);
    var encoded = new ByteArrayOutputStream();

    try (var gzip = new GZIPOutputStream(encoded)) {
      gzip.write(payload);
    }

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.compression();
      app.routes()
          .post(
              "/bytes",
              (request, response) -> response.text(Integer.toString(request.bodyBytes().length)));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/bytes"))
                  .header("Content-Encoding", "gzip")
                  .POST(HttpRequest.BodyPublishers.ofByteArray(encoded.toByteArray()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, result.statusCode());
      assertEquals(Integer.toString(encoded.size()), result.body());
    }
  }

  @Test
  void compressionSkipsAlreadyCompressedMediaAndEventStreams() throws Exception {
    var content = new byte[2048];

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.compression();
      app.routes().get("/image", (request, response) -> response.body("image/png", content));
      app.routes()
          .get("/events", (request, response) -> response.body("text/event-stream", content));
      app.start();
      var base = "http://127.0.0.1:" + app.port();

      for (var path : new String[] {"/image", "/events"}) {
        var result =
            client.send(
                HttpRequest.newBuilder(URI.create(base + path))
                    .header("Accept-Encoding", "gzip")
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, result.statusCode());
        assertEquals(content.length, result.body().length);
        assertTrue(result.headers().firstValue("Content-Encoding").isEmpty());
      }
    }
  }

  @Test
  void rejectsForbiddenIdentityWhenNativeCompressionExcludesMedia() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.compression();
      app.routes().get("/image", (request, response) -> response.body("image/png", new byte[2048]));
      app.start();

      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/image"))
                  .header("Accept-Encoding", "gzip;q=1, identity;q=0")
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());

      assertEquals(406, result.statusCode());
      assertEquals(0, result.body().length);
      assertTrue(result.headers().allValues("Vary").contains("Accept-Encoding"));
    }
  }

  @Test
  void rejectsForbiddenIdentityBelowTheNativeCompressionThreshold() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.compression();
      app.routes().get("/small", (request, response) -> response.text("tiny"));
      app.start();

      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/small"))
                  .header("Accept-Encoding", "gzip;q=1, identity;q=0")
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());

      assertEquals(406, result.statusCode());
      assertEquals(0, result.body().length);
      assertTrue(result.headers().allValues("Vary").contains("Accept-Encoding"));
    }
  }

  @Test
  void honorsNativeCompressionExclusionsWhenIdentityIsForbidden() throws Exception {
    var payload = "otherwise-compressible-".repeat(100);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.compression();
      app.modifyServer(
          server -> {
            var graceful = assertInstanceOf(GracefulHandler.class, server.getHandler());
            var compressor = assertInstanceOf(CompressionHandler.class, graceful.getHandler());
            compressor.putConfiguration(
                "/*",
                CompressionConfig.builder()
                    .defaults()
                    .decompressExcludePath("/*")
                    .compressExcludeMimeType("text/plain")
                    .build());
          });
      app.routes().get("/text", (request, response) -> response.text(payload));
      app.start();

      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/text"))
                  .header("Accept-Encoding", "gzip;q=1, identity;q=0")
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());

      assertEquals(406, result.statusCode());
      assertEquals(0, result.body().length);
      assertTrue(result.headers().allValues("Vary").contains("Accept-Encoding"));
    }
  }

  @Test
  void honorsCompressionConfigurationMatchedOutsideTheApplicationContext() throws Exception {
    var payload = "context-path-content-".repeat(100);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.compression();
      app.modifyServer(
          server -> {
            var graceful = assertInstanceOf(GracefulHandler.class, server.getHandler());
            var compressor = assertInstanceOf(CompressionHandler.class, graceful.getHandler());
            compressor.putConfiguration(
                "/app/*",
                CompressionConfig.builder()
                    .defaults()
                    .decompressExcludePath("/*")
                    .compressExcludeMimeType("text/plain")
                    .build());
            var context = new ContextHandler("/app");
            context.setHandler(compressor.getHandler());
            compressor.setHandler(context);
          });
      app.routes().get("/text", (request, response) -> response.text(payload));
      app.start();

      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/app/text"))
                  .header("Accept-Encoding", "gzip;q=1, identity;q=0")
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());

      assertEquals(406, result.statusCode());
      assertEquals(0, result.body().length);
      assertTrue(result.headers().allValues("Vary").contains("Accept-Encoding"));
    }
  }

  @Test
  void usesOnlyTheSelectedCompressorThresholdWhenIdentityIsForbidden() throws Exception {
    var payload = "selected-gzip-content-".repeat(100);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.compression();
      app.modifyServer(
          server -> {
            var graceful = assertInstanceOf(GracefulHandler.class, server.getHandler());
            var compressor = assertInstanceOf(CompressionHandler.class, graceful.getHandler());
            compressor.putCompression(new GzipCompression());
            var unselected =
                new GzipCompression() {
                  @Override
                  public String getEncodingName() {
                    return UNSELECTED_ENCODING;
                  }
                };
            unselected.setMinCompressSize(10000);
            compressor.putCompression(unselected);
          });
      app.routes().get("/selected", (request, response) -> response.text(payload));
      app.start();

      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/selected"))
                  .header("Accept-Encoding", "gzip;q=1, identity;q=0")
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());

      assertEquals(200, result.statusCode());
      assertEquals("gzip", result.headers().firstValue("Content-Encoding").orElseThrow());

      try (var gzip = new GZIPInputStream(new ByteArrayInputStream(result.body()))) {
        assertEquals(payload, new String(gzip.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
  }

  @Test
  void rejectsForbiddenIdentityWhenNativeMethodExcludesCompression() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.compression();
      app.routes().put("/data", (request, response) -> response.text("method response"));
      app.start();

      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/data"))
                  .header("Accept-Encoding", "gzip;q=1, identity;q=0")
                  .PUT(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());

      assertEquals(406, result.statusCode());
      assertEquals(0, result.body().length);
    }
  }

  @Test
  void streamingResponsesCompressAndCanOptOutBeforeTheFirstWrite() throws Exception {
    var payload = "streamed-text-".repeat(150);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.compression();
      app.routes()
          .get(
              "/stream",
              (request, response) -> {
                var stream = response.startStream("text/plain");
                stream.write(payload);
                stream.flush();
                stream.write(payload);
              });
      app.routes()
          .get(
              "/plain",
              (request, response) -> {
                var stream = response.disableCompression().startStream("text/plain");
                stream.write(payload);
                stream.flush();
                stream.write(payload);
              });
      app.start();
      var base = "http://127.0.0.1:" + app.port();
      var compressed =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/stream"))
                  .header("Accept-Encoding", "gzip")
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals("gzip", compressed.headers().firstValue("Content-Encoding").orElseThrow());

      try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed.body()))) {
        assertEquals(payload + payload, new String(gzip.readAllBytes(), StandardCharsets.UTF_8));
      }

      var plain =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/plain"))
                  .header("Accept-Encoding", "gzip")
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(payload + payload, plain.body());
      assertEquals("identity", plain.headers().firstValue("Content-Encoding").orElseThrow());
    }
  }

  @Test
  void recoveredResponsesRetainCompressionCacheVariation() throws Exception {
    var payload = "recovered-response-".repeat(100);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.compression();
      app.exception(
          IllegalStateException.class,
          (failure, request, response) -> response.status(200).text(payload));
      app.routes()
          .get(
              "/recover",
              (request, response) -> {
                throw new IllegalStateException("recoverable");
              });
      app.start();
      var uri = URI.create("http://127.0.0.1:" + app.port() + "/recover");

      var encoded =
          client.send(
              HttpRequest.newBuilder(uri).header("Accept-Encoding", "gzip").build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, encoded.statusCode());
      assertEquals("gzip", encoded.headers().firstValue("Content-Encoding").orElseThrow());
      assertTrue(encoded.headers().allValues("Vary").contains("Accept-Encoding"));

      try (var gzip = new GZIPInputStream(new ByteArrayInputStream(encoded.body()))) {
        assertEquals(payload, new String(gzip.readAllBytes(), StandardCharsets.UTF_8));
      }

      var plain =
          client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, plain.statusCode());
      assertEquals(payload, plain.body());
      assertTrue(plain.headers().firstValue("Content-Encoding").isEmpty());
      assertTrue(plain.headers().allValues("Vary").contains("Accept-Encoding"));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void fatalHttp2RouteAndMapperErrorsAbortOnlyTheirStream(boolean mapper) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = secureClient(HttpClient.Version.HTTP_2)) {
      enableTls(app);
      app.http2();
      app.exception(
          NotFoundException.class,
          (failure, request, response) -> {
            throw new Error("secret mapper diagnostic");
          });
      app.routes()
          .get(
              "/warmup",
              (request, response) ->
                  response.text(
                      Integer.toString(
                          Objects.requireNonNull((InetSocketAddress) request.remoteAddress())
                              .getPort())));
      app.routes()
          .get(
              "/good",
              (request, response) -> {
                entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
                response.text(
                    Integer.toString(
                        Objects.requireNonNull((InetSocketAddress) request.remoteAddress())
                            .getPort()));
              });
      app.routes()
          .get(
              "/fatal",
              (request, response) -> {
                if (mapper) {
                  throw new NotFoundException();
                }

                throw new Error("secret route diagnostic");
              });
      app.start();
      var base = "https://localhost:" + app.port();
      var warmup =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/warmup")).build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(HttpClient.Version.HTTP_2, warmup.version());
      var sibling =
          client.sendAsync(
              HttpRequest.newBuilder(URI.create(base + "/good")).build(),
              HttpResponse.BodyHandlers.ofString());

      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var failed =
            client.sendAsync(
                HttpRequest.newBuilder(URI.create(base + "/fatal")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertInstanceOf(
            IOException.class,
            assertThrows(ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS))
                .getCause());
        release.countDown();
        var result = sibling.get(5, TimeUnit.SECONDS);
        assertEquals(200, result.statusCode());
        assertEquals(warmup.body(), result.body());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void committedHttp2StreamFailureAbortsOnlyThatStream() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = secureClient(HttpClient.Version.HTTP_2)) {
      enableTls(app);
      app.http2();
      app.routes().get("/warmup", (request, response) -> response.text("ready"));
      app.routes()
          .get(
              "/good",
              (request, response) -> {
                entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
                response.text("survived");
              });
      app.routes()
          .get(
              "/fatal",
              (request, response) -> {
                var stream = response.startStream("text/plain");
                stream.write("prefix");
                stream.flush();
                throw new Error("secret committed diagnostic");
              });
      app.start();
      var base = "https://localhost:" + app.port();
      assertEquals(
          HttpClient.Version.HTTP_2,
          client
              .send(
                  HttpRequest.newBuilder(URI.create(base + "/warmup")).build(),
                  HttpResponse.BodyHandlers.ofString())
              .version());
      var sibling =
          client.sendAsync(
              HttpRequest.newBuilder(URI.create(base + "/good")).build(),
              HttpResponse.BodyHandlers.ofString());

      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var failed =
            client.sendAsync(
                HttpRequest.newBuilder(URI.create(base + "/fatal")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertInstanceOf(
            IOException.class,
            assertThrows(ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS))
                .getCause());
        release.countDown();
        assertEquals("survived", sibling.get(5, TimeUnit.SECONDS).body());
      } finally {
        release.countDown();
      }
    }
  }

  private void enableTls(Shoostr app) throws Exception {
    enableTls(app, new AtomicReference<>());
  }

  private void enableTls(Shoostr app, AtomicReference<SslContextFactory.Server> context)
      throws Exception {
    var keyStore = keyStoreFile();
    app.tls(
        tls -> {
          context.set(tls);
          tls.setKeyStorePath(keyStore.toString());
          tls.setKeyStorePassword(String.valueOf(PASSWORD));
        });
  }

  private Path keyStoreFile() throws Exception {
    var path = temporary.resolve("localhost-test.p12");

    try (var input = Objects.requireNonNull(getClass().getResourceAsStream(KEYSTORE_RESOURCE))) {
      Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING);
    }

    return path;
  }

  private static HttpClient secureClient(HttpClient.Version version) throws Exception {
    var store = KeyStore.getInstance("PKCS12");

    try (var input =
        Objects.requireNonNull(TransportTest.class.getResourceAsStream(KEYSTORE_RESOURCE))) {
      store.load(input, PASSWORD);
    }

    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(store);
    var context = SSLContext.getInstance("TLS");
    context.init(null, trust.getTrustManagers(), null);
    return HttpClient.newBuilder()
        .sslContext(context)
        .version(version)
        .connectTimeout(Duration.ofSeconds(3))
        .build();
  }
}
