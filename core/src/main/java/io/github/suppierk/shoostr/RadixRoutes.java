package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpCharacters;
import io.github.suppierk.shoostr.http.HttpMethods;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;
import org.jspecify.annotations.Nullable;

/** Frozen compressed path patterns with literal, constrained, parameter and catch-all branches. */
class RadixRoutes {
  private static final String CATCH_ALL_EDGE = "{*}";
  private static final String CONSTRAINT_PREFIX = "{:";
  private static final char CONSTRAINT_LABEL = 1;
  private static final Pattern PARAMETER_PATTERN =
      Pattern.compile(
          HttpCharacters.BACKSLASH_STRING
              + HttpCharacters.OPEN_CURLY_BRACE_STRING
              + "([A-Za-z_][A-Za-z0-9_-]*)"
              + HttpCharacters.CLOSE_CURLY_BRACE_STRING);
  private static final Pattern CATCH_ALL_PATTERN =
      Pattern.compile("\\{\\*([A-Za-z_][A-Za-z0-9_-]*)}");
  private final @Nullable String edge;
  private final char label;
  private final Map<HttpMethods, Endpoint> endpoints;
  private final RadixRoutes[] children;
  private final @Nullable RadixRoutes parameter;
  private final List<StaticFiles> staticFiles;

  /**
   * Creates a frozen node. The supplied children are sorted by their first character and are owned
   * by this tree.
   *
   * @param edge compressed literal text, or null for a parameter or special branch
   * @param label branch lookup label; zero and one are reserved for special branches
   * @param endpoints immutable endpoints terminating at this node, indexed by method
   * @param children sorted literal branches
   * @param parameter parameter branch, or null if absent
   * @param staticFiles fallback static mounts retained by the root node
   */
  private RadixRoutes(
      @Nullable String edge,
      char label,
      Map<HttpMethods, Endpoint> endpoints,
      RadixRoutes[] children,
      @Nullable RadixRoutes parameter,
      List<StaticFiles> staticFiles) {
    this.edge = edge;
    this.label = label;
    this.endpoints = endpoints;
    this.children = children;
    this.parameter = parameter;
    this.staticFiles = staticFiles;
  }

  /**
   * Compiles registrations into a compressed tree. Sorting normalized patterns makes shared
   * prefixes adjacent; parameter names stay in endpoints.
   *
   * @param registrations validated endpoints in registration order
   * @return immutable router independent of the registration list
   * @throws IllegalArgumentException if a method is registered twice for the same normalized shape
   */
  static RadixRoutes from(List<Endpoint> registrations) {
    return from(registrations, List.of());
  }

  /**
   * Compiles ordinary endpoints and fallback static mounts into one frozen request router.
   *
   * @param registrations validated endpoints in registration order
   * @param staticFiles ordered fallback static mounts
   * @return immutable router independent of registration collections
   * @throws IllegalArgumentException if a method is registered twice for one normalized shape
   */
  static RadixRoutes from(List<Endpoint> registrations, List<StaticFiles> staticFiles) {
    var patterns = new TreeMap<String, Map<HttpMethods, Endpoint>>();
    var registrationOrder = new IdentityHashMap<Endpoint, Integer>();
    int ordinal = 0;
    for (var endpoint : registrations) {
      registrationOrder.put(endpoint, ordinal++);
      var methods = patterns.computeIfAbsent(endpoint.pattern(), ignored -> new HashMap<>());
      if (methods.putIfAbsent(endpoint.method(), endpoint) != null) {
        throw new IllegalArgumentException("Duplicate route shape: " + endpoint.pattern());
      }
    }
    var paths = patterns.keySet().toArray(String[]::new);
    return build(patterns, registrationOrder, paths, 0, paths.length, 0, List.copyOf(staticFiles));
  }

  /** Releases every static resource source, attempting all closures before reporting failures. */
  void close() {
    RuntimeException failure = null;
    for (var files : staticFiles) {
      try {
        files.close();
      } catch (RuntimeException closeFailure) {
        if (failure == null) {
          failure = closeFailure;
        } else {
          failure.addSuppressed(closeFailure);
        }
      }
    }

    if (failure != null) {
      throw failure;
    }
  }

  /**
   * Finds an existing static resource after ordinary endpoint lookup has failed.
   *
   * @param path request path
   * @param method requested HTTP method
   * @return synthetic static endpoint, or null when no resource matches
   */
  @Nullable Endpoint staticEndpoint(String path, @Nullable HttpMethods method) {
    for (var mount : staticFiles) {
      var endpoint = mount.endpoint(path, method);
      if (endpoint != null) {
        return endpoint;
      }
    }

    return null;
  }

  /**
   * Finds a complete path-and-method match, preferring literal, constrained, ordinary parameter,
   * then catch-all branches at each depth. Overlapping constraints use registration order.
   *
   * @param path Jetty-canonical encoded request path, without query or fragment
   * @param method requested method, or null for an unrecognized verb
   * @return matching endpoint, or null when no registration accepts both path and method
   */
  @Nullable Endpoint match(String path, @Nullable HttpMethods method) {
    return method == null ? null : find(path, method, 0);
  }

  /**
   * Collects methods from every complete path match for a method-not-allowed response.
   *
   * @param path Jetty-canonical encoded request path
   * @return matching methods, or an empty set for an unknown path
   */
  Set<HttpMethods> allowedMethods(String path) {
    var methods = collectMethods(path, 0, null);
    for (var mount : staticFiles) {
      if (mount.exists(path)) {
        if (methods == null) {
          methods = EnumSet.noneOf(HttpMethods.class);
        }

        methods.add(HttpMethods.GET);
        methods.add(HttpMethods.HEAD);
      }
    }

    return methods == null ? Set.of() : methods;
  }

