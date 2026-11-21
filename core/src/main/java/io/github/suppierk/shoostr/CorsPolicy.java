package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpCharacters;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Mutable cross-origin configuration; Cors captures defensive copies before serving. Each policy
 * has standalone defaults; selecting a replacement does not merge collections or inherit omitted
 * fields. Origins use exact serialized HTTP(S) spelling, opaque {@code null}, or noncredentialed
 * {@code *}.
 */
public final class CorsPolicy {
  private Set<String> origins;
  private Set<HttpMethods> methods;
  private boolean allMethods;
  private Set<HttpHeaders> allowedHeaders;
  private boolean credentials;
  private Set<HttpHeaders> exposedHeaders;
  private long maxAgeSeconds;

  /** Creates standalone default permissions for JavaBean configuration loading. */
  public CorsPolicy() {
    this.origins = Set.of();
    this.methods = Set.of(HttpMethods.GET, HttpMethods.HEAD);
    this.allMethods = false;
    this.allowedHeaders = Set.of();
    this.credentials = false;
    this.exposedHeaders = Set.of();
    this.maxAgeSeconds = 5;
  }

  /**
   * Copy constructor that captures and validates complete permissions before provider ownership.
   *
   * @param source mutable caller configuration
   */
  CorsPolicy(CorsPolicy source) {
    this.origins = Objects.requireNonNull(source, "source").origins;
    this.methods = source.methods;
    this.allMethods = source.allMethods;
    this.allowedHeaders = source.allowedHeaders;
    this.credentials = source.credentials;
    this.exposedHeaders = source.exposedHeaders;
    this.maxAgeSeconds = source.maxAgeSeconds;
    validate();
  }

  /**
   * Snapshots values and validates the complete permissions.
   *
   * @param builder declared configuration
   * @throws IllegalArgumentException if credentials use wildcard origins or max-age is negative
   */
  private CorsPolicy(Builder builder) {
    for (var origin : builder.origins) {
      Origins.validateOrigin(origin);
    }

    this.origins = wildcard(builder.origins, HttpCharacters.ASTERISK_STRING);
    this.methods = builder.methods;
    this.allMethods = builder.allMethods;
    this.allowedHeaders =
        wildcard(builder.allowedHeaders, HttpHeaders.of(HttpCharacters.ASTERISK_STRING));
    this.credentials = builder.credentials;
    this.exposedHeaders =
        wildcard(builder.exposedHeaders, HttpHeaders.of(HttpCharacters.ASTERISK_STRING));
    this.maxAgeSeconds = builder.maxAgeSeconds;
    validate();
  }

  /**
   * Creates a builder with standalone defaults.
   *
   * @return independent builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Collapses a wildcard to one immutable value.
   *
   * @param values configured immutable values
   * @param wildcard wildcard value
   * @param <T> value type
   * @return normalized immutable values
   */
  private static <T> Set<T> wildcard(Set<T> values, T wildcard) {
    return values.contains(wildcard) ? Set.of(wildcard) : values;
  }

  /**
   * Returns the configured browser origins.
   *
   * @return immutable origins, defaulting to none
   */
  public Set<String> getOrigins() {
    return origins;
  }

  /**
   * Replaces browser origins without retaining the supplied collection.
   *
   * @param values exact serialized origins; null restores the empty default
   */
  public void setOrigins(@Nullable Set<String> values) {
    var resolved = values == null ? Set.<String>of() : Set.copyOf(values);
    resolved.forEach(Origins::validateOrigin);
    origins = wildcard(resolved, HttpCharacters.ASTERISK_STRING);
  }

  /**
   * Returns allowed origins, defaulting to none.
   *
   * @return immutable origin set
   */
  public Set<String> origins() {
    return origins;
  }

  /**
   * Returns allowed methods, defaulting to GET and HEAD.
   *
   * @return configured methods
   */
  public Set<HttpMethods> getMethods() {
    return methods;
  }

  /**
   * Sets allowed methods, defaulting to GET and HEAD.
   *
   * @param value replacement setting. Null restores GET and HEAD; the wildcard flag is unchanged.
   */
  public void setMethods(@Nullable Set<HttpMethods> value) {
    methods = value == null ? Set.of(HttpMethods.GET, HttpMethods.HEAD) : Set.copyOf(value);
  }

  /**
   * Returns allowed methods, defaulting to GET and HEAD only when unspecified.
   *
   * @return immutable method set; inspect allMethods() to distinguish wildcard from deny-all
   */
  public Set<HttpMethods> methods() {
    return methods;
  }

  /**
   * Returns whether every valid HTTP method is allowed.
   *
   * @return configured allMethods
   */
  public Boolean getAllMethods() {
    return allMethods;
  }

  /**
   * Sets whether every valid HTTP method is allowed.
   *
   * @param value replacement setting. Null restores false. A wildcard policy requires an empty
   *     {@code methods} collection.
   */
  public void setAllMethods(@Nullable Boolean value) {
    allMethods = value != null && value;
  }

