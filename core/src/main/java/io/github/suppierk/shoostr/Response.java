package io.github.suppierk.shoostr;

import static io.github.suppierk.shoostr.Locks.runUnderLock;

import io.github.suppierk.shoostr.http.Cookie;
import io.github.suppierk.shoostr.http.HttpCharacters;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.http.exceptions.AuthorizationDeniedException;
import io.github.suppierk.shoostr.http.exceptions.NotAcceptableException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
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
public sealed class Response implements AutoCloseable {
  static final String EVENT_STREAM_CONTENT_TYPE = "text/event-stream; charset=utf-8";

  private static final byte[] EMPTY = new byte[0];
  private static final List<HttpHeaders> CORS_RESPONSE_HEADERS =
      List.of(
          HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,
          HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
          HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.ACCESS_CONTROL_MAX_AGE);
  private static final ByteRange UNSATISFIABLE_RANGE = new ByteRange(-1, -1);
  private static final SetCookieParser COOKIE_PARSER = SetCookieParser.newInstance();

  private static final String WEAK_ENTITY_TAG_PREFIX = "W/";
  private static final String TEXT_CONTENT_TYPE = "text/plain; charset=utf-8";
  private static final String IDENTITY_ENCODING = "identity";
  private static final String CHUNKED_ENCODING = "chunked";
  private static final String CLOSE_CONNECTION = "close";
  private static final String BYTES_RANGES = "bytes";

  private static final HttpField TEXT =
      new PreEncodedHttpField(HttpHeaders.CONTENT_TYPE.value(), TEXT_CONTENT_TYPE);
  private static final HttpField COMPACT_TEXT =
      new PreEncodedHttpField(HttpHeaders.CONTENT_TYPE.value(), "text/plain;charset=utf-8");
  private static final HttpField JSON =
      new PreEncodedHttpField(HttpHeaders.CONTENT_TYPE.value(), "application/json");

  private final org.eclipse.jetty.server.Response delegate;
  private final Options options;
  private final Callback completion;
  private final boolean head;
  private final Request request;

  private @Nullable Runnable beforeFlush;
  private @Nullable Runnable afterFlush;
  private State state;
  private byte[] body;
  private @Nullable Stream stream;
  private boolean frameworkClosing;
  private boolean flushCallback;
  private boolean beforeFlushFailed;
  private boolean discardInputBeforeFlush;
  private boolean gating;
  private boolean eventStreamOnly;
  private boolean negotiatedByAccept;
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
  private int requiredChallengeStatus;
  private @Nullable Map<String, String> requiredCorsHeaders;
  private @Nullable Throwable afterFlushFailure;

  /**
   * Creates a response while its request is privately under construction. Retains the peer without
   * reading it; framework dispatch sees only the completed pair.
   *
   * @param delegate native output receiving headers and bytes
   * @param options validated response limits
   * @param completion callback completing the exchange exactly once
   * @param head whether the method suppresses response content
   * @param request the one request being paired with this response
   * @throws NullPointerException if a reference input is null
   */
  Response(
      org.eclipse.jetty.server.Response delegate,
      Options options,
      Callback completion,
      boolean head,
      Request request) {
    this.delegate = Objects.requireNonNull(delegate);
    this.options = Objects.requireNonNull(options);
    this.completion = Objects.requireNonNull(completion);
    this.head = head;
    this.request = Objects.requireNonNull(request);
    this.flushCallback = false;
    this.beforeFlushFailed = false;
    this.discardInputBeforeFlush = false;
    this.headContentLength = -1;
    this.state = State.OPEN;
    this.body = EMPTY;
  }

  /**
   * Resolves the native compressor selected by Jetty's encoding and preference rules.
   *
   * @param handler active native compression handler
   * @param config native configuration matched for the outer request path
   * @param acceptEncoding raw field values at the compression handler's context boundary
   * @return selected native compressor, or null if no registered compressor matches
   */
  private static @Nullable Compression selectedCompressor(
      CompressionHandler handler, CompressionConfig config, List<String> acceptEncoding) {
    final var encoders = new TreeMap<String, Compression>(String.CASE_INSENSITIVE_ORDER);
    for (final var encoder : handler.getBeans(Compression.class)) {
      encoders.put(encoder.getEncodingName(), encoder);
    }

    final var parser = new QuotedQualityCSV();
    for (final var value : acceptEncoding) {
      parser.addValue(value);
    }

    final var matches = new ArrayList<String>();
    var wildcardAccepted = false;
    for (final var value : parser.getQualityValues()) {
      final var encoding = value.getValue();
      if (HttpCharacters.ASTERISK_STRING.equals(encoding)) {
        wildcardAccepted = value.isAcceptable();
      } else if (value.isAcceptable()
          && encoders.containsKey(encoding)
          && compressionEncodingAllowed(config, encoding)) {
        matches.add(encoding);
      }
    }

    if (matches.isEmpty()) {
      return wildcardCompressor(config, encoders, wildcardAccepted);
    }

    final var preferred = config.getCompressPreferredEncodings();
    final var selected =
        preferred.stream().filter(matches::contains).findFirst().orElse(matches.getFirst());

    return encoders.get(selected);
  }

