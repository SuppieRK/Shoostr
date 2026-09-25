package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.http.exceptions.ContentTooLargeException;
import io.github.suppierk.shoostr.http.exceptions.HttpException;
import io.github.suppierk.shoostr.http.exceptions.UnsupportedMediaTypeException;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiPartConfig;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.UrlEncoded;
import org.jspecify.annotations.Nullable;

/**
 * Inbound data scoped to the route handler and its application-wide error handler on the same
 * thread. Body reads are lazy, bounded, and cached; access ends after framework finalization.
 */
public final class Request {
  // Java 25 readNBytes(int) starts with this much temporary storage.
  private static final int DIRECT_BODY_READ_LIMIT = 16_384;

  private final org.eclipse.jetty.server.Request delegate;
  private final Response response;
  private final int limit;
  private final int maxParameters;
  private final MultipartOptions multipartOptions;
  private final List<Upload> uploads;
  private final Thread owner;
  private boolean finished;
  private byte @Nullable [] body;
  private @Nullable InputStream input;
  private RadixRoutes.@Nullable Endpoint endpoint;
  private @Nullable Map<String, List<String>> query;
  private @Nullable Map<String, List<String>> form;
  private @Nullable Map<String, List<Upload>> files;
  private MultiPartFormData.@Nullable Parts multipart;
  private @Nullable CompletableFuture<MultiPartFormData.Parts> multipartPublished;
  private @Nullable Map<String, List<String>> cookies;
  private @Nullable Map<String, Object> attributes;
  private @Nullable Principal principal;
  private @Nullable String effectiveUrl;
  private @Nullable InetSocketAddress clientAddress;
  private List<String> webSocketProtocols;
  private BodyRepresentation representation;
  private boolean multipartClosed;

  /**
   * Binds lazy request access to the handler thread and the configured read limit.
   *
   * @param delegate transport request whose input lifecycle remains owned by Jetty
   * @param response framework response used when renewing a session identifier
   * @param limit maximum buffered request-body size in bytes
   * @param maxParameters maximum decoded pairs per query or form
   * @param multipartOptions multipart parsing configuration
   */
  Request(
      org.eclipse.jetty.server.Request delegate,
      Response response,
      int limit,
      int maxParameters,
      MultipartOptions multipartOptions) {
    this.delegate = delegate;
    this.response = response;
    this.limit = limit;
    this.maxParameters = maxParameters;
    this.multipartOptions = multipartOptions;
    uploads = new ArrayList<>();
    webSocketProtocols = List.of();
    owner = Thread.currentThread();
    representation = BodyRepresentation.UNREAD;
    org.eclipse.jetty.server.Request.addCompletionListener(delegate, _ -> closeMultipart());
  }

  /**
   * Reads the request method.
   *
   * @return the HTTP method while this handler is active
   */
  public String method() {
    check();
    return delegate.getMethod();
  }

  /**
   * Returns the Jetty session attached to this request, creating it only when requested. Sessions
   * are available only when enabled on the application.
   *
   * @param create whether an absent session should be created
   * @return the session, or null when absent or session support is disabled
   * @throws IllegalStateException if creation is requested after response commitment
   */
  public @Nullable Session session(boolean create) {
    check();
    var existing = delegate.getSession(false);
    if (existing != null || !create) {
      return existing;
    }

    response.checkSessionCreation();
    return delegate.getSession(true);
  }

  /**
   * Renews an existing session identifier after an authentication or privilege change. Session
   * attributes remain attached to the renewed session, and Jetty updates the response cookie.
   *
   * @return the new session identifier
   * @throws IllegalStateException if no session exists
   */
  public String renewSessionId() {
    check();
    var session = delegate.getSession(false);
    if (session == null) {
      throw new IllegalStateException("No session exists to renew");
    }

    response.renewSessionId(session, delegate);
    return session.getId();
  }

  /**
   * Reads the path used for route matching.
   *
   * @return Jetty's canonically encoded path without query or fragment
   */
  public String path() {
    check();
    return org.eclipse.jetty.server.Request.getPathInContext(delegate);
  }

  /**
   * Reads the request URL without query or fragment, preserving encoded path spelling. Authority is
   * request metadata, not a trusted application origin.
   *
   * @return URL assembled by the HTTP transport
   */
  public String url() {
    check();
    return HttpURI.build(delegate.getHttpURI()).query(null).fragment(null).asString();
  }

