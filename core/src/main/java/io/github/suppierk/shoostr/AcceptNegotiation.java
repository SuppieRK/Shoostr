package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpCharacters;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Parses Accept media ranges and selects one caller-provided representation. */
final class AcceptNegotiation {

  private static final String QUALITY_PARAM_KEY = "q";

  private static final String ZERO = "0";
  private static final char ZERO_CHAR = ZERO.charAt(0);
  private static final String THOUSAND_ZEROES = ZERO.repeat(3);

  private static final String ONE = "1";
  private static final char ONE_CHAR = ONE.charAt(0);

  /**
   * Prevents construction of this stateless parser.
   *
   * @throws UnsupportedOperationException always
   */
  private AcceptNegotiation() {
    throw new UnsupportedOperationException("No instance creation is available");
  }

  /**
   * Selects the highest-quality compatible candidate, retaining caller order for a complete tie.
   *
   * @param fields repeated {@code Accept} field values in wire order
   * @param first most preferred representation
   * @param additional other representations in server preference order, or null for none
   * @return original selected candidate, or null when every candidate is unacceptable
   * @throws BadRequestException if an {@code Accept} field is malformed
   */
  static @Nullable MediaType select(
      List<String> fields, MediaType first, MediaType @Nullable [] additional) {
    final var ranges = ranges(fields);
    var selected = first;
    var selectedQuality = quality(first, ranges);
    if (additional != null) {
      for (final var candidate : additional) {
        final var quality = quality(candidate, ranges);
        if (quality > selectedQuality) {
          selected = candidate;
          selectedQuality = quality;
        }
      }
    }

    return selectedQuality == 0 ? null : selected;
  }

  /**
   * Parses all repeated field lines as one ordered HTTP list.
   *
   * @param fields repeated field values
   * @return parsed ranges in wire order
   */
  private static List<Range> ranges(List<String> fields) {
    final var ranges = new ArrayList<Range>();
    for (final var field : fields) {
      for (final var item : split(field, HttpCharacters.COMMA_SIGN, true)) {
        ranges.add(range(item));
      }
    }

    return ranges;
  }

  /**
   * Parses one media range and its weight.
   *
   * @param item trimmed range item
   * @return parsed range
   */
  private static Range range(String item) {
    if (item.charAt(0) == HttpCharacters.SEMICOLON_SIGN) {
      throw malformed();
    }

    final var pieces = split(item, HttpCharacters.SEMICOLON_SIGN, true);
    if (pieces.isEmpty()) {
      throw malformed();
    }

    final var base = pieces.getFirst().trim();
    final var slash = base.indexOf(HttpCharacters.PATH_SEPARATOR);
    if (slash < 1
        || slash != base.lastIndexOf(HttpCharacters.PATH_SEPARATOR)
        || slash == base.length() - 1) {
      throw malformed();
    }

    final var type = base.substring(0, slash).toLowerCase(Locale.ROOT);
    final var subtype = base.substring(slash + 1).toLowerCase(Locale.ROOT);
    if (!(HttpCharacters.ASTERISK_STRING.equals(type) || HttpCharacters.isValidHttpToken(type))
        || !(HttpCharacters.ASTERISK_STRING.equals(subtype)
            || HttpCharacters.isValidHttpToken(subtype))
        || (HttpCharacters.ASTERISK_STRING.equals(type)
            && !HttpCharacters.ASTERISK_STRING.equals(subtype))) {
      throw malformed();
    }

    final var parameters = parameters(pieces);

    return new Range(type, subtype, parameters.values, parameters.quality);
  }

  /**
   * Finds the quality of the most-specific matching range for one candidate.
   *
   * @param candidate representation to test
   * @param ranges parsed client preferences
   * @return quality in thousandths, or zero when unacceptable
   */
  private static int quality(MediaType candidate, List<Range> ranges) {
    final var representation = representation(candidate.value());
    Range best = null;
    for (final var range : ranges) {
      if (range.matches(representation) && (best == null || range.precedes(best))) {
        best = range;
      }
    }

    return best == null ? 0 : best.quality;
  }

  /**
   * Parses a selected candidate's concrete type and the limited parameters MediaType permits.
   *
   * @param value candidate media-type value
   * @return concrete representation
   */
  private static Representation representation(String value) {
    final var pieces = split(value, HttpCharacters.SEMICOLON_SIGN, false);
    final var base = pieces.getFirst().trim();
    final var slash = base.indexOf(HttpCharacters.PATH_SEPARATOR);

    return new Representation(
        base.substring(0, slash).trim().toLowerCase(Locale.ROOT),
        base.substring(slash + 1).trim().toLowerCase(Locale.ROOT),
        parameters(pieces).values);
  }

