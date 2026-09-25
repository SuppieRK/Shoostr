package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
    var chunks = new ArrayDeque<>(List.of(input));
    var delegate =
        (org.eclipse.jetty.server.Request)
            Proxy.newProxyInstance(
                BufferedRequestBodyTest.class.getClassLoader(),
                new Class<?>[] {org.eclipse.jetty.server.Request.class},
                (proxy, method, arguments) ->
                    switch (method.getName()) {
                      case "getLength" -> declaredLength;
                      case "read" -> chunks.isEmpty() ? Content.Chunk.EOF : chunks.removeFirst();
                      case "demand" -> {
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