  /**
   * Reads the transport's complete URL without decoding or rebuilding its raw query.
   *
   * @return request URL including the raw query when present
   */
  public String fullUrl() {
    check();
    return delegate.getHttpURI().asString();
  }

  /**
   * Reads the request URI scheme. Absolute request targets can supply this value; it does not prove
   * transport security. Proxy headers do not alter it by default.
   *
   * @return scheme supplied by the transport
   */
  public String scheme() {
    check();
    return delegate.getHttpURI().getScheme();
  }

  /**
   * Reads the transport-normalized request authority, retaining IPv6 brackets. A scheme's default
   * port can be omitted even when present in Host. This is distinct from the local socket address.
   *
   * @return authority represented by the transport URI
   */
  public String authority() {
    check();
    return delegate.getHttpURI().getAuthority();
  }

  /**
   * Reads the logical server host from the request authority, with the transport's fallback.
   *
   * @return logical host; IPv6 literals retain brackets
   */
  public String serverName() {
    check();
    return org.eclipse.jetty.server.Request.getServerName(delegate);
  }

  /**
   * Reads the logical server port from the authority or the transport's scheme/configuration. This
   * is not necessarily the port on which the TCP listener accepted the request.
   *
   * @return logical port, or -1 when unavailable
   */
  public int serverPort() {
    check();
    return org.eclipse.jetty.server.Request.getServerPort(delegate);
  }

  /**
   * Reads the HTTP version rather than an application-provided forwarding header.
   *
   * @return protocol spelling, for example HTTP/1.1
   */
  public String protocol() {
    check();
    return delegate.getConnectionMetaData().getHttpVersion().asString();
  }

  /**
   * Reports actual transport security, independently of an absolute target's scheme or forwarding
   * headers. A plain connection behind a TLS-terminating proxy is still plain at this endpoint.
   *
   * @return whether the direct transport endpoint is secure
   */
  public boolean isSecure() {
    check();
    return delegate.getConnectionMetaData().getConnection().getEndPoint().isSecure();
  }

  /**
   * Reads the actual transport peer, bypassing logical/proxy address wrappers. The returned JDK
   * address can be retained after this request ends and does not trigger reverse DNS lookup.
   *
   * @return direct remote socket address, or null if the transport does not expose one
   */
  public @Nullable SocketAddress remoteAddress() {
    check();
    return delegate.getConnectionMetaData().getConnection().getEndPoint().getRemoteSocketAddress();
  }

  /**
   * Reads the actual local transport endpoint, independently of the requested authority.
   *
   * @return direct local socket address, or null if the transport does not expose one
   */
  public @Nullable SocketAddress localAddress() {
    check();
    return delegate.getConnectionMetaData().getConnection().getEndPoint().getLocalSocketAddress();
  }

  /**
   * Reports whether explicitly trusted forwarding metadata was applied.
   *
   * @return false when forwarding is disabled or unavailable
   */
  public boolean isForwarded() {
    check();
    return effectiveUrl != null;
  }

  /**
   * Reads the effective full URL, preserving raw path/query spelling. Without trusted forwarding
   * this is the direct request URL; neither form establishes a trusted application origin.
   *
   * @return full URL suitable for raw metadata inspection
   */
  public String effectiveUrl() {
    check();
    return effectiveUrl == null ? fullUrl() : effectiveUrl;
  }

  /**
   * Reads the effective client address, defaulting to the direct physical IP peer.
   *
   * @return IP socket address, or null when unavailable
   */
  public @Nullable InetSocketAddress clientAddress() {
    check();
    if (effectiveUrl != null) {
      return clientAddress;
    }

    var address = remoteAddress();
    return address instanceof InetSocketAddress inet ? inet : null;
  }

  /**
   * Reads the original composed route template, without substituting captured values.
   *
   * @return named route template, or null if no endpoint was selected
   * @throws IllegalStateException if accessed outside the handler thread or lifetime
   */
  public @Nullable String routePattern() {
    check();
    return endpoint == null ? null : endpoint.routePattern();
  }

  /**
   * Reads a named single-segment path parameter from the selected route.
   *
   * @param name parameter name, including names inherited from parent groups
   * @return UTF-8 percent-decoded value; literal plus signs remain plus signs
   * @throws IllegalArgumentException if the selected route has no such parameter
   * @throws IllegalStateException if accessed outside the handler's thread or lifetime
   */
  public String pathParam(String name) {
    check();
    if (endpoint == null) {
      throw new IllegalArgumentException("No route parameters are available");
    }

    return endpoint.parameter(path(), name);
  }

