package io.github.suppierk.shoostr.http;

import java.util.Objects;

/** Shared HTTP syntax characters and token validation. */
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

  /** String equivalent of {@link #ASTERISK}. */
  public static final String ASTERISK_STRING = "" + ASTERISK;

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

  /** Comma. */
  public static final char COMMA_SIGN = ',';

  /** String equivalent of {@link #COMMA_SIGN}. */
  public static final String COMMA_SIGN_STRING = "" + COMMA_SIGN;

  /** Semicolon. */
  public static final char SEMICOLON_SIGN = ';';

  /** String equivalent of {@link #SEMICOLON_SIGN} */
  public static final String SEMICOLON_SIGN_STRING = "" + SEMICOLON_SIGN;

  /** Colon. */
  public static final char COLON_SIGN = ':';

  /** String equivalent of {@link #COLON_SIGN} */
  public static final String COLON_SIGN_STRING = "" + COLON_SIGN;

  /** Open square bracket. */
  public static final char OPEN_SQUARE_BRACKET = '[';

  /** String equivalent of {@link #OPEN_SQUARE_BRACKET} */
  public static final String OPEN_SQUARE_BRACKET_STRING = "" + OPEN_SQUARE_BRACKET;

  /** Close square bracket. */
  public static final char CLOSE_SQUARE_BRACKET = ']';

  /** String equivalent of {@link #CLOSE_SQUARE_BRACKET} */
  public static final String CLOSE_SQUARE_BRACKET_STRING = "" + CLOSE_SQUARE_BRACKET;

  /** Equals. */
  public static final char EQUALS_SIGN = '=';

  /** String equivalent of {@link #EQUALS_SIGN} */
  public static final String EQUALS_SIGN_STRING = "" + EQUALS_SIGN;

  /** Double quote. */
  public static final char DOUBLE_QUOTE = '"';

  /** String equivalent of {@link #DOUBLE_QUOTE} */
  public static final String DOUBLE_QUOTE_STRING = "" + DOUBLE_QUOTE;

  /** Dot. */
  public static final char DOT = '.';

  /** String equivalent of {@link #DOT} */
  public static final String DOT_STRING = "" + DOT;

  /** Parent directory. */
  public static final String PARENT_DIRECTORY = "" + DOT + DOT;

  /** Dash. */
  public static final char DASH = '-';

  /** String equivalent of {@link #DASH} */
  public static final String DASH_STRING = "" + DASH;

  /** Underscore. */
  public static final char UNDERSCORE = '_';

  /** String equivalent of {@link #UNDERSCORE} */
  public static final String UNDERSCORE_STRING = "" + UNDERSCORE;

  /** Backslash. */
  public static final char BACKSLASH = '\\';

  /** String equivalent of {@link #BACKSLASH} */
  public static final String BACKSLASH_STRING = "" + BACKSLASH;

  /** Tab. */
  public static final char TAB = '\t';

  /** String equivalent of {@link #TAB} */
  public static final String TAB_STRING = "" + TAB;

  /** Prevents instances of this HTTP syntax utility class. */
  private HttpCharacters() {}

  /**
   * Checks whether a value follows the RFC 9110 HTTP token grammar.
   *
   * <p>A token contains one or more ASCII letters, digits, or {@code !#$%&'*+-.^_`|~}. No trimming,
   * case conversion, or registry lookup is performed.
   *
   * @param value candidate HTTP token
   * @return true for a nonempty HTTP token, or false for empty or invalid input
   * @throws NullPointerException if value is null
   */
  public static boolean isValidHttpToken(String value) {
    Objects.requireNonNull(value);
    if (value.isEmpty()) {
      return false;
    }

    for (var index = 0; index < value.length(); index++) {
      var character = value.charAt(index);
      if (!(character >= '0' && character <= '9')
          && !(character >= 'A' && character <= 'Z')
          && !(character >= 'a' && character <= 'z')
          && "!#$%&'*+-.^_`|~".indexOf(character) < 0) {
        return false;
      }
    }
    return true;
  }
}
