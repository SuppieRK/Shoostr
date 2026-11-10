package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.testing.TestServer;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class TypedEventStreamTest {
  @Test
  void encodesObjectEventsAlongsideRawMetadataCommentsAndHeartbeats() throws Exception {
    var app = createAppForEncodesObjectEventsAlongsideRawMetadataCommentsAndHeartbeats();

    try (var server = TestServer.start(app)) {
      var result =
          server.send(request -> request.path("/events"), HttpResponse.BodyHandlers.ofString());

      assertEquals(200, result.statusCode());
      assertEquals(
          "text/event-stream; charset=utf-8",
          result.headers().firstValue("content-type").orElseThrow());
      assertEquals(
          ": ready\n\ndata: héllo\ndata: world\n\nevent: notice\nid: 1\ndata: raw\n\n:\n\ndata: done\n\n",
          result.body());
    }
  }

  private Shoostr createAppForEncodesObjectEventsAlongsideRawMetadataCommentsAndHeartbeats() {
    return new Shoostr()
        .codec(new TextCodec())
        .routes(
            routes ->
                routes.sse(
                    "/events",
                    (ignored, response) ->
                        response
                            .startEventStream()
                            .comment("ready")
                            .sendObject("héllo\nworld")
                            .send(SseEvent.of("raw").withEvent("notice").withId("1"))
                            .heartbeat()
                            .sendObject("done")));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void writesNoFailingEventAndCanContinueAfterCaughtConversionFailures(int mode) throws Exception {
    var failure = new IOException("conversion failed");
    var writes = new AtomicInteger();
    var codec =
        createCodecForWritesNoFailingEventAndCanContinueAfterCaughtConversionFailures(
            writes, mode, failure);
    var app =
        createAppForWritesNoFailingEventAndCanContinueAfterCaughtConversionFailures(
            codec, mode, failure);

    try (var server = TestServer.start(app)) {
      var result =
          server.send(request -> request.path("/recover"), HttpResponse.BodyHandlers.ofString());

      assertEquals(200, result.statusCode());
      assertEquals("data: before\n\ndata: after\n\n", result.body());
      assertEquals(2, writes.get());
    }
  }

  private TextCodec createCodecForWritesNoFailingEventAndCanContinueAfterCaughtConversionFailures(
      AtomicInteger writes, int mode, IOException failure) {
    return new TextCodec() {
      @Override
      @SuppressWarnings("NullAway")
      public byte[] write(Object value) throws Exception {
        writes.incrementAndGet();
        if ("bad".equals(value)) {
          if (mode == 0) {
            throw failure;
          }

          if (mode == 1) {
            return new byte[] {(byte) 0xc3, 0x28};
          }

          return null;
        }

        return super.write(value);
      }
    };
  }

  private Shoostr createAppForWritesNoFailingEventAndCanContinueAfterCaughtConversionFailures(
      TextCodec codec, int mode, IOException failure) {
    return new Shoostr()
        .codec(codec)
        .routes(
            routes ->
                routes.sse(
                    "/recover",
                    (ignoredTypedRequest, response) -> {
                      var events = response.startEventStream();
                      events.send("before");

                      var observed = assertThrows(Exception.class, () -> events.sendObject("bad"));

                      if (mode == 0) {
                        assertSame(failure, observed);
                      } else if (mode == 1) {
                        assertInstanceOf(CharacterCodingException.class, observed);
                      } else {
                        assertInstanceOf(NullPointerException.class, observed);
                      }

                      events.sendObject("after");
                    }));
  }

  @Test
  void rejectsChildThreadOutputBeforeInvokingTheCodec() throws Exception {
    var writes = new AtomicInteger();
    var codec = countingCodec(writes);

    try (var children = Executors.newVirtualThreadPerTaskExecutor()) {
      var app = createAppForRejectsChildThreadOutputBeforeInvokingTheCodec(codec, children);

      try (var server = TestServer.start(app)) {
        assertEquals(
            "data: owner\n\n",
            server
                .send(request -> request.path("/owner"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(0, writes.get());
      }
    }
  }

  private Shoostr createAppForRejectsChildThreadOutputBeforeInvokingTheCodec(
      Codec codec, ExecutorService children) {
    return new Shoostr()
        .codec(codec)
        .routes(
            routes ->
                routes.sse(
                    "/owner",
                    (ignoredTypedRequest, response) -> {
                      var events = response.startEventStream();
                      children
                          .submit(
                              () ->
                                  assertThrows(
                                      IllegalStateException.class,
                                      () -> events.sendObject("child")))
                          .get(3, TimeUnit.SECONDS);
                      events.send("owner");
                    }));
  }

  @Test
  void rejectsCompletedOutputOnTheOriginalHandlerThreadBeforeInvokingTheCodec() throws Exception {
    var writes = new AtomicInteger();
    var stream = new AtomicReference<TypedEventStream>();
    var owner = new AtomicReference<Thread>();

    try (var executor = Executors.newSingleThreadExecutor()) {
      var app =
          createAppForRejectsCompletedOutputOnTheOriginalHandlerThreadBeforeInvokingTheCodec(
              writes, executor, owner, stream);

      try (var server = TestServer.start(app)) {
        assertEquals(
            "data: done\n\n",
            server
                .send(request -> request.path("/retain"), HttpResponse.BodyHandlers.ofString())
                .body());

        executor
            .submit(
                () -> {
                  assertSame(owner.get(), Thread.currentThread());

                  var completedStream = stream.get();

                  assertThrows(
                      IllegalStateException.class, () -> completedStream.sendObject("late"));
                })
            .get(3, TimeUnit.SECONDS);

        assertEquals(0, writes.get());
      }
    }
  }

  private Shoostr
      createAppForRejectsCompletedOutputOnTheOriginalHandlerThreadBeforeInvokingTheCodec(
          AtomicInteger writes,
          ExecutorService executor,
          AtomicReference<Thread> owner,
          AtomicReference<TypedEventStream> stream) {
    return new Shoostr()
        .codec(countingCodec(writes))
        .routes(
            routes ->
                routes.sse(
                    "/retain",
                    executor,
                    (ignoredTypedRequest, response) -> {
                      owner.set(Thread.currentThread());
                      stream.set(response.startEventStream().send("done"));
                    }));
  }

  @Test
  @SuppressWarnings("NullAway")
  void rejectsNullValuesWithoutInvokingTheCodec() throws Exception {
    var writes = new AtomicInteger();
    var app = createAppForRejectsNullValuesWithoutInvokingTheCodec(writes);

    try (var server = TestServer.start(app)) {
      assertEquals(
          "data: valid\n\n",
          server
              .send(request -> request.path("/null"), HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(0, writes.get());
    }
  }

  private Shoostr createAppForRejectsNullValuesWithoutInvokingTheCodec(AtomicInteger writes) {
    return new Shoostr()
        .codec(countingCodec(writes))
        .routes(
            routes ->
                routes.sse(
                    "/null",
                    (ignoredTypedRequest, response) -> {
                      var events = response.startEventStream();

                      assertThrows(NullPointerException.class, () -> events.sendObject(null));

                      events.send("valid");
                    }));
  }

  private static Codec countingCodec(AtomicInteger writes) {
    return new TextCodec() {
      @Override
      public byte[] write(Object value) throws Exception {
        writes.incrementAndGet();

        return super.write(value);
      }
    };
  }

  private static class TextCodec implements Codec {
    @Override
    public <T> T read(byte[] source, Class<T> type) {
      throw new UnsupportedOperationException("Output-only fixture");
    }

    @Override
    public byte[] write(Object value) throws Exception {
      return value.toString().getBytes(StandardCharsets.UTF_8);
    }
  }
}
