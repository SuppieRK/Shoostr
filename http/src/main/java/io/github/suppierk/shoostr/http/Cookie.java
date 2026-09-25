package io.github.suppierk.shoostr.http;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * An immutable response cookie with explicitly encoded application data. Max-Age takes precedence
 * over Expires when both are supplied. Positive ages do not calculate a second absolute expiry.
 * SameSite=None and secure/host prefixes require Secure; host prefixes also require root path and
 * no Domain. Prefix recognition is case-insensitive, following the modern cookie draft.
 *
 * @param name case-sensitive HTTP token
 * @param value cookie octets with optional explicit outer quotes; no automatic encoding or decoding
 * @param path ASCII root-relative cookie path without trailing whitespace
 * @param domain ASCII domain, or null for host-only; one leading dot is removed
 * @param maxAge lifetime in seconds; -1 omits Max-Age, zero deletes
 * @param secure whether transmission requires a secure connection
 * @param httpOnly whether browser scripts must not access the cookie
 * @param sameSite cross-site policy, or null to omit it
 * @param expires absolute expiry, or null to omit it; seconds precision, years 1601 through 9999
 */
public record Cookie(
    String name,
    String value,
    String path,
    @Nullable String domain,
    long maxAge,
    boolean secure,
    boolean httpOnly,
    @Nullable SameSite sameSite,
    @Nullable Instant expires) {
  private static final String SECURE_PREFIX = "__Secure-";
  private static final String HOST_PREFIX = "__Host-";
  private static final Pattern NAME_PATTERN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US)
          .withZone(ZoneOffset.UTC);
  private static final Instant MIN_EXPIRY = Instant.parse("1601-01-01T00:00:00Z");
  private static final Instant MAX_EXPIRY = Instant.parse("9999-12-31T23:59:59Z");

  /**
   * Validates attributes before a cookie can alter any response.
   *
   * @throws IllegalArgumentException if syntax, lifetime or date bounds are invalid
   */
  public Cookie {
    Objects.requireNonNull(name);
    Objects.requireNonNull(value);
    Objects.requireNonNull(path);
    if (!NAME_PATTERN.matcher(name).matches()) {
      throw new IllegalArgumentException("Invalid cookie name");
    }

    validateValue(value);
    validatePath(path);
    domain = normalizeDomain(domain);
    if (maxAge < -1) {
      throw new IllegalArgumentException("Cookie age must be -1 or nonnegative");
    }

    validatePolicy(name, path, domain, secure, sameSite);
    expires = normalizeExpiry(expires);
  }

  /**
   * Creates a root-path, host-only session cookie without security attributes.
   *
   * @param name cookie name
   * @param value already encoded cookie value
   */
  public Cookie(String name, String value) {
    this(name, value, HttpCharacters.PATH_SEPARATOR_STRING, null, -1, false, false, null, null);
  }

  /**
   * Validates a root-relative ASCII cookie path.
   *
   * @param path cookie path
   */
  private static void validatePath(String path) {
    if (path.isEmpty()
        || path.charAt(0) != HttpCharacters.PATH_SEPARATOR
        || path.charAt(path.length() - 1) == ' ') {
      throw new IllegalArgumentException(
          "Cookie path must be root-relative without trailing whitespace");
    }

    for (int index = 0; index < path.length(); index++) {
      char character = path.charAt(index);
      if (character < 0x20 || character > 0x7e || character == ';') {
        throw new IllegalArgumentException("Invalid cookie path");
      }
    }
  }

  /**
   * Canonicalizes and validates an optional domain attribute.
   *
   * @param domain supplied domain, or null for host-only
   * @return normalized domain, or null
   */
  private static @Nullable String normalizeDomain(@Nullable String domain) {
    if (domain == null) {
      return null;
    }

    var normalized =
        (domain.startsWith(".") ? domain.substring(1) : domain).toLowerCase(Locale.ROOT);
    if (normalized.length() > 253 || !validDomain(normalized)) {
      throw new IllegalArgumentException("Invalid cookie domain");
    }

    return normalized;
  }

  /**
   * Enforces cookie prefix and SameSite security requirements.
   *
   * @param name cookie name
   * @param path normalized path
   * @param domain normalized domain, or null
   * @param secure whether Secure is enabled
   * @param sameSite optional SameSite policy
   */
  private static void validatePolicy(
      String name,
      String path,
      @Nullable String domain,
      boolean secure,
      @Nullable SameSite sameSite) {
    boolean hostPrefix = name.regionMatches(true, 0, HOST_PREFIX, 0, HOST_PREFIX.length());
    boolean securePrefix = name.regionMatches(true, 0, SECURE_PREFIX, 0, SECURE_PREFIX.length());
    if (!secure && (sameSite == SameSite.NONE || hostPrefix || securePrefix)) {
      throw new IllegalArgumentException("Cookie policy requires Secure");
    }

    if (hostPrefix && (domain != null || !HttpCharacters.PATH_SEPARATOR_STRING.equals(path))) {
      throw new IllegalArgumentException("Host-prefixed cookies require root path and no Domain");
    }
  }

  /**
   * Truncates a representable expiry to seconds.
   *
   * @param expires optional supplied expiry
   * @return normalized expiry, or null
   */
  private static @Nullable Instant normalizeExpiry(@Nullable Instant expires) {
    if (expires == null) {
      return null;
    }

    var normalized = expires.truncatedTo(ChronoUnit.SECONDS);
    if (normalized.isBefore(MIN_EXPIRY) || normalized.isAfter(MAX_EXPIRY)) {
      throw new IllegalArgumentException("Cookie expiry must be within years 1601 through 9999");
    }

    return normalized;
  }

  /**
   * Creates a secure root-path session cookie, also suitable for secure/host-prefixed names.
   *
   * @param name cookie name
   * @param value already encoded value
   * @return host-only session cookie with Secure enabled
   */
  public static Cookie secure(String name, String value) {
    return new Cookie(
        name, value, HttpCharacters.PATH_SEPARATOR_STRING, null, -1, true, false, null, null);
  }

  /**
   * Formats one Set-Cookie field. Zero age always emits an epoch Expires, overriding an explicit
   * future date. No response-level cache headers are implied.
   *
   * @return field value without the header name
   */
  public String headerValue() {
    var header = new StringBuilder(name).append('=').append(value).append("; Path=").append(path);
    if (domain != null) {
      header.append("; Domain=").append(domain);
    }

    if (maxAge >= 0) {
      header.append("; Max-Age=").append(maxAge);
    }

    if (maxAge == 0 || expires != null) {
      header.append("; Expires=").append(DATE.format(maxAge == 0 ? Instant.EPOCH : expires));
    }

    if (secure) {
      header.append("; Secure");
    }

    if (httpOnly) {
      header.append("; HttpOnly");
    }

    if (sameSite != null) {
      header.append("; SameSite=").append(sameSite.value());
    }

    return header.toString();
  }

  /**
   * Copies this cookie with a different path attribute.
   *
   * @param updated root-relative path
   * @return validated immutable copy
   */
  public Cookie withPath(String updated) {
    return new Cookie(name, value, updated, domain, maxAge, secure, httpOnly, sameSite, expires);
  }

  /**
   * Copies this cookie with a different domain attribute.
   *
   * @param updated ASCII domain, or null for host-only
   * @return validated immutable copy
   */
  public Cookie withDomain(@Nullable String updated) {
    return new Cookie(name, value, path, updated, maxAge, secure, httpOnly, sameSite, expires);
  }

  /**
   * Copies this cookie with a different maxAge attribute.
   *
   * @param updated lifetime in seconds; -1 omits age, zero deletes
   * @return validated immutable copy
   */
  public Cookie withMaxAge(long updated) {
    return new Cookie(name, value, path, domain, updated, secure, httpOnly, sameSite, expires);
  }

  /**
   * Copies this cookie with a different secure attribute.
   *
   * @param updated whether transport must be secure
   * @return validated immutable copy
   */
  public Cookie withSecure(boolean updated) {
    return new Cookie(name, value, path, domain, maxAge, updated, httpOnly, sameSite, expires);
  }

  /**
   * Copies this cookie with a different httpOnly attribute.
   *
   * @param updated whether browser script access is forbidden
   * @return validated immutable copy
   */
  public Cookie withHttpOnly(boolean updated) {
    return new Cookie(name, value, path, domain, maxAge, secure, updated, sameSite, expires);
  }

  /**
   * Copies this cookie with a different sameSite attribute.
   *
   * @param updated cross-site policy, or null to omit
   * @return validated immutable copy
   */
  public Cookie withSameSite(@Nullable SameSite updated) {
    return new Cookie(name, value, path, domain, maxAge, secure, httpOnly, updated, expires);
  }

  /**
   * Copies this cookie with a different expires attribute.
   *
   * @param updated absolute expiry, or null to omit
   * @return validated immutable copy
   */
  public Cookie withExpires(@Nullable Instant updated) {
    return new Cookie(name, value, path, domain, maxAge, secure, httpOnly, sameSite, updated);
  }

  /**
   * Creates a deletion cookie for exactly this scope while preserving security attributes.
   *
   * @return an empty value with zero age and an epoch expiry
   */
  public Cookie expired() {
    return new Cookie(name, "", path, domain, 0, secure, httpOnly, sameSite, Instant.EPOCH);
  }

  /**
   * Checks DNS labels in linear time, with the same ASCII and length rules as the domain syntax.
   *
   * @param value normalized domain
   * @return whether every label is valid
   */
  private static boolean validDomain(String value) {
    int labelStart = 0;
    for (int index = 0; index <= value.length(); index++) {
      if (index < value.length() && value.charAt(index) != '.') {
        char character = value.charAt(index);
        if (!asciiLetterOrDigit(character) && character != '-') {
          return false;
        }

        continue;
      }

      int labelLength = index - labelStart;
      if (labelLength < 1
          || labelLength > 63
          || !asciiLetterOrDigit(value.charAt(labelStart))
          || !asciiLetterOrDigit(value.charAt(index - 1))) {
        return false;
      }

      labelStart = index + 1;
    }

    return true;
  }

  /** Returns whether a character is an ASCII lowercase letter or decimal digit. */
  private static boolean asciiLetterOrDigit(char character) {
    return (character >= 'a' && character <= 'z') || (character >= '0' && character <= '9');
  }

  /**
   * Requires cookie octets rather than silently encoding arbitrary application strings.
   *
   * @param value proposed cookie value
   */
  private static void validateValue(String value) {
    boolean quoted =
        value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"';
    int end = quoted ? value.length() - 1 : value.length();
    for (int index = quoted ? 1 : 0; index < end; index++) {
      char character = value.charAt(index);
      if (character < 0x21
          || character > 0x7e
          || character == '"'
          || character == ','
          || character == ';'
          || character == '\\') {
        throw new IllegalArgumentException(
            "Invalid cookie value; encode application data explicitly");
      }
    }
  }

  /** Browser cross-site cookie policy; omission leaves the browser's default in effect. */
  public enum SameSite {
    /** Send only in same-site contexts. */
    STRICT("Strict"),
    /** Permit same-site and qualifying top-level navigation requests. */
    LAX("Lax"),
    /** Permit cross-site contexts; requires Secure. */
    NONE("None");

    private final String value;

    /**
     * Stores the canonical wire spelling.
     *
     * @param value attribute spelling
     */
    SameSite(String value) {
      this.value = value;
    }

    /**
     * Returns the wire spelling.
     *
     * @return canonical SameSite attribute
     */
    public String value() {
      return value;
    }
  }
}