  /**
   * Parses parameters, reserving q as the range quality.
   *
   * @param pieces media type and parameter components
   * @return parsed values and quality
   */
  private static Parameters parameters(List<String> pieces) {
    final var values = new LinkedHashMap<String, String>();
    var quality = 1000;
    var qualitySeen = false;
    for (var index = 1; index < pieces.size(); index++) {
      var parameter = pieces.get(index);

      final var equals = parameter.indexOf(HttpCharacters.EQUALS_SIGN);
      if (equals < 1) {
        throw malformed();
      }

      final var name = parameter.substring(0, equals).toLowerCase(Locale.ROOT);
      final var value = parameter.substring(equals + 1);
      if (!HttpCharacters.isValidHttpToken(name) || value.isEmpty()) {
        throw malformed();
      }

      if (QUALITY_PARAM_KEY.equals(name)) {
        quality = qualityParameter(value, qualitySeen);
        qualitySeen = true;
      } else {
        putParameter(values, name, value);
      }
    }

    return new Parameters(values, quality);
  }

  /**
   * Parses the one unquoted quality value permitted in a parameter list.
   *
   * @param value raw parameter value
   * @param alreadySeen whether another quality value preceded it
   * @return quality in thousandths
   */
  private static int qualityParameter(String value, boolean alreadySeen) {
    if (alreadySeen || value.charAt(0) == HttpCharacters.DOUBLE_QUOTE) {
      throw malformed();
    }

    return qualityThousandths(tokenValue(value));
  }

  /**
   * Parses an RFC quality value into integer thousandths.
   *
   * @param value quality input
   * @return integer thousandths
   */
  private static int qualityThousandths(String value) {
    if (ZERO.equals(value) || ONE.equals(value)) {
      return ONE.equals(value) ? 1000 : 0;
    }

    if (value.length() < 2 || value.charAt(1) != HttpCharacters.DOT || value.length() > 5) {
      throw malformed();
    }

    final var whole = value.charAt(0);
    final var fraction = value.substring(2);
    if ((whole != ZERO_CHAR && whole != ONE_CHAR)
        || !fraction.chars().allMatch(Character::isDigit)) {
      throw malformed();
    }

    if (whole == ONE_CHAR && !fraction.chars().allMatch(character -> character == ZERO_CHAR)) {
      throw malformed();
    }

    return (whole - ZERO_CHAR) * 1000
        + Integer.parseInt((fraction + THOUSAND_ZEROES).substring(0, 3));
  }

  /**
   * Decodes and inserts one non-quality parameter, rejecting duplicates.
   *
   * @param values parsed parameters
   * @param name normalized parameter name
   * @param value raw parameter value
   */
  private static void putParameter(Map<String, String> values, String name, String value) {
    final var decoded =
        value.charAt(0) == HttpCharacters.DOUBLE_QUOTE ? quoted(value) : tokenValue(value);
    if (values.putIfAbsent(name, decoded) != null) {
      throw malformed();
    }
  }

  /**
   * Decodes a quoted-string parameter, rejecting dangling escapes and controls.
   *
   * @param value quoted input
   * @return decoded value
   */
  private static String quoted(String value) {
    if (value.length() < 2 || value.charAt(value.length() - 1) != HttpCharacters.DOUBLE_QUOTE) {
      throw malformed();
    }

    final var decoded = new StringBuilder(value.length() - 2);
    var escaped = false;
    for (var index = 1; index < value.length() - 1; index++) {
      final var character = value.charAt(index);
      if ((character < HttpCharacters.SPACE && character != HttpCharacters.TAB)
          || character == HttpCharacters.DELETE_CONTROL
          || (character == HttpCharacters.DOUBLE_QUOTE && !escaped)) {
        throw malformed();
      }

      if (escaped) {
        decoded.append(character);
        escaped = false;
      } else if (character == HttpCharacters.BACKSLASH) {
        escaped = true;
      } else {
        decoded.append(character);
      }
    }

    if (escaped) {
      throw malformed();
    }

    return decoded.toString();
  }

  /**
   * Validates an unquoted token parameter value.
   *
   * @param value unquoted input
   * @return validated value
   */
  private static String tokenValue(String value) {
    if (!HttpCharacters.isValidHttpToken(value)) {
      throw malformed();
    }

    return value;
  }

