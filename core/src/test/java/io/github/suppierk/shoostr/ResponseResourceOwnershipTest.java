package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(10)
class ResponseResourceOwnershipTest {
  @TempDir Path directory;

  @Test
  void closesSelectedStaticFileDescriptorAfterHeadCompletion() throws Exception {
    Files.writeString(directory.resolve("file.txt"), "file");
    var descriptor = new AtomicReference<SeekableByteChannel>();
    var completed = new CompletableFuture<RequestOutcome>();
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.beforeResponseFlush((request, response) -> captureDescriptor(response, descriptor));
      app.afterRequest(completed::complete);
      app.routes().staticFiles("/assets", directory);

      try (var test = TestServer.start(app);
          Closeable descriptorCleanup = () -> closeDescriptor(descriptor)) {
        var result = headResponse(test);

        assertEquals(200, result.statusCode());
        assertEquals("", result.body());
        assertEquals(4, result.headers().firstValueAsLong("Content-Length").orElseThrow());

        var outcome = completed.get(3, TimeUnit.SECONDS);

        assertNull(outcome.applicationFailure());
        assertNull(outcome.transportFailure());

        var selected = descriptor.get();

        assertNotNull(selected);
        assertFalse(selected.isOpen());
      }
    }
  }

  @Test
  void closesSelectedStaticFileDescriptorWhenFlushHookFailsFatally() throws Exception {
    Files.writeString(directory.resolve("file.txt"), "file");
    var descriptor = new AtomicReference<SeekableByteChannel>();
    var completed = new CompletableFuture<RequestOutcome>();
    var failure = new LinkageError("Flush failed");
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.beforeResponseFlush((request, response) -> {
        captureDescriptor(response, descriptor);
        throw failure;
      });
      app.afterRequest(completed::complete);
      app.routes().staticFiles("/assets", directory);

      try (var test = TestServer.start(app);
          Closeable descriptorCleanup = () -> closeDescriptor(descriptor);
          var socket = new Socket()) {
        configureSocket(socket, test);
        sendStaticFileRequest(socket);

        assertEquals(-1, socket.getInputStream().read());

        var outcome = completed.get(3, TimeUnit.SECONDS);

        assertSame(failure, outcome.applicationFailure());
        assertNotNull(outcome.transportFailure());

        var selected = descriptor.get();

        assertNotNull(selected);
        assertFalse(selected.isOpen());
      }
    }
  }

  @Test
  void completesUnsatisfiableStaticRangeWithAnEmptyBodyAndZeroContentLength() throws Exception {
    Files.writeString(directory.resolve("file.txt"), "file");
    var app = new Shoostr();

    try (Closeable appCleanup = app::close) {
      app.routes().staticFiles("/assets", directory);

      try (var test = TestServer.start(app)) {
        var result = unsatisfiableRangeResponse(test);

        assertEquals(416, result.statusCode());
        assertEquals("", result.body());
        assertEquals(0, result.headers().firstValueAsLong("Content-Length").orElseThrow());
        assertEquals("bytes */4", result.headers().firstValue("Content-Range").orElseThrow());
      }
    }
  }

  private HttpResponse<String> unsatisfiableRangeResponse(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request ->
            request
                .path("/assets/file.txt")
                .header("Range", "bytes=99-")
                .timeout(Duration.ofSeconds(1)),
        HttpResponse.BodyHandlers.ofString());
  }

  private void configureSocket(Socket socket, TestServer test) throws IOException {
    socket.setSoTimeout(1000);
    socket.connect(new InetSocketAddress(test.baseUri().getHost(), test.baseUri().getPort()), 1000);
  }

  private void sendStaticFileRequest(Socket socket) throws IOException {
    socket
        .getOutputStream()
        .write(
            String.join(
                    "\r\n",
                    "GET /assets/file.txt HTTP/1.1",
                    "Host: localhost",
                    "Connection: close",
                    "",
                    "")
                .getBytes(StandardCharsets.US_ASCII));
    socket.getOutputStream().flush();
  }

  private HttpResponse<String> headResponse(TestServer test)
      throws IOException, InterruptedException {
    return test.send(
        request -> request.path("/assets/file.txt").method("HEAD").timeout(Duration.ofSeconds(1)),
        HttpResponse.BodyHandlers.ofString());
  }

  private void captureDescriptor(Response response, AtomicReference<SeekableByteChannel> descriptor)
      throws ReflectiveOperationException {
    var field = Response.class.getDeclaredField("resourceChannel");
    field.setAccessible(true);
    var selected = (SeekableByteChannel) Objects.requireNonNull(field.get(response));

    assertTrue(selected.isOpen());
    assertTrue(descriptor.compareAndSet(null, selected));
  }

  private void closeDescriptor(AtomicReference<SeekableByteChannel> descriptor) throws IOException {
    var selected = descriptor.get();
    if (selected != null) {
      selected.close();
    }
  }
}