  /**
   * Validates a route and prepares metadata for matching and lazy parameter decoding. Parameter
   * names are removed from the tree key; constrained expressions remain in the key.
   *
   * @param method registered HTTP method
   * @param path absolute route pattern
   * @param handler handler invoked after a match
   * @return compiled endpoint metadata
   */
  static Endpoint endpoint(HttpMethods method, String path, Handler handler) {
    var syntax = parse(path);
    return new Endpoint(
        method,
        syntax.pattern(),
        path,
        Objects.requireNonNull(handler),
        syntax.parameters(),
        syntax.firstParameterOffset(),
        syntax.firstParameterSegment(),
        null);
  }

  /**
   * Prepares a WebSocket upgrade route with the same path matching rules as HTTP GET.
   *
   * @param path absolute route pattern
   * @param admission handshake handler; compiled behavior supplies admission before upgrade
   * @param factory creates one listener from the handshake request and response
   * @return compiled WebSocket endpoint metadata
   */
  static Endpoint websocketEndpoint(
      String path,
      Handler admission,
      BiFunction<Request, ServerUpgradeResponse, Session.Listener> factory) {
    var endpoint = endpoint(HttpMethods.GET, path, admission);
    return new Endpoint(
        endpoint.method(),
        endpoint.pattern(),
        endpoint.routePattern(),
        endpoint.handler(),
        endpoint.parameters(),
        endpoint.firstParameterOffset(),
        endpoint.firstParameterSegment(),
        Objects.requireNonNull(factory));
  }

  /**
   * Validates literal, whole-segment parameter, constrained, and terminal catch-all syntax and
   * indexes parameter names by segment. Splitting retains empty segments so repeated and trailing
   * slashes remain significant.
   *
   * @param path absolute pattern without query or fragment
   * @return immutable parameter names and their segment indexes, counting the leading empty segment
   * @throws IllegalArgumentException if the path syntax is invalid or a parameter name is repeated
   * @throws NullPointerException if the path is null
   */
  static Map<String, Integer> parameters(@Nullable String path) {
    return parse(path).parameters();
  }

  /**
   * Compiles route syntax into a normalized tree key and lazy-capture positions.
   *
   * @param path absolute route pattern
   * @return validated route metadata
   * @throws IllegalArgumentException if the route syntax is invalid
   * @throws NullPointerException if the path is null
   */
  private static RouteSyntax parse(@Nullable String path) {
    Objects.requireNonNull(path, "path");
    if (path.isEmpty() || path.charAt(0) != HttpCharacters.PATH_SEPARATOR) {
      throw new IllegalArgumentException("Expected an absolute path without query or fragment");
    }

    var parameters = new HashMap<String, Integer>();
    var pattern = new StringBuilder(path.length());
    pattern.append(HttpCharacters.PATH_SEPARATOR);
    var segments = path.split(HttpCharacters.PATH_SEPARATOR_STRING, -1);
    int offset = 1;
    int firstParameterOffset = -1;
    int firstParameterSegment = 0;
    for (int i = 1; i < segments.length; i++) {
      if (i > 1) {
        pattern.append(HttpCharacters.PATH_SEPARATOR);
      }

      String segment = segments[i];
      appendSyntaxSegment(segment, i, segments.length, parameters, pattern);

      if (firstParameterOffset < 0 && !parameters.isEmpty()) {
        firstParameterOffset = offset;
        firstParameterSegment = i;
      }

      offset += segment.length() + 1;
    }
    return new RouteSyntax(
        parameters.isEmpty() ? path : pattern.toString(),
        Map.copyOf(parameters),
        firstParameterOffset,
        firstParameterSegment);
  }

  /**
   * Appends one normalized segment and records its capture position.
   *
   * @param segment route segment
   * @param index segment index, counting the leading empty segment
   * @param segmentCount number of segments in the route
   * @param parameters collected capture positions
   * @param pattern normalized pattern under construction
   * @throws IllegalArgumentException if the segment syntax is invalid
   */
  private static void appendSyntaxSegment(
      String segment,
      int index,
      int segmentCount,
      Map<String, Integer> parameters,
      StringBuilder pattern) {
    var parameter = PARAMETER_PATTERN.matcher(segment);
    if (parameter.matches()) {
      addParameter(parameters, parameter.group(1), index);
      pattern.append(HttpCharacters.CURLY_BRACES);
      return;
    }

    if (CATCH_ALL_PATTERN.matcher(segment).matches()) {
      appendCatchAllSegment(segment, index, segmentCount, parameters, pattern);
      return;
    }

    if (isConstrainedSegment(segment)) {
      appendConstrainedSegment(segment, index, parameters, pattern);
      return;
    }

    if (invalidLiteralSegment(segment)) {
      throw new IllegalArgumentException("Expected a literal segment or {name}: " + segment);
    }

    pattern.append(segment);
  }

  /**
   * Appends a terminal named tail capture.
   *
   * @param segment catch-all segment
   * @param index segment index
   * @param segmentCount route segment count
   * @param parameters collected capture positions
   * @param pattern normalized pattern under construction
   * @throws IllegalArgumentException if the catch-all is nonterminal or repeats a name
   */
  private static void appendCatchAllSegment(
      String segment,
      int index,
      int segmentCount,
      Map<String, Integer> parameters,
      StringBuilder pattern) {
    if (index != segmentCount - 1) {
      throw new IllegalArgumentException("Catch-all must be the final segment: " + segment);
    }

    String name = segment.substring(2, segment.length() - 1);
    addParameter(parameters, name, -index);
    pattern.append(CATCH_ALL_EDGE);
  }

  /**
   * Appends a validated segment-local constraint marker.
   *
   * @param segment constrained segment
   * @param index segment index
   * @param parameters collected capture positions
   * @param pattern normalized pattern under construction
   * @throws IllegalArgumentException if the name or constraint is invalid or repeated
   */
  private static void appendConstrainedSegment(
      String segment, int index, Map<String, Integer> parameters, StringBuilder pattern) {
    int separator = segment.indexOf(':');
    String name = segment.substring(1, separator);
    if (!PARAMETER_PATTERN.matcher("{" + name + "}").matches()) {
      throw new IllegalArgumentException("Invalid constrained parameter name: " + name);
    }

    String constraint = segment.substring(separator + 1, segment.length() - 1);
    var compiled = compileConstraint(constraint, Map.of());
    addParameter(parameters, name, index);
    pattern.append(CONSTRAINT_PREFIX).append(compiled.expression()).append('}');
  }

