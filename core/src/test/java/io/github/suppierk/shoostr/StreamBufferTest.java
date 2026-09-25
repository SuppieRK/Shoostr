package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

class StreamBufferTest {
  @Test
  void emptyStreamCommitsHeadersAndCompletesWithAnEmptyFinalWrite() throws Exception {
    var writes = new ArrayList<Write>();
    var response = response(8192, writes, -1);

    response.startStream("application/octet-stream");
    response.complete();

    assertEquals(List.of(0, 0), writes.stream().map(write -> write.bytes().length).toList());
    assertEquals(List.of(false, true), writes.stream().map(Write::last).toList());
  }

  @Test
  void physicalGrowthDoesNotFlushBeforeConfiguredCapacity() throws Exception {
    var writes = new ArrayList<Write>();
    var response = response(300, writes, -1);
    var stream = response.startStream("application/octet-stream");
    var first = new byte[255];
    var second = new byte[44];
    Arrays.fill(first, (byte) 1);
    Arrays.fill(second, (byte) 2);

    stream.write(first);
    stream.write(second);
    assertEquals(1, writes.size());

    stream.write(new byte[] {3});
    assertEquals(List.of(0, 300), writes.stream().map(write -> write.bytes().length).toList());

    response.complete();
    assertEquals(List.of(0, 300, 0), writes.stream().map(write -> write.bytes().length).toList());
    var expected = new byte[300];
    Arrays.fill(expected, 0, 255, (byte) 1);
    Arrays.fill(expected, 255, 299, (byte) 2);
    expected[299] = 3;
    assertArrayEquals(expected, writes.get(1).bytes());
  }

  @Test
  void configuredCapacityBelowInitialStorageStillControlsAutomaticFlushes() throws Exception {
    var writes = new ArrayList<Write>();
    var response = response(3, writes, -1);
    var stream = response.startStream("application/octet-stream");

    stream.write(new byte[] {1, 2, 3, 4, 5, 6, 7});
    response.complete();

    assertEquals(List.of(0, 3, 3, 1), writes.stream().map(write -> write.bytes().length).toList());
    assertEquals(List.of(false, false, false, true), writes.stream().map(Write::last).toList());
  }

  @Test
  void largeWriteUsesConfiguredCapacityAndFinishesWithPendingBytes() throws Exception {
    var writes = new ArrayList<Write>();
    var response = response(8192, writes, -1);
    var stream = response.startStream("application/octet-stream");
    var input = new byte[65537];
    for (int index = 0; index < input.length; index++) {
      input[index] = (byte) (index % 251 + 1);
    }

    stream.write(input);
    response.complete();

    assertEquals(10, writes.size());
    assertEquals(0, writes.getFirst().bytes().length);
    for (int index = 1; index <= 8; index++) {
      assertEquals(8192, writes.get(index).bytes().length);
    }

    assertArrayEquals(new byte[] {input[65536]}, writes.getLast().bytes());
    assertTrue(writes.getLast().last());
    var emitted = new ByteArrayOutputStream();
    for (var write : writes) {
      emitted.write(write.bytes());
    }

    assertArrayEquals(input, emitted.toByteArray());
  }

  @Test
  void streamRetainsCopiedBytesWhenCallerMutatesItsArray() throws Exception {
    var writes = new ArrayList<Write>();
    var response = response(8192, writes, -1);
    var stream = response.startStream("application/octet-stream");
    var input = new byte[] {1, 2, 3};

    stream.write(input);
    input[0] = 9;
    stream.flush();
    response.complete();

    assertArrayEquals(new byte[] {1, 2, 3}, writes.get(1).bytes());
    assertEquals(List.of(0, 3, 0), writes.stream().map(write -> write.bytes().length).toList());
  }

