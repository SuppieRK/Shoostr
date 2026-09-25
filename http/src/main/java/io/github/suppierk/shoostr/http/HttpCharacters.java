package io.github.suppierk.shoostr.http;

/** Characters used in HTTP request paths and route patterns. */
public final class HttpCharacters {
  /** Separates segments in a URI path ({@code /}). */
  public static final char PATH_SEPARATOR = '/';

  /** String equivalent of {@link #PATH_SEPARATOR} for APIs that require a string. */
  public static final String PATH_SEPARATOR_STRING = "" + PATH_SEPARATOR;

  /** Introduces the query component of a URI ({@code ?}). */
  public static final char QUERY_SEPARATOR = '?';

  /** Introduces the fragment component of a URI ({@code #}). */
  public static final char FRAGMENT_SEPARATOR = '#';

  /** Opens a named parameter in a route pattern (&#123;). */
  public static final char OPEN_CURLY_BRACE = '{';

  /** String equivalent of {@link #OPEN_CURLY_BRACE} for APIs that require a string. */
  public static final String OPEN_CURLY_BRACE_STRING = "" + OPEN_CURLY_BRACE;

  /** Closes a named parameter in a route pattern (&#125;). */
  public static final char CLOSE_CURLY_BRACE = '}';

  /** String equivalent of {@link #CLOSE_CURLY_BRACE} for APIs that require a string. */
  public static final String CLOSE_CURLY_BRACE_STRING = "" + CLOSE_CURLY_BRACE;

  /** An empty pair of curly braces for route-pattern placeholders. */
  public static final String CURLY_BRACES = OPEN_CURLY_BRACE_STRING + CLOSE_CURLY_BRACE_STRING;

  /** Asterisk ({@code *}), rejected in route patterns. */
  public static final char ASTERISK = '*';

  /** Opening angle bracket (&lt;), rejected in route patterns. */
  public static final char OPEN_ANGLE_BRACKET = '<';

  /** Closing angle bracket (&gt;), rejected in route patterns. */
  public static final char CLOSE_ANGLE_BRACKET = '>';

  /** Introduces a percent-encoded octet in a URI ({@code %}). */
  public static final char PERCENT_SIGN = '%';

  /** A literal plus sign in a URI path ({@code +}). */
  public static final char PLUS_SIGN = '+';

  /** String equivalent of {@link #PLUS_SIGN} for APIs that require a string. */
  public static final String PLUS_SIGN_STRING = "" + PLUS_SIGN;

  /** Percent-encoded plus sign, preserving a literal plus during form-style decoding. */
  public static final String PERCENT_ENCODED_PLUS = PERCENT_SIGN + "2B";

  /** Prevents instances of this constants-only utility class. */
  private HttpCharacters() {}
}
