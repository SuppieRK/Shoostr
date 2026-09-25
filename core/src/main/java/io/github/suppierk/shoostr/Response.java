package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.Cookie;
import io.github.suppierk.shoostr.http.HttpCharacters;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.http.exceptions.NotAcceptableException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.compression.server.CompressionConfig;
import org.eclipse.jetty.compression.server.CompressionHandler;
import org.eclipse.jetty.compression.server.internal.CompressionResponse;
import org.eclipse.jetty.http.HttpDateTime;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.QuotedQualityCSV;
import org.eclipse.jetty.http.SetCookieParser;
import org.eclipse.jetty.http.content.HttpContent;
import org.eclipse.jetty.http.content.ResourceHttpContent;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.session.AbstractSessionManager;
import org.eclipse.jetty.session.ManagedSession;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.jspecify.annotations.Nullable;

/** Finite output is staged until handler return. The framework owns close(). */
public final class Response implements AutoCloseable {
  /** Tracks writable phases and terminal outcomes independently of transport commitment. */
  private enum State {
    OPEN,
    STREAMING,
    CLOSED,
    FAILED
  }

  /** Represents the inclusive interval selected from one byte range request. */
  private record ByteRange(long first, long last) {
    /**
     * Creates one inclusive byte interval.
     *
     * @param first first selected byte offset
     * @param last last selected byte offset
     * @throws IllegalArgumentException if the first offset exceeds the last
     */
    private ByteRange {
      if (first > last) {
        throw new IllegalArgumentException("Byte range start exceeds end");
      }
    }

    /**
     * Calculates the selected number of bytes.
     *
     * @return the interval length
     */
    private long length() {
      return last - first + 1;
    }
  }

  private static final byte[] EMPTY = new byte[0];
  private static final String EVENT_STREAM_CONTENT_TYPE = "text/event-stream; charset=utf-8";
  private static final List<HttpHeaders> CORS_RESPONSE_HEADERS =
      List.of(
          HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,
          HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
          HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.ACCESS_CONTROL_MAX_AGE);
  private static final ByteRange UNSATISFIABLE_RANGE = new ByteRange(-1, -1);
  private static final SetCookieParser COOKIE_PARSER = SetCookieParser.newInstance();
  private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
  private static final String TEXT_CONTENT_TYPE = "text/plain; charset=utf-8";
  private static final String IDENTITY_ENCODING = "identity";
  private static final HttpField TEXT =
      new PreEncodedHttpField(HttpHeaders.CONTENT_TYPE.value(), TEXT_CONTENT_TYPE);
  private static final HttpField COMPACT_TEXT =
      new PreEncodedHttpField(HttpHeaders.CONTENT_TYPE.value(), "text/plain;charset=utf-8");
  private static final HttpField JSON =
      new PreEncodedHttpField(HttpHeaders.CONTENT_TYPE.value(), "application/json");
  private final org.eclipse.jetty.server.Response delegate;
  private final Options options;
  private final Callback completion;
  private final Thread owner;
  private final boolean head;
  private final org.eclipse.jetty.server.@Nullable Request request;
  private Runnable beforeFlush;
  private Runnable afterFlush;
  private State state;
  private byte[] body;
  private @Nullable Stream stream;
  private boolean frameworkClosing;
  private boolean flushCallback;
  private boolean beforeFlushFailed;
  private boolean gating;
  private boolean eventStreamOnly;
  private boolean negotiatedByAccept;
  private boolean compressionEnabled;
  private @Nullable CompressionHandler compressionHandler;
  private boolean gzipExcludedByAccept;
  private boolean identityUnacceptable;
  private boolean encodingUnacceptable;
  private boolean encodingRejected;
  private boolean fileSelected;
  private long headContentLength;
  private @Nullable ResourceHttpContent resourceContent;
  private @Nullable SeekableByteChannel resourceChannel;
  private long resourceOffset;
  private long resourceLength;
  private @Nullable String requiredAllow;
  private @Nullable String requiredChallenge;
  private @Nullable Map<String, String> requiredCorsHeaders;
  private @Nullable Throwable afterFlushFailure;

  /**
   * Creates handler-thread-owned output with an empty staged body.
   *
   * @param delegate transport response receiving headers and bytes
   * @param options finite-body and streaming-buffer limits
   * @param completion callback completing the transport exchange exactly once
   */
  Response(org.eclipse.jetty.server.Response delegate, Options options, Callback completion) {
    this(delegate, options, completion, false, null);
  }

  /**
   * Creates handler-thread-owned output with request-method body semantics.
   *
   * @param delegate transport response receiving headers and bytes
   * @param options finite-body and streaming-buffer limits
   * @param completion callback completing the transport exchange exactly once
   * @param head whether the request method suppresses response content
   */
  Response(
      org.eclipse.jetty.server.Response delegate,
      Options options,
      Callback completion,
      boolean head) {
    this(delegate, options, completion, head, null);
  }

  /**
   * Creates handler-thread-owned output with the transport request needed for file semantics.
   *
   * @param delegate transport response receiving headers and bytes
   * @param options finite-body and streaming-buffer limits
   * @param completion callback completing the transport exchange exactly once
   * @param head whether the request method suppresses response content
   * @param request transport request supplying range and condition fields
   */
  Response(
      org.eclipse.jetty.server.Response delegate,
      Options options,
      Callback completion,
      boolean head,
      org.eclipse.jetty.server.@Nullable Request request) {
    this.delegate = delegate;
    this.options = options;
    this.completion = completion;
    owner = Thread.currentThread();
    this.head = head;
    this.request = request;
    beforeFlush = () -> {};
    afterFlush = () -> {};
    flushCallback = false;
    beforeFlushFailed = false;
    headContentLength = -1;
    state = State.OPEN;
    body = EMPTY;
  }

  /**
   * Installs framework-owned callbacks around synchronous streaming writes.
   *
   * @param before callback before bytes are committed
   * @param after callback after bytes are committed
   */
  void flushHooks(Runnable before, Runnable after) {
    beforeFlush = Objects.requireNonNull(before);
    afterFlush = Objects.requireNonNull(after);
  }

  /**
   * Records the native response compressor for this exchange.
   *
   * @param handler Jetty compressor installed around application dispatch
   */
  void compression(CompressionHandler handler) {
    compressionEnabled = true;
    compressionHandler = Objects.requireNonNull(handler);
  }

  /**
   * Records a gzip rejection that must override Jetty's wildcard encoding selection.
   *
   * @param excluded whether the request explicitly rejects gzip despite accepting a wildcard
   */
  void gzipExcludedByAccept(boolean excluded) {
    gzipExcludedByAccept = excluded;
  }

  /**
   * Records that neither framework-supported content coding is acceptable to the client.
   *
   * @param unacceptable whether both gzip and identity have zero effective quality
   */
  void encodingUnacceptable(boolean unacceptable) {
    encodingUnacceptable = unacceptable;
  }

  /**
   * Records whether an explicit identity content coding would violate the request preference.
   *
   * @param unacceptable whether identity has zero effective quality
   */
  void identityUnacceptable(boolean unacceptable) {
    identityUnacceptable = unacceptable;
  }

  /**
   * Reports a pre-commit content-coding rejection to the framework error path.
   *
   * @return whether this response rejected the client's encodings
   */
  boolean encodingRejected() {
    return encodingRejected;
  }

  /**
   * Sends an empty 406 after a pre-commit encoding rejection, preserving cache variation.
   *
   * @throws IOException if transport completion fails
   */
  void emptyEncodingError() throws IOException {
    encodingUnacceptable = false;
    reset(HttpStatusCodes.NOT_ACCEPTABLE);
    vary(HttpHeaders.ACCEPT_ENCODING);
    complete();
  }

  /**
   * Retains the router-derived Allow field through a generated 405 renderer and its flushes.
   *
   * @param value allowed methods rendered as an HTTP field value
   */
  void requiredAllow(String value) {
    requiredAllow = Objects.requireNonNull(value);
    delegate.getHeaders().put(HttpHeaders.ALLOW.value(), value);
  }

  /**
   * Preserves a required authentication challenge through global error rendering.
   *
   * @param value validated challenge from an authentication-required failure
   */
  void requiredChallenge(String value) {
    requiredChallenge = value;
  }

  /**
   * Renews a live Jetty session using this response before it is committed.
   *
   * @param session existing session
   * @param request transport request that owns it
   */
  void renewSessionId(Session session, org.eclipse.jetty.server.Request request) {
    require(State.OPEN);
    session.renewId(request, delegate);
  }

  /**
   * Rejects lazy session creation once its cookie can no longer be sent.
   *
   * @throws IllegalStateException if response commitment has begun
   */
  void checkSessionCreation() {
    require(State.OPEN);
    if (delegate.isCommitted()) {
      throw new IllegalStateException("Session cookie cannot be sent after response commitment");
    }
  }