  @Test
  void explicitAndTerminalFlushesEachRunHooksOnce() throws Exception {
    var writes = new ArrayList<Write>();
    var calls = new ArrayList<String>();
    var response = response(8192, writes, -1);
    response.flushHooks(() -> calls.add("before"), () -> calls.add("after"));
    var stream = response.startStream("application/octet-stream");

    stream.write(new byte[128]);
    stream.flush();
    response.complete();

    assertEquals(List.of("before", "after", "before", "after", "before", "after"), calls);
  }

  @Test
  void failedAutomaticFlushMakesStreamTerminal() throws Exception {
    var writes = new ArrayList<Write>();
    var response = response(3, writes, 2);
    var stream = response.startStream("application/octet-stream");

    assertThrows(IOException.class, () -> stream.write(new byte[] {1, 2, 3}));
    assertThrows(IllegalStateException.class, () -> stream.write(new byte[] {4}));
    assertEquals(List.of(0, 3), writes.stream().map(write -> write.bytes().length).toList());
  }

  @Test
  void explicitFlushWaitsForDelayedTransportCompletionBeforeReusingStorage() throws Exception {
    var writes = new ArrayList<Write>();
    var pending = new ArrayBlockingQueue<Callback>(1);
    var completed = new CompletableFuture<Void>();
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                var response =
                    response(
                        128,
                        writes,
                        -1,
                        (index, callback) -> {
                          if (index == 2) {
                            pending.add(callback);
                          } else {
                            callback.succeeded();
                          }
                        });
                var stream = response.startStream("application/octet-stream");
                stream.write(new byte[] {1, 2, 3});
                stream.flush();
                stream.write(new byte[] {4});
                response.complete();
                completed.complete(null);
              } catch (Exception failure) {
                completed.completeExceptionally(failure);
              }
            });

    var callback = pending.poll(3, TimeUnit.SECONDS);
    assertNotNull(callback);
    assertFalse(completed.isDone());
    assertThrows(TimeoutException.class, () -> completed.get(100, TimeUnit.MILLISECONDS));
    callback.succeeded();
    completed.get(3, TimeUnit.SECONDS);

    assertArrayEquals(new byte[] {1, 2, 3}, writes.get(1).bytes());
    assertArrayEquals(new byte[] {4}, writes.getLast().bytes());
  }

  private Response response(int capacity, List<Write> writes, int failureWrite) {
    return response(capacity, writes, failureWrite, (index, callback) -> callback.succeeded());
  }

  private Response response(
      int capacity,
      List<Write> writes,
      int failureWrite,
      BiConsumer<Integer, Callback> completeWrite) {
    var headers = HttpFields.build();
    var delegate =
        (org.eclipse.jetty.server.Response)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {org.eclipse.jetty.server.Response.class},
                (proxy, method, arguments) ->
                    switch (method.getName()) {
                      case "getStatus" -> 200;
                      case "getHeaders" -> headers;
                      case "isCommitted" -> !writes.isEmpty();
                      case "write" -> {
                        var source = ((ByteBuffer) arguments[1]).slice();
                        var bytes = new byte[source.remaining()];
                        source.get(bytes);
                        writes.add(new Write((boolean) arguments[0], bytes));
                        var callback = (Callback) arguments[2];
                        if (writes.size() == failureWrite) {
                          callback.failed(new IOException("transport failed"));
                        } else {
                          completeWrite.accept(writes.size(), callback);
                        }

                        yield null;
                      }
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    var defaults = Options.defaults();
    var options =
        new Options(
            defaults.host(),
            defaults.port(),
            defaults.maxRequestBytes(),
            defaults.maxResponseBytes(),
            capacity,
            defaults.idleTimeoutMillis());
    return new Response(delegate, options, Callback.NOOP);
  }

  private static final class Write {
    private final boolean last;
    private final byte[] bytes;

    private Write(boolean last, byte[] bytes) {
      this.last = last;
      this.bytes = bytes;
    }

    private boolean last() {
      return last;
    }

    private byte[] bytes() {
      return bytes;
    }
  }
}