  /**
   * Takes an immutable snapshot of named parameters from the selected composed route. Values use
   * the same single percent-decoding step as pathParam; literal plus signs remain unchanged.
   *
   * @return decoded names and values, empty for a literal route or before route selection
   */
  public Map<String, String> pathParamMap() {
    check();
    if (endpoint == null || endpoint.parameters().isEmpty()) {
      return Map.of();
    }

    var parameters = new LinkedHashMap<String, String>();
    var rawPath = path();
    for (var name : endpoint.parameters().keySet()) {
      parameters.put(name, endpoint.parameter(rawPath, name));
    }
    return Collections.unmodifiableMap(parameters);
  }

  /**
   * Reads the query component without decoding, including its original percent escapes.
   *
   * @return raw query without the question mark, or null when absent
   */
  public @Nullable String queryString() {
    check();
    return delegate.getHttpURI().getQuery();
  }

  /**
   * Reads the first UTF-8 query value for a case-sensitive decoded name.
   *
   * @param name decoded parameter name
   * @return first value, or null when absent
   */
  public @Nullable String queryParam(String name) {
    var values = queryParams(name);
    return values.isEmpty() ? null : values.getFirst();
  }

  /**
   * Reads repeated query values in arrival order, with plus decoded as space.
   *
   * @param name decoded parameter name
   * @return immutable values for the name, or an empty list
   */
  public List<String> queryParams(String name) {
    return queryParamMap().getOrDefault(Objects.requireNonNull(name), List.of());
  }

  /**
   * Lazily decodes the query, keeping it separate from body parameters. Empty values and names are
   * preserved; empty pairs between ampersands are ignored.
   *
   * @return deeply immutable map of decoded names to values in arrival order
   * @throws BadRequestException if encoding is malformed or the pair limit is exceeded
   */
  public Map<String, List<String>> queryParamMap() {
    check();
    if (query == null) {
      var raw = delegate.getHttpURI().getQuery();
      query = parameters(raw);
    }

    return query;
  }

  /**
   * Reads the first UTF-8 form value for a case-sensitive decoded name.
   *
   * @param name decoded parameter name
   * @return first value, or null when absent
   * @throws IOException if input fails
   */
  public @Nullable String formParam(String name) throws IOException {
    var values = formParams(name);
    return values.isEmpty() ? null : values.getFirst();
  }

  /**
   * Reads repeated form values in arrival order. URL-encoded forms decode plus as space; multipart
   * fields preserve their submitted text.
   *
   * @param name decoded parameter name
   * @return immutable values for the name, or an empty list
   * @throws IOException if input fails
   */
  public List<String> formParams(String name) throws IOException {
    return formParamMap().getOrDefault(Objects.requireNonNull(name), List.of());
  }

  /**
   * Lazily decodes UTF-8 URL-encoded fields or multipart text fields without merging query
   * parameters. Multipart and raw-body access are mutually exclusive. Missing or unsupported
   * representation metadata is rejected.
   *
   * @return deeply immutable map of decoded names to values in arrival order
   * @throws IOException if input fails
   * @throws BadRequestException if encoding is malformed or the pair limit is exceeded
   * @throws ContentTooLargeException if the body exceeds the byte limit
   * @throws UnsupportedMediaTypeException if the media type or charset is unsupported
   */
  public Map<String, List<String>> formParamMap() throws IOException {
    check();
    if (form == null) {
      if (isMultipart()) {
        form = multipartFields();
      } else {
        checkFormType();

        try {
          form =
              parameters(
                  StandardCharsets.UTF_8
                      .newDecoder()
                      .decode(ByteBuffer.wrap(bodyBytes()))
                      .toString());
        } catch (CharacterCodingException failure) {
          throw new BadRequestException("Malformed UTF-8 form body", failure);
        }
      }
    }

    return form;
  }

  /**
   * Reads the first value for a header.
   *
   * @param name case-insensitive field name
   * @return the field value, or null when absent
   */
  public @Nullable String header(String name) {
    check();
    return delegate.getHeaders().get(name);
  }