  /**
   * Splits one field component while preserving quoted commas and semicolons.
   *
   * @param value source field component
   * @param separator component delimiter
   * @param ignoreEmpty whether empty list elements are ignored
   * @return trimmed components
   */
  private static List<String> split(String value, char separator, boolean ignoreEmpty) {
    final var values = new ArrayList<String>();
    var start = 0;
    var quoted = false;
    var escaped = false;
    for (var index = 0; index < value.length(); index++) {
      final var character = value.charAt(index);
      if (escaped) {
        escaped = false;
      } else if (character == HttpCharacters.BACKSLASH && quoted) {
        escaped = true;
      } else if (character == HttpCharacters.DOUBLE_QUOTE) {
        quoted = !quoted;
      } else if (character == separator && !quoted) {
        add(values, value.substring(start, index), ignoreEmpty);
        start = index + 1;
      }
    }

    if (quoted) {
      throw malformed();
    }

    add(values, value.substring(start), ignoreEmpty);

    return values;
  }

  /**
   * Adds one trimmed component and optionally ignores an empty element.
   *
   * @param values destination components
   * @param value candidate component
   * @param ignoreEmpty whether empty components are acceptable
   */
  private static void add(List<String> values, String value, boolean ignoreEmpty) {
    final var trimmed = value.trim();
    if (trimmed.isEmpty()) {
      if (!ignoreEmpty) {
        throw malformed();
      }

      return;
    }

    values.add(trimmed);
  }

  /**
   * Creates the consistent strict malformed-field exception.
   *
   * @return malformed input error
   */
  private static BadRequestException malformed() {
    return new BadRequestException("Malformed Accept field");
  }

  /** Parsed media parameters and quality. */
  private record Parameters(Map<String, String> values, int quality) {
    /**
     * Captures an immutable parameter map and a representable quality weight.
     *
     * @param values parsed parameters
     * @param quality quality in thousandths
     * @throws IllegalArgumentException if quality is outside zero through one thousand
     */
    private Parameters {
      if (quality < 0 || quality > 1000) {
        throw new IllegalArgumentException("Accept quality must be between zero and one");
      }

      values = Map.copyOf(values);
    }
  }

  /** A concrete candidate representation. */
  private record Representation(String type, String subtype, Map<String, String> parameters) {
    /**
     * Captures nonempty type components and immutable parameter metadata.
     *
     * @param type concrete media type
     * @param subtype concrete media subtype
     * @param parameters parsed parameters
     * @throws IllegalArgumentException if either type component is empty
     */
    private Representation {
      if (type.isEmpty() || subtype.isEmpty()) {
        throw new IllegalArgumentException("Representation type cannot be empty");
      }

      parameters = Map.copyOf(parameters);
    }
  }

  /** One weighted media range. */
  private record Range(String type, String subtype, Map<String, String> parameters, int quality) {
    /**
     * Captures nonempty range components, immutable parameters, and a valid quality weight.
     *
     * @param type media type or wildcard
     * @param subtype media subtype or wildcard
     * @param parameters parsed parameters
     * @param quality quality in thousandths
     * @throws IllegalArgumentException if a component is empty or quality is out of range
     */
    private Range {
      if (type.isEmpty() || subtype.isEmpty() || quality < 0 || quality > 1000) {
        throw new IllegalArgumentException("Invalid Accept range");
      }

      parameters = Map.copyOf(parameters);
    }

    /**
     * Compares charset names case-insensitively and other parameter values exactly.
     *
     * @param name parameter name
     * @param first candidate value
     * @param second range value
     * @return whether the values are equivalent
     */
    private static boolean equals(String name, String first, String second) {
      return Request.CHARSET_PARAMETER.equals(name)
          ? first.equalsIgnoreCase(second)
          : first.equals(second);
    }

    /**
     * Tests whether this range applies to a concrete representation.
     *
     * @param representation concrete candidate
     * @return whether this range matches
     */
    private boolean matches(Representation representation) {
      if (!(HttpCharacters.ASTERISK_STRING.equals(type) || type.equals(representation.type))
          || !(HttpCharacters.ASTERISK_STRING.equals(subtype)
              || subtype.equals(representation.subtype))) {
        return false;
      }

      for (final var parameter : parameters.entrySet()) {
        final var actual = representation.parameters.get(parameter.getKey());
        if (actual == null || !equals(parameter.getKey(), actual, parameter.getValue())) {
          return false;
        }
      }

      return true;
    }

    /**
     * Compares matching-range precedence without considering the quality.
     *
     * @param other competing range
     * @return whether this range has higher precedence
     */
    private boolean precedes(Range other) {
      if (specificity() != other.specificity()) {
        return specificity() > other.specificity();
      }

      return parameters.size() > other.parameters.size();
    }

    /**
     * Scores wildcard specificity.
     *
     * @return wildcard specificity score
     */
    private int specificity() {
      if (HttpCharacters.ASTERISK_STRING.equals(type)) {
        return 0;
      }

      return HttpCharacters.ASTERISK_STRING.equals(subtype) ? 1 : 2;
    }
  }
}
