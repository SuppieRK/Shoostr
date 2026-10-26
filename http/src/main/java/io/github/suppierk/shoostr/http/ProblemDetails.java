package io.github.suppierk.shoostr.http;

import java.net.URI;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Application-owned RFC 9457 problem data without response rendering or encoding. Standard fields
 * are optional. Supplied maps and lists are recursively copied into unmodifiable containers; other
 * custom objects and map keys remain caller-owned references. This does not guarantee deep
 * immutability of arbitrary custom objects or JSON encodability. Copied maps use ordinary key
 * equality; a copy that would collapse distinct entries is rejected. Equality and hashing use the
 * stored standard members and extensions. Mutating caller-owned objects or keys can change equality
 * results and hash codes.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9457.html">RFC 9457</a>
 */
public final class ProblemDetails {
  private static final Set<String> STANDARD_MEMBERS =
      Set.of("type", "title", "status", "detail", "instance");

  private final @Nullable URI type;
  private final @Nullable String title;
  private final @Nullable Integer status;
  private final @Nullable String detail;
  private final @Nullable URI instance;
  private final Map<String, @Nullable Object> extensions;

  /**
   * Captures the standard members supplied to a builder.
   *
   * @param builder supplied members
   */
  private ProblemDetails(Builder builder) {
    this.type = builder.type;
    this.title = builder.title;
    this.status = builder.status;
    if (status != null) {
      validateStatus(status);
    }

    this.detail = builder.detail;
    this.instance = builder.instance;
    this.extensions = Collections.unmodifiableMap(new LinkedHashMap<>(builder.extensions));
  }

  /**
   * Starts a builder with every standard member absent.
   *
   * @return new, non-thread-safe builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the supplied type; absent type implicitly means about:blank under RFC 9457.
   *
   * @return type identifier, or null if absent
   */
  public @Nullable URI type() {
    return type;
  }

  /**
   * Returns the supplied problem-type summary.
   *
   * @return summary, or null if absent
   */
  public @Nullable String title() {
    return title;
  }

  /**
   * Returns the supplied advisory HTTP status without inventing a default.
   *
   * @return HTTP status, or null if absent
   */
  public @Nullable Integer status() {
    return status;
  }

  /**
   * Returns the supplied explanation of this occurrence.
   *
   * @return public explanation, or null if absent
   */
  public @Nullable String detail() {
    return detail;
  }

  /**
   * Returns the supplied problem occurrence identifier.
   *
   * @return occurrence identifier, or null if absent
   */
  public @Nullable URI instance() {
    return instance;
  }

  /**
   * Returns retained custom members. Copied maps and lists are unmodifiable; other custom objects
   * and map keys remain caller-owned references.
   *
   * @return unmodifiable custom member map, permitting null values
   */
  public Map<String, @Nullable Object> extensions() {
    return extensions;
  }

  /**
   * Projects supplied standard and custom members without encoding them. Absent standard fields are
   * omitted, standard URIs become ASCII strings, and custom members appear at the top level. Copied
   * nested containers remain unmodifiable; other custom objects remain shared references.
   *
   * @return fresh mutable top-level map
   */
  public Map<String, @Nullable Object> toMap() {
    var result = new LinkedHashMap<String, @Nullable Object>();
    if (type != null) {
      result.put("type", type.toASCIIString());
    }

    if (title != null) {
      result.put("title", title);
    }

    if (status != null) {
      result.put("status", status);
    }

    if (detail != null) {
      result.put("detail", detail);
    }

    if (instance != null) {
      result.put("instance", instance.toASCIIString());
    }

    result.putAll(extensions);
    return result;
  }

  /**
   * Compares supplied standard members and extensions using their Java equality semantics.
   *
   * @param other candidate value, or null
   * @return true when all stored members are equal
   */
  @Override
  public boolean equals(@Nullable Object other) {
    return other instanceof ProblemDetails problem
        && Objects.equals(type, problem.type)
        && Objects.equals(title, problem.title)
        && Objects.equals(status, problem.status)
        && Objects.equals(detail, problem.detail)
        && Objects.equals(instance, problem.instance)
        && extensions.equals(problem.extensions);
  }

  /**
   * Hashes the standard members and extensions used by equality.
   *
   * @return hash consistent with equality
   */
  @Override
  public int hashCode() {
    return Objects.hash(type, title, status, detail, instance, extensions);
  }