  /**
   * Reads repeated header field values without splitting comma-separated content.
   *
   * @param name case-insensitive field name
   * @return immutable raw field values in arrival order, or an empty list
   */
  public List<String> headers(String name) {
    check();
    return List.copyOf(delegate.getHeaders().getValuesList(Objects.requireNonNull(name)));
  }

  /**
   * Returns the client-offered WebSocket subprotocols during a WebSocket listener factory call.
   * Jetty parses repeated and comma-separated offers; ordinary HTTP handlers receive an empty list.
   *
   * @return immutable offered protocol names in client order
   */
  public List<String> webSocketProtocols() {
    check();
    return webSocketProtocols;
  }

  /**
   * Copies all received fields into a deeply immutable, case-insensitive snapshot. Repeated field
   * lines retain their order and are not split at commas.
   *
   * @return immutable names and raw value lists
   */
  public Map<String, List<String>> headerMap() {
    check();
    Map<String, List<String>> fields = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (var field : delegate.getHeaders()) {
      fields.computeIfAbsent(field.getName(), ignored -> new ArrayList<>()).add(field.getValue());
    }
    fields.replaceAll((_, values) -> List.copyOf(values));
    return Collections.unmodifiableMap(fields);
  }

  /**
   * Reads the first cookie with the exact case-sensitive name, without URL decoding.
   *
   * @param name cookie name
   * @return first value, or null when absent
   */
  public @Nullable String cookie(String name) {
    var values = cookies(name);
    return values.isEmpty() ? null : values.getFirst();
  }

  /**
   * Reads all cookies with the exact name in arrival order. Duplicate names can indicate ambiguity;
   * authentication code can reject them explicitly instead of relying on their order.
   *
   * @param name case-sensitive cookie name
   * @return immutable values, empty when absent
   */
  public List<String> cookies(String name) {
    return cookieValues().getOrDefault(Objects.requireNonNull(name), List.of());
  }

  /**
   * Takes an immutable first-value snapshot, consistent with cookie(String). Parsing uses Jetty's
   * RFC6265 compatibility policy; malformed cookies may be discarded and quoted values unquoted.
   *
   * @return case-sensitive map with the first value for each name
   */
  public Map<String, String> cookieMap() {
    var first = new LinkedHashMap<String, String>();
    cookieValues().forEach((name, values) -> first.put(name, values.getFirst()));
    return Collections.unmodifiableMap(first);
  }

  /**
   * Reads the identity explicitly assigned by application authentication logic. The framework does
   * not infer an identity from request headers or verify credentials in this accessor.
   *
   * @return application principal, or null when none was assigned
   */
  public @Nullable Principal principal() {
    check();
    return principal;
  }

  /**
   * Assigns an application-owned identity for subsequent handler and error-handler access. Passing
   * null clears the identity; the principal object is not copied or made thread-safe.
   *
   * @param value authenticated application identity, or null to clear
   * @return this request
   */
  public Request principal(@Nullable Principal value) {
    check();
    principal = value;
    return this;
  }

  /**
   * Reads an application-owned value stored for this request. Keys are case-sensitive.
   *
   * @param name attribute key
   * @return stored value, or null when absent
   */
  public @Nullable Object attribute(String name) {
    check();
    Objects.requireNonNull(name);
    return attributes == null ? null : attributes.get(name);
  }

  /**
   * Stores a request-local value, or removes the key when value is null. Storage is allocated only
   * for the first nonnull value. Values remain application-owned and are not cloned.
   *
   * @param name case-sensitive attribute key
   * @param value application value, or null to remove
   * @return this request
   */
  public Request attribute(String name, @Nullable Object value) {
    check();
    Objects.requireNonNull(name);
    if (value == null) {
      if (attributes != null) {
        attributes.remove(name);
      }
    } else {
      if (attributes == null) {
        attributes = new HashMap<>();
      }

      attributes.put(name, value);
    }

    return this;
  }

