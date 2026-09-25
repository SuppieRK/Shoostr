package io.github.suppierk.shoostr.http;

import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Immutable outbound media-type name; construction does not inspect or encode payloads. */
public final class MediaType {
  private static final String NAME = "[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,126}";
  private static final Pattern BARE_TYPE = Pattern.compile(NAME + "/" + NAME);
  private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

  /** JSON media type from RFC 8259; no charset parameter is added. */
  public static final MediaType APPLICATION_JSON = of("application/json");

  /** URL-encoded form fields using the HTML form encoding format. */
  public static final MediaType APPLICATION_FORM_URLENCODED =
      of("application/x-www-form-urlencoded");

  /** Arbitrary binary data, as registered by RFC 2046. */
  public static final MediaType APPLICATION_OCTET_STREAM = of("application/octet-stream");

  /** Plain text without an implicit charset parameter. */
  public static final MediaType TEXT_PLAIN = of("text/plain");

  /** HTML without an implicit charset parameter. */
  public static final MediaType TEXT_HTML = of("text/html");

  private final String name;
  private final String value;

  /**
   * Validates and stores a canonical media-type value.
   *
   * @param name bare type/subtype
   * @param charset optional character encoding metadata
   * @throws NullPointerException if name is null
   * @throws IllegalArgumentException if name or the charset spelling is invalid
   */
  private MediaType(String name, @Nullable Charset charset) {
    Objects.requireNonNull(name);
    if (!BARE_TYPE.matcher(name).matches()) {
      throw new IllegalArgumentException("Expected an RFC 6838 type/subtype name");
    }

    this.name = name.toLowerCase(Locale.ROOT);
    if (charset == null) {
      this.value = this.name;
    } else {
      var charsetName = charset.name();
      if (!TOKEN.matcher(charsetName).matches()) {
        throw new IllegalArgumentException("Charset name must be an HTTP token");
      }

      this.value = this.name + "; charset=" + charsetName;
    }
  }

  /**
   * Constructs a bare type/subtype using the ASCII name grammar from RFC 6838 section 4.2. Each
   * component contains 1 to 127 characters. Parameters, whitespace, and media ranges are excluded;
   * this is a constructor, not a Content-Type parser. No registry lookup is performed.
   *
   * @param name bare type/subtype
   * @return canonical media type
   * @throws NullPointerException if name is null
   * @throws IllegalArgumentException if name is outside the bare-name grammar
   */
  public static MediaType of(String name) {
    return new MediaType(name, null);
  }

  /**
   * Returns a new value with one charset parameter, replacing any previous charset. This labels
   * bytes without encoding them or checking whether the selected type defines this parameter.
   *
   * @param charset character encoding metadata
   * @return derived media type; this value remains unchanged
   * @throws NullPointerException if charset is null
   * @throws IllegalArgumentException if the canonical charset name requires HTTP quoting
   */
  public MediaType withCharset(Charset charset) {
    return new MediaType(name, Objects.requireNonNull(charset));
  }

  /**
   * Returns the outbound Content-Type value.
   *
   * @return canonical header value
   */
  public String value() {
    return value;
  }

  /**
   * Compares type names and the optional charset without case sensitivity.
   *
   * @param other value to compare
   * @return true for an equivalent media-type value
   */
  @Override
  public boolean equals(Object other) {
    return other instanceof MediaType mediaType && value.equalsIgnoreCase(mediaType.value);
  }

  /**
   * Hashes the case-insensitive media-type value.
   *
   * @return hash consistent with equality
   */
  @Override
  public int hashCode() {
    return value.toLowerCase(Locale.ROOT).hashCode();
  }
}
