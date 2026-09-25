package io.github.suppierk.shoostr;

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
  /** Prevents construction of this stateless parser. */
  private AcceptNegotiation() {}

  /**
   * Selects the highest-quality compatible candidate, retaining caller order for a complete tie.
   *
   * @param fields repeated Accept field values in wire order
   * @param candidates nonempty server-preference-ordered representations
   * @return original selected candidate, or null when every candidate is unacceptable
   * @throws BadRequestException if an Accept field is malformed
   */
  static @Nullable MediaType select(List<String> fields, MediaType[] candidates) {
    var ranges = ranges(fields);
    MediaType selected = null;
    int selectedQuality = 0;
    for (var candidate : candidates) {
      var quality = quality(candidate, ranges);
      if (quality > selectedQuality) {
        selected = candidate;
        selectedQuality = quality;
      }
    }

    return selected;
  }

  /**
   * Parses all repeated field lines as one ordered HTTP list.
   *
   * @param fields repeated field values
   * @return parsed ranges in wire order
   */
  private static List<Range> ranges(List<String> fields) {
    var ranges = new ArrayList<Range>();
    for (var field : fields) {
      for (var item : split(field, ',', true)) {
        ranges.add(range(item));
      }
    }

    return ranges;
  }

  /**
   * Finds the quality of the most-specific matching range for one candidate.
   *
   * @param candidate representation to test
   * @param ranges parsed client preferences
   * @return quality in thousandths, or zero when unacceptable
   */
  private static int quality(MediaType candidate, List<Range> ranges) {
    var representation = representation(candidate.value());
    Range best = null;
    for (var range : ranges) {
      if (range.matches(representation) && (best == null || range.precedes(best))) {
        best = range;
      }
    }

    return best == null ? 0 : best.quality;
  }

  /**
   * Parses one media range and its weight.
   *
   * @param item trimmed range item
   * @return parsed range
   */
  private static Range range(String item) {
    if (item.charAt(0) == ';') {
      throw malformed();
    }

    var pieces = split(item, ';', true);
    if (pieces.isEmpty()) {
      throw malformed();
    }

    var base = pieces.getFirst().trim();
    int slash = base.indexOf('/');
    if (slash < 1 || slash != base.lastIndexOf('/') || slash == base.length() - 1) {
      throw malformed();
    }

    var type = base.substring(0, slash).toLowerCase(Locale.ROOT);
    var subtype = base.substring(slash + 1).toLowerCase(Locale.ROOT);
    if (!("*".equals(type) || token(type))
        || !("*".equals(subtype) || token(subtype))
        || ("*".equals(type) && !"*".equals(subtype))) {
      throw malformed();
    }

    var parameters = parameters(pieces);
    return new Range(type, subtype, parameters.values, parameters.quality);
  }

  /**
   * Parses a selected candidate's concrete type and the limited parameters MediaType permits.
   *
   * @param value candidate media-type value
   * @return concrete representation
   */
  private static Representation representation(String value) {
    var pieces = split(value, ';', false);
    var base = pieces.getFirst().trim();
    int slash = base.indexOf('/');
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
    var values = new LinkedHashMap<String, String>();
    int quality = 1000;
    boolean qualitySeen = false;
    for (int index = 1; index < pieces.size(); index++) {
      var parameter = pieces.get(index);
      int equals = parameter.indexOf('=');
      if (equals < 1) {
        throw malformed();
      }

      var name = parameter.substring(0, equals).toLowerCase(Locale.ROOT);
      var value = parameter.substring(equals + 1);
      if (!token(name) || value.isEmpty()) {
        throw malformed();
      }

      if ("q".equals(name)) {
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
    if (alreadySeen || value.charAt(0) == '"') {
      throw malformed();
    }

    return quality(tokenValue(value));
  }

  /**
   * Decodes and inserts one non-quality parameter, rejecting duplicates.
   *
   * @param values parsed parameters
   * @param name normalized parameter name
   * @param value raw parameter value
   */
  private static void putParameter(Map<String, String> values, String name, String value) {
    var decoded = value.charAt(0) == '"' ? quoted(value) : tokenValue(value);
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
    if (value.length() < 2 || value.charAt(value.length() - 1) != '"') {
      throw malformed();
    }

    var decoded = new StringBuilder(value.length() - 2);
    boolean escaped = false;
    for (int index = 1; index < value.length() - 1; index++) {
      char character = value.charAt(index);
      if ((character < 0x20 && character != '\t')
          || character == 0x7f
          || (character == '"' && !escaped)) {
        throw malformed();
      }

      if (escaped) {
        decoded.append(character);
        escaped = false;
      } else if (character == '\\') {
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
    if (!token(value)) {
      throw malformed();
    }

    return value;
  }

  /**
   * Parses an RFC quality value into integer thousandths.
   *
   * @param value quality input
   * @return integer thousandths
   */
  private static int quality(String value) {
    if ("0".equals(value) || "1".equals(value)) {
      return "1".equals(value) ? 1000 : 0;
    }

    if (value.length() < 2 || value.charAt(1) != '.' || value.length() > 5) {
      throw malformed();
    }

    var whole = value.charAt(0);
    var fraction = value.substring(2);
    if ((whole != '0' && whole != '1') || !fraction.chars().allMatch(Character::isDigit)) {
      throw malformed();
    }

    if (whole == '1' && !fraction.chars().allMatch(character -> character == '0')) {
      throw malformed();
    }

    return (whole - '0') * 1000 + Integer.parseInt((fraction + "000").substring(0, 3));
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
    var values = new ArrayList<String>();
    int start = 0;
    boolean quoted = false;
    boolean escaped = false;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (escaped) {
        escaped = false;
      } else if (character == '\\' && quoted) {
        escaped = true;
      } else if (character == '"') {
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
    var trimmed = value.trim();
    if (trimmed.isEmpty()) {
      if (!ignoreEmpty) {
        throw malformed();
      }

      return;
    }

    values.add(trimmed);
  }

  /**
   * Tests the HTTP token grammar.
   *
   * @param value candidate token
   * @return whether the input is a token
   */
  private static boolean token(String value) {
    if (value.isEmpty()) {
      return false;
    }

    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (!(character >= '0' && character <= '9')
          && !(character >= 'A' && character <= 'Z')
          && !(character >= 'a' && character <= 'z')
          && "!#$%&'*+-.^_`|~".indexOf(character) < 0) {
        return false;
      }
    }

    return true;
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
      values = Map.copyOf(values);
      if (quality < 0 || quality > 1000) {
        throw new IllegalArgumentException("Accept quality must be between zero and one");
      }
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
     * Tests whether this range applies to a concrete representation.
     *
     * @param representation concrete candidate
     * @return whether this range matches
     */
    private boolean matches(Representation representation) {
      if (!("*".equals(type) || type.equals(representation.type))
          || !("*".equals(subtype) || subtype.equals(representation.subtype))) {
        return false;
      }

      for (var parameter : parameters.entrySet()) {
        var actual = representation.parameters.get(parameter.getKey());
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
      if ("*".equals(type)) {
        return 0;
      }

      return "*".equals(subtype) ? 1 : 2;
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
      return "charset".equals(name) ? first.equalsIgnoreCase(second) : first.equals(second);
    }
  }
}