  /**
   * Takes an immutable snapshot of attribute bindings. Values are the original application objects,
   * not deep copies, and may themselves be mutable.
   *
   * @return immutable key/value bindings
   */
  public Map<String, Object> attributeMap() {
    check();
    return attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  /**
   * Reads the bounded body as UTF-8 text.
   *
   * @return decoded request body
   * @throws IOException if input fails
   * @throws ContentTooLargeException if the body exceeds the configured limit
   */
  public String bodyText() throws IOException {
    return new String(bodyBytes(), StandardCharsets.UTF_8);
  }

  /**
   * Reads and caches the bounded body, returning a defensive copy.
   *
   * @return request body bytes
   * @throws IOException if input fails
   * @throws ContentTooLargeException if the body exceeds the configured limit
   */
  public byte[] bodyBytes() throws IOException {
    check();
    claim(BodyRepresentation.BUFFERED);
    if (body == null) {
      body = readBody();
    }

    if (body.length > limit) {
      throw new ContentTooLargeException();
    }

    return Objects.requireNonNull(body).clone();
  }

  /**
   * Reads a bounded body while avoiding an oversized temporary array for short declared lengths.
   * The adapter remains owned by the HTTP engine, including its input/draining lifecycle.
   *
   * @return buffered body, including an oversized sentinel for repeatable limit rejection
   * @throws IOException if content ends prematurely or input fails
   * @throws ContentTooLargeException if the body exceeds the configured limit
   * @throws EOFException if the body ends before its declared length
   * @throws BadRequestException if the body exceeds its declared length
   */
  private byte[] readBody() throws IOException {
    long declaredLength = delegate.getLength();
    if (declaredLength > limit) {
      throw new ContentTooLargeException();
    }

    // Do not close this adapter: the HTTP engine owns request input/draining.
    var source = Content.Source.asInputStream(delegate);
    if (declaredLength >= 0 && declaredLength <= Math.min(limit, DIRECT_BODY_READ_LIMIT)) {
      var exact = new byte[(int) declaredLength];
      if (source.readNBytes(exact, 0, exact.length) != exact.length) {
        throw new EOFException("Request body ended before its declared length");
      }

      if (source.read() != -1) {
        throw new BadRequestException("Request body exceeds its declared length");
      }

      return exact;
    }

    var result = source.readNBytes(limit + 1);
    if (result.length > limit) {
      // Cache the oversized sentinel so a repeated read cannot appear empty.
      return result;
    }

    if (declaredLength >= 0 && result.length < declaredLength) {
      throw new EOFException("Request body ended before its declared length");
    }

    if (declaredLength >= 0 && result.length > declaredLength) {
      throw new BadRequestException("Request body exceeds its declared length");
    }

    return result;
  }

  /**
   * Opens one bounded request-body stream for the active handler. The framework closes it when
   * handler processing ends; it cannot be combined with buffered or multipart body access.
   *
   * @return handler-scoped bounded input stream
   * @throws ContentTooLargeException if consumed input exceeds the configured limit
   */
  public InputStream input() {
    check();
    claim(BodyRepresentation.STREAMING);
    if (input == null) {
      input = boundedInput();
    }

    return input;
  }

  /**
   * Reads the first multipart upload with the exact field name, or null when absent.
   *
   * @param name exact multipart field name
   * @return first upload, or null
   * @throws IOException if multipart input cannot be parsed
   */
  public @Nullable Upload file(String name) throws IOException {
    var uploads = files(name);
    return uploads.isEmpty() ? null : uploads.getFirst();
  }

  /**
   * Reads multipart uploads for the exact field name in arrival order.
   *
   * @param name exact multipart field name
   * @return immutable uploads, or an empty list
   * @throws IOException if multipart input cannot be parsed
   */
  public List<Upload> files(String name) throws IOException {
    return files().getOrDefault(Objects.requireNonNull(name), List.of());
  }

  /**
   * Reads every multipart upload by field name in arrival order.
   *
   * @return immutable uploads grouped by field name
   * @throws IOException if multipart input cannot be parsed
   */
  public Map<String, List<Upload>> files() throws IOException {
    check();
    if (files == null) {
      var grouped = new LinkedHashMap<String, List<Upload>>();
      for (var part : parts()) {
        if (part.getFileName() != null) {
          var upload = new Upload(part, this::check);
          uploads.add(upload);
          grouped.computeIfAbsent(part.getName(), ignored -> new ArrayList<>()).add(upload);
        }
      }
      grouped.replaceAll((_, uploads) -> List.copyOf(uploads));
      files = Collections.unmodifiableMap(grouped);
    }

    return files;
  }

  /**
   * Attaches the matched endpoint so parameters can be decoded only when requested.
   *
   * @param endpoint selected immutable route metadata
   */
  void route(RadixRoutes.Endpoint endpoint) {
    this.endpoint = endpoint;
  }

  /**
   * Supplies Jetty's parsed offers immediately before invoking the WebSocket listener factory.
   *
   * @param protocols client-offered subprotocols
   */
  void webSocketProtocols(List<String> protocols) {
    check();
    webSocketProtocols = List.copyOf(protocols);
  }

  /**
   * Returns the applicable known-length admission limit for this request representation.
   *
   * @return buffered-body or multipart aggregate limit
   */
  long maxBodyBytes() {
    return isMultipart() ? multipartOptions.maxBytes() : limit;
  }

  /**
   * Publishes validated effective metadata without changing the transport request.
   *
   * @param address selected client, or null when undisclosed
   * @param url full URL preserving the raw path and query
   */
  void forwarded(@Nullable InetSocketAddress address, String url) {
    clientAddress = address;
    effectiveUrl = url;
  }

  /** Revokes access and releases local application state without closing transport-owned input. */
  void finish() {
    finished = true;
    closeInput();
    attributes = null;
    principal = null;
  }

  /** Closes one streamed request input after framework handler finalization. */
  private void closeInput() {
    if (input != null) {
      try {
        input.close();
      } catch (IOException ignored) {
        // Transport cleanup continues after input-close failure.
      }
    }
  }

  /**
   * Creates a one-shot stream that counts consumed bytes rather than declared length.
   *
   * @return a bounded stream backed by the transport request body
   */
  private InputStream boundedInput() {
    return new FilterInputStream(Content.Source.asInputStream(delegate)) {
      private long consumed;

      /** Reads one byte while enforcing the configured consumed-byte limit. */
      @Override
      public int read() throws IOException {
        check();
        var value = super.read();
        if (value >= 0) {
          count(1);
        }

        return value;
      }

      /**
       * Reads bytes into the supplied buffer while enforcing the configured consumed-byte limit.
       */
      @Override
      public int read(byte[] value, int offset, int length) throws IOException {
        check();
        var read = super.read(value, offset, length);
        if (read > 0) {
          count(read);
        }

        return read;
      }

      /** Skips bytes by reading them through the same lifecycle and size guards. */
      @Override
      public long skip(long amount) throws IOException {
        if (amount <= 0) {
          return 0;
        }

        var skipped = 0L;
        var buffer = new byte[(int) Math.min(amount, 8192)];
        while (skipped < amount) {
          var read = read(buffer, 0, (int) Math.min(buffer.length, amount - skipped));
          if (read < 0) {
            break;
          }

          skipped += read;
        }

        return skipped;
      }

      /** Adds successfully read bytes to the consumed-byte total. */
      private void count(int amount) {
        consumed += amount;
        if (consumed > limit) {
          throw new ContentTooLargeException();
        }
      }
    };
  }

  /** Closes multipart temporary resources after Jetty completes the request exchange. */
  private void closeMultipart() {
    synchronized (this) {
      multipartClosed = true;
      for (var upload : uploads) {
        upload.close();
      }

      if (multipart != null) {
        multipart.close();
      }
    }
  }

  /**
   * Reads text multipart fields in arrival order.
   *
   * @return immutable text fields grouped by name
   * @throws IOException if multipart input cannot be parsed
   */
  private Map<String, List<String>> multipartFields() throws IOException {
    var fields = new LinkedHashMap<String, List<String>>();
    for (var part : parts()) {
      if (part.getFileName() == null) {
        checkMultipartFieldType(part);
        fields
            .computeIfAbsent(part.getName(), ignored -> new ArrayList<>())
            .add(part.getContentAsString(StandardCharsets.UTF_8));
      }
    }
    fields.replaceAll((_, values) -> List.copyOf(values));
    return Collections.unmodifiableMap(fields);
  }

  /**
   * Parses the multipart body once through Jetty's bounded parser.
   *
   * @return parsed parts
   * @throws IOException if parsing is interrupted or fails
   * @throws BadRequestException if multipart syntax is invalid
   * @throws ContentTooLargeException if a configured multipart limit is exceeded
   */
  private Iterable<MultiPart.Part> parts() throws IOException {
    claim(BodyRepresentation.MULTIPART);
    if (multipartPublished == null) {
      var parsed = new CompletableFuture<MultiPartFormData.Parts>();
      multipartPublished = parsed.thenApply(this::publishMultipart);

      try {
        MultiPartFormData.onParts(
            delegate,
            delegate,
            Objects.requireNonNull(header(HttpHeaders.CONTENT_TYPE.value())),
            multipartConfig(),
            Promise.Invocable.toPromise(parsed));
      } catch (RuntimeException failure) {
        multipartPublished = null;
        throw failure;
      }
    }

    try {
      var parts = multipartPublished.get();
      synchronized (this) {
        if (multipartClosed) {
          parts.close();
          throw new IOException("Multipart request completed before parsing finished");
        }
      }
      return parts;
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while parsing multipart body", failure);
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof CompletionException completion
          && completion.getCause() instanceof HttpException httpFailure) {
        throw httpFailure;
      }

      if (failure.getCause() instanceof HttpException httpFailure) {
        throw httpFailure;
      }

      if (exceededMultipartLimit(failure)) {
        throw new ContentTooLargeException();
      }

      if (failure.getCause() instanceof EOFException) {
        throw new BadRequestException("Malformed multipart body", failure);
      }

      if (failure.getCause() instanceof IOException ioFailure) {
        throw ioFailure;
      }

      throw new BadRequestException("Malformed multipart body", failure);
    }
  }