  /**
   * Records a unique parameter name and its segment position.
   *
   * @param parameters collected capture positions
   * @param name parameter name
   * @param index positive single-segment or negative catch-all position
   * @throws IllegalArgumentException if the name is repeated
   */
  private static void addParameter(Map<String, Integer> parameters, String name, int index) {
    if (parameters.putIfAbsent(name, index) != null) {
      throw new IllegalArgumentException("Repeated path parameter: " + name);
    }
  }

  /**
   * Identifies a whole-segment constrained parameter candidate.
   *
   * @param segment route segment
   * @return whether the segment has a constraint separator inside braces
   */
  private static boolean isConstrainedSegment(String segment) {
    return segment.startsWith("{") && segment.endsWith("}") && segment.contains(":");
  }

  /**
   * Rejects literal segments containing reserved route or URI syntax.
   *
   * @param segment route segment
   * @return whether the segment cannot be a literal
   */
  private static boolean invalidLiteralSegment(String segment) {
    return segment.indexOf(HttpCharacters.OPEN_CURLY_BRACE) >= 0
        || segment.indexOf(HttpCharacters.CLOSE_CURLY_BRACE) >= 0
        || segment.indexOf(HttpCharacters.ASTERISK) >= 0
        || segment.indexOf(HttpCharacters.OPEN_ANGLE_BRACKET) >= 0
        || segment.indexOf(HttpCharacters.CLOSE_ANGLE_BRACKET) >= 0
        || segment.indexOf(HttpCharacters.QUERY_SEPARATOR) >= 0
        || segment.indexOf(HttpCharacters.FRAGMENT_SEPARATOR) >= 0
        || containsControl(segment);
  }

  /**
   * Prevents literals from using the reserved control-character branch labels.
   *
   * @param segment literal path segment
   * @return whether it contains an ASCII control character
   */
  private static boolean containsControl(String segment) {
    for (int i = 0; i < segment.length(); i++) {
      char character = segment.charAt(i);
      if (character < 32 || character == 127) {
        return true;
      }
    }
    return false;
  }

  /**
   * Validates a safe character-class constraint and compiles its ASCII lookup bits.
   *
   * @param constraint constraint expression
   * @param ranks original registration positions for matching endpoints
   * @return compiled constraint
   * @throws IllegalArgumentException if the expression is unsupported
   */
  private static Constraint compileConstraint(String constraint, Map<Endpoint, Integer> ranks) {
    if (constraint.length() < 3 || constraint.charAt(0) != '[') {
      throw new IllegalArgumentException("Expected one character class in route constraint");
    }

    int close = constraint.indexOf(']');
    if (close < 2) {
      throw new IllegalArgumentException("Expected a nonempty character class in route constraint");
    }

    long[] bits = classBits(constraint, close);
    int[] bounds = repetitionBounds(constraint.substring(close + 1));
    return new Constraint(constraint, bits[0], bits[1], bounds[0], bounds[1], ranks);
  }

  /**
   * Compiles the supported class atoms into two ASCII membership bitsets.
   *
   * @param constraint constraint expression
   * @param close index of the closing class bracket
   * @return membership bits below and above ASCII code 64
   * @throws IllegalArgumentException if a class character or range is unsupported
   */
  private static long[] classBits(String constraint, int close) {
    long[] bits = new long[2];
    boolean rangeStartAvailable = false;
    int i = 1;
    while (i < close) {
      char character = constraint.charAt(i);
      if (!allowedClassCharacter(character)) {
        throw new IllegalArgumentException("Unsupported route constraint character: " + character);
      }

      if (character == '-' && i > 1 && constraint.charAt(i - 1) == '-') {
        throw new IllegalArgumentException("Unsupported consecutive hyphens in route constraint");
      }

      if (character == '-' && rangeStartAvailable && i < close - 1) {
        includeRange(bits, constraint.charAt(i - 1), constraint.charAt(i + 1));
        i += 2;
        rangeStartAvailable = false;
      } else {
        includeCharacter(bits, character);
        i++;
        rangeStartAvailable = character != '-';
      }
    }
    return bits;
  }

  /**
   * Adds every member of a same-category ascending range.
   *
   * @param bits mutable ASCII membership bits
   * @param first first range character
   * @param last final range character
   * @throws IllegalArgumentException if the range is unsupported or descending
   */
  private static void includeRange(long[] bits, char first, char last) {
    if (!sameRange(first, last) || last < first) {
      throw new IllegalArgumentException("Invalid route constraint range");
    }

    for (char value = first; value <= last; value++) {
      includeCharacter(bits, value);
    }
  }

  /**
   * Sets one ASCII membership bit.
   *
   * @param bits mutable ASCII membership bits
   * @param character allowed ASCII class character
   */
  private static void includeCharacter(long[] bits, char character) {
    if (character < 64) {
      bits[0] |= 1L << character;
    } else {
      bits[1] |= 1L << (character - 64);
    }
  }

  /**
   * Parses the optional simple repetition after a character class.
   *
   * @param repetition expression suffix
   * @return inclusive minimum and maximum repetition counts
   * @throws IllegalArgumentException if the repetition form is unsupported
   */
  private static int[] repetitionBounds(String repetition) {
    if (repetition.isEmpty()) {
      return new int[] {1, 1};
    }

    if ("+".equals(repetition)) {
      return new int[] {1, Integer.MAX_VALUE};
    }

    if ("*".equals(repetition)) {
      return new int[] {0, Integer.MAX_VALUE};
    }

    if ("?".equals(repetition)) {
      return new int[] {0, 1};
    }

    if (!validBoundedRepetition(repetition)) {
      throw new IllegalArgumentException("Unsupported route constraint repetition: " + repetition);
    }

    String bounds = repetition.substring(1, repetition.length() - 1);
    int comma = bounds.indexOf(',');
    int minimum = Integer.parseInt(comma < 0 ? bounds : bounds.substring(0, comma));
    int maximum = comma < 0 ? minimum : Integer.parseInt(bounds.substring(comma + 1));
    return new int[] {minimum, maximum};
  }

