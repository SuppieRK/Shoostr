package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class TypedCodecLifetimeTest {
  @Test
  void rejectsCompletedTypedAccessOnOriginalHandlerThreadBeforeCallingCodec() throws Exception {
    var codec = new CountingCodec();
    var savedRequest = new AtomicReference<TypedRequest>();
    var savedResponse = new AtomicReference<TypedResponse>();
    var handlerThread = new AtomicReference<Thread>();

    try (var executor = Executors.newSingleThreadExecutor();
        var app = new Shoostr()) {
      app.codec(codec)
          .routes()
          .get(
              "/retain",
              executor,
              (request, response) -> {
                savedRequest.set(request);
                savedResponse.set(response);
                handlerThread.set(Thread.currentThread());
                response.text("retained");
              });

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/retain")).statusCode());
        var retainedRequest = savedRequest.get();
        var retainedResponse = savedResponse.get();
        var originalThread = handlerThread.get();
        executor
            .submit(
                () -> {
                  assertSame(originalThread, Thread.currentThread());
                  assertThrows(
                      IllegalStateException.class, () -> retainedRequest.body(String.class));
                  assertThrows(
                      IllegalStateException.class,
                      () -> retainedResponse.body("application/json", "late"));
                  assertThrows(
                      IllegalStateException.class,
                      () -> retainedResponse.body(MediaType.APPLICATION_JSON, "late"));
                })
            .get(5, TimeUnit.SECONDS);
        assertEquals(0, codec.reads.get());
        assertEquals(0, codec.writes.get());
      }
    }
  }

  @Test
  void rejectsChildThreadAccessBeforeCallingCodec() throws Exception {
    var codec = new CountingCodec();

    try (var children = Executors.newVirtualThreadPerTaskExecutor();
        var app = new Shoostr()) {
      app.codec(codec)
          .routes()
          .get(
              "/child",
              (request, response) -> {
                children
                    .submit(
                        () -> {
                          assertThrows(
                              IllegalStateException.class, () -> request.body(String.class));
                          assertThrows(
                              IllegalStateException.class,
                              () -> response.body("application/json", "child"));
                        })
                    .get();
                response.text("guarded");
              });

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/child")).statusCode());
        assertEquals(0, codec.reads.get());
        assertEquals(0, codec.writes.get());
      }
    }
  }

  @Test
  void rejectsEncodingAfterStreamStartsBeforeCallingCodec() throws Exception {
    var codec = new CountingCodec();

    try (var app = new Shoostr()) {
      app.codec(codec)
          .routes()
          .get(
              "/stream",
              (_, response) -> {
                response.startStream("text/plain").write("streamed");
                assertThrows(
                    IllegalStateException.class,
                    () -> response.body(MediaType.APPLICATION_JSON, "late"));
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/stream"), HttpResponse.BodyHandlers.ofString());
        assertEquals("streamed", result.body());
        assertEquals(0, codec.writes.get());
      }
    }
  }

  @Test
  void rejectsEncodingAfterFileSelectionBeforeCallingCodec(@TempDir Path directory)
      throws Exception {
    var file = directory.resolve("body.txt");
    Files.writeString(file, "file body");
    var codec = new CountingCodec();

    try (var app = new Shoostr()) {
      app.codec(codec)
          .routes()
          .get(
              "/file",
              (_, response) -> {
                response.file(file, "text/plain");
                assertThrows(
                    IllegalStateException.class, () -> response.body("application/json", "late"));
              });

      try (var test = TestServer.start(app)) {
        var result =
            test.send(request -> request.path("/file"), HttpResponse.BodyHandlers.ofString());
        assertEquals("file body", result.body());
        assertEquals(0, codec.writes.get());
      }
    }
  }

  @Test
  void rejectsDecodingAfterStreamingInputWasSelected() throws Exception {
    var codec = new CountingCodec();

    try (var app = new Shoostr()) {
      app.codec(codec)
          .routes()
          .post(
              "/input",
              (request, response) -> {
                request.input();
                assertThrows(IllegalStateException.class, () -> request.body(String.class));
                response.text("guarded");
              });

      try (var test = TestServer.start(app)) {
        assertEquals(
            200,
            test.send(request -> request.path("/input").method("POST").body(new byte[] {1}))
                .statusCode());
        assertEquals(0, codec.reads.get());
      }
    }
  }

  @Test
  void rejectsEncodingInsideAfterFlushCallback() throws Exception {
    var codec = new CountingCodec();
    var rejection = new CompletableFuture<IllegalStateException>();

    try (var app = new Shoostr()) {
      app.codec(codec)
          .routes()
          .get(
              "/flush",
              (_, response) -> response.text("plain"),
              extensions ->
                  extensions.afterResponseFlush(
                      (_, response) -> {
                        try {
                          rejection.complete(
                              assertThrows(
                                  IllegalStateException.class,
                                  () ->
                                      ((TypedResponse) response).body("application/json", "late")));
                        } catch (RuntimeException | Error failure) {
                          rejection.completeExceptionally(failure);
                        }
                      }));

      try (var test = TestServer.start(app)) {
        assertEquals(200, test.send(request -> request.path("/flush")).statusCode());
        rejection.get(5, TimeUnit.SECONDS);
        assertEquals(0, codec.writes.get());
      }
    }
  }

  private static class CountingCodec extends TestCodec {
    private final AtomicInteger reads;
    private final AtomicInteger writes;

    private CountingCodec() {
      reads = new AtomicInteger();
      writes = new AtomicInteger();
    }

    @Override
    public <T> T read(byte[] source, Class<T> type) throws Exception {
      reads.incrementAndGet();
      return super.read(source, type);
    }

    @Override
    public byte[] write(Object value) throws Exception {
      writes.incrementAndGet();
      return super.write(value);
    }
  }
}