  /**
   * Validates and publishes completed parser output as one atomic operation.
   *
   * @param parts completed parser output
   * @return the validated parser output
   * @throws CompletionException if multipart metadata is invalid
   */
  private synchronized MultiPartFormData.Parts publishMultipart(MultiPartFormData.Parts parts) {
    if (multipartClosed) {
      parts.close();
      return parts;
    }

    try {
      checkMultipartParts(parts);
    } catch (RuntimeException failure) {
      parts.close();
      throw new CompletionException(failure);
    }

    multipart = parts;
    return parts;
  }

  /**
   * Validates the required metadata for every multipart part before exposing it to handlers.
   *
   * @param parts parsed multipart parts
   * @throws BadRequestException if a part is missing required form-data metadata
   */
  private void checkMultipartParts(Iterable<MultiPart.Part> parts) {
    for (var part : parts) {
      var parameters = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
      var disposition =
          HttpField.getValueParameters(
              part.getHeaders().get(HttpHeaders.CONTENT_DISPOSITION.value()), parameters);
      if (!"form-data".equalsIgnoreCase(disposition)
          || part.getName() == null
          || part.getName().isEmpty()) {
        throw new BadRequestException("Malformed multipart part metadata");
      }
    }
  }

  /**
   * Requires text multipart fields to declare only UTF-8 when they declare a charset.
   *
   * @param part text field candidate
   * @throws UnsupportedMediaTypeException if the media type or charset is unsupported
   */
  private void checkMultipartFieldType(MultiPart.Part part) {
    var parameters = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
    var type =
        HttpField.getValueParameters(
            part.getHeaders().get(HttpHeaders.CONTENT_TYPE.value()), parameters);
    if (type != null && !MediaType.TEXT_PLAIN.value().equalsIgnoreCase(type)) {
      throw new UnsupportedMediaTypeException();
    }

    try {
      if (parameters.containsKey("charset")
          && !StandardCharsets.UTF_8.equals(Charset.forName(parameters.get("charset")))) {
        throw new UnsupportedMediaTypeException();
      }
    } catch (IllegalArgumentException failure) {
      throw new UnsupportedMediaTypeException(
          "Unsupported multipart field representation", failure);
    }
  }