  /**
   * Restricts character classes to ASCII letters, digits, underscore and hyphen.
   *
   * @param character class character
   * @return whether the character belongs to the supported alphabet
   */
  private static boolean allowedClassCharacter(char character) {
    return (character >= 'A' && character <= 'Z')
        || (character >= 'a' && character <= 'z')
        || (character >= '0' && character <= '9')
        || character == '_'
        || character == '-';
  }

  /**
   * Accepts ranges only within one ASCII letter case or the digits.
   *
   * @param first first range character
   * @param last final range character
   * @return whether the range is supported
   */
  private static boolean sameRange(char first, char last) {
    return (first >= 'A' && first <= 'Z' && last >= 'A' && last <= 'Z')
        || (first >= 'a' && first <= 'z' && last >= 'a' && last <= 'z')
        || (first >= '0' && first <= '9' && last >= '0' && last <= '9');
  }

  /**
   * Checks bounded repetition syntax before its numeric limits are parsed.
   *
   * @param repetition repetition suffix
   * @return whether the bounds are valid
   */
  private static boolean validBoundedRepetition(String repetition) {
    if (repetition.length() < 3
        || repetition.charAt(0) != '{'
        || repetition.charAt(repetition.length() - 1) != '}') {
      return false;
    }

    String bounds = repetition.substring(1, repetition.length() - 1);
    int comma = bounds.indexOf(',');
    String minimum = comma < 0 ? bounds : bounds.substring(0, comma);
    String maximum = comma < 0 ? bounds : bounds.substring(comma + 1);
    if (minimum.isEmpty() || maximum.isEmpty() || minimum.length() > 5 || maximum.length() > 5) {
      return false;
    }

    for (int i = 0; i < minimum.length(); i++) {
      if (minimum.charAt(i) < '0' || minimum.charAt(i) > '9') {
        return false;
      }
    }
    for (int i = 0; i < maximum.length(); i++) {
      if (maximum.charAt(i) < '0' || maximum.charAt(i) > '9') {
        return false;
      }
    }
    int lower = Integer.parseInt(minimum);
    int upper = Integer.parseInt(maximum);
    return upper > 0 && lower <= upper;
  }

  /**
   * Searches literal, constrained, plain parameter, then catch-all branches. A partial literal
   * prefix or method mismatch cannot hide a less-specific complete match.
   *
   * @param path Jetty-canonical encoded request path
   * @param method requested method
   * @param offset first unconsumed character
   * @return preferred complete match in this subtree, or null
   */
  @Nullable Endpoint find(String path, HttpMethods method, int offset) {
    int end = consume(path, offset);
    if (end < 0) {
      return null;
    }

    if (end == path.length()) {
      return endpoints.get(method);
    }

    var literal = child(path.charAt(end));
    if (literal != null) {
      var match = literal.find(path, method, end);
      if (match != null) {
        return match;
      }
    }

    return parameter == null ? null : parameter.find(path, method, end);
  }

  /**
   * Tries the remaining branch classes after a literal path fails to complete.
   *
   * @param path encoded request path
   * @param method requested method
   * @param offset first unconsumed character
   * @return matching endpoint or null
   */
  private @Nullable Endpoint findFallback(String path, HttpMethods method, int offset) {
    if (children.length == 0 || children[0].label > CONSTRAINT_LABEL) {
      return parameter == null ? null : parameter.find(path, method, offset);
    }

    if (hasConstraints()) {
      var match = findConstrainedChildren(path, method, offset);
      if (match != null) {
        return match;
      }
    }

    if (parameter != null) {
      var match = parameter.find(path, method, offset);
      if (match != null) {
        return match;
      }
    }

    return children[0].label == 0
        ? ((CatchAllRoutes) children[0]).findCatchAll(path, method, offset)
        : null;
  }

  /**
   * Continues matching below a special branch after its segment has been consumed.
   *
   * @param path encoded request path
   * @param method requested method
   * @param end offset after the consumed segment
   * @return matching endpoint or null
   */
  private @Nullable Endpoint findAfterSpecial(String path, HttpMethods method, int end) {
    if (end == path.length()) {
      return endpoints.get(method);
    }

    var literal = child(path.charAt(end));
    if (literal != null) {
      var match = literal.find(path, method, end);
      if (match != null) {
        return match;
      }
    }

    return findFallback(path, method, end);
  }

  /**
   * Visits every complete matching branch, allocating the method set only after a match. Unknown
   * paths therefore do not allocate an accumulator.
   *
   * @param path Jetty-canonical encoded request path
   * @param offset first unconsumed character
   * @param methods accumulated methods, or null until the first match
   * @return updated accumulator, or null if no endpoint has matched
   */
  @Nullable Set<HttpMethods> collectMethods(
      String path, int offset, @Nullable Set<HttpMethods> methods) {
    int end = consume(path, offset);
    if (end < 0) {
      return methods;
    }

    if (end == path.length()) {
      if (!endpoints.isEmpty()) {
        if (methods == null) {
          methods = EnumSet.noneOf(HttpMethods.class);
        }

        methods.addAll(endpoints.keySet());
      }

      return methods;
    }

    var literal = child(path.charAt(end));
    if (literal != null) {
      methods = literal.collectMethods(path, end, methods);
    }

    return parameter == null ? methods : parameter.collectMethods(path, end, methods);
  }