  /**
   * Returns whether every syntactically valid method token is allowed.
   *
   * @return whether the method collection is a wildcard
   */
  public boolean allMethods() {
    return allMethods;
  }

  /**
   * Returns permitted preflight headers, defaulting to none.
   *
   * @return configured allowedHeaders
   */
  public Set<HttpHeaders> getAllowedHeaders() {
    return allowedHeaders;
  }

  /**
   * Sets permitted preflight headers, defaulting to none.
   *
   * @param value replacement setting. Null restores the empty default.
   */
  public void setAllowedHeaders(@Nullable Set<HttpHeaders> value) {
    allowedHeaders =
        wildcard(
            value == null ? Set.of() : Set.copyOf(value),
            HttpHeaders.of(HttpCharacters.ASTERISK_STRING));
  }

  /**
   * Returns permitted preflight headers, defaulting to none.
   *
   * @return immutable typed header set
   */
  public Set<HttpHeaders> allowedHeaders() {
    return allowedHeaders;
  }

  /**
   * Returns whether credentialed sharing is allowed.
   *
   * @return configured credentials
   */
  public Boolean getCredentials() {
    return credentials;
  }

  /**
   * Sets whether credentialed sharing is allowed.
   *
   * @param value replacement setting. Null restores false.
   */
  public void setCredentials(@Nullable Boolean value) {
    credentials = value != null && value;
  }

  /**
   * Returns whether credentialed sharing is allowed, defaulting to false.
   *
   * @return credential permission
   */
  public boolean credentials() {
    return credentials;
  }

  /**
   * Returns additional response headers, defaulting to none.
   *
   * @return configured exposedHeaders
   */
  public Set<HttpHeaders> getExposedHeaders() {
    return exposedHeaders;
  }

  /**
   * Sets additional response headers, defaulting to none.
   *
   * @param value replacement setting. Null restores the empty default.
   */
  public void setExposedHeaders(@Nullable Set<HttpHeaders> value) {
    exposedHeaders =
        wildcard(
            value == null ? Set.of() : Set.copyOf(value),
            HttpHeaders.of(HttpCharacters.ASTERISK_STRING));
  }

  /**
   * Returns additional response headers visible to scripts, defaulting to none.
   *
   * @return immutable typed header set
   */
  public Set<HttpHeaders> exposedHeaders() {
    return exposedHeaders;
  }

  /**
   * Returns browser preflight cache lifetime in seconds, defaulting to five.
   *
   * @return configured maxAgeSeconds
   */
  public Long getMaxAgeSeconds() {
    return maxAgeSeconds;
  }

  /**
   * Sets browser preflight cache lifetime in seconds, defaulting to five.
   *
   * @param value replacement setting. Null restores five seconds; zero disables caching.
   * @throws IllegalArgumentException if the lifetime is negative
   */
  public void setMaxAgeSeconds(@Nullable Long value) {
    long resolved = value == null ? 5 : value;
    if (resolved < 0) {
      throw new IllegalArgumentException("CORS cache lifetime must be nonnegative");
    }

    maxAgeSeconds = resolved;
  }

  /**
   * Returns the preflight cache lifetime, defaulting to five seconds.
   *
   * @return nonnegative lifetime
   */
  public long maxAgeSeconds() {
    return maxAgeSeconds;
  }

  /** {@inheritDoc} */
  @Override
  public boolean equals(@Nullable Object value) {
    return value instanceof CorsPolicy other
        && Objects.equals(origins, other.origins)
        && Objects.equals(methods, other.methods)
        && allMethods == other.allMethods
        && Objects.equals(allowedHeaders, other.allowedHeaders)
        && credentials == other.credentials
        && Objects.equals(exposedHeaders, other.exposedHeaders)
        && maxAgeSeconds == other.maxAgeSeconds;
  }

  /** {@inheritDoc} */
  @Override
  public int hashCode() {
    return Objects.hash(
        origins, methods, allMethods, allowedHeaders, credentials, exposedHeaders, maxAgeSeconds);
  }

  /** {@inheritDoc} */
  @Override
  public String toString() {
    return "CorsPolicy[origins="
        + origins()
        + ", methods="
        + methods()
        + ", allMethods="
        + allMethods
        + ", allowedHeaders="
        + allowedHeaders()
        + ", credentials="
        + credentials()
        + ", exposedHeaders="
        + exposedHeaders()
        + ", maxAgeSeconds="
        + maxAgeSeconds()
        + "]";
  }