  /**
   * Creates the configured Jetty parser settings for one request.
   *
   * @return bounded Jetty parser configuration
   */
  private MultiPartConfig multipartConfig() {
    var builder =
        new MultiPartConfig.Builder()
            .maxSize(multipartOptions.maxBytes())
            .maxPartSize(multipartOptions.maxFileBytes())
            .maxParts(multipartOptions.maxParts())
            .maxHeadersSize(multipartOptions.maxPartHeadersBytes())
            .maxMemoryPartSize(multipartOptions.maxMemoryPartBytes());
    builder.location(
        multipartOptions.temporaryDirectory() == null
            ? Path.of(System.getProperty("java.io.tmpdir"))
            : multipartOptions.temporaryDirectory());

    return builder.build();
  }

  /**
   * Identifies Jetty's documented multipart size-limit failures through asynchronous wrappers.
   *
   * @param failure parser completion failure
   * @return whether a configured size limit was exceeded
   */
  private static boolean exceededMultipartLimit(Throwable failure) {
    for (var current = failure; current != null; current = current.getCause()) {
      if (current instanceof IllegalStateException && current.getMessage() != null) {
        var message = current.getMessage();
        if (message.startsWith("max length exceeded:")
            || message.startsWith("max file size exceeded:")
            || message.startsWith("max memory file size exceeded:")
            || message.startsWith("headers max length exceeded:")
            || message.startsWith("Form with too many keys")
            || message.toLowerCase(java.util.Locale.ROOT).contains("too many parts")
            || message.toLowerCase(java.util.Locale.ROOT).contains("headers size")) {
          return true;
        }
      }
    }

    return false;
  }