  /**
   * Collects complete-path methods below a consumed special branch.
   *
   * @param path encoded request path
   * @param end offset after the consumed segment
   * @param methods accumulated methods or null
   * @return updated accumulator or null
   */
  private @Nullable Set<HttpMethods> collectAfterSpecial(
      String path, int end, @Nullable Set<HttpMethods> methods) {
    if (end == path.length()) {
      if (!endpoints.isEmpty()) {
        if (methods == null) {
          methods = EnumSet.noneOf(HttpMethods.class);
        }

        methods.addAll(endpoints.keySet());
      }

      return methods;
    }

    var literal = child(path.charAt(end));
    if (literal != null) {
      methods = literal.collectMethods(path, end, methods);
    }

    if (hasConstraints()) {
      for (int i = firstConstraint();
          i < children.length && children[i].label == CONSTRAINT_LABEL;
          i++) {
        methods = ((ConstrainedRoutes) children[i]).collectConstrained(path, end, methods);
      }
    }

    if (parameter != null) {
      methods = parameter.collectMethods(path, end, methods);
    }

    return children.length > 0 && children[0].label == 0
        ? ((CatchAllRoutes) children[0]).collectCatchAll(path, end, methods)
        : methods;
  }

  /**
   * Consumes this node's literal edge or one nonempty parameter segment without creating
   * substrings. Special branches are matched by their parent instead.
   *
   * @param path Jetty-canonical encoded request path
   * @param offset first unconsumed character
   * @return offset after the edge, or -1 when the edge does not match
   */
  private int consume(String path, int offset) {
    if (edge != null) {
      return path.startsWith(edge, offset) ? offset + edge.length() : -1;
    }

    if (offset == path.length() || path.charAt(offset) == HttpCharacters.PATH_SEPARATOR) {
      return -1;
    }

    int end = path.indexOf(HttpCharacters.PATH_SEPARATOR, offset);
    return end < 0 ? path.length() : end;
  }

  /**
   * Reports whether the sorted child array contains constrained branches.
   *
   * @return whether constrained branches exist
   */
  private boolean hasConstraints() {
    int first = firstConstraint();
    return first < children.length && children[first].label == CONSTRAINT_LABEL;
  }

  /**
   * Skips an optional catch-all child at the start of the sorted child array.
   *
   * @return first possible constrained-child index
   */
  private int firstConstraint() {
    return children.length > 0 && children[0].label == 0 ? 1 : 0;
  }

  /**
   * Selects the earliest registered complete match among constrained children.
   *
   * @param path encoded request path
   * @param method requested method
   * @param offset first unconsumed character
   * @return earliest matching endpoint or null
   */
  private @Nullable Endpoint findConstrainedChildren(String path, HttpMethods method, int offset) {
    Endpoint best = null;
    int bestRank = Integer.MAX_VALUE;
    for (int i = firstConstraint();
        i < children.length && children[i].label == CONSTRAINT_LABEL;
        i++) {
      var child = (ConstrainedRoutes) children[i];
      var match = child.findConstrainedSegment(path, method, offset);
      if (match != null) {
        int rank = child.constraint.rank(match);
        if (rank < bestRank) {
          best = match;
          bestRank = rank;
        }
      }
    }
    return best;
  }

  /**
   * Finds a literal child by its first character, using binary search for multiple branches.
   *
   * @param next next request-path character
   * @return matching literal child, or null
   */
  private @Nullable RadixRoutes child(char next) {
    if (children.length == 1) {
      var candidate = children[0];
      return candidate.label == next ? candidate : null;
    }

    int low = 0;
    int high = children.length - 1;
    while (low <= high) {
      int middle = (low + high) >>> 1;
      var candidate = children[middle];
      int comparison = Character.compare(next, candidate.label);
      if (comparison < 0) {
        high = middle - 1;
      } else if (comparison > 0) {
        low = middle + 1;
      } else {
        return candidate;
      }
    }
    return null;
  }

  /**
   * Builds a subtree from a sorted half-open range of normalized patterns. Common literal prefixes
   * become one edge; parameter and constraint markers form separate single-segment edges. Endpoint
   * maps are copied before the mutable registration structures can be cleared.
   *
   * @param routes normalized patterns mapped to method-specific endpoints
   * @param registrationOrder original endpoint order for overlapping constraints
   * @param paths sorted normalized patterns
   * @param from inclusive range start
   * @param to exclusive range end
   * @param offset first character not consumed by an ancestor
   * @param staticFiles fallback static mounts attached only to the root
   * @return frozen subtree covering the supplied range
   */
  private static RadixRoutes build(
      Map<String, Map<HttpMethods, Endpoint>> routes,
      Map<Endpoint, Integer> registrationOrder,
      String[] paths,
      int from,
      int to,
      int offset,
      List<StaticFiles> staticFiles) {
    if (from == to) {
      return new RadixRoutes(
          Routes.EMPTY_PATH, (char) 0, Map.of(), new RadixRoutes[0], null, staticFiles);
    }

    int subtreeFrom = from;
    String first = paths[from];
    String last = paths[to - 1];
    boolean parameterEdge = first.startsWith(HttpCharacters.CURLY_BRACES, offset);
    boolean catchAllEdge = first.startsWith(CATCH_ALL_EDGE, offset);
    boolean constrainedEdge = first.startsWith(CONSTRAINT_PREFIX, offset);
    int end = edgeEnd(first, last, offset);

    Map<HttpMethods, Endpoint> endpoints = Map.of();
    if (first.length() == end) {
      endpoints = Map.copyOf(routes.get(first));
      from++;
    }

    var children = new ArrayList<RadixRoutes>();
    RadixRoutes parameter = null;
    while (from < to) {
      char label = branchLabel(paths[from], end);
      int next = nextBranchEnd(paths, from, to, end, label);
      var child = build(routes, registrationOrder, paths, from, next, end, List.of());
      if (label == HttpCharacters.OPEN_CURLY_BRACE) {
        parameter = child;
      } else {
        children.add(child);
      }

      from = next;
    }
    children.sort(Comparator.comparingInt(node -> node.label));
    var frozenChildren = children.toArray(RadixRoutes[]::new);
    if (constrainedEdge) {
      return new ConstrainedRoutes(
          compileConstraint(
              first.substring(offset + CONSTRAINT_PREFIX.length(), end - 1),
              ranks(routes, registrationOrder, paths, subtreeFrom, to)),
          endpoints,
          frozenChildren,
          parameter,
          staticFiles);
    }

    if (catchAllEdge) {
      return new CatchAllRoutes(endpoints, frozenChildren, parameter, staticFiles);
    }

    String edge = parameterEdge ? null : first.substring(offset, end);
    char label = edge == null || edge.isEmpty() ? 0 : edge.charAt(0);
    return frozenChildren.length > 0 && frozenChildren[0].label <= CONSTRAINT_LABEL
        ? new SpecialBranchRoutes(edge, label, endpoints, frozenChildren, parameter, staticFiles)
        : new RadixRoutes(edge, label, endpoints, frozenChildren, parameter, staticFiles);
  }

