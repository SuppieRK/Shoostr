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
 * Complete immutable cross-origin permissions. Each policy has standalone defaults; selecting a
 * replacement does not merge collections or inherit omitted fields. Origins use exact serialized
 * HTTP(S) spelling, opaque {@code null}, or noncredentialed {@code *}.
 */
public final class CorsPolicy {
  private final Set<String> origins;
  private final Set<HttpMethods> methods;
  private final boolean allMethods;
  private final Set<HttpHeaders> allowedHeaders;
  private final boolean credentials;
  private final Set<HttpHeaders> exposedHeaders;
  private final long maxAgeSeconds;

  /**
   * Creates a policy with every value explicitly configured.
   *
   * @param origins exact allowed serialized origins
   * @param methods allowed methods, or an empty set to deny all
   * @param allowedHeaders permitted preflight headers
   * @param credentials whether credentialed sharing is allowed
   * @param exposedHeaders additional response headers visible to scripts
   * @param maxAgeSeconds nonnegative browser preflight cache lifetime
   */
  public CorsPolicy(
      Set<String> origins,
      Set<HttpMethods> methods,
      Set<HttpHeaders> allowedHeaders,
      boolean credentials,
      Set<HttpHeaders> exposedHeaders,
      long maxAgeSeconds) {
    this(
        builder()
            .origins(origins)
            .methods(methods)
            .allowedHeaders(allowedHeaders)
            .credentials(credentials)
            .exposedHeaders(exposedHeaders)
            .maxAgeSeconds(maxAgeSeconds));
  }

  /**
   * Configures origins with standalone defaults for the other permissions.
   *
   * @param origins exact allowed serialized origins
   */
  public CorsPolicy(Set<String> origins) {
    this(builder().origins(origins));
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

    origins = wildcard(builder.origins, HttpCharacters.ASTERISK_STRING);
    methods = builder.methods;
    allMethods = builder.allMethods;
    allowedHeaders =
        wildcard(builder.allowedHeaders, HttpHeaders.of(HttpCharacters.ASTERISK_STRING));
    credentials = builder.credentials;
    exposedHeaders =
        wildcard(builder.exposedHeaders, HttpHeaders.of(HttpCharacters.ASTERISK_STRING));
    maxAgeSeconds = builder.maxAgeSeconds;
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

  /**
   * Creates a builder with standalone defaults.
   *
   * @return independent builder
   */
  public static Builder builder() {
    return new Builder();
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
   * Returns allowed methods, defaulting to GET and HEAD only when unspecified.
   *
   * @return immutable method set; inspect allMethods() to distinguish wildcard from deny-all
   */
  public Set<HttpMethods> methods() {
    return methods;
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
   * @return immutable typed header set
   */
  public Set<HttpHeaders> allowedHeaders() {
    return allowedHeaders;
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
   * Returns additional response headers visible to scripts, defaulting to none.
   *
   * @return immutable typed header set
   */
  public Set<HttpHeaders> exposedHeaders() {
    return exposedHeaders;
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

  /** Builds complete immutable permissions. */
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
      origins = Set.of();
      methods = Set.of(HttpMethods.GET, HttpMethods.HEAD);
      allMethods = false;
      allowedHeaders = Set.of();
      credentials = false;
      exposedHeaders = Set.of();
      maxAgeSeconds = 5;
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
     * @return immutable policy
     */
    public CorsPolicy build() {
      return new CorsPolicy(this);
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
  }
}