  /**
   * Reports whether the request declares multipart form data.
   *
   * @return whether the Content-Type is multipart/form-data
   */
  private boolean isMultipart() {
    try {
      var type =
          HttpField.getValueParameters(header(HttpHeaders.CONTENT_TYPE.value()), new HashMap<>());
      return "multipart/form-data".equalsIgnoreCase(type);
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  /**
   * Claims the request input for one mutually exclusive representation.
   *
   * @param requested requested body representation
   * @throws IllegalStateException if another representation already consumed the input
   */
  private void claim(BodyRepresentation requested) {
    if (representation != BodyRepresentation.UNREAD && representation != requested) {
      throw new IllegalStateException("Request body has already been consumed");
    }

    representation = requested;
  }

  /** Tracks which public request representation consumed transport input. */
  private enum BodyRepresentation {
    UNREAD,
    BUFFERED,
    STREAMING,
    MULTIPART
  }

  /**
   * Requires a URL-encoded form with an absent or supported UTF-8 charset declaration.
   *
   * @throws UnsupportedMediaTypeException if the representation cannot be decoded as a form
   */
  private void checkFormType() {
    var parameters = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);

    try {
      var type = HttpField.getValueParameters(header(HttpHeaders.CONTENT_TYPE.value()), parameters);
      if (!MediaType.APPLICATION_FORM_URLENCODED.value().equalsIgnoreCase(type)) {
        throw new UnsupportedMediaTypeException();
      }

      if (parameters.containsKey("charset")) {
        var charset = parameters.get("charset");
        if (charset == null || !StandardCharsets.UTF_8.equals(Charset.forName(charset))) {
          throw new UnsupportedMediaTypeException();
        }
      }
    } catch (IllegalArgumentException failure) {
      throw new UnsupportedMediaTypeException("Unsupported form representation", failure);
    }
  }

  /**
   * Uses Jetty's UTF-8 decoder with a pair limit, publishing no partial result on failure.
   *
   * @param encoded encoded query or form, or null for an absent query
   * @return deeply immutable parameters
   * @throws BadRequestException if decoding fails or the pair limit is exceeded
   */
  private Map<String, List<String>> parameters(@Nullable String encoded) {
    var parsed = new LinkedHashMap<String, List<String>>();
    if (encoded != null) {
      try {
        UrlEncoded.decodeTo(
            encoded,
            (name, value) -> parsed.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value),
            StandardCharsets.UTF_8,
            maxParameters);
      } catch (IllegalArgumentException | IllegalStateException failure) {
        throw new BadRequestException("Malformed parameters or parameter limit exceeded", failure);
      }
    }

    parsed.replaceAll((_, values) -> List.copyOf(values));
    return Collections.unmodifiableMap(parsed);
  }

  /**
   * Copies the transport's connection-cached cookie objects into a request-local immutable map.
   *
   * @return repeated values separated by case-sensitive name
   */
  private Map<String, List<String>> cookieValues() {
    check();
    if (cookies == null) {
      var parsed = new LinkedHashMap<String, List<String>>();
      for (var cookie : org.eclipse.jetty.server.Request.getCookies(delegate)) {
        parsed
            .computeIfAbsent(cookie.getName(), ignored -> new ArrayList<>())
            .add(cookie.getValue());
      }
      parsed.replaceAll((_, values) -> List.copyOf(values));
      cookies = Collections.unmodifiableMap(parsed);
    }

    return cookies;
  }

  /**
   * Enforces handler-thread ownership and rejects access after completion or failure.
   *
   * @throws IllegalStateException if accessed from another thread or after handler completion
   */
  private void check() {
    if (Thread.currentThread() != owner || finished) {
      throw new IllegalStateException("Request is only available inside its handler");
    }
  }
}