  /**
   * Chooses the boundary of one normalized edge.
   *
   * @param first first path in the subtree
   * @param last last path in the subtree
   * @param offset already consumed prefix length
   * @return edge boundary
   */
  private static int edgeEnd(String first, String last, int offset) {
    if (first.startsWith(HttpCharacters.CURLY_BRACES, offset)) {
      return offset + HttpCharacters.CURLY_BRACES.length();
    }

    if (first.startsWith(CATCH_ALL_EDGE, offset)) {
      return offset + CATCH_ALL_EDGE.length();
    }

    if (first.startsWith(CONSTRAINT_PREFIX, offset)) {
      return constraintEnd(first, offset);
    }

    return prefixEnd(first, last, offset);
  }

  /**
   * Finds the exclusive end of one sibling branch in sorted route patterns.
   *
   * @param paths sorted normalized patterns
   * @param from first pattern in the branch
   * @param to first pattern outside the subtree
   * @param offset branch start
   * @param label reserved or literal branch label
   * @return first pattern outside this branch
   */
  private static int nextBranchEnd(String[] paths, int from, int to, int offset, char label) {
    int next = from + 1;
    if (label == CONSTRAINT_LABEL) {
      int branchEnd = constraintEnd(paths[from], offset);
      String token = paths[from].substring(offset, branchEnd);
      while (next < to && paths[next].startsWith(token, offset)) {
        next++;
      }
      return next;
    }

    while (next < to && branchLabel(paths[next], offset) == label) {
      next++;
    }
    return next;
  }

  /**
   * Finds the end of a literal edge shared by the first and last sorted paths.
   *
   * @param first first path in the range
   * @param last last path in the range
   * @param offset prefix already consumed by an ancestor
   * @return first differing character or parameter marker
   */
  private static int prefixEnd(String first, String last, int offset) {
    int end = offset;
    while (end < first.length()
        && end < last.length()
        && first.charAt(end) != HttpCharacters.OPEN_CURLY_BRACE
        && first.charAt(end) == last.charAt(end)) {
      end++;
    }

    return end;
  }

  /**
   * Maps a normalized branch marker to its reserved sorted-child label.
   *
   * @param path normalized route pattern
   * @param offset branch start
   * @return sorted-child label
   */
  private static char branchLabel(String path, int offset) {
    if (path.startsWith(CATCH_ALL_EDGE, offset)) {
      return 0;
    }

    if (path.startsWith(CONSTRAINT_PREFIX, offset)) {
      return CONSTRAINT_LABEL;
    }

    return path.charAt(offset);
  }

  /**
   * Finds the boundary of one normalized constrained-segment marker.
   *
   * @param path normalized route pattern
   * @param offset constraint start
   * @return offset after the marker
   */
  private static int constraintEnd(String path, int offset) {
    int slash = path.indexOf(HttpCharacters.PATH_SEPARATOR, offset);
    return slash < 0 ? path.length() : slash;
  }

  /**
   * Retains original registration order for endpoints under a constrained branch.
   *
   * @param routes normalized route table
   * @param registrationOrder registration positions
   * @param paths sorted route patterns
   * @param from first included pattern
   * @param to first excluded pattern
   * @return endpoint ranks
   */
  private static Map<Endpoint, Integer> ranks(
      Map<String, Map<HttpMethods, Endpoint>> routes,
      Map<Endpoint, Integer> registrationOrder,
      String[] paths,
      int from,
      int to) {
    var ranks = new IdentityHashMap<Endpoint, Integer>();
    for (int i = from; i < to; i++) {
      for (var endpoint : Objects.requireNonNull(routes.get(paths[i])).values()) {
        ranks.put(endpoint, registrationOrder.get(endpoint));
      }
    }
    return ranks;
  }

  /** Node with special children; ordinary nodes retain the original matching methods. */
  private static final class SpecialBranchRoutes extends RadixRoutes {
    /**
     * Creates a frozen node with constrained or catch-all children.
     *
     * @param edge compressed literal edge or null for a plain parameter
     * @param label sorted-child lookup label
     * @param endpoints terminal route methods
     * @param children sorted child branches
     * @param parameter plain parameter branch or null
     * @param staticFiles static mounts
     */
    private SpecialBranchRoutes(
        @Nullable String edge,
        char label,
        Map<HttpMethods, Endpoint> endpoints,
        RadixRoutes[] children,
        @Nullable RadixRoutes parameter,
        List<StaticFiles> staticFiles) {
      super(edge, label, endpoints, children, parameter, staticFiles);
    }

    /**
     * Matches this edge and applies literal, constrained, plain and catch-all precedence.
     *
     * @param path encoded request path
     * @param method requested method
     * @param offset first unconsumed character
     * @return matching endpoint or null
     */
    @Override
    @Nullable Endpoint find(String path, HttpMethods method, int offset) {
      int end = ((RadixRoutes) this).consume(path, offset);
      return end < 0 ? null : ((RadixRoutes) this).findAfterSpecial(path, method, end);
    }

