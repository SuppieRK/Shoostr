package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.Request;
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
  void successiveFlushesUseOnlyTheirPendingBytesAcrossStorageGrowth() throws Exception {
    var writes = new ArrayList<Write>();
    var response = response(1024, writes, -1);
    var stream = response.startStream("application/octet-stream");

    stream
        .write(new byte[] {1, 2, 3})
        .flush()
        .write(new byte[300])
        .flush()
        .write(new byte[] {4})
        .flush();
    response.complete();

    assertEquals(
        List.of(0, 3, 300, 1, 0), writes.stream().map(write -> write.bytes().length).toList());
    assertArrayEquals(new byte[] {1, 2, 3}, writes.get(1).bytes());
    assertArrayEquals(new byte[300], writes.get(2).bytes());
    assertArrayEquals(new byte[] {4}, writes.get(3).bytes());
    assertEquals(
        List.of(false, false, false, false, true), writes.stream().map(Write::last).toList());
  }

  @Test
  void emptyExplicitFlushAndPendingFinalWritePreserveBytesAndHooks() throws Exception {
    var writes = new ArrayList<Write>();
    var calls = new ArrayList<String>();
    var response = response(128, writes, -1);
    response.flushHooks(() -> calls.add("before"), () -> calls.add("after"));
    var stream = response.startStream("application/octet-stream");

    stream.flush();
    stream.write(new byte[] {5, 6});
    response.complete();

    assertEquals(List.of(0, 0, 2), writes.stream().map(write -> write.bytes().length).toList());
    assertArrayEquals(new byte[] {5, 6}, writes.getLast().bytes());
    assertEquals(List.of(false, false, true), writes.stream().map(Write::last).toList());
    assertEquals(List.of("before", "after", "before", "after", "before", "after"), calls);
  }

  @Test
  void interleavedResponsesKeepTheirStreamBytesIndependent() throws Exception {
    var firstWrites = new ArrayList<Write>();
    var secondWrites = new ArrayList<Write>();
    var first = response(128, firstWrites, -1);
    var second = response(128, secondWrites, -1);
    var firstStream = first.startStream("application/octet-stream");
    var secondStream = second.startStream("application/octet-stream");

    firstStream.write(new byte[] {1, 2}).flush();
    secondStream.write(new byte[] {9}).flush();
    firstStream.write(new byte[] {3});
    secondStream.write(new byte[] {8, 7});
    first.complete();
    second.complete();

    assertArrayEquals(new byte[] {1, 2}, firstWrites.get(1).bytes());
    assertArrayEquals(new byte[] {3}, firstWrites.getLast().bytes());
    assertArrayEquals(new byte[] {9}, secondWrites.get(1).bytes());
    assertArrayEquals(new byte[] {8, 7}, secondWrites.getLast().bytes());
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
  void synchronousTransportFailureMakesExplicitFlushTerminal() throws Exception {
    var writes = new ArrayList<Write>();
    var calls = new ArrayList<String>();
    var response =
        response(
            128,
            writes,
            -1,
            (index, callback) -> {
              if (index == 2) {
                throw new IllegalArgumentException("synchronous transport failure");
              }

              callback.succeeded();
            });
    response.flushHooks(() -> calls.add("before"), () -> calls.add("after"));
    var stream = response.startStream("application/octet-stream");
    stream.write(new byte[] {1});

    assertThrows(IllegalArgumentException.class, stream::flush);
    assertThrows(IllegalStateException.class, () -> stream.write(new byte[] {2}));
    assertThrows(IllegalStateException.class, stream::flush);
    assertEquals(List.of("before", "after", "before"), calls);
  }

  @Test
  void delayedAsynchronousTransportFailureMakesStreamTerminal() throws Exception {
    var writes = new ArrayList<Write>();
    var pending = new ArrayBlockingQueue<Callback>(1);
    var outcome = new CompletableFuture<Throwable>();
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

                try {
                  stream.flush();
                  fail("flush unexpectedly succeeded");
                } catch (IOException failure) {
                  assertThrows(IllegalStateException.class, () -> stream.write(new byte[] {4}));
                  outcome.complete(failure);
                }
              } catch (Throwable failure) {
                outcome.complete(failure);
              }
            });

    var callback = pending.poll(3, TimeUnit.SECONDS);
    assertNotNull(callback);
    assertFalse(outcome.isDone());
    callback.failed(new IOException("delayed transport failure"));

    assertInstanceOf(IOException.class, outcome.get(3, TimeUnit.SECONDS));
    assertEquals(List.of(0, 3), writes.stream().map(write -> write.bytes().length).toList());
  }

  @Test
  void interruptedTransportWaitMakesStreamTerminalEvenIfCallbackLaterSucceeds() throws Exception {
    var writes = new ArrayList<Write>();
    var pending = new ArrayBlockingQueue<Callback>(1);
    var submitted = new ArrayBlockingQueue<ByteBuffer>(1);
    var outcome = new CompletableFuture<Throwable>();
    var writer =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    var response =
                        response(
                            128,
                            writes,
                            -1,
                            (_, callback) -> callback.succeeded(),
                            2,
                            (source, callback) -> {
                              submitted.add(source);
                              pending.add(callback);
                            });
                    var stream = response.startStream("application/octet-stream");
                    stream.write(new byte[] {1, 2, 3});

                    try {
                      stream.flush();
                      fail("flush unexpectedly succeeded");
                    } catch (IOException failure) {
                      assertThrows(IllegalStateException.class, () -> stream.write(new byte[] {4}));
                      outcome.complete(failure);
                    }
                  } catch (Throwable failure) {
                    outcome.complete(failure);
                  }
                });

    var source = submitted.poll(3, TimeUnit.SECONDS);
    var callback = pending.poll(3, TimeUnit.SECONDS);
    assertNotNull(source);
    assertNotNull(callback);
    assertFalse(outcome.isDone());
    int position = source.position();
    int limit = source.limit();
    var expected = new byte[] {1, 2, 3};
    var before = new byte[source.remaining()];
    source.duplicate().get(before);
    assertArrayEquals(expected, before);

    writer.interrupt();
    assertInstanceOf(IOException.class, outcome.get(3, TimeUnit.SECONDS));
    assertEquals(position, source.position());
    assertEquals(limit, source.limit());
    var after = new byte[source.remaining()];
    source.duplicate().get(after);
    assertArrayEquals(expected, after);

    var consumed = new byte[source.remaining()];
    source.get(consumed);
    writes.add(new Write(false, consumed));
    callback.succeeded();
    assertArrayEquals(expected, writes.get(1).bytes());
  }

  @Test
  void explicitFlushWaitsForDelayedTransportCompletionBeforeReusingStorage() throws Exception {
    var writes = new ArrayList<Write>();
    var calls = new CopyOnWriteArrayList<String>();
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
                response.flushHooks(() -> calls.add("before"), () -> calls.add("after"));
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
    assertEquals(List.of("before", "after", "before"), calls);
    assertThrows(TimeoutException.class, () -> completed.get(100, TimeUnit.MILLISECONDS));
    callback.succeeded();
    completed.get(3, TimeUnit.SECONDS);

    assertArrayEquals(new byte[] {1, 2, 3}, writes.get(1).bytes());
    assertArrayEquals(new byte[] {4}, writes.getLast().bytes());
    assertEquals(List.of("before", "after", "before", "after", "before", "after"), calls);
  }

  private Response response(int capacity, List<Write> writes, int failureWrite) {
    return response(capacity, writes, failureWrite, (_, callback) -> callback.succeeded());
  }

  private Response response(
      int capacity,
      List<Write> writes,
      int failureWrite,
      BiConsumer<Integer, Callback> completeWrite) {
    return response(capacity, writes, failureWrite, completeWrite, -1, (_, _) -> {});
  }

  private Response response(
      int capacity,
      List<Write> writes,
      int failureWrite,
      BiConsumer<Integer, Callback> completeWrite,
      int deferredWrite,
      BiConsumer<ByteBuffer, Callback> deferWrite) {
    var headers = HttpFields.build();
    var delegate =
        (org.eclipse.jetty.server.Response)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {org.eclipse.jetty.server.Response.class},
                (_, method, arguments) ->
                    switch (method.getName()) {
                      case "getStatus" -> 200;
                      case "getHeaders" -> headers;
                      case "isCommitted" -> !writes.isEmpty();
                      case "write" -> {
                        var source = (ByteBuffer) arguments[1];
                        var callback = (Callback) arguments[2];
                        if (writes.size() + 1 == deferredWrite) {
                          deferWrite.accept(source, callback);
                          yield null;
                        }

                        var bytes = new byte[source.remaining()];
                        source.get(bytes);
                        writes.add(new Write((boolean) arguments[0], bytes));
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
    var nativeRequest =
        (Request)
            Proxy.newProxyInstance(
                StreamBufferTest.class.getClassLoader(),
                new Class<?>[] {Request.class},
                (_, method, _) ->
                    switch (method.getName()) {
                      case "getMethod" -> "GET";
                      case "getHeaders" -> HttpFields.EMPTY;
                      case "addHttpStreamWrapper" -> null;
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    return io.github.suppierk.shoostr.Request.create(
            nativeRequest, delegate, options, Callback.NOOP)
        .response();
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
