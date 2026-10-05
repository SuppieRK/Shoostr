package io.github.suppierk.shoostr.testing;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Objects;

/**
 * Configures one outgoing request during {@link TestServer#send}. Not an incoming Shoostr request.
 * Each send has fresh configuration; retaining it after the callback does not allow later changes.
 */
public final class TestRequest {
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
    builder = HttpRequest.newBuilder(baseUri).timeout(Duration.ofSeconds(10));
    method = "GET";
    body = HttpRequest.BodyPublishers.noBody();
    pathConfigured = false;
    finished = false;
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
    var relative = URI.create(Objects.requireNonNull(path));
    if (path.isEmpty()
        || relative.isAbsolute()
        || relative.getRawAuthority() != null
        || relative.getRawFragment() != null
        || path.startsWith("//")) {
      throw new IllegalArgumentException(
          "A nonempty relative path without authority or fragment is required");
    }

    builder.uri(baseUri.resolve(relative));
    pathConfigured = true;
    return this;
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
}
