package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpHeaders;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

class ResponseResourceTest {
  @Test
  void closesTheDescriptorWhenSeekingTheSelectedResourceFails() throws Exception {
    var channel = new TrackingChannel("0123456789", true);
    var response = response(HttpFields.build(), HttpFields.build(), new ByteArrayOutputStream());

    response.resource(channel, 10, Instant.EPOCH, "text/plain");

    assertThrows(IOException.class, response::complete);
    assertTrue(channel.closed());
  }

  @Test
  void seeksToTheRangeBeforeReadingTheSelectedResource() throws Exception {
    var requestHeaders = HttpFields.build();
    requestHeaders.add(HttpHeaders.RANGE.value(), "bytes=3-5");
    var body = new ByteArrayOutputStream();
    var channel = new TrackingChannel("0123456789", false);
    var response = response(requestHeaders, HttpFields.build(), body);

    response.resource(channel, 10, Instant.EPOCH, "text/plain");
    response.complete();

    assertEquals(List.of(3L), channel.readPositions());
    assertEquals("345", body.toString(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static Response response(
      HttpFields requestHeaders, HttpFields responseHeaders, ByteArrayOutputStream body) {
    var request =
        (Request)
            Proxy.newProxyInstance(
                ResponseResourceTest.class.getClassLoader(),
                new Class<?>[] {Request.class},
                (proxy, method, arguments) ->
                    switch (method.getName()) {
                      case "getMethod" -> "GET";
                      case "getHeaders" -> requestHeaders;
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    var delegate =
        (org.eclipse.jetty.server.Response)
            Proxy.newProxyInstance(
                ResponseResourceTest.class.getClassLoader(),
                new Class<?>[] {org.eclipse.jetty.server.Response.class},
                (proxy, method, arguments) ->
                    switch (method.getName()) {
                      case "getStatus" -> 200;
                      case "setStatus" -> null;
                      case "getHeaders" -> responseHeaders;
                      case "isCommitted" -> false;
                      case "write" -> {
                        var buffer = ((ByteBuffer) arguments[1]).duplicate();
                        var bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        body.write(bytes);
                        ((Callback) arguments[2]).succeeded();
                        yield null;
                      }
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    return new Response(delegate, Options.defaults(), Callback.NOOP, false, request);
  }

  private static final class TrackingChannel implements SeekableByteChannel {
    private final byte[] bytes;
    private final boolean failPosition;
    private final List<Long> readPositions;
    private long position;
    private boolean open;

    private TrackingChannel(String content, boolean failPosition) {
      bytes = content.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
      this.failPosition = failPosition;
      readPositions = new ArrayList<>();
      open = true;
    }

    @Override
    public int read(ByteBuffer destination) {
      readPositions.add(position);
      var length = Math.min(destination.remaining(), bytes.length - Math.toIntExact(position));
      destination.put(bytes, Math.toIntExact(position), length);
      position += length;
      return length;
    }

    @Override
    public int write(ByteBuffer source) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long position() {
      return position;
    }

    @Override
    public SeekableByteChannel position(long next) throws IOException {
      if (failPosition) {
        throw new IOException("seek failure");
      }

      position = next;
      return this;
    }

    @Override
    public long size() {
      return bytes.length;
    }

    @Override
    public SeekableByteChannel truncate(long size) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void close() {
      open = false;
    }

    private boolean closed() {
      return !open;
    }

    private List<Long> readPositions() {
      return List.copyOf(readPositions);
    }
  }
}