  /**
   * Validates cross-field permissions before a provider publishes its private copy.
   *
   * @throws IllegalArgumentException if wildcard methods or credentialed wildcards conflict
   */
  private void validate() {
    if (allMethods && !methods.isEmpty()) {
      throw new IllegalArgumentException("Wildcard methods require an explicitly empty method set");
    }

    if (credentials() && origins().contains(HttpCharacters.ASTERISK_STRING)) {
      throw new IllegalArgumentException("Credentialed CORS requires explicit origins");
    }

    if (credentials()
        && exposedHeaders().contains(HttpHeaders.of(HttpCharacters.ASTERISK_STRING))) {
      throw new IllegalArgumentException(
          "Credentialed CORS requires explicit exposed header names");
    }

    if (maxAgeSeconds() < 0) {
      throw new IllegalArgumentException("CORS cache lifetime must be nonnegative");
    }
  }

  /** Builds complete independent permissions. */
  public static final class Builder {
    private Set<String> origins;
    private Set<HttpMethods> methods;
    private boolean allMethods;
    private Set<HttpHeaders> allowedHeaders;
    private boolean credentials;
    private Set<HttpHeaders> exposedHeaders;
    private long maxAgeSeconds;

    /** Creates standalone default permissions. */
    private Builder() {
      this.origins = Set.of();
      this.methods = Set.of(HttpMethods.GET, HttpMethods.HEAD);
      this.allMethods = false;
      this.allowedHeaders = Set.of();
      this.credentials = false;
      this.exposedHeaders = Set.of();
      this.maxAgeSeconds = 5;
    }

    /**
     * Copies nonnull values without retaining a caller's array.
     *
     * @param first required first value
     * @param additional optional remaining values
     * @param <T> value type
     * @return immutable values
     */
    private static <T> Set<T> values(T first, T @Nullable [] additional) {
      var values = new HashSet<T>();
      values.add(Objects.requireNonNull(first));
      if (additional != null) {
        values.addAll(Arrays.asList(additional));
      }

      return Set.copyOf(values);
    }

    /**
     * Sets allowed origins with a required first value.
     *
     * @param first first value
     * @param additional remaining values; null means none
     * @return this builder
     */
    public Builder origins(String first, String @Nullable ... additional) {
      return origins(values(first, additional));
    }

    /**
     * Sets exact serialized origins.
     *
     * @param values allowed origins
     * @return this builder
     */
    public Builder origins(Set<String> values) {
      origins = Set.copyOf(values);
      return this;
    }

    /**
     * Sets allowed methods with a required first value.
     *
     * @param first first value
     * @param additional remaining values; null means none
     * @return this builder
     */
    public Builder methods(HttpMethods first, HttpMethods @Nullable ... additional) {
      return methods(values(first, additional));
    }

    /**
     * Sets allowed methods; an explicitly empty set denies all methods.
     *
     * @param values allowed methods
     * @return this builder
     */
    public Builder methods(Set<HttpMethods> values) {
      methods = Set.copyOf(values);
      allMethods = false;
      return this;
    }

    /**
     * Allows every valid method token, replacing this builder's finite method declaration.
     *
     * @return this builder
     */
    public Builder allMethods() {
      methods = Set.of();
      allMethods = true;
      return this;
    }

    /**
     * Sets permitted request headers with a required first value.
     *
     * @param first first value
     * @param additional remaining values; null means none
     * @return this builder
     */
    public Builder allowedHeaders(HttpHeaders first, HttpHeaders @Nullable ... additional) {
      return allowedHeaders(values(first, additional));
    }

    /**
     * Sets permitted preflight headers.
     *
     * @param values typed request headers
     * @return this builder
     */
    public Builder allowedHeaders(Set<HttpHeaders> values) {
      allowedHeaders = Set.copyOf(values);
      return this;
    }

    /**
     * Sets whether credentialed response sharing is allowed.
     *
     * @param value credential permission
     * @return this builder
     */
    public Builder credentials(boolean value) {
      credentials = value;
      return this;
    }

    /**
     * Sets exposed response headers with a required first value.
     *
     * @param first first value
     * @param additional remaining values; null means none
     * @return this builder
     */
    public Builder exposedHeaders(HttpHeaders first, HttpHeaders @Nullable ... additional) {
      return exposedHeaders(values(first, additional));
    }

    /**
     * Sets additional response headers visible to scripts.
     *
     * @param values typed response headers
     * @return this builder
     */
    public Builder exposedHeaders(Set<HttpHeaders> values) {
      exposedHeaders = Set.copyOf(values);
      return this;
    }

    /**
     * Sets the browser cache lifetime; zero disables preflight caching.
     *
     * @param value nonnegative lifetime in seconds
     * @return this builder
     * @throws IllegalArgumentException if the lifetime is negative
     */
    public Builder maxAgeSeconds(long value) {
      if (value < 0) {
        throw new IllegalArgumentException("CORS cache lifetime must be nonnegative");
      }

      maxAgeSeconds = value;
      return this;
    }

    /**
     * Snapshots and validates declared permissions.
     *
     * @return independent policy
     */
    public CorsPolicy build() {
      return new CorsPolicy(this);
    }
  }
}
