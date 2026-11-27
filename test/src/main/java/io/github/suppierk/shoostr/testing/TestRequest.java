package io.github.suppierk.shoostr.testing;

import io.github.suppierk.shoostr.http.HttpCharacters;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Configures one outgoing request during {@link TestServer#send}. Not an incoming Shoostr request.
 * Each send has fresh configuration; retaining it after the callback does not allow later changes.
 */
public final class TestRequest {
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{[A-Za-z_][A-Za-z0-9_-]*}");
  private static final HexFormat HEX = HexFormat.of().withUpperCase();

  private final URI baseUri;
  private final HttpRequest.Builder builder;
  private String method;
  private HttpRequest.BodyPublisher body;
  private boolean pathConfigured;
  private boolean finished;

  /**
   * Creates callback-scoped configuration with GET, no body and a ten-second request timeout.
   *
   * @param baseUri fixture origin against which relative paths resolve
   */
  TestRequest(URI baseUri) {
    this.baseUri = Objects.requireNonNull(baseUri);
    this.builder = HttpRequest.newBuilder(baseUri).timeout(Duration.ofSeconds(10));
    this.method = "GET";
    this.body = HttpRequest.BodyPublishers.noBody();
    this.pathConfigured = false;
    this.finished = false;
  }

  /**
   * Sets a relative route path, optionally including an already encoded query string. Schemes,
   * authorities and fragments are rejected; this configurator cannot select a different origin.
   *
   * @param path relative URI such as /users/42?include=roles
   * @return this configuration
   * @throws IllegalArgumentException if the URI is invalid, empty or not relative to this origin
   * @throws NullPointerException if path is null
   * @throws IllegalStateException if this callback has finished
   */
  public TestRequest path(String path) {
    requireOpen();

    builder.uri(resolvePath(baseUri, path));
    pathConfigured = true;

    return this;
  }

  /**
   * Sets a relative path and appends query parameters before assigning it to the request.
   *
   * @param path relative path with an optional raw query
   * @param configure synchronous query configuration
   * @return this configuration
   * @throws IllegalArgumentException if the target is invalid
   * @throws NullPointerException if an argument is null
   * @throws IllegalStateException if this callback has finished
   */
  public TestRequest path(String path, Consumer<PathBuilder> configure) {
    requireOpen();

    return path(buildPath(path, configure));
  }

  /**
   * Fills named segments and appends query parameters before assigning the path to the request.
   *
   * @param template relative path template with an optional raw query
   * @param configure synchronous path and query configuration
   * @return this configuration
   * @throws IllegalArgumentException if the template is invalid
   * @throws NullPointerException if an argument is null
   * @throws IllegalStateException if a placeholder lacks a value or this callback has finished
   */
  public TestRequest pathTemplate(String template, Consumer<PathBuilder> configure) {
    requireOpen();

    return path(buildPathFromTemplate(template, configure));
  }

  /**
   * Starts with an already encoded relative target, optionally including a raw query.
   *
   * @param path relative target such as {@code /search?locale=en}
   * @param configure synchronous query configuration
   * @return completed relative path and query
   * @throws IllegalArgumentException if the target is empty, invalid or specifies a scheme,
   *     authority or fragment
   * @throws NullPointerException if an argument is null
   */
  static String buildPath(String path, Consumer<PathBuilder> configure) {
    final var target = parseRelative(path);
    final var builder =
        new PathBuilder(new String[] {target.getRawPath()}, Map.of(), target.getRawQuery());

    return configurePath(builder, configure);
  }

  /**
   * Starts with a relative target whose whole path segments can be placeholders such as {@code
   * {room}}. Names start with an ASCII letter or underscore and can also contain digits and hyphens
   * after the first character. Constrained patterns and catch-alls are route syntax. Use plain
   * named segments when building a request to those routes.
   *
   * @param template relative path template with an optional already encoded query
   * @param configure synchronous path and query configuration
   * @return completed relative path and query
   * @throws IllegalArgumentException if the template or its literal target is invalid
   * @throws IllegalStateException if any placeholder lacks a value
   * @throws NullPointerException if an argument is null
   */
  static String buildPathFromTemplate(String template, Consumer<PathBuilder> configure) {
    final var queryStart = Objects.requireNonNull(template).indexOf(HttpCharacters.QUERY_SEPARATOR);
    final var path = queryStart < 0 ? template : template.substring(0, queryStart);
    final var querySuffix = queryStart < 0 ? "" : template.substring(queryStart);
    final var segments = path.split(HttpCharacters.PATH_SEPARATOR_STRING, -1);
    final var literalProbe = segments.clone();
    final Map<String, String> pathParameters = new HashMap<>();

    for (int index = 0; index < segments.length; index++) {
      final var segment = segments[index];
      if (PLACEHOLDER.matcher(segment).matches()) {
        pathParameters.put(segment, "");
        literalProbe[index] = "value";
      }
    }

    final var target =
        parseRelative(
            String.join(HttpCharacters.PATH_SEPARATOR_STRING, literalProbe) + querySuffix);
    final var builder = new PathBuilder(segments, pathParameters, target.getRawQuery());

    return configurePath(builder, configure);
  }

  /**
   * Limits mutations to the synchronous callback and returns a detached path.
   *
   * @param builder fresh path builder
   * @param configure caller's configuration
   * @return completed relative path
   */
  private static String configurePath(PathBuilder builder, Consumer<PathBuilder> configure) {
    try {
      Objects.requireNonNull(configure).accept(builder);
      return builder.build();
    } finally {
      builder.finished = true;
    }
  }

  /**
   * Resolves a request target against the fixture origin while preserving raw URI spelling.
   *
   * @param baseUri fixture origin
   * @param path relative route path and optional raw query
   * @return resolved target
   * @throws IllegalArgumentException if the target is invalid or specifies another origin or
   *     fragment
   */
  static URI resolvePath(URI baseUri, String path) {
    return baseUri.resolve(parseRelative(path));
  }

  /**
   * Parses a raw target without allowing it to select an origin or include a fragment.
   *
   * @param path relative route path and optional raw query
   * @return validated relative URI
   * @throws IllegalArgumentException if the target is invalid
   */
  private static URI parseRelative(String path) {
    final var relative = URI.create(Objects.requireNonNull(path));
    if (path.isEmpty()
        || relative.isAbsolute()
        || relative.getRawAuthority() != null
        || relative.getRawFragment() != null
        || path.startsWith("//")) {
      throw new IllegalArgumentException(
          "A nonempty relative path without authority or fragment is required");
    }

    return relative;
  }

  /**
   * Sets a JDK-valid HTTP method without discarding a previously configured body.
   *
   * @param method case-sensitive method token
   * @return this configuration
   * @throws IllegalArgumentException if the JDK rejects the method
   * @throws NullPointerException if method is null
   * @throws IllegalStateException if this callback has finished
   */
  public TestRequest method(String method) {
    requireOpen();

    builder.method(Objects.requireNonNull(method), body);
    this.method = method;

    return this;
  }

  /**
   * Sets a registered method using its wire value, including hyphenated tokens.
   *
   * @param method registered method
   * @return this configuration
   * @throws NullPointerException if method is null
   * @throws IllegalStateException if this callback has finished
   */
  public TestRequest method(HttpMethods method) {
    return method(Objects.requireNonNull(method).value());
  }

  /**
   * Appends a header value, retaining earlier values for that name.
   *
   * @param name header wire name
   * @param value header value
   * @return this configuration
   * @throws IllegalArgumentException if the JDK rejects the header
   * @throws NullPointerException if an argument is null
   * @throws IllegalStateException if this callback has finished
   */
  public TestRequest header(String name, String value) {
    requireOpen();

    builder.header(name, value);

    return this;
  }

  /**
   * Appends a header through the existing HTTP header vocabulary.
   *
   * @param name header name
   * @param value header value
   * @return this configuration
   * @throws IllegalArgumentException if the JDK rejects the header
   * @throws NullPointerException if an argument is null
   * @throws IllegalStateException if this callback has finished
   */
  public TestRequest header(HttpHeaders name, String value) {
    return header(Objects.requireNonNull(name).value(), value);
  }

  /**
   * Copies raw bytes for this request without changing its method.
   *
   * @param bytes body copied immediately, including an empty body
   * @return this configuration
   * @throws NullPointerException if bytes is null
   * @throws IllegalStateException if this callback has finished
   */
  public TestRequest body(byte[] bytes) {
    requireOpen();

    body = HttpRequest.BodyPublishers.ofByteArray(Objects.requireNonNull(bytes).clone());
    builder.method(method, body);

    return this;
  }

  /**
   * Overrides the JDK request timeout. This is not an independent streaming body read deadline.
   *
   * @param timeout positive request timeout
   * @return this configuration
   * @throws IllegalArgumentException if timeout is not positive
   * @throws NullPointerException if timeout is null
   * @throws IllegalStateException if this callback has finished
   */
  public TestRequest timeout(Duration timeout) {
    requireOpen();

    builder.timeout(timeout);

    return this;
  }

  /**
   * Builds the configured JDK request once the callback completes.
   *
   * @return immutable outgoing request
   * @throws IllegalStateException if no path was configured or the callback has finished
   */
  @SuppressWarnings("javabugs:S6416")
  // Missing paths deliberately fail after caller configuration.
  HttpRequest build() {
    requireOpen();

    if (!pathConfigured) {
      throw new IllegalStateException("Configure a relative path before sending a request");
    }

    finished = true;

    return builder.build();
  }

  /** Ends configuration even when the callback throws. */
  void finish() {
    finished = true;
  }

  /**
   * Rejects use outside the configuration callback.
   *
   * @throws IllegalStateException if configuration has finished
   */
  private void requireOpen() {
    if (finished) {
      throw new IllegalStateException("Request configuration is finished");
    }
  }

  /** Configures one path during an HTTP request or WebSocket handshake callback. */
  public static final class PathBuilder {
    private final String[] segments;
    private final Map<String, String> pathParameters;
    private final StringJoiner query;
    private boolean finished;

    /**
     * Creates a builder with a parsed path and optional raw query.
     *
     * @param segments path segments with any original placeholders
     * @param pathParameters known placeholders with empty initial values
     * @param rawQuery original query without its delimiter, or null when absent
     */
    private PathBuilder(
        String[] segments, Map<String, String> pathParameters, @Nullable String rawQuery) {
      this.segments = segments;
      this.pathParameters = pathParameters;
      this.finished = false;
      this.query = new StringJoiner("&", "?", "").setEmptyValue(rawQuery == null ? "" : "?");

      if (rawQuery != null && !rawQuery.isEmpty()) {
        query.add(rawQuery);
      }
    }

    /**
     * Fills every occurrence of a named path segment, replacing an earlier assignment.
     *
     * @param name case-sensitive placeholder name without braces
     * @param value unencoded segment value
     * @return this builder
     * @throws IllegalArgumentException if the name is unknown, the value is empty, is a dot
     *     segment, contains a slash or backslash, or contains malformed Unicode
     * @throws NullPointerException if an argument is null
     * @throws IllegalStateException if path configuration has finished
     */
    public PathBuilder pathParam(String name, String value) {
      requireOpen();

      final var placeholder =
          HttpCharacters.OPEN_CURLY_BRACE_STRING
              + Objects.requireNonNull(name)
              + HttpCharacters.CLOSE_CURLY_BRACE_STRING;
      if (!pathParameters.containsKey(placeholder)) {
        throw new IllegalArgumentException("Unknown path parameter: " + name);
      }

      if (Objects.requireNonNull(value).isEmpty()
          || HttpCharacters.DOT_STRING.equals(value)
          || "..".equals(value)
          || value.indexOf(HttpCharacters.PATH_SEPARATOR) >= 0
          || value.indexOf(HttpCharacters.BACKSLASH) >= 0) {
        throw new IllegalArgumentException("A nonempty path segment is required: " + name);
      }

      pathParameters.put(placeholder, encode(value));
      return this;
    }

    /**
     * Appends a query pair after the original raw query and any earlier pairs. Repeated names
     * retain their order, and empty values are allowed.
     *
     * @param name unencoded query name
     * @param value unencoded query value
     * @return this builder
     * @throws IllegalArgumentException if a name or value contains malformed Unicode
     * @throws NullPointerException if an argument is null
     * @throws IllegalStateException if path configuration has finished
     */
    public PathBuilder queryParam(String name, String value) {
      requireOpen();

      query.add(encode(name) + HttpCharacters.EQUALS_SIGN_STRING + encode(value));
      return this;
    }

    /**
     * Returns the current target. Later builder changes do not affect an earlier result.
     *
     * @return relative path and optional query
     * @throws IllegalStateException if a placeholder has no value
     */
    private String build() {
      final var path = new StringJoiner(HttpCharacters.PATH_SEPARATOR_STRING);

      for (var segment : segments) {
        final var value = pathParameters.get(segment);
        if ("".equals(value)) {
          throw new IllegalStateException("Missing path parameter: " + segment);
        }

        path.add(value == null ? segment : value);
      }

      return path.toString() + query;
    }

    /**
     * Rejects mutation after the configuration callback.
     *
     * @throws IllegalStateException if path configuration has finished
     */
    private void requireOpen() {
      if (finished) {
        throw new IllegalStateException("Path configuration is finished");
      }
    }

    /**
     * Percent-encodes a single UTF-8 component, leaving only unreserved characters literal.
     *
     * @param value unencoded component
     * @return encoded component
     * @throws IllegalArgumentException if value contains malformed Unicode
     */
    private static String encode(String value) {
      try {
        final var bytes = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
        final var encoded = new StringBuilder(bytes.remaining());

        while (bytes.hasRemaining()) {
          final var next = bytes.get();
          final var character = Byte.toUnsignedInt(next);
          if (isUnreserved(character)) {
            encoded.append((char) character);
          } else {
            encoded.append(HttpCharacters.PERCENT_SIGN).append(HEX.toHexDigits(next));
          }
        }

        return encoded.toString();
      } catch (CharacterCodingException failure) {
        throw new IllegalArgumentException(
            "A target parameter must contain valid Unicode", failure);
      }
    }

    /**
     * Checks for an unreserved URI character.
     *
     * @param character unsigned UTF-8 byte
     * @return whether the byte can be written literally
     */
    private static boolean isUnreserved(int character) {
      return (character >= 'a' && character <= 'z')
          || (character >= 'A' && character <= 'Z')
          || (character >= '0' && character <= '9')
          || character == HttpCharacters.DASH
          || character == HttpCharacters.DOT
          || character == HttpCharacters.UNDERSCORE
          || character == HttpCharacters.TILDE;
    }
  }
}