  /**
   * Selects a compressor when the client accepts only a wildcard encoding.
   *
   * @param config selected native compression configuration
   * @param encoders available compressors by encoding name
   * @param wildcardAccepted whether the wildcard has positive quality
   * @return selected compressor, or null when none is permitted
   */
  private static @Nullable Compression wildcardCompressor(
      CompressionConfig config, Map<String, Compression> encoders, boolean wildcardAccepted) {
    if (!wildcardAccepted) {
      return null;
    }

    final var preferred = config.getCompressPreferredEncodings();
    String candidate;
    if (preferred.isEmpty()) {
      candidate = encoders.isEmpty() ? null : encoders.keySet().iterator().next();
    } else {
      candidate = preferred.stream().filter(encoders::containsKey).findFirst().orElse(null);
    }

    return candidate != null && compressionEncodingAllowed(config, candidate)
        ? encoders.get(candidate)
        : null;
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

  /**
   * Produces a safe dual-form attachment field under RFC 6266 and RFC 8187 conventions.
   *
   * @param filename caller-supplied attachment filename
   * @return complete Content-Disposition value
   * @throws IllegalArgumentException if the filename is empty or unsafe
   */
  private static String attachmentDisposition(String filename) {
    final var value = Objects.requireNonNull(filename);
    if (value.isEmpty()) {
      throw new IllegalArgumentException("Attachment filename cannot be empty");
    }

    final var fallback = new StringBuilder(value.length());
    for (var index = 0; index < value.length(); index++) {
      final var character = value.charAt(index);
      if (character < HttpCharacters.SPACE
          || character == HttpCharacters.DELETE_CONTROL
          || character == HttpCharacters.PATH_SEPARATOR
          || character == HttpCharacters.BACKSLASH) {
        throw new IllegalArgumentException("Attachment filename contains an unsafe character");
      }

      fallback.append(
          character <= HttpCharacters.TILDE && character != HttpCharacters.DOUBLE_QUOTE
              ? character
              : HttpCharacters.UNDERSCORE);
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
    final var encoded = new StringBuilder();
    for (final var value : filename.getBytes(StandardCharsets.UTF_8)) {
      final var octet = (char) Byte.toUnsignedInt(value);
      if (HttpCharacters.isValidHttpToken(octet)
          && octet != HttpCharacters.PERCENT_SIGN
          && octet != '\''
          && octet != HttpCharacters.ASTERISK) {
        encoded.append(octet);
      } else {
        encoded
            .append(HttpCharacters.PERCENT_SIGN)
            .append(Character.toUpperCase(Character.forDigit(octet >>> 4, 16)));
        encoded.append(
            Character.toUpperCase(Character.forDigit(octet & HttpCharacters.SHIFT_IN_CONTROL, 16)));
      }
    }

    return encoded.toString();
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
   * Creates a weak descriptor-relative entity tag without reading a mutable filesystem pathname.
   *
   * @param length selected representation length
   * @param lastModified selected resource modification time, independent of the response date
   * @return valid weak entity tag
   */
  private static String resourceTag(long length, Instant lastModified) {
    return WEAK_ENTITY_TAG_PREFIX
        + HttpCharacters.DOUBLE_QUOTE_STRING
        + length
        + HttpCharacters.DASH_STRING
        + lastModified.toEpochMilli()
        + HttpCharacters.DOUBLE_QUOTE_STRING;
  }

  /**
   * Removes a weak marker so cache validation compares the opaque tag value.
   *
   * @param value entity tag value
   * @return opaque-tag representation
   */
  private static String weakTag(String value) {
    return value.startsWith(WEAK_ENTITY_TAG_PREFIX) ? value.substring(2) : value;
  }

  /**
   * Validates the limited entity-tag syntax accepted for application-supplied validators.
   *
   * @param value candidate entity tag
   * @return whether the value is a strong or weak quoted entity tag
   */
  private static boolean entityTag(String value) {
    final var start = value.startsWith(WEAK_ENTITY_TAG_PREFIX) ? 2 : 0;
    if (value.length() <= start + 1
        || value.charAt(start) != HttpCharacters.DOUBLE_QUOTE
        || value.charAt(value.length() - 1) != HttpCharacters.DOUBLE_QUOTE) {
      return false;
    }

    for (var index = start + 1; index < value.length() - 1; index++) {
      final var character = value.charAt(index);
      if (character == HttpCharacters.DOUBLE_QUOTE
          || character < HttpCharacters.EXCLAMATION_MARK
          || character == HttpCharacters.DELETE_CONTROL
          || character > HttpCharacters.MAX_UNSIGNED_BYTE_VALUE) {
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
    final var tags = new ArrayList<String>();
    final var current = new StringBuilder();
    var quoted = false;
    for (final var value : values) {
      for (var index = 0; index < value.length(); index++) {
        final var character = value.charAt(index);
        if (character == HttpCharacters.DOUBLE_QUOTE) {
          quoted = !quoted;
        }

        if (character == HttpCharacters.COMMA_SIGN && !quoted) {
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

    final var suffix = Long.parseLong(suffixText);
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

    final var first = Long.parseLong(firstText);
    final var requestedLast = lastText.isEmpty() ? Long.MAX_VALUE : Long.parseLong(lastText);
    if (requestedLast < first) {
      return null;
    }

    if (first >= length) {
      return UNSATISFIABLE_RANGE;
    }

    return new ByteRange(first, Math.min(requestedLast, length - 1));
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

    for (var index = 0; index < value.length(); index++) {
      if (value.charAt(index) < '0' || value.charAt(index) > '9') {
        return false;
      }
    }

    return true;
  }

  /**
   * Validates a mutable application field name while reserving framing for the framework.
   *
   * @param name proposed field name
   * @throws IllegalArgumentException if the name is invalid or controls HTTP framing
   */
  private static void validateHeaderName(String name) {
    Objects.requireNonNull(name);
    if (!HttpCharacters.isValidHttpToken(name)) {
      throw new IllegalArgumentException("Invalid header");
    }

    if (HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)
        || HttpHeaders.TRANSFER_ENCODING.equalsIgnoreCase(name)) {
      throw new IllegalArgumentException("HTTP framing is managed by the framework");
    }
  }

  /**
   * Rejects null values and invalid HTTP controls before values reach the transport.
   *
   * @param value proposed HTTP field value
   * @throws IllegalArgumentException if the value contains a control other than horizontal tab
   */
  private static void validateHeaderValue(String value) {
    Objects.requireNonNull(value);
    for (var index = 0; index < value.length(); index++) {
      final var character = value.charAt(index);
      if ((character < HttpCharacters.SPACE && character != HttpCharacters.TAB)
          || character == HttpCharacters.DELETE_CONTROL) {
        throw new IllegalArgumentException("Invalid header");
      }
    }
  }

  /**
   * Uses the transport parser to compare explicit cookie scopes without parsing attributes locally.
   *
   * @param field existing Set-Cookie field
   * @param cookie replacement cookie
   * @return whether the existing field has the same case-sensitive name/path and canonical domain
   */
  private static boolean matchesCookie(HttpField field, Cookie cookie) {
    final var previous = COOKIE_PARSER.parse(field.getValue());
    if (previous == null) {
      return false;
    }

    var domain = previous.getDomain();
    if (domain != null && domain.startsWith(HttpCharacters.DOT_STRING)) {
      domain = domain.substring(1);
    }

    return cookie.name().equals(previous.getName())
        && cookie.path().equals(previous.getPath())
        && (cookie.domain() == null ? domain == null : cookie.domain().equalsIgnoreCase(domain));
  }

  /**
   * Installs framework-owned callbacks around response submission and streaming writes.
   *
   * @param before callback before bytes are committed, or null when absent
   * @param after callback after submission, or null when absent
   */
  void flushHooks(@Nullable Runnable before, @Nullable Runnable after) {
    beforeFlush = before;
    afterFlush = after;
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
    beforeFlush = null;
    afterFlush = null;
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
   * Runs the pre-flush callback while prohibiting reentrant response mutation.
   *
   * @param last whether this flush ends the response
   * @param length bytes supplied by this flush when no Content-Length is staged
   * @throws NotAcceptableException if no acceptable content coding is available before commitment
   */
  private void notifyBeforeFlush(boolean last, long length) {
    if (beforeFlush != null) {
      flushCallback = true;

      try {
        beforeFlush.run();
      } catch (RuntimeException failure) {
        beforeFlushFailed = true;
        throw failure;
      } finally {
        flushCallback = false;
      }
    }

    if (compressionHandler != null
        && delegate.getStatus() == HttpStatusCodes.PARTIAL_CONTENT.value()
        && delegate.getHeaders().contains(HttpHeaders.CONTENT_RANGE.value())
        && !delegate.getHeaders().contains(HttpHeaders.CONTENT_ENCODING.value())) {
      delegate.getHeaders().put(HttpHeaders.CONTENT_ENCODING.value(), IDENTITY_ENCODING);
    }

    final var contentEncoding = delegate.getHeaders().get(HttpHeaders.CONTENT_ENCODING.value());
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

    configureHeadCompression();
    prepareInputBeforeFlush();
  }

  /** Runs the post-flush callback while prohibiting all response mutation. */
  private void notifyAfterFlush() {
    if (afterFlush == null) {
      return;
    }

    flushCallback = true;

    try {
      afterFlush.run();
    } finally {
      flushCallback = false;
    }
  }

  /**
   * Returns the native output for framework-owned matched-lifecycle dispatch.
   *
   * @return transport-owned output
   */
  org.eclipse.jetty.server.Response nativeResponse() {
    return delegate;
  }

  /**
   * Records the native response compressor for this exchange.
   *
   * @param handler Jetty compressor installed around application dispatch
   */
  void compression(CompressionHandler handler) {
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
   * Keeps this response uncompressed when application-wide compression is enabled. The explicit
   * identity content coding tells Jetty to leave the body unchanged. Call before streaming starts.
   *
   * @return this response
   * @throws IllegalStateException if the response is no longer open for mutation
   */
  public Response disableCompression() {
    return setHeader(HttpHeaders.CONTENT_ENCODING.value(), IDENTITY_ENCODING);
  }

  /** Adjusts HEAD framing when native compression may change the representation length. */
  private void configureHeadCompression() {
    if (compressionHandler == null || !head) {
      return;
    }

    delegate.getHeaders().remove(HttpHeaders.CONTENT_LENGTH.value());
    vary(HttpHeaders.ACCEPT_ENCODING);
    if (nativeRequest().getConnectionMetaData().getHttpVersion() == HttpVersion.HTTP_1_1) {
      delegate.getHeaders().put(HttpHeader.TRANSFER_ENCODING, CHUNKED_ENCODING);
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
    final var handler = compressionHandler;
    if (handler == null
        || !identityUnacceptable
        || head
        || contentEncoding != null
        || !permitsBody()
        || (delegate.getStatus() != 0
            && (delegate.getStatus() < 200 || delegate.getStatus() >= 300))
        || delegate.isCommitted()) {
      return;
    }

    // Jetty returns null for an absent wrapper; declare that contract for the generic result.
    final var selected =
        org.eclipse.jetty.server.Response.<@Nullable CompressionResponse>as(
            delegate, CompressionResponse.class);
    if (selected == null) {
      encodingRejected = true;
      throw new NotAcceptableException();
    }

    final var mappings = handler.getBean(PathMappings.class);
    final var match =
        Objects.requireNonNull(mappings)
            .getMatched(org.eclipse.jetty.server.Request.getPathInContext(selected.getRequest()));
    final var config = (CompressionConfig) Objects.requireNonNull(match).getResource();
    final var contentType = delegate.getHeaders().get(HttpHeaders.CONTENT_TYPE.value());
    if (contentType != null
        && !config.isCompressMimeTypeSupported(
            MimeTypes.getContentTypeWithoutCharset(contentType))) {
      encodingRejected = true;
      throw new NotAcceptableException();
    }

    var contentLength = delegate.getHeaders().getLongField(HttpHeader.CONTENT_LENGTH);
    if (contentLength < 0 && last) {
      contentLength = length;
    }

    if (contentLength >= 0) {
      final var encoder =
          selectedCompressor(
              handler,
              config,
              selected.getRequest().getHeaders().getValuesList(HttpHeader.ACCEPT_ENCODING));
      if (encoder == null || contentLength < encoder.getMinCompressSize()) {
        encodingRejected = true;
        throw new NotAcceptableException();
      }
    }
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
   * @param failure application failure, which may require an authentication challenge
   */
  void requiredChallenge(Throwable failure) {
    if (failure instanceof AuthenticationRequiredException authentication) {
      requiredChallenge = authentication.challenge();
      requiredChallengeStatus = HttpStatusCodes.UNAUTHORIZED.value();
    } else if (failure instanceof AuthorizationDeniedException authorization) {
      requiredChallenge = authorization.challenge();
      requiredChallengeStatus = HttpStatusCodes.FORBIDDEN.value();
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

  /** Restores required security, routing and cache fields before transport submission. */
  void protectRequiredHeaders() {

    if (requiredCorsHeaders != null) {
      protectCorsHeaders(requiredCorsHeaders);
    }

    if (requiredChallenge != null && delegate.getStatus() == requiredChallengeStatus) {
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
    for (final var header : CORS_RESPONSE_HEADERS) {
      delegate.getHeaders().remove(header.value());
    }

    for (final var entry : fields.entrySet()) {
      if (HttpHeaders.VARY.equalsIgnoreCase(entry.getKey())) {
        mergeVary(entry.getValue());
      } else {
        delegate.getHeaders().put(entry.getKey(), entry.getValue());
      }
    }
  }

  /**
   * Merges the required cache variation without weakening an existing wildcard.
   *
   * @param required comma-separated field names selected by policy
   */
  private void mergeVary(String required) {
    final var existing = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    for (final var value : delegate.getHeaders().getValuesList(HttpHeaders.VARY.value())) {
      for (final var token : value.split(HttpCharacters.COMMA_SIGN_STRING, -1)) {
        existing.add(token.trim());
      }
    }

    if (existing.contains(HttpCharacters.ASTERISK_STRING)) {
      return;
    }

    for (final var token : required.split(HttpCharacters.COMMA_SIGN_STRING, -1)) {
      final var name = token.trim();
      if (existing.add(name)) {
        delegate.getHeaders().add(HttpHeaders.VARY.value(), name);
      }
    }
  }

  /**
   * Stages a UTF-8 plain-text response until the handler returns.
   *
   * @param value response text
   * @return this response
   * @throws NullPointerException if value is null
   */
  public Response text(String value) {
    Objects.requireNonNull(value);

    return body(TEXT_CONTENT_TYPE, value.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Stages plain text encoded with the selected charset and matching Content-Type metadata until
   * the handler returns. Malformed and unmappable input uses the charset's replacement bytes, as
   * defined by {@link String#getBytes(Charset)}.
   *
   * @param value response text
   * @param charset encoding used for the response bytes
   * @return this response
   * @throws NullPointerException if value or charset is null
   * @throws IllegalArgumentException if the charset name requires HTTP quoting or the encoded body
   *     exceeds the configured limit
   * @throws IllegalStateException if the response is unavailable for finite output
   */
  public Response text(String value, Charset charset) {
    Objects.requireNonNull(value);
    Objects.requireNonNull(charset);
    final var contentType =
        StandardCharsets.UTF_8.equals(charset)
            ? TEXT_CONTENT_TYPE
            : MediaType.TEXT_PLAIN.withCharset(charset).value();
    requireEncodedBody(contentType);

    return body(contentType, value.getBytes(charset));
  }

  /**
   * Selects one candidate compatible with the request's Accept field and marks this response as
   * varying by Accept. The returned candidate only labels bytes; callers remain responsible for
   * generating and sending the representation. A missing Accept field selects the first candidate.
   *
   * @param first most preferred representation this handler can generate
   * @param additional other representations, in server preference order, or null for none
   * @return selected original candidate
   * @throws NotAcceptableException if an explicit Accept field excludes every candidate
   * @throws io.github.suppierk.shoostr.http.exceptions.BadRequestException if an Accept field is
   *     malformed
   * @throws NullPointerException if first or an additional candidate is null
   * @throws IllegalStateException if the response is unavailable for mutation
   */
  public MediaType negotiate(MediaType first, MediaType @Nullable ... additional) {
    require(State.OPEN);
    Objects.requireNonNull(first);
    if (additional != null) {
      for (final var candidate : additional) {
        Objects.requireNonNull(candidate);
      }
    }

    final var fields = request.headers(HttpHeaders.ACCEPT.value());
    if (fields.isEmpty()) {
      variedByAccept();

      return first;
    }

    final var selected = AcceptNegotiation.select(fields, first, additional);
    variedByAccept();
    if (selected != null) {
      return selected;
    }

    throw new NotAcceptableException();
  }

  /** Marks a successful Accept decision so generated error responses retain their Vary fields. */
  private void variedByAccept() {
    varyAccept();
    negotiatedByAccept = true;
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
    for (final var value : delegate.getHeaders().getValuesList(HttpHeaders.VARY.value())) {
      for (final var token : value.split(HttpCharacters.COMMA_SIGN_STRING, -1)) {
        final var field = token.trim();
        if (HttpCharacters.ASTERISK_STRING.equals(field) || header.equalsIgnoreCase(field)) {
          return;
        }
      }
    }

    delegate.getHeaders().add(HttpHeaders.VARY.value(), header.value());
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
    requireBodySelection();

    Objects.requireNonNull(value);
    if (value.length > options.getMaxResponseBytes()) {
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
   * Validates finite output admission before caller-supplied encoding or codec conversion, without
   * changing headers.
   *
   * @param contentType caller-selected response media type
   * @throws IllegalStateException if the phase, thread or selected file prohibits finite output
   */
  final void requireEncodedBody(String contentType) {
    requireBodySelection();

    validateHeaderValue(contentType);
  }

  /**
   * Streams an application-owned input source through the bounded response buffer and closes it
   * before this method returns, including when transport output fails.
   *
   * @param source application input source
   * @param contentType response media type
   * @return this response
   * @throws IOException if reading or output fails
   * @throws IllegalStateException if file output is already selected
   */
  public Response input(InputStream source, String contentType) throws IOException {
    Objects.requireNonNull(source);

    try (source) {
      requireBodySelection();

      if (!permitsBody()) {
        return this;
      }

      if (head) {
        contentType(contentType);
        headContentLength = -1;
        return this;
      }

      final var output = startStream(contentType);
      final var buffer = new byte[options.getStreamBufferBytes()];
      for (var read = 0; (read = source.read(buffer)) >= 0; ) {
        if (read > 0) {
          output.write(read == buffer.length ? buffer : Arrays.copyOf(buffer, read));
        }
      }

      return this;
    }
  }

  /**
   * Requires an open response with no selected file before accepting body output.
   *
   * @throws IllegalStateException if the phase, thread or selected file prohibits output
   */
  private void requireBodySelection() {
    require(State.OPEN);
    if (fileSelected) {
      throw new IllegalStateException("Response already has file output");
    }
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
    final var resource = Objects.requireNonNull(source);
    requireFileSelection();

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
    final var disposition = attachmentDisposition(filename);
    file(source, contentType);

    return setHeader(HttpHeaders.CONTENT_DISPOSITION.value(), disposition);
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
    requireFileSelection();

    fileSelected = true;
    final var content = new ResourceHttpContent(resource, contentType);
    final var length = content.getContentLengthValue();
    if (length < 0) {
      throw new IOException("File length is unavailable");
    }

    contentType(contentType);
    final var lastModified =
        effectiveLastModified(content.getLastModifiedInstant(), responseDate());
    resourceHeaders(content, lastModified);
    delegate.getHeaders().put(HttpHeaders.ACCEPT_RANGES.value(), BYTES_RANGES);
    delegate.getHeaders().put(HttpHeader.CONTENT_LENGTH, length);
    final var tag = delegate.getHeaders().get(HttpHeaders.ETAG.value());
    if (tag != null && !entityTag(tag)) {
      throw new IllegalArgumentException("Invalid entity tag");
    }

    if (selectResourceInterval(length, tag, lastModified)) {
      resourceContent = content;
    }

    return this;
  }

  /**
   * Stages an already-open filesystem channel with its captured length and modification time.
   *
   * @param channel selected descriptor retained until terminal transfer
   * @param length selected representation length
   * @param lastModified modification time sampled when selecting the resource
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
    requireFileSelection();

    if (length < 0) {
      throw new IOException("File length is unavailable");
    }

    fileSelected = true;
    resourceChannel = channel;
    contentType(contentType);
    final var effectiveLastModified =
        Objects.requireNonNull(effectiveLastModified(lastModified, responseDate()));
    final var tag = resourceTag(length, lastModified);
    delegate.getHeaders().put(HttpHeaders.ETAG.value(), tag);
    delegate
        .getHeaders()
        .put(HttpHeaders.LAST_MODIFIED.value(), HttpDateTime.format(effectiveLastModified));
    delegate.getHeaders().put(HttpHeaders.ACCEPT_RANGES.value(), BYTES_RANGES);
    delegate.getHeaders().put(HttpHeader.CONTENT_LENGTH, length);

    selectResourceInterval(length, tag, effectiveLastModified);
    return this;
  }

  /**
   * Requires an open response with no gate, finite body or file output pending.
   *
   * @throws IllegalStateException if the phase, thread or pending output prohibits file selection
   */
  private void requireFileSelection() {
    require(State.OPEN);
    if (gating || body.length != 0 || fileSelected) {
      throw new IllegalStateException("Response cannot become a file");
    }
  }

  /**
   * Resolves conditional and range requests for either resource representation.
   *
   * @param length full representation length
   * @param tag selected entity tag, or null
   * @param lastModified selected modification time, or null
   * @return whether the selected resource body should be transferred
   */
  private boolean selectResourceInterval(
      long length, @Nullable String tag, @Nullable Instant lastModified) {
    final var conditional = conditionalResourceStatus(tag, lastModified);
    if (conditional != 0) {
      status(conditional);
      delegate.getHeaders().remove(HttpHeader.CONTENT_LENGTH);
      closeResourceChannel();

      return false;
    }

    final var range = range(length, tag);
    if (UNSATISFIABLE_RANGE.equals(range)) {
      status(HttpStatusCodes.RANGE_NOT_SATISFIABLE.value());
      delegate.getHeaders().put(HttpHeaders.CONTENT_RANGE.value(), "bytes */" + length);
      closeResourceChannel();

      return false;
    }

    if (range != null) {
      status(HttpStatusCodes.PARTIAL_CONTENT.value());
      delegate
          .getHeaders()
          .put(
              HttpHeaders.CONTENT_RANGE.value(),
              "bytes "
                  + range.first
                  + HttpCharacters.DASH_STRING
                  + range.last
                  + HttpCharacters.PATH_SEPARATOR_STRING
                  + length);
      delegate.getHeaders().put(HttpHeader.CONTENT_LENGTH, range.length());
    }

    resourceOffset = range == null ? 0 : range.first;
    resourceLength = range == null ? length : range.length();

    return true;
  }

  /**
   * Determines whether request preconditions suppress a resource body.
   *
   * @param tag selected entity tag, or null
   * @param lastModified selected modification time, or null
   * @return HTTP status for a satisfied conditional request, or zero to send the body
   */
  private int conditionalResourceStatus(@Nullable String tag, @Nullable Instant lastModified) {
    if (!conditionalStatus()) {
      return 0;
    }

    if (!matchesIfMatch(tag) || !unmodifiedSince(lastModified)) {
      return HttpStatusCodes.PRECONDITION_FAILED.value();
    }

    if (noneMatch(tag)) {
      return HttpMethods.GET.value().equals(nativeRequest().getMethod())
              || HttpMethods.HEAD.value().equals(nativeRequest().getMethod())
          ? HttpStatusCodes.NOT_MODIFIED.value()
          : HttpStatusCodes.PRECONDITION_FAILED.value();
    }

    return notModifiedSince(lastModified) ? HttpStatusCodes.NOT_MODIFIED.value() : 0;
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
   * Gets or establishes the file response Date field used as the validator-generation boundary.
   *
   * @return response origination date
   */
  private Instant responseDate() {
    final var value = delegate.getHeaders().get(HttpHeaders.DATE.value());
    final var epoch = value == null ? -1 : HttpDateTime.parseToEpoch(value);
    if (epoch >= 0) {
      return Instant.ofEpochMilli(epoch);
    }

    final var date = Instant.now();
    delegate.getHeaders().put(HttpHeaders.DATE.value(), HttpDateTime.format(date));

    return date;
  }

  /**
   * Applies strong If-Match comparison before every lower-precedence precondition.
   *
   * @param tag selected entity tag
   * @return whether the request passes the precondition
   */
  private boolean matchesIfMatch(@Nullable String tag) {
    final var values = nativeRequest().getHeaders().getValuesList(HttpHeaders.IF_MATCH.value());
    if (values.isEmpty()) {
      return true;
    }

    final var candidates = entityTags(values);
    if (candidates.contains(HttpCharacters.ASTERISK_STRING)) {
      return true;
    }

    if (tag == null || tag.startsWith(WEAK_ENTITY_TAG_PREFIX)) {
      return false;
    }

    return candidates.contains(tag);
  }

  /**
   * Applies If-Unmodified-Since only when the higher-precedence If-Match field is absent.
   *
   * @param lastModified selected resource modification time
   * @return whether the request passes the precondition
   */
  private boolean unmodifiedSince(@Nullable Instant lastModified) {
    if (lastModified == null
        || !nativeRequest().getHeaders().getValuesList(HttpHeaders.IF_MATCH.value()).isEmpty()) {
      return true;
    }

    final var values =
        nativeRequest().getHeaders().getValuesList(HttpHeaders.IF_UNMODIFIED_SINCE.value());
    if (values.size() != 1) {
      return true;
    }

    final var date = HttpDateTime.parseToEpoch(values.getFirst());

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

    for (final var candidate :
        entityTags(nativeRequest().getHeaders().getValuesList(HttpHeaders.IF_NONE_MATCH.value()))) {
      if (HttpCharacters.ASTERISK_STRING.equals(candidate)
          || weakTag(candidate).equals(weakTag(tag))) {
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
        || !nativeRequest().getHeaders().getValuesList(HttpHeaders.IF_NONE_MATCH.value()).isEmpty()
        || (!HttpMethods.GET.value().equals(nativeRequest().getMethod())
            && !HttpMethods.HEAD.value().equals(nativeRequest().getMethod()))) {
      return false;
    }

    final var values =
        nativeRequest().getHeaders().getValuesList(HttpHeaders.IF_MODIFIED_SINCE.value());
    if (values.size() != 1) {
      return false;
    }

    final var date = HttpDateTime.parseToEpoch(values.getFirst());

    return date >= 0 && lastModified.getEpochSecond() <= date / 1000;
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
        || !HttpMethods.GET.value().equals(nativeRequest().getMethod())) {
      return null;
    }

    if (!ifRangeMatches(tag)) {
      return null;
    }

    final var values = nativeRequest().getHeaders().getValuesList(HttpHeaders.RANGE.value());
    if (values.size() != 1
        || !values.getFirst().startsWith("bytes=")
        || values.getFirst().contains(HttpCharacters.COMMA_SIGN_STRING)) {
      return null;
    }

    final var value = values.getFirst().substring("bytes=".length());
    final var separator = value.indexOf(HttpCharacters.DASH);
    if (separator < 0 || separator != value.lastIndexOf(HttpCharacters.DASH)) {
      return null;
    }

    try {
      if (separator == 0) {
        return suffixRange(length, value.substring(1));
      }

      return explicitRange(length, value.substring(0, separator), value.substring(separator + 1));
    } catch (NumberFormatException ignoredFailure) {
      return null;
    }
  }

  /**
   * Applies a strong entity-tag If-Range validator to the one supported transfer.
   *
   * @param tag selected entity tag
   * @return whether Range can select a partial representation
   */
  private boolean ifRangeMatches(@Nullable String tag) {
    final var values = nativeRequest().getHeaders().getValuesList(HttpHeaders.IF_RANGE.value());
    if (values.isEmpty()) {
      return true;
    }

    if (values.size() != 1) {
      return false;
    }

    final var validator = values.getFirst();

    return tag != null && !tag.startsWith(WEAK_ENTITY_TAG_PREFIX) && validator.equals(tag);
  }

  /**
   * Restricts inspection to the live handler thread, including active streams.
   *
   * @throws IllegalStateException if the response is terminal or the thread does not own it
   */
  private void checkReadable() {
    if (!request.ownedByCurrentThread()
        || (!flushCallback && (state == State.CLOSED || state == State.FAILED))) {
      throw new IllegalStateException("Response is not readable in this phase/thread");
    }
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

    write(false, 0);

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
    final var code = delegate.getStatus();

    return code == 0 ? HttpStatusCodes.OK.value() : code;
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
    final var fields = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
    for (final var field : delegate.getHeaders()) {
      fields.computeIfAbsent(field.getName(), ignored -> new ArrayList<>()).add(field.getValue());
    }
    fields.replaceAll((ignoredString, values) -> List.copyOf(values));

    return Collections.unmodifiableMap(fields);
  }

  /**
   * Sets a response header before commitment; framing headers belong to the framework.
   *
   * @param name HTTP field name
   * @param value field value without controls other than horizontal tab
   * @return this response
   * @throws IllegalArgumentException if the name or value is invalid or the field controls framing
   */
  public Response setHeader(String name, String value) {
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
    putCookie(cookie);
    return this;
  }

  /**
   * Stages a cookie without reopening metadata during required-header restoration.
   *
   * @param cookie validated cookie
   */
  private void putCookie(Cookie cookie) {
    final var value = Objects.requireNonNull(cookie).headerValue();
    final var fields = delegate.getHeaders().listIterator();
    while (fields.hasNext()) {
      final var field = fields.next();
      if (HttpHeaders.SET_COOKIE.equalsIgnoreCase(field.getName())
          && matchesCookie(field, cookie)) {
        fields.remove();
      }
    }
    delegate.getHeaders().add(HttpHeaders.SET_COOKIE.value(), value);
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
   * Renews a live Jetty session using this response before it is committed.
   *
   * @param session existing session
   */
  void renewSessionId(Session session) {
    checkCookieMutation();
    session.renewId(nativeRequest(), delegate);
  }

  /**
   * Rejects lazy session creation once its cookie can no longer be sent.
   *
   * @throws IllegalStateException if response commitment has begun
   */
  void checkCookieMutation() {
    require(State.OPEN);
    if (delegate.isCommitted()) {
      throw new IllegalStateException("Cookies cannot be changed after response commitment");
    }
  }

  /**
   * Retains only Jetty's current session cookie when an error discards application output.
   *
   * @return session cookie values already set by Jetty
   */
  private List<String> sessionCookies() {
    final var session = nativeRequest().getSession(false);
    if (!(session instanceof ManagedSession managed)
        || !(managed.getSessionManager() instanceof AbstractSessionManager manager)) {
      return List.of();
    }

    final var name = manager.getSessionCookie() + HttpCharacters.EQUALS_SIGN_STRING;

    return delegate.getHeaders().getValuesList(HttpHeaders.SET_COOKIE.value()).stream()
        .filter(value -> value.startsWith(name))
        .toList();
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

    final var destination = URI.create(Objects.requireNonNull(location));
    final var http = Origins.isHttpScheme(destination.getScheme());
    if (location.isEmpty()
        || ((http || destination.getRawAuthority() != null)
            && (destination.getHost() == null
                || destination.getRawUserInfo() != null
                || destination.getPort() > 65535))) {
      throw new IllegalArgumentException("Invalid redirect destination");
    }

    setHeader(HttpHeaders.LOCATION.value(), destination.toASCIIString());
    status(code.value());

    if (fileSelected) {
      discardResource();
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
   * Schedules non-blocking input disposal after application pre-flush callbacks and before the
   * first error write. Jetty can then advertise connection closure when rejected input is not fully
   * available, while custom renderers and pre-flush callbacks retain access to the body.
   */
  void discardInputBeforeFlush() {
    discardInputBeforeFlush = true;
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
    final var message = status.reasonPhrase().getBytes(StandardCharsets.UTF_8);
    body = message.length <= options.getMaxResponseBytes() ? message : EMPTY;
    complete();
  }

  /**
   * Clears staged output before error handling while retaining required cache variation. The caller
   * must reject committed/terminal output.
   *
   * @param status initial error status, overridable by the application callback
   */
  void reset(HttpStatusCodes status) {
    final var vary =
        (negotiatedByAccept || requiredCorsHeaders != null || compressionHandler != null)
            ? List.copyOf(delegate.getHeaders().getValuesList(HttpHeaders.VARY.value()))
            : List.<String>of();
    final var sessionCookies = sessionCookies();
    delegate.getHeaders().clear();
    for (final var value : vary) {
      delegate.getHeaders().add(HttpHeaders.VARY.value(), value);
    }
    for (final var value : sessionCookies) {
      delegate.getHeaders().add(HttpHeaders.SET_COOKIE.value(), value);
    }

    state = State.OPEN;
    body = EMPTY;
    stream = null;
    eventStreamOnly = false;
    discardResource();
    requiredAllow = null;
    afterFlushFailure = null;
    headContentLength = -1;
    status(status.value());
  }

  /**
   * Replaces admission-stage output with an empty preflight while retaining ordinary headers.
   * Releases any staged file transfer before selecting the bodyless response.
   */
  void preflight() {
    require(State.OPEN);
    body = EMPTY;
    discardResource();
    headContentLength = -1;
    delegate.getHeaders().remove(HttpHeaders.CONTENT_TYPE.value());
    delegate.getHeaders().remove(HttpHeaders.CONTENT_LENGTH.value());
    delegate.getHeaders().remove(HttpHeaders.CONTENT_RANGE.value());
    status(HttpStatusCodes.NO_CONTENT.value());
  }

  /** Drops resource metadata, closes its unsubmitted channel and clears selection. */
  private void discardResource() {
    resourceContent = null;
    closeResourceChannel();
    fileSelected = false;
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
    if (!frameworkClosing || flushCallback || !request.ownedByCurrentThread()) {
      throw new IllegalStateException("The framework owns the response lifecycle");
    }

    switch (state) {
      case OPEN -> completeOpen();
      case STREAMING -> completeStreaming();
      default -> throw new IOException("Response already terminated");
    }
  }

  /**
   * Submits a staged finite body or resource after the handler returns.
   *
   * @throws IOException if resource transfer or output submission fails
   * @throws IllegalStateException if the selected status forbids a staged body
   */
  private void completeOpen() throws IOException {
    if (afterFlush == null) {
      discardInputBeforeFlush = true;
    }

    if (resourceContent != null || resourceChannel != null) {
      completeResource();
      return;
    }

    if (!permitsBody() && !head && body.length != 0) {
      throw new IllegalStateException("Status does not allow a body");
    }

    stageContentLength();
    notifyBeforeFlush(true, body.length);
    // End application access before handing the buffer to a potentially asynchronous write.
    state = State.CLOSED;
    protectRequiredHeaders();
    final var bytes = ByteBuffer.wrap(body);
    body = EMPTY;
    if (head && compressionHandler != null && permitsBody()) {
      Content.Sink.write(delegate, false, ByteBuffer.wrap(EMPTY));
    }

    if (afterFlush == null) {
      delegate.write(true, bytes, completion);
    } else {
      final var flushing = new FlushCompletion();

      delegate.write(true, bytes, flushing);
      flushing.afterFlush();
    }
  }

  /**
   * Finishes the bounded streaming buffer and releases the transport callback.
   *
   * @throws IOException if the final transport write fails
   */
  private void completeStreaming() throws IOException {
    final var activeStream = Objects.requireNonNull(stream);

    write(true, activeStream.used);
    activeStream.used = 0;
    body = EMPTY;
    state = State.CLOSED;

    completion.succeeded();
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
    final var flushing = new FlushCompletion();

    if (head || !permitsBody()) {
      closeResourceChannel();
      if (head && compressionHandler != null && permitsBody()) {
        Content.Sink.write(delegate, false, ByteBuffer.wrap(EMPTY));
      }

      delegate.write(true, ByteBuffer.wrap(EMPTY), flushing);
      flushing.afterFlush();
      return;
    }

    final var channel = resourceChannel;
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
      final var content = Objects.requireNonNull(resourceContent);
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
    final var channel = resourceChannel;
    resourceChannel = null;
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException ignoredFailure) {
        // Response cleanup cannot safely report an unsubmitted descriptor close failure.
      }
    }
  }

  /** Sets the finite representation length when the selected status permits one. */
  private void stageContentLength() {
    if (permitsBody() && !head) {
      delegate.getHeaders().put(HttpHeader.CONTENT_LENGTH, body.length);
    } else if (head && headContentLength >= 0) {
      delegate.getHeaders().put(HttpHeader.CONTENT_LENGTH, headContentLength);
    } else if (head && body.length > 0) {
      delegate.getHeaders().put(HttpHeader.CONTENT_LENGTH, body.length);
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
   * Rejects staged HTTP output before a managed WebSocket upgrade.
   *
   * @throws IllegalStateException if response output or a non-default status was selected
   */
  void requireWebSocketUpgrade() {
    require(State.OPEN);
    if (delegate.isCommitted()
        || body.length != 0
        || fileSelected
        || status() != HttpStatusCodes.OK.value()
        || delegate.getHeaders().contains(HttpHeaders.CONTENT_TYPE.value())) {
      throw new IllegalStateException(
          "Reject WebSocket admission by throwing, not by staging HTTP output");
    }
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
   * Validates the media type and reuses pre-encoded fields for common responses.
   *
   * @param value media type to send
   */
  private void contentType(String value) {
    validateHeaderValue(value);
    final var field = contentTypeField(value);
    delegate.getHeaders().put(field);
  }

  /**
   * Builds content-type metadata using a predefined field when its value matches.
   *
   * @param value header value
   * @return native content-type header
   */
  private HttpField contentTypeField(String value) {
    return switch (value) {
      case TEXT_CONTENT_TYPE -> TEXT;
      case "text/plain;charset=utf-8" -> COMPACT_TEXT;
      case "application/json" -> JSON;
      default -> new HttpField(HttpHeader.CONTENT_TYPE, value);
    };
  }

  /**
   * Checks status-specific body restrictions; the transport separately suppresses HEAD response
   * bytes.
   *
   * @return false for 204, 205, or 304 responses
   */
  private boolean permitsBody() {
    return delegate.getStatus() != HttpStatusCodes.NO_CONTENT.value()
        && delegate.getStatus() != HttpStatusCodes.RESET_CONTENT.value()
        && delegate.getStatus() != HttpStatusCodes.NOT_MODIFIED.value();
  }

  /**
   * Returns the transport request required by conditional and range file handling.
   *
   * @return the request retained for the selected file response
   */
  private org.eclipse.jetty.server.Request nativeRequest() {
    return request.nativeRequest();
  }

  /**
   * Guards response mutation by both lifecycle phase and handler-thread ownership.
   *
   * @param expected required writable phase
   * @throws IllegalStateException if the current phase or thread does not permit access
   */
  private void require(State expected) {
    if (!request.ownedByCurrentThread() || flushCallback || state != expected) {
      throw new IllegalStateException("Response is not writable in this phase/thread");
    }
  }

  /**
   * Waits for transport consumption before the caller can reuse the streaming buffer. Any failed
   * write makes the response terminal so error handling cannot replace partial output.
   *
   * @param last whether this write ends the response
   * @param length number of bytes to send from the start of the buffer
   * @throws IOException if the transport cannot complete the write
   */
  private void write(boolean last, int length) throws IOException {
    final var activeStream = Objects.requireNonNull(stream);
    notifyBeforeFlush(last, length);

    try {
      protectRequiredHeaders();

      Content.Sink.write(delegate, last, activeStream.view(length));
      notifyAfterFlush();
    } catch (IOException | RuntimeException failure) {
      state = State.FAILED;
      activeStream.view = null;
      throw failure;
    }
  }

  /** Disposes available input or advertises finite closure before response commitment. */
  private void prepareInputBeforeFlush() {
    if (discardInputBeforeFlush) {
      nativeRequest().consumeAvailable();
      discardInputBeforeFlush = false;
    } else if (frameworkClosing && state == State.OPEN) {
      advertiseUnreadInputClosure();
    }
  }

  /** Advertises finite HTTP/1 closure while leaving input available to post-flush callbacks. */
  private void advertiseUnreadInputClosure() {
    final var transportRequest = org.eclipse.jetty.server.Request.unWrap(nativeRequest());
    final var length = transportRequest.getLength();
    final var unread =
        length > 0
            && org.eclipse.jetty.server.Request.getContentBytesRead(transportRequest) < length;
    final var chunked =
        length < 0 && transportRequest.getHeaders().contains(HttpHeader.TRANSFER_ENCODING);
    if (unread || chunked) {
      final var version = transportRequest.getConnectionMetaData().getHttpVersion();
      if (version == HttpVersion.HTTP_1_0 || version == HttpVersion.HTTP_1_1) {
        delegate.getHeaders().put(HttpHeader.CONNECTION, CLOSE_CONNECTION);
      }
    }
  }

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

  /** Writes complete server-sent event frames through the response's bounded stream. */
  public static sealed class EventStream {
    private static final byte[] DATA_PREFIX = "data: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EVENT_TERMINATOR = "\n\n".getBytes(StandardCharsets.UTF_8);
    private final Stream output;

    /**
     * Wraps the response-owned byte stream.
     *
     * @param output bounded response stream
     */
    EventStream(Stream output) {
      this.output = Objects.requireNonNull(output);
    }

    /** Checks the existing response-owned stream without writing any event bytes. */
    final void requireWritable() {
      output.checkWritable();
    }

    /**
     * Sends one UTF-8 data event, including its dispatching blank line.
     *
     * @param data event payload; each line becomes a data field
     * @return this event stream
     * @throws IOException if the transport write fails
     */
    public EventStream send(String data) throws IOException {
      writeData(Objects.requireNonNull(data));
      output.flush();
      return this;
    }

    /**
     * Sends an event with optional name, ID and retry metadata.
     *
     * @param event immutable event to send
     * @return this event stream
     * @throws IOException if the transport write fails
     */
    public EventStream send(SseEvent event) throws IOException {
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
      return this;
    }

    /**
     * Sends one comment block without dispatching an event.
     *
     * @param value comment text, split safely at every event-stream line ending
     * @return this event stream
     * @throws IOException if the transport write fails
     */
    public EventStream comment(String value) throws IOException {
      writeLines(Objects.requireNonNull(value), ": ");

      output.write("\n");
      output.flush();
      return this;
    }

    /**
     * Sends a minimal comment heartbeat without dispatching an event.
     *
     * @return this event stream
     * @throws IOException if the transport write fails
     */
    public EventStream heartbeat() throws IOException {
      output.write(":\n\n");
      output.flush();
      return this;
    }

    /**
     * Writes every payload line as one data field without ending the event until the final line.
     *
     * @param data event payload
     * @throws IOException if the transport write fails
     */
    private void writeData(String data) throws IOException {
      // Check LF first so common multiline data avoids a full scan for absent CR.
      if (data.indexOf('\n') < 0 && data.indexOf('\r') < 0) {
        output.write(DATA_PREFIX);
        output.write(data);
        output.write(EVENT_TERMINATOR);
        return;
      }

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
      final var normalized = value.replace("\r\n", "\n").replace('\r', '\n');
      var start = 0;
      int end;
      while ((end = normalized.indexOf('\n', start)) >= 0) {
        output.write(prefix);
        output.write(normalized.substring(start, end));
        output.write("\n");
        start = end + 1;
      }

      output.write(prefix);
      output.write(normalized.substring(start));
      output.write("\n");
    }

    /** Codec-backed SSE output over the response's single handler-owned stream. */
    public static final class Typed extends EventStream {
      private final Codec codec;

      /**
       * Shares ordinary event framing and stream ownership.
       *
       * @param output existing response stream
       * @param codec shared application conversion
       */
      Typed(Stream output, Codec codec) {
        super(output);
        this.codec = Objects.requireNonNull(codec);
      }

      /**
       * Encodes one object as UTF-8 text event data and flushes the event. The codec must produce a
       * UTF-8 textual representation. Malformed UTF-8 and conversion failures write no part of this
       * event, but headers and earlier events are already committed. Validates stream ownership and
       * lifetime before invoking the codec; does not bound codec allocations.
       *
       * @param value nonnull application representation
       * @return this event stream
       * @throws Exception if conversion, strict UTF-8 decoding or transport output fails
       */
      @SuppressWarnings("java:S112")
      public Response.EventStream.Typed sendObject(Object value) throws Exception {
        requireWritable();
        Objects.requireNonNull(value);

        final var bytes = Objects.requireNonNull(codec.write(value), "Codec returned null bytes");
        // Reporting decoding rejects malformed output before even a data prefix is written.
        final var text =
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();

        super.send(text);
        return this;
      }

      /** {@inheritDoc} */
      @Override
      public Response.EventStream.Typed send(String data) throws IOException {
        super.send(data);
        return this;
      }

      /** {@inheritDoc} */
      @Override
      public Response.EventStream.Typed send(SseEvent event) throws IOException {
        super.send(event);
        return this;
      }

      /** {@inheritDoc} */
      @Override
      public Response.EventStream.Typed comment(String value) throws IOException {
        super.comment(value);
        return this;
      }

      /** {@inheritDoc} */
      @Override
      public Response.EventStream.Typed heartbeat() throws IOException {
        super.heartbeat();
        return this;
      }
    }
  }

  /** Coordinates transport completion with the post-flush callback exactly once. */
  private final class FlushCompletion implements Callback {
    private final ReentrantLock completionLock;
    private CompletionProgress progress;
    private @Nullable Throwable afterFailure;
    private @Nullable Throwable transportFailure;

    /** Creates an independent lock for this submission's completion state. */
    private FlushCompletion() {
      this.completionLock = new ReentrantLock();
      this.progress = CompletionProgress.WAITING_FOR_BOTH;
    }

    /** Marks the response submission as successful from the transport's perspective. */
    @Override
    public void succeeded() {
      runUnderLock(completionLock, () -> complete(CompletionProgress.WAITING_FOR_AFTER_FLUSH));
    }

    /**
     * Records the response submission failure from the transport.
     *
     * @param failure transport failure
     */
    @Override
    public void failed(Throwable failure) {
      runUnderLock(
          completionLock,
          () -> {
            transportFailure = failure;
            complete(CompletionProgress.WAITING_FOR_AFTER_FLUSH);
          });
    }

    /** Runs post-flush observation and releases the terminal callback once both sides finish. */
    private void afterFlush() {
      runUnderLock(
          completionLock,
          () -> {
            try {
              notifyAfterFlush();
            } catch (RuntimeException failure) {
              afterFailure = failure;
              afterFlushFailure = failure;
            }

            complete(CompletionProgress.WAITING_FOR_TRANSPORT);
          });
    }

    /**
     * Records an arrival and completes when the other event has already finished.
     *
     * @param waitingFor remaining event after this arrival
     */
    private void complete(CompletionProgress waitingFor) {
      if (progress == CompletionProgress.COMPLETED || progress == waitingFor) {
        return;
      }

      if (progress == CompletionProgress.WAITING_FOR_BOTH) {
        progress = waitingFor;
        return;
      }

      progress = CompletionProgress.COMPLETED;

      if (transportFailure != null) {
        completion.failed(transportFailure);
      } else if (afterFailure != null) {
        completion.failed(afterFailure);
      } else {
        completion.succeeded();
      }
    }

    /** Tracks the two independent arrivals and the terminal callback claim. */
    private enum CompletionProgress {
      WAITING_FOR_BOTH,
      WAITING_FOR_TRANSPORT,
      WAITING_FOR_AFTER_FLUSH,
      COMPLETED
    }
  }

  /** Bounded writes emit when the configured capacity fills or the handler flushes explicitly. */
  public final class Stream {
    private static final int INITIAL_BUFFER_BYTES = 256;
    private byte[] buffer;
    private @Nullable ByteBuffer view;
    private int used;

    /** Starts with shared empty storage so empty streams allocate no body buffer. */
    private Stream() {
      this.buffer = EMPTY;
    }

    /** Checks the response thread, lifetime and streaming state before caller conversion. */
    private void checkWritable() {
      require(State.STREAMING);
    }

    /**
     * Writes text as UTF-8, flushing at the configured capacity as needed.
     *
     * @param value text to write
     * @return this stream
     * @throws IOException if a transport write fails
     */
    public Stream write(String value) throws IOException {
      return write(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Copies bytes into the bounded buffer, blocking on full-buffer writes.
     *
     * @param bytes bytes to write
     * @return this stream
     * @throws IOException if a transport write fails
     */
    public Stream write(byte[] bytes) throws IOException {
      require(State.STREAMING);
      Objects.requireNonNull(bytes);
      var offset = 0;
      while (offset < bytes.length) {
        final var count = Math.min(options.getStreamBufferBytes() - used, bytes.length - offset);
        ensureStorage(used + count);
        System.arraycopy(bytes, offset, buffer, used, count);
        used += count;
        offset += count;
        if (used == options.getStreamBufferBytes()) {
          flush();
        }
      }

      return this;
    }

    /**
     * Sends pending bytes and waits for the transport to finish using the buffer.
     *
     * @return this stream
     * @throws IOException if the transport write fails
     */
    public Stream flush() throws IOException {
      require(State.STREAMING);

      Response.this.write(false, used);
      used = 0;
      return this;
    }

    /**
     * Resets this stream's heap view only after the preceding transport write succeeded.
     *
     * @param length pending bytes to expose from the start of the backing array
     * @return the view positioned at zero and limited to the pending bytes
     */
    private ByteBuffer view(int length) {
      var current = view;
      if (current == null) {
        current = ByteBuffer.wrap(buffer);
        view = current;
      }

      current.clear();
      current.limit(length);

      return current;
    }

    /**
     * Grows physical storage without changing the configured automatic-flush boundary.
     *
     * @param required bytes that must fit before the next copy
     */
    private void ensureStorage(int required) {
      if (required <= buffer.length) {
        return;
      }

      final var limit = (int) options.getStreamBufferBytes();
      final var doubled = buffer.length > limit / 2 ? limit : buffer.length * 2;
      final var capacity = Math.clamp(Math.max(INITIAL_BUFFER_BYTES, doubled), required, limit);
      var grown = new byte[capacity];
      System.arraycopy(buffer, 0, grown, 0, used);
      buffer = grown;
      view = null;
    }
  }

  /** Codec-enabled response; inherited byte arrays and text never invoke the codec. */
  public static final class Typed extends Response {
    private final Codec codec;

    /**
     * Initializes the response paired with its one request, using the ordinary response state.
     *
     * @param delegate native output
     * @param options exchange limits
     * @param completion transport completion
     * @param head whether only representation metadata is sent
     * @param request paired request
     * @param codec shared application conversion
     */
    Typed(
        org.eclipse.jetty.server.Response delegate,
        Options options,
        Callback completion,
        boolean head,
        Request request,
        Codec codec) {
      super(delegate, options, completion, head, request);
      this.codec = Objects.requireNonNull(codec);
    }

    /**
     * Stages finite codec output with explicit media metadata. Validates writable state before
     * invoking the codec; conversion failure leaves the previous staged representation untouched.
     *
     * @param contentType response media type, including any explicit charset
     * @param value nonnull application representation
     * @return this response
     * @throws Exception if conversion fails; response limits and state rules remain unchanged
     */
    @SuppressWarnings("java:S112") // User codecs share Handler's checked exception contract.
    public Response.Typed body(String contentType, Object value) throws Exception {
      requireEncodedBody(contentType);
      Objects.requireNonNull(value);

      super.body(
          contentType, Objects.requireNonNull(codec.write(value), "Codec returned null bytes"));
      return this;
    }

    /**
     * Stages finite codec output without interpreting the selected media type or transcoding bytes.
     *
     * @param contentType response media metadata
     * @param value nonnull application representation
     * @return this response
     * @throws Exception if conversion fails
     */
    @SuppressWarnings("java:S112") // User codecs share Handler's checked exception contract.
    public Response.Typed body(MediaType contentType, Object value) throws Exception {
      return body(Objects.requireNonNull(contentType).value(), value);
    }

    /** {@inheritDoc} */
    @Override
    public Response.Typed body(String contentType, byte[] value) {
      super.body(contentType, value);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Response.Typed body(MediaType contentType, byte[] value) {
      super.body(contentType, value);
      return this;
    }

    /**
     * Starts handler-owned SSE output with codec-backed object sends. Starting commits HTTP headers
     * immediately; object conversion cannot replace that committed response.
     *
     * @return event writer using this response's existing stream lifecycle
     * @throws IOException if initial output fails
     * @throws IllegalStateException if the response cannot become a stream
     */
    @Override
    public EventStream.Typed startEventStream() throws IOException {
      return new EventStream.Typed(startStream(EVENT_STREAM_CONTENT_TYPE), codec);
    }

    /** {@inheritDoc} */
    @Override
    public Response.Typed status(int code) {
      super.status(code);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Response.Typed setHeader(String name, String value) {
      super.setHeader(name, value);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Response.Typed addHeader(String name, String value) {
      super.addHeader(name, value);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Response.Typed removeHeader(String name) {
      super.removeHeader(name);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Response.Typed text(String value) {
      super.text(value);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Response.Typed text(String value, Charset charset) {
      super.text(value, charset);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Response.Typed input(InputStream source, String contentType) throws IOException {
      super.input(source, contentType);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Response.Typed file(Path source, String contentType) throws IOException {
      super.file(source, contentType);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Response.Typed attachment(Path source, String contentType, String filename)
        throws IOException {
      super.attachment(source, contentType, filename);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Response.Typed disableCompression() {
      super.disableCompression();
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Response.Typed cookie(String name, String value) {
      super.cookie(name, value);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Response.Typed cookie(Cookie cookie) {
      super.cookie(cookie);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Response.Typed removeCookie(String name) {
      super.removeCookie(name);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Response.Typed removeCookie(String name, String path, @Nullable String domain) {
      super.removeCookie(name, path, domain);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Response.Typed removeCookie(Cookie cookie) {
      super.removeCookie(cookie);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Response.Typed redirect(String location) {
      super.redirect(location);
      return this;
    }

    /** {@inheritDoc} */
    @Override
    public Response.Typed redirect(String location, HttpStatusCodes code) {
      super.redirect(location, code);
      return this;
    }
  }
}