  /**
   * Disables lifecycle callbacks after a pre-submission callback failure so error rendering can
   * complete through the normal exception mapper.
   */
  void disableFlushHooksAfterBeforeFailure() {
    if (!beforeFlushFailed) {
      return;
    }

    beforeFlushFailed = false;
    beforeFlush = () -> {};
    afterFlush = () -> {};
  }

  /**
   * Returns the application callback failure observed after a finite or file submission.
   *
   * @return callback failure, or null when post-flush observation succeeded
   */
  @Nullable Throwable afterFlushFailure() {
    return afterFlushFailure;
  }

  /**
   * Stages a UTF-8 plain-text response until the handler returns.
   *
   * @param value response text
   * @return this response
   */
  public Response text(String value) {
    return body(TEXT_CONTENT_TYPE, value.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Selects one candidate compatible with the request's Accept field and marks this response as
   * varying by Accept. The returned candidate only labels bytes; callers remain responsible for
   * generating and sending the representation. A missing Accept field selects the first candidate.
   *
   * @param request request whose Accept fields constrain the selection
   * @param candidates representations this handler can generate, in server preference order
   * @return selected original candidate
   * @throws NotAcceptableException if an explicit Accept field excludes every candidate
   * @throws io.github.suppierk.shoostr.http.exceptions.BadRequestException if an Accept field is
   *     malformed
   * @throws IllegalArgumentException if candidates is empty
   * @throws NullPointerException if request, candidates, or a candidate is null
   * @throws IllegalStateException if the response is unavailable for mutation
   */
  public MediaType negotiate(Request request, MediaType... candidates) {
    require(State.OPEN);
    Objects.requireNonNull(request);
    Objects.requireNonNull(candidates);
    if (candidates.length == 0) {
      throw new IllegalArgumentException("At least one media type is required");
    }

    for (var candidate : candidates) {
      Objects.requireNonNull(candidate);
    }

    var fields = request.headers(HttpHeaders.ACCEPT.value());
    if (fields.isEmpty()) {
      variedByAccept();
      return candidates[0];
    }

    var selected = AcceptNegotiation.select(fields, candidates);
    variedByAccept();
    if (selected != null) {
      return selected;
    }

    throw new NotAcceptableException();
  }

  /**
   * Stages encoded bytes, copied so later caller mutation cannot change them.
   *
   * @param contentType response media type
   * @param value encoded body
   * @return this response
   * @throws IllegalArgumentException if the body exceeds the configured limit
   * @throws IllegalStateException if file output is already selected
   */
  public Response body(String contentType, byte[] value) {
    require(State.OPEN);
    if (fileSelected) {
      throw new IllegalStateException("Response already has file output");
    }

    Objects.requireNonNull(value);
    if (value.length > options.maxResponseBytes()) {
      throw new IllegalArgumentException("Finite response exceeds limit");
    }

    contentType(contentType);
    headContentLength = -1;
    body = value.clone();
    return this;
  }

  /**
   * Stages caller-encoded bytes with typed media metadata, without transcoding or serialization.
   *
   * @param contentType response media type, including any caller-selected charset
   * @param value encoded body, copied before returning
   * @return this response
   * @throws NullPointerException if contentType or value is null
   * @throws IllegalArgumentException if the body exceeds the configured limit
   */
  public Response body(MediaType contentType, byte[] value) {
    return body(Objects.requireNonNull(contentType).value(), value);
  }

  /**
   * Streams an application-owned input source through the bounded response buffer and closes it
   * before this method returns, including when transport output fails.
   *
   * @param source application input source
   * @param contentType response media type
   * @return this response
   * @throws IOException if reading or output fails
   */
  public Response input(InputStream source, String contentType) throws IOException {
    return input(source, contentType, -1);
  }

  /**
   * Stages a default-filesystem file for framework-owned Jetty transfer after handler return. The
   * selected file must remain readable until the terminal transport callback. Transfer failures are
   * reported by that callback rather than this method once staging succeeds.
   *
   * @param source application-selected file
   * @param contentType response media type
   * @return this response
   * @throws IOException if opening, reading, or output fails
   * @throws IllegalArgumentException if an application-supplied ETag is invalid
   * @throws IllegalStateException if finite, resource, or gate output is already pending
   */
  public Response file(Path source, String contentType) throws IOException {
    var resource = Objects.requireNonNull(source);
    require(State.OPEN);
    if (gating || body.length != 0 || fileSelected) {
      throw new IllegalStateException("Response cannot become a file");
    }

    if (request == null) {
      var length = Files.size(resource);
      return input(Files.newInputStream(resource), contentType, length);
    }

    if (!Files.isRegularFile(resource) || !Files.isReadable(resource)) {
      throw new IOException("Expected a readable regular file");
    }

    if (!FileSystems.getDefault().equals(resource.getFileSystem())) {
      throw new IOException("Only default-filesystem paths are supported");
    }

    return resource(ResourceFactory.root().newResource(resource), contentType);
  }

  /**
   * Stages a file download and supplies both legacy ASCII and UTF-8 attachment filenames.
   *
   * @param source application-selected file
   * @param contentType response media type
   * @param filename attachment filename presented to clients
   * @return this response
   * @throws IOException if opening, reading, or output fails
   * @throws IllegalArgumentException if the filename has a control or path-separator character
   * @throws IllegalStateException if finite, resource, or gate output is already pending
   */
  public Response attachment(Path source, String contentType, String filename) throws IOException {
    var disposition = attachmentDisposition(filename);
    file(source, contentType);
    return header(HttpHeaders.CONTENT_DISPOSITION.value(), disposition);
  }

  /**
   * Stages one framework-selected Jetty resource for transfer after handler return.
   *
   * @param resource readable resource selected by framework infrastructure
   * @param contentType response media type
   * @return this response
   * @throws IOException if metadata is unavailable
   * @throws IllegalStateException if finite, resource, or gate output is already pending
   * @throws IllegalArgumentException if a selected entity tag is invalid
   */
  Response resource(Resource resource, String contentType) throws IOException {
    Objects.requireNonNull(resource);
    require(State.OPEN);
    if (gating || body.length != 0 || fileSelected) {
      throw new IllegalStateException("Response cannot become a file");
    }

    fileSelected = true;
    var content = new ResourceHttpContent(resource, contentType);
    var length = content.getContentLengthValue();
    if (length < 0) {
      throw new IOException("File length is unavailable");
    }

    contentType(contentType);
    var lastModified = effectiveLastModified(content.getLastModifiedInstant(), responseDate());
    resourceHeaders(content, lastModified);
    delegate.getHeaders().put(HttpHeaders.ACCEPT_RANGES.value(), "bytes");
    delegate.getHeaders().put(HttpHeader.CONTENT_LENGTH, length);
    var tag = delegate.getHeaders().get(HttpHeaders.ETAG.value());
    if (tag != null && !entityTag(tag)) {
      throw new IllegalArgumentException("Invalid entity tag");
    }

    if (conditionalStatus() && (!matchesIfMatch(tag) || !unmodifiedSince(lastModified))) {
      status(HttpStatusCodes.PRECONDITION_FAILED.value());
      delegate.getHeaders().remove(HttpHeader.CONTENT_LENGTH);
      closeResourceChannel();
      return this;
    }

    if (conditionalStatus() && noneMatch(tag)) {
      status(
          HttpMethods.GET.value().equals(request().getMethod())
                  || HttpMethods.HEAD.value().equals(request().getMethod())
              ? HttpStatusCodes.NOT_MODIFIED.value()
              : HttpStatusCodes.PRECONDITION_FAILED.value());
      delegate.getHeaders().remove(HttpHeader.CONTENT_LENGTH);
      closeResourceChannel();
      return this;
    }

    if (conditionalStatus() && notModifiedSince(lastModified)) {
      status(HttpStatusCodes.NOT_MODIFIED.value());
      delegate.getHeaders().remove(HttpHeader.CONTENT_LENGTH);
      closeResourceChannel();
      return this;
    }

    var range = range(length, tag);
    if (UNSATISFIABLE_RANGE.equals(range)) {
      status(HttpStatusCodes.RANGE_NOT_SATISFIABLE.value());
      delegate.getHeaders().put(HttpHeaders.CONTENT_RANGE.value(), "bytes */" + length);
      closeResourceChannel();
      return this;
    }

    if (range != null) {
      status(HttpStatusCodes.PARTIAL_CONTENT.value());
      delegate
          .getHeaders()
          .put(
              HttpHeaders.CONTENT_RANGE.value(),
              "bytes " + range.first + "-" + range.last + "/" + length);
      delegate.getHeaders().put(HttpHeader.CONTENT_LENGTH, range.length());
    }

    resourceContent = content;
    resourceOffset = range == null ? 0 : range.first;
    resourceLength = range == null ? length : range.length();
    return this;
  }

  /**
   * Stages an already-open filesystem descriptor using only descriptor-relative metadata.
   *
   * @param channel selected descriptor retained until terminal transfer
   * @param length selected representation length
   * @param lastModified descriptor-relative modification time
   * @param contentType response media type
   * @return this response
   * @throws IOException if output cannot be staged
   * @throws IllegalStateException if finite, resource, or gate output is already pending
   */
  Response resource(
      SeekableByteChannel channel, long length, Instant lastModified, String contentType)
      throws IOException {
    Objects.requireNonNull(channel);
    Objects.requireNonNull(lastModified);
    require(State.OPEN);
    if (gating || body.length != 0 || fileSelected) {
      throw new IllegalStateException("Response cannot become a file");
    }

    if (length < 0) {
      throw new IOException("File length is unavailable");
    }

    fileSelected = true;
    resourceChannel = channel;
    contentType(contentType);
    var effectiveLastModified =
        Objects.requireNonNull(effectiveLastModified(lastModified, responseDate()));
    var tag = resourceTag(length, effectiveLastModified);
    delegate.getHeaders().put(HttpHeaders.ETAG.value(), tag);
    delegate
        .getHeaders()
        .put(HttpHeaders.LAST_MODIFIED.value(), HttpDateTime.format(effectiveLastModified));
    delegate.getHeaders().put(HttpHeaders.ACCEPT_RANGES.value(), "bytes");
    delegate.getHeaders().put(HttpHeader.CONTENT_LENGTH, length);

    if (conditionalStatus() && (!matchesIfMatch(tag) || !unmodifiedSince(effectiveLastModified))) {
      status(HttpStatusCodes.PRECONDITION_FAILED.value());
      delegate.getHeaders().remove(HttpHeader.CONTENT_LENGTH);
      closeResourceChannel();
      return this;
    }

    if (conditionalStatus() && noneMatch(tag)) {
      status(
          HttpMethods.GET.value().equals(request().getMethod())
                  || HttpMethods.HEAD.value().equals(request().getMethod())
              ? HttpStatusCodes.NOT_MODIFIED.value()
              : HttpStatusCodes.PRECONDITION_FAILED.value());
      delegate.getHeaders().remove(HttpHeader.CONTENT_LENGTH);
      closeResourceChannel();
      return this;
    }

    if (conditionalStatus() && notModifiedSince(effectiveLastModified)) {
      status(HttpStatusCodes.NOT_MODIFIED.value());
      delegate.getHeaders().remove(HttpHeader.CONTENT_LENGTH);
      closeResourceChannel();
      return this;
    }

    var range = range(length, tag);
    if (UNSATISFIABLE_RANGE.equals(range)) {
      status(HttpStatusCodes.RANGE_NOT_SATISFIABLE.value());
      delegate.getHeaders().put(HttpHeaders.CONTENT_RANGE.value(), "bytes */" + length);
      closeResourceChannel();
      return this;
    }

    if (range != null) {
      status(HttpStatusCodes.PARTIAL_CONTENT.value());
      delegate
          .getHeaders()
          .put(
              HttpHeaders.CONTENT_RANGE.value(),
              "bytes " + range.first + "-" + range.last + "/" + length);
      delegate.getHeaders().put(HttpHeader.CONTENT_LENGTH, range.length());
    }

    resourceOffset = range == null ? 0 : range.first;
    resourceLength = range == null ? length : range.length();
    return this;
  }

  /**
   * Commits status and headers immediately and starts bounded streaming output.
   *
   * @param contentType response media type
   * @return the handler-scoped stream
   * @throws IOException if the initial write fails
   * @throws IllegalStateException if invoked from a gate, finite output is pending, or the status
   *     forbids a body
   */
  public Stream startStream(String contentType) throws IOException {
    require(State.OPEN);
    if (eventStreamOnly && !EVENT_STREAM_CONTENT_TYPE.equals(Objects.requireNonNull(contentType))) {
      throw new IllegalStateException("SSE route requires an event stream");
    }

    if (gating || body.length != 0 || fileSelected || !permitsBody()) {
      throw new IllegalStateException("Response cannot become a stream");
    }

    contentType(contentType);
    state = State.STREAMING;
    stream = new Stream();
    write(false, EMPTY, 0);
    return stream;
  }

  /**
   * Starts framework-owned streaming with typed media metadata. Byte writes are unchanged;
   * Stream.write(String) always encodes UTF-8 regardless of the declared charset.
   *
   * @param contentType response media type
   * @return the handler-scoped stream
   * @throws NullPointerException if contentType is null
   * @throws IOException if the initial write fails
   * @throws IllegalStateException if invoked from a gate, finite output is pending, or the status
   *     forbids a body
   */
  public Stream startStream(MediaType contentType) throws IOException {
    return startStream(Objects.requireNonNull(contentType).value());
  }

  /**
   * Starts a handler-scoped UTF-8 server-sent event stream. The framework completes it when the
   * handler returns.
   *
   * @return the event writer owned by this handler thread
   * @throws IOException if initial output fails
   * @throws IllegalStateException if the response cannot become a stream
   */
  public EventStream startEventStream() throws IOException {
    return new EventStream(startStream(EVENT_STREAM_CONTENT_TYPE));
  }

  /** Requires SSE-compatible streaming while the registered SSE handler owns this response. */
  void requireEventStream() {
    require(State.OPEN);
    eventStreamOnly = true;
  }

  /**
   * Reports whether the transport has committed the status and response fields.
   *
   * @return false for staged finite output, true after a stream starts successfully
   * @throws IllegalStateException if accessed outside the response thread or lifetime
   */
  public boolean isCommitted() {
    checkReadable();
    return delegate.isCommitted();
  }

  /**
   * Reads the current status while the handler owns the response.
   *
   * @return staged or committed status code
   * @throws IllegalStateException if accessed outside the response thread or lifetime
   */
  public int status() {
    checkReadable();
    int code = delegate.getStatus();
    return code == 0 ? HttpStatusCodes.OK.value() : code;
  }

  /**
   * Reads the first response header value without interpreting its contents.
   *
   * @param name case-insensitive field name
   * @return first value, or null when absent
   * @throws IllegalStateException if accessed outside the response thread or lifetime
   */
  public @Nullable String header(String name) {
    checkReadable();
    return delegate.getHeaders().get(Objects.requireNonNull(name));
  }

  /**
   * Copies all values for a field, retaining separate lines and their original order.
   *
   * @param name case-insensitive field name
   * @return immutable values, empty when absent
   * @throws IllegalStateException if accessed outside the response thread or lifetime
   */
  public List<String> headers(String name) {
    checkReadable();
    return List.copyOf(delegate.getHeaders().getValuesList(Objects.requireNonNull(name)));
  }

  /**
   * Takes an immutable, case-insensitive snapshot of the current response fields. Values are not
   * split or combined at commas, including Set-Cookie fields.
   *
   * @return snapshot with immutable lists in field occurrence order
   * @throws IllegalStateException if accessed outside the response thread or lifetime
   */
  public Map<String, List<String>> headerMap() {
    checkReadable();
    Map<String, List<String>> fields = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (var field : delegate.getHeaders()) {
      fields.computeIfAbsent(field.getName(), ignored -> new ArrayList<>()).add(field.getValue());
    }
    fields.replaceAll((name, values) -> List.copyOf(values));
    return Collections.unmodifiableMap(fields);
  }

  /**
   * Sets the final response status before commitment.
   *
   * @param code status between 200 and 599
   * @return this response
   * @throws IllegalArgumentException if the status is outside the supported range
   */
  public Response status(int code) {
    require(State.OPEN);
    if (code < 200 || code > 599) {
      throw new IllegalArgumentException("Expected final HTTP status");
    }

    delegate.setStatus(code);
    return this;
  }

  /**
   * Keeps this response uncompressed when application-wide compression is enabled. The explicit
   * identity content coding tells Jetty to leave the body unchanged. Call before streaming starts.
   *
   * @return this response
   * @throws IllegalStateException if the response is no longer open for mutation
   */
  public Response disableCompression() {
    return header(HttpHeaders.CONTENT_ENCODING.value(), IDENTITY_ENCODING);
  }

  /**
   * Sets a response header before commitment; framing headers belong to the framework.
   *
   * @param name HTTP field name
   * @param value field value without controls other than horizontal tab
   * @return this response
   * @throws IllegalArgumentException if the name or value is invalid or the field controls framing
   */
  public Response header(String name, String value) {
    require(State.OPEN);
    validateHeaderName(name);
    validateHeaderValue(value);

    delegate.getHeaders().put(name, value);
    return this;
  }

  /**
   * Appends a separate response field without replacing existing values.
   *
   * @param name HTTP field name
   * @param value field value without controls other than horizontal tab
   * @return this response
   * @throws IllegalArgumentException if the name or value is invalid or the field controls framing
   */
  public Response addHeader(String name, String value) {
    require(State.OPEN);
    validateHeaderName(name);
    validateHeaderValue(value);

    delegate.getHeaders().add(name, value);
    return this;
  }

  /**
   * Removes every occurrence of a response field before commitment.
   *
   * @param name case-insensitive field name
   * @return this response, also when the field was absent
   * @throws IllegalArgumentException if the name is invalid or controls HTTP framing
   */
  public Response removeHeader(String name) {
    require(State.OPEN);
    validateHeaderName(name);

    delegate.getHeaders().remove(name);
    return this;
  }

  /**
   * Stages a root-path, host-only session cookie with an already encoded value.
   *
   * @param name case-sensitive cookie name
   * @param value cookie value
   * @return this response
   */
  public Response cookie(String name, String value) {
    return cookie(new Cookie(name, value));
  }

  /**
   * Stages a validated cookie, replacing fields with the same name, domain and path. Other scopes
   * and malformed raw Set-Cookie fields remain unchanged. Replacement is appended last.
   *
   * @param cookie cookie to send
   * @return this response
   * @throws IllegalStateException if response metadata is no longer writable
   */
  public Response cookie(Cookie cookie) {
    require(State.OPEN);
    var value = Objects.requireNonNull(cookie).headerValue();
    var fields = delegate.getHeaders().listIterator();
    while (fields.hasNext()) {
      var field = fields.next();
      if (HttpHeaders.SET_COOKIE.equalsIgnoreCase(field.getName())
          && matchesCookie(field, cookie)) {
        fields.remove();
      }
    }
    delegate.getHeaders().add(HttpHeaders.SET_COOKIE.value(), value);
    return this;
  }

  /**
   * Deletes a root-path, host-only cookie. Prefix-protected cookies require the Cookie overload.
   *
   * @param name cookie name
   * @return this response
   */
  public Response removeCookie(String name) {
    return removeCookie(name, HttpCharacters.PATH_SEPARATOR_STRING, null);
  }

  /**
   * Deletes the explicitly selected scope; request cookies do not reveal their original scope.
   *
   * @param name cookie name
   * @param path original root-relative path
   * @param domain original domain, or null for host-only
   * @return this response
   */
  public Response removeCookie(String name, String path, @Nullable String domain) {
    return removeCookie(new Cookie(name, "").withPath(path).withDomain(domain));
  }

  /**
   * Deletes this cookie's scope while retaining its Secure, HttpOnly and SameSite attributes.
   *
   * @param cookie cookie whose scope and security attributes must be preserved
   * @return this response
   */
  public Response removeCookie(Cookie cookie) {
    return cookie(Objects.requireNonNull(cookie).expired());
  }

  /**
   * Stages a 302 redirect, replacing the selected representation while preserving other fields.
   * Does not commit output or terminate the current handler; a gate must reject explicitly.
   *
   * @param location nonempty URI reference; Unicode components are escaped to ASCII
   * @return this response
   * @throws IllegalArgumentException if the destination is malformed or lacks an HTTP host
   */
  public Response redirect(String location) {
    return redirect(location, HttpStatusCodes.FOUND);
  }

  /**
   * Stages a navigation redirect with an explicit status, replacing the selected representation.
   * The handler continues and may override the staged response before it returns. Network
   * destinations require a host, no user information and a port at most 65535. Relative references
   * and non-HTTP schemes are accepted; destination authorization belongs to application code.
   *
   * @param location nonempty URI reference; Unicode components are escaped to ASCII
   * @param code navigation status: 300, 301, 302, 303, 307 or 308
   * @return this response
   * @throws IllegalArgumentException if the status or destination is invalid
   */
  public Response redirect(String location, HttpStatusCodes code) {
    require(State.OPEN);
    Objects.requireNonNull(code);
    switch (code) {
      case MULTIPLE_CHOICES,
          MOVED_PERMANENTLY,
          FOUND,
          SEE_OTHER,
          TEMPORARY_REDIRECT,
          PERMANENT_REDIRECT -> {
        // These statuses permit Location-based navigation.
      }
      default -> throw new IllegalArgumentException("Expected navigation redirect status");
    }
    var destination = URI.create(Objects.requireNonNull(location));
    boolean http =
        "http".equalsIgnoreCase(destination.getScheme())
            || "https".equalsIgnoreCase(destination.getScheme());
    if (location.isEmpty()
        || ((http || destination.getRawAuthority() != null)
            && (destination.getHost() == null
                || destination.getRawUserInfo() != null
                || destination.getPort() > 65535))) {
      throw new IllegalArgumentException("Invalid redirect destination");
    }

    header(HttpHeaders.LOCATION.value(), destination.toASCIIString());
    status(code.value());
    if (fileSelected) {
      resourceContent = null;
      closeResourceChannel();
      fileSelected = false;
      headContentLength = -1;
      delegate.getHeaders().remove(HttpHeaders.ETAG.value());
      delegate.getHeaders().remove(HttpHeaders.LAST_MODIFIED.value());
      delegate.getHeaders().remove(HttpHeaders.ACCEPT_RANGES.value());
      delegate.getHeaders().remove(HttpHeaders.CONTENT_DISPOSITION.value());
    }

    delegate.getHeaders().remove(HttpHeaders.CONTENT_TYPE.value());
    delegate.getHeaders().remove(HttpHeaders.CONTENT_LENGTH.value());
    delegate.getHeaders().remove(HttpHeaders.CONTENT_RANGE.value());
    body = EMPTY;
    return this;
  }

  /**
   * Prevents pre-route callbacks from committing a stream while still allowing finite staging.
   *
   * @param active whether the framework is invoking route gates
   */
  void gating(boolean active) {
    gating = active;
  }

  /**
   * Replaces uncommitted application output with a safe status reason phrase and completes it. The
   * caller must check commitment first. A reason phrase exceeding the body limit is omitted.
   *
   * @param status HTTP error status to send
   * @throws IOException if response completion fails
   */
  void error(HttpStatusCodes status) throws IOException {
    reset(status);
    delegate.getHeaders().put(TEXT);
    var message = status.reasonPhrase().getBytes(StandardCharsets.UTF_8);
    body = message.length <= options.maxResponseBytes() ? message : EMPTY;
    complete();
  }

  /**
   * Clears staged output before error handling while retaining required cache variation. The caller
   * must reject committed/terminal output.
   *
   * @param status initial error status, overridable by the application callback
   */
  void reset(HttpStatusCodes status) {
    var vary =
        (negotiatedByAccept || requiredCorsHeaders != null || compressionEnabled)
            ? List.copyOf(delegate.getHeaders().getValuesList(HttpHeaders.VARY.value()))
            : List.<String>of();
    var sessionCookies = sessionCookies();
    delegate.getHeaders().clear();
    for (var value : vary) {
      delegate.getHeaders().add(HttpHeaders.VARY.value(), value);
    }
    for (var value : sessionCookies) {
      delegate.getHeaders().add(HttpHeaders.SET_COOKIE.value(), value);
    }

    state = State.OPEN;
    body = EMPTY;
    stream = null;
    eventStreamOnly = false;
    resourceContent = null;
    closeResourceChannel();
    fileSelected = false;
    requiredAllow = null;
    afterFlushFailure = null;
    headContentLength = -1;
    status(status.value());
  }

  /**
   * Retains only Jetty's current session cookie when an error discards application output.
   *
   * @return session cookie values already set by Jetty
   */
  private List<String> sessionCookies() {
    if (request == null) {
      return List.of();
    }

    var session = request.getSession(false);
    if (!(session instanceof ManagedSession managed)
        || !(managed.getSessionManager() instanceof AbstractSessionManager manager)) {
      return List.of();
    }

    var name = manager.getSessionCookie() + "=";
    return delegate.getHeaders().getValuesList(HttpHeaders.SET_COOKIE.value()).stream()
        .filter(value -> value.startsWith(name))
        .toList();
  }

  /**
   * Replaces admission-stage output with an empty preflight while retaining ordinary headers.
   * Releases any staged file transfer before selecting the bodyless response.
   */
  void preflight() {
    require(State.OPEN);
    body = EMPTY;
    resourceContent = null;
    closeResourceChannel();
    fileSelected = false;
    headContentLength = -1;
    delegate.getHeaders().remove(HttpHeaders.CONTENT_TYPE.value());
    delegate.getHeaders().remove(HttpHeaders.CONTENT_LENGTH.value());
    delegate.getHeaders().remove(HttpHeaders.CONTENT_RANGE.value());
    status(HttpStatusCodes.NO_CONTENT.value());
  }

  /**
   * Temporarily authorizes framework-owned closure after handler return or error handling. The
   * authorization is revoked even when completion fails.
   *
   * @throws IOException if response completion fails
   */
  void complete() throws IOException {
    frameworkClosing = true;

    try {
      close();
    } finally {
      frameworkClosing = false;
    }
  }

  /**
   * Completes finite output asynchronously or finishes streaming with a blocking final write.
   * Application access ends before asynchronous submission; only the framework may invoke closure.
   *
   * @throws IllegalStateException if called by application code, from another thread, or with a
   *     body forbidden by the selected status
   * @throws IOException if output already terminated or the final streaming write fails
   */
  @Override
  public void close() throws IOException {
    if (!frameworkClosing || flushCallback || Thread.currentThread() != owner) {
      throw new IllegalStateException("The framework owns the response lifecycle");
    }

    if (state == State.OPEN) {
      if (resourceContent != null || resourceChannel != null) {
        completeResource();
        return;
      }

      if (!permitsBody() && !head && body.length != 0) {
        throw new IllegalStateException("Status does not allow a body");
      }

      if (permitsBody() && !head) {
        delegate.getHeaders().put(HttpHeader.CONTENT_LENGTH, body.length);
      } else if (head && headContentLength >= 0) {
        delegate.getHeaders().put(HttpHeader.CONTENT_LENGTH, headContentLength);
      } else if (head && body.length > 0) {
        delegate.getHeaders().put(HttpHeader.CONTENT_LENGTH, body.length);
      }

      notifyBeforeFlush(true, body.length);
      // End application access before handing the buffer to a potentially asynchronous write.
      state = State.CLOSED;
      protectRequiredHeaders();
      var bytes = ByteBuffer.wrap(body);
      body = EMPTY;
      var flushing = new FlushCompletion();
      if (head && compressionEnabled && permitsBody()) {
        Content.Sink.write(delegate, false, ByteBuffer.wrap(EMPTY));
      }

      delegate.write(true, bytes, flushing);
      flushing.afterFlush();
    } else if (state == State.STREAMING) {
      var activeStream = Objects.requireNonNull(stream);
      write(true, activeStream.buffer, activeStream.used);
      activeStream.used = 0;
      body = EMPTY;
      state = State.CLOSED;
      completion.succeeded();
    } else {
      throw new IOException("Response already terminated");
    }
  }

  /**
   * Reports whether replacing output with an error would violate the response lifecycle. Terminal
   * submissions count as committed even before the transport exposes commitment.
   *
   * @return true if headers are committed or output has reached a terminal state
   */
  boolean committed() {
    return delegate.isCommitted() || state == State.CLOSED || state == State.FAILED;
  }

  /**
   * Revokes application access and drops staged finite output without flushing pending stream
   * bytes.
   */
  void fail() {
    closeResourceChannel();
    body = EMPTY;
    state = State.FAILED;
  }

  /**
   * Uses the transport parser to compare explicit cookie scopes without parsing attributes locally.
   *
   * @param field existing Set-Cookie field
   * @param cookie replacement cookie
   * @return whether the existing field has the same case-sensitive name/path and canonical domain
   */
  private static boolean matchesCookie(HttpField field, Cookie cookie) {
    var previous = COOKIE_PARSER.parse(field.getValue());
    if (previous == null) {
      return false;
    }

    var domain = previous.getDomain();
    if (domain != null && domain.startsWith(".")) {
      domain = domain.substring(1);
    }

    return cookie.name().equals(previous.getName())
        && cookie.path().equals(previous.getPath())
        && (cookie.domain() == null ? domain == null : cookie.domain().equalsIgnoreCase(domain));
  }

  /**
   * Validates the media type and reuses pre-encoded fields for common responses.
   *
   * @param value media type to send
   */
  private void contentType(String value) {
    validateHeaderValue(value);
    var field =
        switch (value) {
          case TEXT_CONTENT_TYPE -> TEXT;
          case "text/plain;charset=utf-8" -> COMPACT_TEXT;
          case "application/json" -> JSON;
          default -> new HttpField(HttpHeader.CONTENT_TYPE, value);
        };
    delegate.getHeaders().put(field);
  }

  /** Adds Accept to Vary once while preserving an existing wildcard or field spelling. */
  private void varyAccept() {
    vary(HttpHeaders.ACCEPT);
  }

  /**
   * Adds one request field to Vary without replacing existing fields or a wildcard.
   *
   * @param header request field controlling the selected response representation
   */
  private void vary(HttpHeaders header) {
    for (var value : delegate.getHeaders().getValuesList(HttpHeaders.VARY.value())) {
      for (var token : value.split(",", -1)) {
        var field = token.trim();
        if ("*".equals(field) || header.equalsIgnoreCase(field)) {
          return;
        }
      }
    }

    delegate.getHeaders().add(HttpHeaders.VARY.value(), header.value());
  }

  /** Marks a successful Accept decision so generated error responses retain their Vary fields. */
  private void variedByAccept() {
    varyAccept();
    negotiatedByAccept = true;
  }

  /**
   * Validates a mutable application field name while reserving framing for the framework.
   *
   * @param name proposed field name
   * @throws IllegalArgumentException if the name is invalid or controls HTTP framing
   */
  private static void validateHeaderName(String name) {
    Objects.requireNonNull(name);
    if (!HEADER_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Invalid header");
    }

    if (HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)
        || HttpHeaders.TRANSFER_ENCODING.equalsIgnoreCase(name)) {
      throw new IllegalArgumentException("HTTP framing is managed by the framework");
    }
  }

  /**
   * Produces a safe dual-form attachment field under RFC 6266 and RFC 8187 conventions.
   *
   * @param filename caller-supplied attachment filename
   * @return complete Content-Disposition value
   * @throws IllegalArgumentException if the filename is empty or unsafe
   */
  private static String attachmentDisposition(String filename) {
    var value = Objects.requireNonNull(filename);
    if (value.isEmpty()) {
      throw new IllegalArgumentException("Attachment filename cannot be empty");
    }

    var fallback = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      var character = value.charAt(index);
      if (character < 0x20 || character == 0x7f || character == '/' || character == '\\') {
        throw new IllegalArgumentException("Attachment filename contains an unsafe character");
      }

      fallback.append(character >= 0x20 && character <= 0x7e && character != '"' ? character : '_');
    }

    return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encodedFilename(value);
  }