    /**
     * Collects all complete-path methods beneath this special-child node.
     *
     * @param path encoded request path
     * @param offset first unconsumed character
     * @param methods accumulated methods or null
     * @return updated accumulator or null
     */
    @Override
    @Nullable Set<HttpMethods> collectMethods(
        String path, int offset, @Nullable Set<HttpMethods> methods) {
      int end = ((RadixRoutes) this).consume(path, offset);
      return end < 0 ? methods : ((RadixRoutes) this).collectAfterSpecial(path, end, methods);
    }
  }

  /** Terminal tail matcher kept off the ordinary node's consume hot path. */
  private static final class CatchAllRoutes extends RadixRoutes {
    /**
     * Creates a frozen catch-all branch.
     *
     * @param endpoints terminal route methods
     * @param children sorted child branches
     * @param parameter plain parameter branch or null
     * @param staticFiles static mounts
     */
    private CatchAllRoutes(
        Map<HttpMethods, Endpoint> endpoints,
        RadixRoutes[] children,
        @Nullable RadixRoutes parameter,
        List<StaticFiles> staticFiles) {
      super(null, (char) 0, endpoints, children, parameter, staticFiles);
    }

    /**
     * Matches a nonempty tail and then resolves the requested method.
     *
     * @param path encoded request path
     * @param method requested method
     * @param offset tail start
     * @return matched endpoint or null
     */
    private @Nullable Endpoint findCatchAll(String path, HttpMethods method, int offset) {
      return validTail(path, offset)
          ? ((RadixRoutes) this).findAfterSpecial(path, method, path.length())
          : null;
    }

    /**
     * Collects methods registered for a complete nonempty tail.
     *
     * @param path encoded request path
     * @param offset tail start
     * @param methods accumulated methods or null
     * @return updated accumulator or null
     */
    private @Nullable Set<HttpMethods> collectCatchAll(
        String path, int offset, @Nullable Set<HttpMethods> methods) {
      return validTail(path, offset)
          ? ((RadixRoutes) this).collectAfterSpecial(path, path.length(), methods)
          : methods;
    }

    /**
     * Requires at least one nonempty segment at the catch-all position.
     *
     * @param path encoded request path
     * @param offset tail start
     * @return whether the tail is valid
     */
    private static boolean validTail(String path, int offset) {
      return offset < path.length() && path.charAt(offset) != HttpCharacters.PATH_SEPARATOR;
    }
  }

  /** Segment-local character-class matcher kept off the ordinary node's consume hot path. */
  private static final class ConstrainedRoutes extends RadixRoutes {
    private final Constraint constraint;

    /**
     * Creates a frozen constrained branch.
     *
     * @param constraint compiled constraint
     * @param endpoints terminal route methods
     * @param children sorted child branches
     * @param parameter plain parameter branch or null
     * @param staticFiles static mounts
     */
    private ConstrainedRoutes(
        Constraint constraint,
        Map<HttpMethods, Endpoint> endpoints,
        RadixRoutes[] children,
        @Nullable RadixRoutes parameter,
        List<StaticFiles> staticFiles) {
      super(null, CONSTRAINT_LABEL, endpoints, children, parameter, staticFiles);
      this.constraint = Objects.requireNonNull(constraint);
    }

    /**
     * Matches a constrained segment and continues through any route suffix.
     *
     * @param path encoded request path
     * @param method requested method
     * @param offset segment start
     * @return matched endpoint or null
     */
    private @Nullable Endpoint findConstrainedSegment(String path, HttpMethods method, int offset) {
      int end = segmentEnd(path, offset);
      return constraint.matches(path, offset, end)
          ? ((RadixRoutes) this).findAfterSpecial(path, method, end)
          : null;
    }

    /**
     * Collects methods under a matching constrained segment.
     *
     * @param path encoded request path
     * @param offset segment start
     * @param methods accumulated methods or null
     * @return updated accumulator or null
     */
    private @Nullable Set<HttpMethods> collectConstrained(
        String path, int offset, @Nullable Set<HttpMethods> methods) {
      int end = segmentEnd(path, offset);
      return constraint.matches(path, offset, end)
          ? ((RadixRoutes) this).collectAfterSpecial(path, end, methods)
          : methods;
    }

    /**
     * Finds the next slash without allocating a segment substring.
     *
     * @param path encoded request path
     * @param offset segment start
     * @return offset after the segment
     */
    private static int segmentEnd(String path, int offset) {
      int end = path.indexOf(HttpCharacters.PATH_SEPARATOR, offset);
      return end < 0 ? path.length() : end;
    }
  }

  /** Registration-time normalized key and capture metadata. */
  private record RouteSyntax(
      String pattern,
      Map<String, Integer> parameters,
      int firstParameterOffset,
      int firstParameterSegment) {
    /**
     * Requires capture metadata to agree with the normalized route shape.
     *
     * @param pattern normalized route pattern
     * @param parameters named capture indexes
     * @param firstParameterOffset first capture position
     * @param firstParameterSegment first capture segment
     * @throws IllegalArgumentException if capture metadata is inconsistent
     */
    RouteSyntax {
      Objects.requireNonNull(pattern);
      parameters = Map.copyOf(parameters);
      if (firstParameterOffset < -1
          || firstParameterSegment < 0
          || parameters.isEmpty() != (firstParameterOffset == -1)
          || parameters.isEmpty() != (firstParameterSegment == 0)) {
        throw new IllegalArgumentException("Invalid first route parameter position");
      }
    }
  }

