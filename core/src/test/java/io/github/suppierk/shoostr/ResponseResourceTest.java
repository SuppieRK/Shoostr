package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResponseResourceTest {
  private static Response response(
      HttpFields requestHeaders, HttpFields responseHeaders, ByteArrayOutputStream body) {
    var request = createRequestForResponse(requestHeaders);
    var delegate = createDelegateForResponse(responseHeaders, body);

    return io.github.suppierk.shoostr.Request.create(
            request, delegate, new Options(), Callback.NOOP)
        .response();
  }

  private static Request createRequestForResponse(HttpFields requestHeaders) {
    return (Request)
        Proxy.newProxyInstance(
            ResponseResourceTest.class.getClassLoader(),
            new Class<?>[] {Request.class},
            (ignored, method, ignoredObjectValues) ->
                switch (method.getName()) {
                  case "getMethod" -> "GET";
                  case "getHeaders" -> requestHeaders;
                  case "getSession" -> null;
                  case "consumeAvailable" -> true;
                  case "addHttpStreamWrapper" -> null;
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static org.eclipse.jetty.server.Response createDelegateForResponse(
      HttpFields responseHeaders, ByteArrayOutputStream body) {
    return (org.eclipse.jetty.server.Response)
        Proxy.newProxyInstance(
            ResponseResourceTest.class.getClassLoader(),
            new Class<?>[] {org.eclipse.jetty.server.Response.class},
            (ignoredObject, method, arguments) ->
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
  }

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
    assertEquals("345", body.toString(StandardCharsets.UTF_8));
  }

  @Test
  void returnsTheStagedResponseForFurtherHeaderConfiguration() throws Exception {
    var responseHeaders = HttpFields.build();
    var body = new ByteArrayOutputStream();
    var channel = new TrackingChannel("0123456789", false);
    var response = response(HttpFields.build(), responseHeaders, body);

    var chained =
        response.resource(channel, 10, Instant.EPOCH, "text/plain").setHeader("X-Chain", "ok");
    response.complete();

    assertSame(response, chained);
    assertEquals("ok", responseHeaders.get("X-Chain"));
    assertEquals("0123456789", body.toString(StandardCharsets.UTF_8));
  }

  @ParameterizedTest
  @ValueSource(strings = {"redirect", "reset", "preflight"})
  void releasesSelectedDescriptorsBeforeAllowingReplacementBodies(String replacement)
      throws Exception {
    var body = new ByteArrayOutputStream();
    var channel = new TrackingChannel("0123456789", false);
    var response = response(HttpFields.build(), HttpFields.build(), body);
    response.resource(channel, 10, Instant.EPOCH, "text/plain");

    switch (replacement) {
      case "redirect" -> response.redirect("/replacement");
      case "reset" -> response.reset(HttpStatusCodes.INTERNAL_SERVER_ERROR);
      case "preflight" -> response.preflight();
      default -> throw new IllegalArgumentException(replacement);
    }

    assertTrue(channel.closed());
    response.status(200).text("replacement");
    response.complete();

    assertEquals("replacement", body.toString(StandardCharsets.UTF_8));
  }

  @Test
  void closesRejectedInputWithoutReplacingTheSelectedResource() throws Exception {
    var body = new ByteArrayOutputStream();
    var channel = new TrackingChannel("0123456789", false);
    var response = response(HttpFields.build(), HttpFields.build(), body);
    response.resource(channel, 10, Instant.EPOCH, "text/plain");
    var closed = new AtomicBoolean();
    var input = trackedInput(closed);

    assertThrows(IllegalStateException.class, () -> response.input(input, "text/plain"));
    assertTrue(closed.get());
    response.complete();

    assertEquals("0123456789", body.toString(StandardCharsets.UTF_8));
  }

  private InputStream trackedInput(AtomicBoolean closed) {
    return new ByteArrayInputStream("replacement".getBytes(StandardCharsets.UTF_8)) {
      @Override
      public void close() {
        closed.set(true);
      }
    };
  }

  private static final class TrackingChannel implements SeekableByteChannel {
    private final byte[] bytes;
    private final boolean failPosition;
    private final List<Long> readPositions;
    private long position;
    private boolean open;

    private TrackingChannel(String content, boolean failPosition) {
      this.bytes = content.getBytes(StandardCharsets.US_ASCII);
      this.failPosition = failPosition;
      this.readPositions = new ArrayList<>();
      this.open = true;
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