  /**
   * Percent-encodes non-attribute UTF-8 octets for the extended attachment filename.
   *
   * @param filename validated attachment filename
   * @return RFC 8187 encoded filename value
   */
  private static String encodedFilename(String filename) {
    var encoded = new StringBuilder();
    for (var value : filename.getBytes(StandardCharsets.UTF_8)) {
      var octet = Byte.toUnsignedInt(value);
      if ((octet >= 'a' && octet <= 'z')
          || (octet >= 'A' && octet <= 'Z')
          || (octet >= '0' && octet <= '9')
          || "!#$&+-.^_`|~".indexOf(octet) >= 0) {
        encoded.append((char) octet);
      } else {
        encoded.append('%').append(Character.toUpperCase(Character.forDigit(octet >>> 4, 16)));
        encoded.append(Character.toUpperCase(Character.forDigit(octet & 0xf, 16)));
      }
    }

    return encoded.toString();
  }

  /**
   * Rejects null values and invalid HTTP controls before values reach the transport.
   *
   * @param value proposed HTTP field value
   * @throws IllegalArgumentException if the value contains a control other than horizontal tab
   */
  private static void validateHeaderValue(String value) {
    Objects.requireNonNull(value);
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if ((character < 0x20 && character != '\t') || character == 0x7f) {
        throw new IllegalArgumentException("Invalid header");
      }
    }
  }

  /**
   * Checks status-specific body restrictions; the transport separately suppresses HEAD response
   * bytes.
   *
   * @return false for 204, 205, or 304 responses
   */
  private boolean permitsBody() {
    return delegate.getStatus() != 204
        && delegate.getStatus() != 205
        && delegate.getStatus() != 304;
  }

  /**
   * Identifies response statuses for which conditional request fields can change the outcome.
   *
   * @return whether the response is an eligible successful representation response
   */
  private boolean conditionalStatus() {
    return (status() >= 200 && status() < 300)
        || status() == HttpStatusCodes.PRECONDITION_FAILED.value();
  }

  /**
   * Copies transport-generated file validators into the selected representation response.
   *
   * @param content Jetty resource metadata
   * @param lastModified selected resource modification time
   */
  private void resourceHeaders(HttpContent content, @Nullable Instant lastModified) {
    if (delegate.getHeaders().get(HttpHeaders.ETAG.value()) == null && content.getETag() != null) {
      delegate.getHeaders().put(content.getETag());
    }

    if (lastModified != null) {
      delegate
          .getHeaders()
          .put(HttpHeaders.LAST_MODIFIED.value(), HttpDateTime.format(lastModified));
    }
  }

  /**
   * Clamps resource metadata to the selected response date because a future Last-Modified value is
   * invalid for conditional comparison and caching.
   *
   * @param lastModified resource modification time
   * @param date response date
   * @return a usable modification time, or null when unavailable
   */
  private static @Nullable Instant effectiveLastModified(
      @Nullable Instant lastModified, Instant date) {
    if (lastModified == null) {
      return null;
    }

    return lastModified.isAfter(date) ? date : lastModified;
  }

  /**
   * Gets or establishes the file response Date field used as the validator-generation boundary.
   *
   * @return response origination date
   */
  private Instant responseDate() {
    var value = delegate.getHeaders().get(HttpHeaders.DATE.value());
    var epoch = value == null ? -1 : HttpDateTime.parseToEpoch(value);
    if (epoch >= 0) {
      return Instant.ofEpochMilli(epoch);
    }

    var date = Instant.now();
    delegate.getHeaders().put(HttpHeaders.DATE.value(), HttpDateTime.format(date));
    return date;
  }

  /**
   * Returns the transport request required by conditional and range file handling.
   *
   * @return the request retained for the selected file response
   */
  private org.eclipse.jetty.server.Request request() {
    return Objects.requireNonNull(request);
  }

  /**
   * Creates a weak descriptor-relative entity tag without reading a mutable filesystem pathname.
   *
   * @param length selected representation length
   * @param lastModified selected effective modification time
   * @return valid weak entity tag
   */
  private static String resourceTag(long length, Instant lastModified) {
    return "W/\"" + length + "-" + lastModified.toEpochMilli() + "\"";
  }

  /**
   * Applies strong If-Match comparison before every lower-precedence precondition.
   *
   * @param tag selected entity tag
   * @return whether the request passes the precondition
   */
  private boolean matchesIfMatch(@Nullable String tag) {
    var values = request().getHeaders().getValuesList(HttpHeaders.IF_MATCH.value());
    if (values.isEmpty()) {
      return true;
    }

    if (entityTags(values).contains("*")) {
      return true;
    }

    if (tag == null || tag.startsWith("W/")) {
      return false;
    }

    for (var candidate : entityTags(values)) {
      if (!candidate.startsWith("W/") && candidate.equals(tag)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Applies If-Unmodified-Since only when the higher-precedence If-Match field is absent.
   *
   * @param lastModified selected resource modification time
   * @return whether the request passes the precondition
   */
  private boolean unmodifiedSince(@Nullable Instant lastModified) {
    if (lastModified == null
        || !request().getHeaders().getValuesList(HttpHeaders.IF_MATCH.value()).isEmpty()) {
      return true;
    }

    var values = request().getHeaders().getValuesList(HttpHeaders.IF_UNMODIFIED_SINCE.value());
    if (values.size() != 1) {
      return true;
    }

    var date = HttpDateTime.parseToEpoch(values.getFirst());
    return date < 0 || lastModified.getEpochSecond() <= date / 1000;
  }

  /**
   * Applies weak If-None-Match comparison for the selected file representation.
   *
   * @param tag selected entity tag
   * @return whether any request tag matches
   */
  private boolean noneMatch(@Nullable String tag) {
    if (tag == null) {
      return false;
    }

    for (var candidate :
        entityTags(request().getHeaders().getValuesList(HttpHeaders.IF_NONE_MATCH.value()))) {
      if ("*".equals(candidate) || weakTag(candidate).equals(weakTag(tag))) {
        return true;
      }
    }

    return false;
  }

  /**
   * Applies If-Modified-Since only when ETag-based validation was not requested.
   *
   * @param lastModified selected resource modification time
   * @return whether the representation is unchanged
   */
  private boolean notModifiedSince(@Nullable Instant lastModified) {
    if (lastModified == null
        || !request().getHeaders().getValuesList(HttpHeaders.IF_NONE_MATCH.value()).isEmpty()
        || (!HttpMethods.GET.value().equals(request().getMethod())
            && !HttpMethods.HEAD.value().equals(request().getMethod()))) {
      return false;
    }

    var values = request().getHeaders().getValuesList(HttpHeaders.IF_MODIFIED_SINCE.value());
    if (values.size() != 1) {
      return false;
    }

    var date = HttpDateTime.parseToEpoch(values.getFirst());
    return date >= 0 && lastModified.getEpochSecond() <= date / 1000;
  }

  /**
   * Removes a weak marker so cache validation compares the opaque tag value.
   *
   * @param value entity tag value
   * @return opaque-tag representation
   */
  private static String weakTag(String value) {
    return value.startsWith("W/") ? value.substring(2) : value;
  }

  /**
   * Validates the limited entity-tag syntax accepted for application-supplied validators.
   *
   * @param value candidate entity tag
   * @return whether the value is a strong or weak quoted entity tag
   */
  private static boolean entityTag(String value) {
    var start = value.startsWith("W/") ? 2 : 0;
    if (value.length() <= start + 1
        || value.charAt(start) != '"'
        || value.charAt(value.length() - 1) != '"') {
      return false;
    }

    for (int index = start + 1; index < value.length() - 1; index++) {
      var character = value.charAt(index);
      if (character == '"'
          || character == ' '
          || character < 0x21
          || character == 0x7f
          || character > 0xff) {
        return false;
      }
    }

    return true;
  }

  /**
   * Splits entity-tag list members without treating quoted opaque commas as list delimiters.
   *
   * @param values field values containing entity-tag lists
   * @return parsed list members
   */
  private static List<String> entityTags(List<String> values) {
    var tags = new ArrayList<String>();
    var current = new StringBuilder();
    var quoted = false;
    for (var value : values) {
      for (int index = 0; index < value.length(); index++) {
        var character = value.charAt(index);
        if (character == '"') {
          quoted = !quoted;
        }

        if (character == ',' && !quoted) {
          tags.add(current.toString().trim());
          current.setLength(0);
        } else {
          current.append(character);
        }
      }

      if (!current.isEmpty()) {
        tags.add(current.toString().trim());
        current.setLength(0);
      }
    }

    return tags;
  }

  /**
   * Completes a selected file representation without staging its bytes in a Java array.
   *
   * @throws IOException if the transport cannot complete the response
   */
  private void completeResource() throws IOException {
    notifyBeforeFlush(true, resourceLength);
    state = State.CLOSED;
    protectRequiredHeaders();
    var flushing = new FlushCompletion();
    if (head || !permitsBody()) {
      closeResourceChannel();
      if (head && compressionEnabled && permitsBody()) {
        Content.Sink.write(delegate, false, ByteBuffer.wrap(EMPTY));
      }

      delegate.write(true, ByteBuffer.wrap(EMPTY), flushing);
      flushing.afterFlush();
      return;
    }

    var channel = resourceChannel;
    if (channel != null) {
      Content.Source source;

      try {
        channel.position(resourceOffset);
        source = Content.Source.from(ByteBufferPool.SIZED_NON_POOLING, channel, 0, resourceLength);
      } catch (IOException | RuntimeException failure) {
        closeResourceChannel();
        throw failure;
      }

      resourceChannel = null;
      Content.copy(source, delegate, flushing);
    } else {
      var content = Objects.requireNonNull(resourceContent);
      if (!content.getResource().exists() || !content.getResource().isReadable()) {
        completion.failed(new IOException("Selected file is no longer readable"));
        return;
      }

      content.writeTo(delegate, resourceOffset, resourceLength, flushing);
    }

    flushing.afterFlush();
  }

  /** Closes an unsubmitted filesystem descriptor without masking response cleanup. */
  private void closeResourceChannel() {
    var channel = resourceChannel;
    resourceChannel = null;
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException _) {
        // Response cleanup cannot safely report an unsubmitted descriptor close failure.
      }
    }
  }

  /**
   * Parses the one supported satisfiable byte interval, ignoring unsupported Range syntax.
   *
   * @param length full representation length
   * @param tag selected entity tag
   * @return selected range, unsatisfiable marker, or null when Range is ignored
   */
  private @Nullable ByteRange range(long length, @Nullable String tag) {
    if (head
        || status() != HttpStatusCodes.OK.value()
        || !HttpMethods.GET.value().equals(request().getMethod())) {
      return null;
    }

    if (!ifRangeMatches(tag)) {
      return null;
    }

    var values = request().getHeaders().getValuesList(HttpHeaders.RANGE.value());
    if (values.size() != 1
        || !values.getFirst().startsWith("bytes=")
        || values.getFirst().contains(",")) {
      return null;
    }

    var value = values.getFirst().substring("bytes=".length());
    var separator = value.indexOf('-');
    if (separator < 0 || separator != value.lastIndexOf('-')) {
      return null;
    }

    try {
      if (separator == 0) {
        return suffixRange(length, value.substring(1));
      }

      return explicitRange(length, value.substring(0, separator), value.substring(separator + 1));
    } catch (NumberFormatException _) {
      return null;
    }
  }

  /**
   * Parses a suffix interval after its leading hyphen.
   *
   * @param length full representation length
   * @param suffixText digits after the hyphen
   * @return selected range, unsatisfiable marker, or null for invalid syntax
   */
  private static @Nullable ByteRange suffixRange(long length, String suffixText) {
    if (!decimal(suffixText)) {
      return null;
    }

    var suffix = Long.parseLong(suffixText);
    if (suffix <= 0 || length == 0) {
      return UNSATISFIABLE_RANGE;
    }

    return new ByteRange(Math.max(0, length - suffix), length - 1);
  }

  /**
   * Parses a first-last interval, including an omitted final position.
   *
   * @param length full representation length
   * @param firstText first byte position
   * @param lastText final byte position, or empty for the end of the representation
   * @return selected range, unsatisfiable marker, or null for invalid syntax
   */
  private static @Nullable ByteRange explicitRange(long length, String firstText, String lastText) {
    if (!decimal(firstText) || (!lastText.isEmpty() && !decimal(lastText))) {
      return null;
    }

    var first = Long.parseLong(firstText);
    if (lastText.isEmpty() && first >= length) {
      return UNSATISFIABLE_RANGE;
    }

    var last = lastText.isEmpty() ? length - 1 : Long.parseLong(lastText);
    if (last < first) {
      return null;
    }

    if (first >= length) {
      return UNSATISFIABLE_RANGE;
    }

    return new ByteRange(first, Math.min(last, length - 1));
  }

  /**
   * Verifies that an HTTP numeric field uses only ASCII decimal digits.
   *
   * @param value candidate decimal text
   * @return whether the text is a nonempty unsigned decimal integer
   */
  private static boolean decimal(String value) {
    if (value.isEmpty()) {
      return false;
    }

    for (int index = 0; index < value.length(); index++) {
      if (value.charAt(index) < '0' || value.charAt(index) > '9') {
        return false;
      }
    }

    return true;
  }

  /**
   * Applies a strong entity-tag If-Range validator to the one supported transfer.
   *
   * @param tag selected entity tag
   * @return whether Range can select a partial representation
   */
  private boolean ifRangeMatches(@Nullable String tag) {
    var values = request().getHeaders().getValuesList(HttpHeaders.IF_RANGE.value());
    if (values.isEmpty()) {
      return true;
    }

    if (values.size() != 1) {
      return false;
    }

    var validator = values.getFirst();
    return tag != null && !tag.startsWith("W/") && validator.equals(tag);
  }

  /**
   * Streams a source or publishes only its known representation metadata for a HEAD request.
   *
   * @param source application input source
   * @param contentType response media type
   * @param knownLength source length when known, otherwise a negative value
   * @return this response
   * @throws IOException if reading or output fails
   * @throws IllegalStateException if file output is already selected
   */
  private Response input(InputStream source, String contentType, long knownLength)
      throws IOException {
    Objects.requireNonNull(source);

    try (source) {
      require(State.OPEN);
      if (fileSelected) {
        throw new IllegalStateException("Response already has file output");
      }

      if (!permitsBody()) {
        return this;
      }

      if (head) {
        contentType(contentType);
        headContentLength = knownLength;
        return this;
      }

      var output = startStream(contentType);
      var buffer = new byte[options.streamBufferBytes()];
      for (int read; (read = source.read(buffer)) >= 0; ) {
        if (read > 0) {
          output.write(read == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, read));
        }
      }

      return this;
    }
  }

  /**
   * Restricts inspection to the live handler thread, including active streams.
   *
   * @throws IllegalStateException if the response is terminal or the thread does not own it
   */
  private void checkReadable() {
    if (Thread.currentThread() != owner
        || (!flushCallback && (state == State.CLOSED || state == State.FAILED))) {
      throw new IllegalStateException("Response is not readable in this phase/thread");
    }
  }

  /**
   * Guards response mutation by both lifecycle phase and handler-thread ownership.
   *
   * @param expected required writable phase
   * @throws IllegalStateException if the current phase or thread does not permit access
   */
  private void require(State expected) {
    if (Thread.currentThread() != owner || flushCallback || state != expected) {
      throw new IllegalStateException("Response is not writable in this phase/thread");
    }
  }

  /**
   * Waits for transport consumption before the caller can reuse the streaming buffer. Any failed
   * write makes the response terminal so error handling cannot replace partial output.
   *
   * @param last whether this write ends the response
   * @param bytes source buffer owned by the response
   * @param length number of bytes to send from the start of the buffer
   * @throws IOException if the transport cannot complete the write
   */
  private void write(boolean last, byte[] bytes, int length) throws IOException {
    notifyBeforeFlush(last, length);

    try {
      protectRequiredHeaders();
      Content.Sink.write(delegate, last, ByteBuffer.wrap(bytes, 0, length));
      notifyAfterFlush();
    } catch (IOException | RuntimeException failure) {
      state = State.FAILED;
      throw failure;
    }
  }

  /**
   * Runs the pre-flush callback while prohibiting reentrant response mutation.
   *
   * @param last whether this flush ends the response
   * @param length bytes supplied by this flush when no Content-Length is staged
   * @throws NotAcceptableException if no acceptable content coding is available before commitment
   */
  private void notifyBeforeFlush(boolean last, long length) {
    flushCallback = true;

    try {
      beforeFlush.run();
    } catch (RuntimeException failure) {
      beforeFlushFailed = true;
      throw failure;
    } finally {
      flushCallback = false;
    }

    if (compressionEnabled
        && delegate.getStatus() == HttpStatusCodes.PARTIAL_CONTENT.value()
        && delegate.getHeaders().contains(HttpHeaders.CONTENT_RANGE.value())
        && !delegate.getHeaders().contains(HttpHeaders.CONTENT_ENCODING.value())) {
      delegate.getHeaders().put(HttpHeaders.CONTENT_ENCODING.value(), IDENTITY_ENCODING);
    }

    var contentEncoding = delegate.getHeaders().get(HttpHeaders.CONTENT_ENCODING.value());
    rejectNativeIdentity(last, length, contentEncoding);
    if (((encodingUnacceptable && contentEncoding == null)
            || (identityUnacceptable && IDENTITY_ENCODING.equalsIgnoreCase(contentEncoding)))
        && permitsBody()
        && (delegate.getStatus() == 0
            || (delegate.getStatus() >= 200 && delegate.getStatus() < 300))
        && !delegate.isCommitted()) {
      encodingRejected = true;
      throw new NotAcceptableException();
    }

    if (gzipExcludedByAccept
        && !head
        && permitsBody()
        && !delegate.getHeaders().contains(HttpHeaders.CONTENT_ENCODING.value())) {
      delegate.getHeaders().put(HttpHeaders.CONTENT_ENCODING.value(), IDENTITY_ENCODING);
    }

    if (compressionEnabled && head) {
      delegate.getHeaders().remove(HttpHeaders.CONTENT_LENGTH.value());
      vary(HttpHeaders.ACCEPT_ENCODING);
      if (request != null
          && request.getConnectionMetaData().getHttpVersion() == HttpVersion.HTTP_1_1) {
        delegate.getHeaders().put(HttpHeader.TRANSFER_ENCODING, "chunked");
      }
    }
  }

  /**
   * Rejects an implicit identity body when Jetty's selected compressor will leave it unchanged.
   *
   * @param last whether this flush ends the response
   * @param length bytes supplied by this flush when no Content-Length is staged
   * @param contentEncoding current explicit coding, or null
   * @throws NotAcceptableException if Jetty would emit explicitly rejected identity bytes
   */
  private void rejectNativeIdentity(boolean last, long length, @Nullable String contentEncoding) {
    if (!compressionEnabled
        || !identityUnacceptable
        || head
        || contentEncoding != null
        || !permitsBody()
        || (delegate.getStatus() != 0
            && (delegate.getStatus() < 200 || delegate.getStatus() >= 300))
        || delegate.isCommitted()) {
      return;
    }

    var handler = Objects.requireNonNull(compressionHandler);
    var selected = org.eclipse.jetty.server.Response.as(delegate, CompressionResponse.class);
    if (selected == null) {
      encodingRejected = true;
      throw new NotAcceptableException();
    }

    var mappings = handler.getBean(PathMappings.class);
    var match =
        Objects.requireNonNull(mappings)
            .getMatched(org.eclipse.jetty.server.Request.getPathInContext(selected.getRequest()));
    var config = (CompressionConfig) Objects.requireNonNull(match).getResource();
    var contentType = delegate.getHeaders().get(HttpHeaders.CONTENT_TYPE.value());
    if (contentType != null
        && !config.isCompressMimeTypeSupported(
            MimeTypes.getContentTypeWithoutCharset(contentType))) {
      encodingRejected = true;
      throw new NotAcceptableException();
    }

    long contentLength = delegate.getHeaders().getLongField(HttpHeader.CONTENT_LENGTH);
    if (contentLength < 0 && last) {
      contentLength = length;
    }

    if (contentLength >= 0) {
      var encoder = selectedCompressor(handler, config, selected.getRequest());
      if (encoder == null || contentLength < encoder.getMinCompressSize()) {
        encodingRejected = true;
        throw new NotAcceptableException();
      }
    }
  }

  /**
   * Resolves the native compressor selected by Jetty's encoding and preference rules.
   *
   * @param handler active native compression handler
   * @param config native configuration matched for the outer request path
   * @param request request at the compression handler's context boundary
   * @return selected native compressor, or null if no registered compressor matches
   */
  private static @Nullable Compression selectedCompressor(
      CompressionHandler handler,
      CompressionConfig config,
      org.eclipse.jetty.server.Request request) {
    var encoders = new TreeMap<String, Compression>(String.CASE_INSENSITIVE_ORDER);
    for (var encoder : handler.getBeans(Compression.class)) {
      encoders.put(encoder.getEncodingName(), encoder);
    }

    var parser = new QuotedQualityCSV();
    for (var value : request.getHeaders().getValuesList(HttpHeader.ACCEPT_ENCODING)) {
      parser.addValue(value);
    }

    var matches = new ArrayList<String>();
    boolean wildcardAccepted = false;
    for (var value : parser.getQualityValues()) {
      var encoding = value.getValue();
      if ("*".equals(encoding)) {
        wildcardAccepted = value.isAcceptable();
      } else if (value.isAcceptable()
          && encoders.containsKey(encoding)
          && compressionEncodingAllowed(config, encoding)) {
        matches.add(encoding);
      }
    }

    var preferred = config.getCompressPreferredEncodings();
    if (matches.isEmpty()) {
      if (!wildcardAccepted) {
        return null;
      }

      String candidate;
      if (preferred.isEmpty()) {
        candidate = encoders.isEmpty() ? null : encoders.firstKey();
      } else {
        candidate = preferred.stream().filter(encoders::containsKey).findFirst().orElse(null);
      }

      return candidate != null && compressionEncodingAllowed(config, candidate)
          ? encoders.get(candidate)
          : null;
    }

    var selected =
        preferred.stream().filter(matches::contains).findFirst().orElse(matches.getFirst());
    return encoders.get(selected);
  }

  /**
   * Applies a native configuration's encoding exclusions before includes.
   *
   * @param config selected native configuration
   * @param encoding candidate content coding
   * @return whether that coding remains enabled
   */
  private static boolean compressionEncodingAllowed(CompressionConfig config, String encoding) {
    return !config.getCompressExcludeEncodings().contains(encoding)
        && (config.getCompressIncludeEncodings().isEmpty()
            || config.getCompressIncludeEncodings().contains(encoding));
  }

  /** Runs the post-flush callback while prohibiting all response mutation. */
  private void notifyAfterFlush() {
    flushCallback = true;

    try {
      afterFlush.run();
    } finally {
      flushCallback = false;
    }
  }

  /**
   * Retains selected CORS fields through error resets and application response mutation.
   *
   * @param headers immutable policy-selected fields, including cache variation
   */
  void corsHeaders(Map<String, String> headers) {
    requiredCorsHeaders = headers;
  }

  /** Restores required routing and authentication fields before bytes can be submitted. */
  private void protectRequiredHeaders() {
    if (requiredCorsHeaders != null) {
      protectCorsHeaders(requiredCorsHeaders);
    }

    if (requiredChallenge != null && delegate.getStatus() == HttpStatusCodes.UNAUTHORIZED.value()) {
      delegate.getHeaders().put(HttpHeaders.WWW_AUTHENTICATE.value(), requiredChallenge);
    }

    if (requiredAllow != null) {
      delegate.getHeaders().put(HttpHeaders.ALLOW.value(), requiredAllow);
    }
  }

  /**
   * Restores only policy-approved sharing grants and merges cache variation without widening it.
   *
   * @param fields immutable CORS decision for this invocation
   */
  private void protectCorsHeaders(Map<String, String> fields) {
    for (var header : CORS_RESPONSE_HEADERS) {
      delegate.getHeaders().remove(header.value());
    }
    for (var entry : fields.entrySet()) {
      if (!HttpHeaders.VARY.equalsIgnoreCase(entry.getKey())) {
        delegate.getHeaders().put(entry.getKey(), entry.getValue());
        continue;
      }

      var existing = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
      for (var value : delegate.getHeaders().getValuesList(HttpHeaders.VARY.value())) {
        for (var token : value.split(",", -1)) {
          existing.add(token.trim());
        }
      }
      if (existing.contains("*")) {
        continue;
      }

      for (var token : entry.getValue().split(",", -1)) {
        var name = token.trim();
        if (existing.add(name)) {
          delegate.getHeaders().add(HttpHeaders.VARY.value(), name);
        }
      }
    }
  }

  /** Coordinates transport completion with the post-flush callback exactly once. */
  private final class FlushCompletion implements Callback {
    private boolean afterFinished;
    private @Nullable Throwable afterFailure;
    private boolean transportFinished;
    private @Nullable Throwable transportFailure;
    private boolean completed;

    /** Marks the response submission as successful from the transport's perspective. */
    @Override
    public synchronized void succeeded() {
      transportFinished = true;
      complete();
    }

    /**
     * Records the response submission failure from the transport.
     *
     * @param failure transport failure
     */
    @Override
    public synchronized void failed(Throwable failure) {
      transportFinished = true;
      transportFailure = failure;
      complete();
    }

    /** Runs post-flush observation and releases the terminal callback once both sides finish. */
    private synchronized void afterFlush() {
      try {
        notifyAfterFlush();
      } catch (RuntimeException failure) {
        afterFailure = failure;
        afterFlushFailure = failure;
      }

      afterFinished = true;
      complete();
    }

    /** Completes the original callback only when submission and observation have both ended. */
    private void complete() {
      if (completed || !afterFinished || !transportFinished) {
        return;
      }

      completed = true;

      if (transportFailure != null) {
        completion.failed(transportFailure);
      } else if (afterFailure != null) {
        completion.failed(afterFailure);
      } else {
        completion.succeeded();
      }
    }
  }

  /** Bounded buffered writes. Full buffers and explicit flushes can emit during the handler. */
  public final class Stream {
    private final byte[] buffer;
    private int used;

    /** Allocates the configured bounded buffer for this handler-scoped stream. */
    private Stream() {
      buffer = new byte[options.streamBufferBytes()];
    }

    /**
     * Writes text as UTF-8, flushing full buffers as needed.
     *
     * @param value text to write
     * @throws IOException if a transport write fails
     */
    public void write(String value) throws IOException {
      write(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Copies bytes into the bounded buffer, blocking on full-buffer writes.
     *
     * @param bytes bytes to write
     * @throws IOException if a transport write fails
     */
    public void write(byte[] bytes) throws IOException {
      require(State.STREAMING);
      Objects.requireNonNull(bytes);
      int offset = 0;
      while (offset < bytes.length) {
        int count = Math.min(buffer.length - used, bytes.length - offset);
        System.arraycopy(bytes, offset, buffer, used, count);
        used += count;
        offset += count;
        if (used == buffer.length) {
          flush();
        }
      }
    }

    /**
     * Sends pending bytes and waits for the transport to finish using the buffer.
     *
     * @throws IOException if the transport write fails
     */
    public void flush() throws IOException {
      require(State.STREAMING);
      Response.this.write(false, buffer, used);
      used = 0;
    }
  }

  /** Writes complete server-sent event frames through the response's bounded stream. */
  public static final class EventStream {
    private final Stream output;

    /**
     * Wraps the response-owned byte stream.
     *
     * @param output bounded response stream
     */
    private EventStream(Stream output) {
      this.output = output;
    }

    /**
     * Sends one UTF-8 data event, including its dispatching blank line.
     *
     * @param data event payload; each line becomes a data field
     * @throws IOException if the transport write fails
     */
    public void send(String data) throws IOException {
      send(SseEvent.of(data));
    }

    /**
     * Sends an event with optional name, ID and retry metadata.
     *
     * @param event immutable event to send
     * @throws IOException if the transport write fails
     */
    public void send(SseEvent event) throws IOException {
      Objects.requireNonNull(event);
      if (event.event() != null) {
        output.write("event: " + event.event() + "\n");
      }

      if (event.id() != null) {
        output.write("id: " + event.id() + "\n");
      }

      if (event.retry() != null) {
        output.write("retry: " + event.retry().toMillis() + "\n");
      }

      writeData(event.data());
      output.flush();
    }

    /**
     * Sends one comment block without dispatching an event.
     *
     * @param value comment text, split safely at every event-stream line ending
     * @throws IOException if the transport write fails
     */
    public void comment(String value) throws IOException {
      writeLines(Objects.requireNonNull(value), ": ");
      output.write("\n");
      output.flush();
    }

    /**
     * Sends a minimal comment heartbeat without dispatching an event.
     *
     * @throws IOException if the transport write fails
     */
    public void heartbeat() throws IOException {
      output.write(":\n\n");
      output.flush();
    }

    /**
     * Writes every payload line as one data field without ending the event until the final line.
     *
     * @param data event payload
     * @throws IOException if the transport write fails
     */
    private void writeData(String data) throws IOException {
      writeLines(data, "data: ");
      output.write("\n");
    }

    /**
     * Normalizes all event-stream line endings and prefixes every line, including a trailing empty
     * line, so payload text cannot create another protocol field.
     *
     * @param value caller-supplied field text
     * @param prefix wire field prefix
     * @throws IOException if the transport write fails
     */
    private void writeLines(String value, String prefix) throws IOException {
      var normalized = value.replace("\r\n", "\n").replace('\r', '\n');
      int start = 0;
      int end;
      while ((end = normalized.indexOf('\n', start)) >= 0) {
        output.write(prefix + normalized.substring(start, end) + "\n");
        start = end + 1;
      }

      output.write(prefix + normalized.substring(start) + "\n");
    }
  }
}