  /** Immutable ASCII character-class matcher and registration-order index. */
  private record Constraint(
      String expression,
      long lowerBits,
      long upperBits,
      int minimum,
      int maximum,
      Map<Endpoint, Integer> ranks) {
    /**
     * Requires nonempty character membership and valid repetition bounds.
     *
     * @param expression constraint expression
     * @param lowerBits ASCII membership bits for characters below 64
     * @param upperBits ASCII membership bits for characters from 64 to 127
     * @param minimum minimum repetition count
     * @param maximum maximum repetition count
     * @param ranks endpoint registration positions
     * @throws IllegalArgumentException if the constraint is invalid
     */
    Constraint {
      Objects.requireNonNull(expression);
      ranks = Map.copyOf(ranks);
      if ((lowerBits | upperBits) == 0 || minimum < 0 || maximum < 1 || minimum > maximum) {
        throw new IllegalArgumentException("Invalid route constraint");
      }
    }

    /**
     * Tests a path slice without a request-time regex object or allocation.
     *
     * @param path encoded request path
     * @param start segment start
     * @param end segment end
     * @return whether the constraint matches
     */
    boolean matches(String path, int start, int end) {
      int length = end - start;
      if (length == 0 || length < minimum || length > maximum) {
        return false;
      }

      for (int i = start; i < end; i++) {
        char value = path.charAt(i);
        if (value >= 128
            || (value < 64
                ? (lowerBits & (1L << value)) == 0
                : (upperBits & (1L << (value - 64))) == 0)) {
          return false;
        }
      }
      return true;
    }

    /**
     * Returns the original registration position of a matched endpoint.
     *
     * @param endpoint matched route endpoint
     * @return original registration position
     */
    int rank(Endpoint endpoint) {
      return Objects.requireNonNull(ranks.get(endpoint));
    }
  }

  /** Precompiled endpoint metadata shared by all requests matching this registration. */
  record Endpoint(
      HttpMethods method,
      String pattern,
      String routePattern,
      Handler handler,
      Map<String, Integer> parameters,
      int firstParameterOffset,
      int firstParameterSegment,
      @Nullable BiFunction<Request, ServerUpgradeResponse, Session.Listener> websocketFactory,
      @Nullable EndpointBehavior behavior) {
    /**
     * Creates an ordinary endpoint without local runtime behavior.
     *
     * @param method HTTP method
     * @param pattern radix pattern
     * @param routePattern public composed template
     * @param handler submitted handler
     * @param parameters parameter names and positions
     * @param firstParameterOffset first capture character offset
     * @param firstParameterSegment first capture segment
     * @param websocketFactory optional handshake factory
     */
    Endpoint(
        HttpMethods method,
        String pattern,
        String routePattern,
        Handler handler,
        Map<String, Integer> parameters,
        int firstParameterOffset,
        int firstParameterSegment,
        @Nullable BiFunction<Request, ServerUpgradeResponse, Session.Listener> websocketFactory) {
      this(
          method,
          pattern,
          routePattern,
          handler,
          parameters,
          firstParameterOffset,
          firstParameterSegment,
          websocketFactory,
          null);
    }

    /**
     * Copies parameter metadata so later changes to the caller's map cannot affect requests.
     *
     * @param method registered HTTP method
     * @param pattern normalized pattern with unnamed parameter markers
     * @param routePattern original composed pattern including parameter names
     * @param handler matched handler
     * @param parameters parameter names mapped to segment indexes
     * @param firstParameterOffset character offset of the first parameter, or -1 for literal routes
     * @param firstParameterSegment segment index of the first parameter, or zero for literal routes
     * @param websocketFactory per-upgrade listener factory, or null for ordinary HTTP routes
     * @param behavior immutable local runtime behavior, or null
     * @throws IllegalArgumentException if the first parameter position is invalid
     */
    Endpoint {
      Objects.requireNonNull(method);
      Objects.requireNonNull(pattern);
      Objects.requireNonNull(routePattern);
      Objects.requireNonNull(handler);
      parameters = Map.copyOf(parameters);
      boolean literal = parameters.isEmpty();
      if (firstParameterOffset < -1
          || firstParameterSegment < 0
          || literal != (firstParameterOffset == -1)
          || (literal && firstParameterSegment != 0)) {
        throw new IllegalArgumentException("Invalid first parameter position");
      }
    }

    /**
     * Attaches immutable behavior while preserving all validated route facts.
     *
     * @param behavior local runtime data, or null
     * @return this endpoint when behavior is absent, otherwise its enriched copy
     */
    Endpoint withBehavior(@Nullable EndpointBehavior behavior) {
      return behavior == null
          ? this
          : new Endpoint(
              method,
              pattern,
              routePattern,
              handler,
              parameters,
              firstParameterOffset,
              firstParameterSegment,
              websocketFactory,
              behavior);
    }

    /**
     * Extracts one parameter lazily from an already matched encoded path and decodes UTF-8 escapes
     * once. Literal plus signs are preserved because path segments do not use form-encoding
     * semantics.
     *
     * @param path Jetty-canonical encoded path that matched this endpoint
     * @param name declared parameter name
     * @return decoded parameter value
     * @throws IllegalArgumentException if the parameter is undeclared or its percent encoding is
     *     invalid
     */
    String parameter(String path, String name) {
      Integer segment = parameters.get(Objects.requireNonNull(name));
      if (segment == null) {
        throw new IllegalArgumentException("Unknown path parameter: " + name);
      }

      int start = firstParameterOffset;
      int targetSegment = Math.abs(segment);
      for (int i = firstParameterSegment; i < targetSegment; i++) {
        start = path.indexOf(HttpCharacters.PATH_SEPARATOR, start) + 1;
      }
      int end = segment < 0 ? path.length() : path.indexOf(HttpCharacters.PATH_SEPARATOR, start);
      String value = path.substring(start, end < 0 ? path.length() : end);
      return value.indexOf(HttpCharacters.PERCENT_SIGN) < 0
          ? value
          : URLDecoder.decode(
              value.replace(HttpCharacters.PLUS_SIGN_STRING, HttpCharacters.PERCENT_ENCODED_PLUS),
              StandardCharsets.UTF_8);
    }
  }
}
