package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.http.exceptions.ContentTooLargeException;
import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BufferedRequestBodyTest {
  @Test
  void rejectsKnownLengthBodyThatEndsBeforeTheDeclaredLength() {
    var request = request(4, 16, Content.Chunk.from(ByteBuffer.wrap(new byte[] {1, 2, 3}), true));

    assertThrows(EOFException.class, request::bodyBytes);
  }

  @ParameterizedTest
  @CsvSource({
    "0,true",
    "0,false",
    "1024,true",
    "1024,false",
    "16384,true",
    "16384,false",
    "1048576,true",
    "1048576,false"
  })
  void readsFragmentedBodiesAtSizeBoundaries(int size, boolean known) throws Exception {
    var expected = new byte[size];
    Arrays.fill(expected, (byte) 0x5A);
    var request = request(known ? size : -1, 1_048_576, fragments(expected, 1024));

    assertArrayEquals(expected, request.bodyBytes());
  }

  @Test
  void returnsIndependentCopiesOfCachedKnownLengthBody() throws Exception {
    var request = request(3, 16, Content.Chunk.from(ByteBuffer.wrap(new byte[] {1, 2, 3}), true));

    var first = request.bodyBytes();
    first[0] = 9;

    assertArrayEquals(new byte[] {1, 2, 3}, request.bodyBytes());
  }

  @Test
  void rejectsContentBeyondTheDeclaredLength() {
    var request =
        request(3, 16, Content.Chunk.from(ByteBuffer.wrap(new byte[] {1, 2, 3, 4}), true));

    assertThrows(BadRequestException.class, request::bodyBytes);
  }

  @Test
  void rejectsTruncatedKnownLengthBodyBeyondTheDirectReadThreshold() {
    var bytes = new byte[16_384];
    var request = request(16_385, 1_048_576, Content.Chunk.from(ByteBuffer.wrap(bytes), true));

    assertThrows(EOFException.class, request::bodyBytes);
  }

  @Test
  void propagatesFailureAfterExactlyTheDeclaredBytes() {
    var request =
        request(
            3,
            16,
            Content.Chunk.from(ByteBuffer.wrap(new byte[] {1, 2, 3}), false),
            Content.Chunk.from(new IOException("read failed"), true));

    var failure = assertThrows(IOException.class, request::bodyBytes);
    assertEquals("read failed", failure.getMessage());
  }

  @Test
  void rejectsOversizedKnownLengthBeforeReading() {
    var request = request(17, 16, Content.Chunk.from(ByteBuffer.wrap(new byte[0]), true));

    assertThrows(ContentTooLargeException.class, request::bodyBytes);
  }

  @Test
  void rejectsUnknownLengthAfterTheConsumedByteLimit() {
    var request = request(-1, 16, Content.Chunk.from(ByteBuffer.wrap(new byte[17]), true));

    assertThrows(ContentTooLargeException.class, request::bodyBytes);
  }

  @Test
  void releasesPartiallyConsumedChunkAfterSizeRejection() {
    var releases = new AtomicInteger();
    var chunk =
        Content.Chunk.from(
            ByteBuffer.wrap(new byte[32]), true, (Runnable) releases::incrementAndGet);
    var request = request(-1, 16, chunk);

    assertThrows(ContentTooLargeException.class, request::bodyBytes);
    assertThrows(ContentTooLargeException.class, request::bodyBytes);
    assertEquals(1, releases.get());
  }

  @ParameterizedTest
  @ValueSource(ints = {16, 16_384})
  void rejectsOverflowWithoutDemandingAnotherChunk(int limit) {
    var request =
        request(-1, limit, false, Content.Chunk.from(ByteBuffer.wrap(new byte[limit + 1]), false));

    assertThrows(ContentTooLargeException.class, request::bodyBytes);
  }

  @Test
  void keepsOversizedRejectionPrimaryWhenCleanupFails() {
    var cleanupFailure = new IOException("cleanup failed");
    var request =
        request(
            -1,
            16,
            Content.Chunk.from(ByteBuffer.wrap(new byte[17]), false),
            Content.Chunk.from(cleanupFailure, true));

    var failure = assertThrows(ContentTooLargeException.class, request::bodyBytes);
    assertArrayEquals(new Throwable[] {cleanupFailure}, failure.getSuppressed());
    assertThrows(ContentTooLargeException.class, request::bodyBytes);
  }

  @Test
  void releasesPartiallyConsumedChunkAfterDeclaredLengthMismatch() {
    var releases = new AtomicInteger();
    var chunk =
        Content.Chunk.from(
            ByteBuffer.wrap(new byte[32]), true, (Runnable) releases::incrementAndGet);
    var request = request(3, 16, chunk);

    assertThrows(BadRequestException.class, request::bodyBytes);
    assertEquals(1, releases.get());
  }

  @Test
  void keepsDeclaredLengthMismatchPrimaryWhenCleanupFails() {
    var cleanupFailure = new IOException("cleanup failed");
    var request =
        request(
            3,
            16,
            Content.Chunk.from(ByteBuffer.wrap(new byte[4]), false),
            Content.Chunk.from(cleanupFailure, true));

    var failure = assertThrows(BadRequestException.class, request::bodyBytes);
    assertArrayEquals(new Throwable[] {cleanupFailure}, failure.getSuppressed());
  }

  @Test
  void keepsReadFailurePrimaryWhenCleanupFails() {
    var original = new IOException("read failed");
    var cleanup = new IOException("cleanup failed");
    var request =
        request(-1, 16, Content.Chunk.from(original, false), Content.Chunk.from(cleanup, true));

    var failure = assertThrows(IOException.class, request::bodyBytes);
    assertSame(original, failure);
    assertArrayEquals(new Throwable[] {cleanup}, failure.getSuppressed());
  }

  @Test
  void continuesRejectingAnOversizedUnknownLengthBodyOnRepeatedAccess() {
    var request = request(-1, 16, Content.Chunk.from(ByteBuffer.wrap(new byte[17]), true));

    assertThrows(ContentTooLargeException.class, request::bodyBytes);
    assertThrows(ContentTooLargeException.class, request::bodyBytes);
  }

  private static Content.Chunk[] fragments(byte[] bytes, int size) {
    if (bytes.length == 0) {
      return new Content.Chunk[] {Content.Chunk.from(ByteBuffer.wrap(bytes), true)};
    }

    var chunks = new ArrayList<Content.Chunk>();
    for (int offset = 0; offset < bytes.length; offset += size) {
      int end = Math.min(offset + size, bytes.length);
      chunks.add(
          Content.Chunk.from(ByteBuffer.wrap(bytes, offset, end - offset), end == bytes.length));
    }

    return chunks.toArray(Content.Chunk[]::new);
  }

  private static Request request(long declaredLength, int limit, Content.Chunk... input) {
    return request(declaredLength, limit, true, input);
  }

  private static Request request(
      long declaredLength, int limit, boolean complete, Content.Chunk... input) {
    var chunks = new ArrayDeque<>(List.of(input));
    var failure = new AtomicReference<>(Content.Chunk.EMPTY);
    var delegate =
        (org.eclipse.jetty.server.Request)
            Proxy.newProxyInstance(
                BufferedRequestBodyTest.class.getClassLoader(),
                new Class<?>[] {org.eclipse.jetty.server.Request.class},
                (proxy, method, arguments) ->
                    switch (method.getName()) {
                      case "getHeaders" -> HttpFields.EMPTY;
                      case "getLength" -> declaredLength;
                      case "read" -> {
                        var failed = failure.get();
                        if (failed != Content.Chunk.EMPTY) {
                          yield failed;
                        }

                        if (chunks.isEmpty()) {
                          yield complete ? Content.Chunk.EOF : null;
                        }

                        yield chunks.removeFirst();
                      }
                      case "fail" -> {
                        failure.compareAndSet(
                            Content.Chunk.EMPTY, Content.Chunk.from((Throwable) arguments[0]));
                        chunks.forEach(Content.Chunk::release);
                        chunks.clear();
                        yield null;
                      }
                      case "demand" -> {
                        if (!complete && chunks.isEmpty()) {
                          fail("Input is unavailable after the overflow byte");
                        }

                        ((Runnable) arguments[0]).run();
                        yield null;
                      }
                      case "addHttpStreamWrapper" -> null;
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    var sink =
        (org.eclipse.jetty.server.Response)
            Proxy.newProxyInstance(
                BufferedRequestBodyTest.class.getClassLoader(),
                new Class<?>[] {org.eclipse.jetty.server.Response.class},
                (proxy, method, arguments) -> {
                  throw new UnsupportedOperationException(method.getName());
                });

    return new Request(
        delegate,
        new Response(sink, Options.defaults(), Callback.NOOP),
        limit,
        1000,
        MultipartOptions.defaults());
  }
}