  /**
   * Copies map values and list elements recursively, leaving other objects and keys untouched.
   *
   * @param value supplied extension value
   * @param ancestors containers on the current traversal path
   * @return structurally copied value, or the original non-container reference
   * @throws IllegalArgumentException if map/list values contain a cycle or map entries collapse
   *     under ordinary key equality
   */
  private static @Nullable Object copyValue(@Nullable Object value, Set<Object> ancestors) {
    if (value instanceof Map<?, ?> map) {
      enterContainer(map, ancestors);

      try {
        var copy = new LinkedHashMap<@Nullable Object, @Nullable Object>();
        map.forEach((key, nested) -> copy.put(key, copyValue(nested, ancestors)));
        if (copy.size() != map.size()) {
          throw new IllegalArgumentException(
              "Custom member map keys must not collapse when copied");
        }

        return Collections.unmodifiableMap(copy);
      } finally {
        ancestors.remove(map);
      }
    }

    if (value instanceof List<?> list) {
      enterContainer(list, ancestors);

      try {
        return list.stream().map(nested -> copyValue(nested, ancestors)).toList();
      } finally {
        ancestors.remove(list);
      }
    }

    return value;
  }

  /**
   * Rejects repeated identities on one traversal path without rejecting shared acyclic values.
   *
   * @param container container being entered
   * @param ancestors containers on the current path
   * @throws IllegalArgumentException if the container is already an ancestor
   */
  private static void enterContainer(Object container, Set<Object> ancestors) {
    if (!ancestors.add(container)) {
      throw new IllegalArgumentException("Custom member map/list values must not contain cycles");
    }
  }

  /**
   * Checks that a supplied number is in HTTP's status code range.
   *
   * @param status supplied status
   * @throws IllegalArgumentException if status is outside 100 through 599
   */
  private static void validateStatus(int status) {
    if (status < 100 || status > 599) {
      throw new IllegalArgumentException("HTTP status must be between 100 and 599");
    }
  }

  /** Builds problem data without selecting a response status or encoder. */
  public static final class Builder {
    private @Nullable URI type;
    private @Nullable String title;
    private @Nullable Integer status;
    private @Nullable String detail;
    private @Nullable URI instance;
    private final Map<String, @Nullable Object> extensions;

    /** Starts with every member absent. */
    private Builder() {
      this.type = null;
      this.title = null;
      this.status = null;
      this.detail = null;
      this.instance = null;
      this.extensions = new LinkedHashMap<>();
    }

    /**
     * Sets the problem type identifier, permitting relative URI references.
     *
     * @param type problem type identifier
     * @return this builder
     * @throws NullPointerException if type is null
     */
    public Builder type(URI type) {
      this.type = Objects.requireNonNull(type);
      return this;
    }

    /**
     * Sets the human-readable summary of the problem type.
     *
     * @param title summary, preserved verbatim
     * @return this builder
     * @throws NullPointerException if title is null
     */
    public Builder title(String title) {
      this.title = Objects.requireNonNull(title);
      return this;
    }

    /**
     * Sets the advisory HTTP status; applications must match the actual response status.
     *
     * @param status HTTP status
     * @return this builder
     * @throws IllegalArgumentException if status is outside 100 through 599
     */
    public Builder status(int status) {
      validateStatus(status);
      this.status = status;
      return this;
    }

    /**
     * Sets the public explanation of this problem occurrence.
     *
     * @param detail explanation, preserved verbatim
     * @return this builder
     * @throws NullPointerException if detail is null
     */
    public Builder detail(String detail) {
      this.detail = Objects.requireNonNull(detail);
      return this;
    }

    /**
     * Sets the occurrence identifier, permitting relative URI references.
     *
     * @param instance occurrence identifier
     * @return this builder
     * @throws NullPointerException if instance is null
     */
    public Builder instance(URI instance) {
      this.instance = Objects.requireNonNull(instance);
      return this;
    }

    /**
     * Adds or replaces a custom member, snapshotting its maps and lists immediately. Other objects
     * and map keys remain caller-owned references. Do not modify containers concurrently with
     * copying; this builder is not thread-safe.
     *
     * @param name custom member name
     * @param value custom value, including null
     * @return this builder
     * @throws NullPointerException if name is null
     * @throws IllegalArgumentException if name is a standard member name or map/list values contain
     *     a cycle, or map entries collapse under ordinary key equality
     */
    public Builder extension(String name, @Nullable Object value) {
      Objects.requireNonNull(name);
      if (STANDARD_MEMBERS.contains(name)) {
        throw new IllegalArgumentException("Custom member must not replace a standard member");
      }

      var copy = copyValue(value, Collections.newSetFromMap(new IdentityHashMap<>()));
      extensions.put(name, copy);
      return this;
    }

    /**
     * Constructs independent problem data.
     *
     * @return problem with the supplied members
     */
    public ProblemDetails build() {
      return new ProblemDetails(this);
    }
  }
}
