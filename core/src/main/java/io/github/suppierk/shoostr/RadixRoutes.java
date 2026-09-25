package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpCharacters;
import io.github.suppierk.shoostr.http.HttpMethods;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
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

/** Frozen compressed path patterns that prefer complete matches through literal branches. */
final class RadixRoutes {
  private static final Pattern PARAMETER_PATTERN =
      Pattern.compile(
          "\\"
              + HttpCharacters.OPEN_CURLY_BRACE_STRING
              + "([A-Za-z_][A-Za-z0-9_-]*)"
              + HttpCharacters.CLOSE_CURLY_BRACE_STRING);
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
   * @param edge compressed literal text, or null for a single nonempty parameter segment
   * @param endpoints immutable endpoints terminating at this node, indexed by method
   * @param children sorted literal branches
   * @param parameter parameter branch, or null if absent
   * @param staticFiles fallback static mounts retained by the root node
   */
  private RadixRoutes(
      @Nullable String edge,
      Map<HttpMethods, Endpoint> endpoints,
      RadixRoutes[] children,
      @Nullable RadixRoutes parameter,
      List<StaticFiles> staticFiles) {
    this.edge = edge;
    this.label = edge == null || edge.isEmpty() ? 0 : edge.charAt(0);
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
    for (var endpoint : registrations) {
      var methods = patterns.computeIfAbsent(endpoint.pattern(), ignored -> new HashMap<>());
      if (methods.putIfAbsent(endpoint.method(), endpoint) != null) {
        throw new IllegalArgumentException("Duplicate route shape: " + endpoint.pattern());
      }
    }
    var paths = patterns.keySet().toArray(String[]::new);
    return build(patterns, paths, 0, paths.length, 0, List.copyOf(staticFiles));
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
   * Finds a complete path-and-method match, preferring literal branches at each depth and falling
   * back to a parameter branch when the literal branch cannot complete the match.
   *
   * @param path raw absolute request path, without query or fragment
   * @param method requested method, or null for an unrecognized verb
   * @return matching endpoint, or null when no registration accepts both path and method
   */
  @Nullable Endpoint match(String path, @Nullable HttpMethods method) {
    return method == null ? null : find(path, method, 0);
  }

  /**
   * Collects methods from every complete path match for a method-not-allowed response.
   *
   * @param path raw absolute request path
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
   * names are removed from the tree key so equivalent shapes share the same branch.
   *
   * @param method registered HTTP method
   * @param path absolute route pattern
   * @param handler handler invoked after a match
   * @return compiled endpoint metadata
   */
  static Endpoint endpoint(HttpMethods method, String path, Handler handler) {
    var parameters = parameters(path);
    var matcher = PARAMETER_PATTERN.matcher(path);
    boolean hasParameter = matcher.find();
    int firstParameterOffset = hasParameter ? matcher.start() : -1;
    int firstParameterSegment = hasParameter ? parameters.get(matcher.group(1)) : 0;
    String pattern = matcher.replaceAll(HttpCharacters.CURLY_BRACES);
    return new Endpoint(
        method,
        pattern,
        path,
        Objects.requireNonNull(handler),
        parameters,
        firstParameterOffset,
        firstParameterSegment,
        null);
  }

  /**
   * Prepares a WebSocket upgrade route with the same path matching rules as HTTP GET.
   *
   * @param path absolute route pattern
   * @param admission handler applying inherited route policies before upgrade
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
   * Validates literal and whole-segment parameter syntax and indexes parameter names by segment.
   * Splitting retains empty segments so repeated and trailing slashes remain significant.
   *
   * @param path absolute pattern without query or fragment
   * @return immutable parameter names and their segment indexes, counting the leading empty segment
   * @throws IllegalArgumentException if the path syntax is invalid or a parameter name is repeated
   */
  static Map<String, Integer> parameters(@Nullable String path) {
    if (path == null
        || path.isEmpty()
        || path.charAt(0) != HttpCharacters.PATH_SEPARATOR
        || path.indexOf(HttpCharacters.QUERY_SEPARATOR) >= 0
        || path.indexOf(HttpCharacters.FRAGMENT_SEPARATOR) >= 0) {
      throw new IllegalArgumentException("Expected an absolute path without query or fragment");
    }

    var parameters = new HashMap<String, Integer>();
    var segments = path.split(HttpCharacters.PATH_SEPARATOR_STRING, -1);
    for (int i = 1; i < segments.length; i++) {
      String segment = segments[i];
      var parameter = PARAMETER_PATTERN.matcher(segment);
      if (parameter.matches()) {
        if (parameters.putIfAbsent(parameter.group(1), i) != null) {
          throw new IllegalArgumentException("Repeated path parameter: " + parameter.group(1));
        }
      } else if (segment.indexOf(HttpCharacters.OPEN_CURLY_BRACE) >= 0
          || segment.indexOf(HttpCharacters.CLOSE_CURLY_BRACE) >= 0
          || segment.indexOf(HttpCharacters.ASTERISK) >= 0
          || segment.indexOf(HttpCharacters.OPEN_ANGLE_BRACKET) >= 0
          || segment.indexOf(HttpCharacters.CLOSE_ANGLE_BRACKET) >= 0) {
        throw new IllegalArgumentException("Expected a literal segment or {name}: " + segment);
      }
    }
    return Map.copyOf(parameters);
  }

  /**
   * Searches the literal branch first, then a parameter branch if no complete path-and-method match
   * was found. A partial literal prefix or method mismatch cannot hide a complete parameter match.
   *
   * @param path raw request path
   * @param method requested method
   * @param offset first unconsumed character
   * @return preferred complete match in this subtree, or null
   */
  private @Nullable Endpoint find(String path, HttpMethods method, int offset) {
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
   * Visits matching literal and parameter branches, allocating the method set only on a complete
   * match. Unknown paths therefore do not allocate an accumulator.
   *
   * @param path raw request path
   * @param offset first unconsumed character
   * @param methods accumulated methods, or null until the first match
   * @return updated accumulator, or null if no endpoint has matched
   */
  private @Nullable Set<HttpMethods> collectMethods(
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
   * Consumes this node's literal edge or one nonempty parameter segment without creating
   * substrings.
   *
   * @param path raw request path
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
   * become one edge; parameter markers form separate single-segment edges. Endpoint maps are copied
   * before the mutable registration structures can be cleared.
   *
   * @param routes normalized patterns mapped to method-specific endpoints
   * @param paths sorted normalized patterns
   * @param from inclusive range start
   * @param to exclusive range end
   * @param offset first character not consumed by an ancestor
   * @param staticFiles fallback static mounts attached only to the root
   * @return frozen subtree covering the supplied range
   */
  private static RadixRoutes build(
      Map<String, Map<HttpMethods, Endpoint>> routes,
      String[] paths,
      int from,
      int to,
      int offset,
      List<StaticFiles> staticFiles) {
    if (from == to) {
      return new RadixRoutes(Routes.EMPTY_PATH, Map.of(), new RadixRoutes[0], null, staticFiles);
    }

    String first = paths[from];
    String last = paths[to - 1];
    boolean parameterEdge =
        offset < first.length() && first.charAt(offset) == HttpCharacters.OPEN_CURLY_BRACE;
    int end =
        parameterEdge
            ? offset + HttpCharacters.CURLY_BRACES.length()
            : prefixEnd(first, last, offset);

    Map<HttpMethods, Endpoint> endpoints = Map.of();
    if (first.length() == end) {
      endpoints = Map.copyOf(routes.get(first));
      from++;
    }

    var children = new ArrayList<RadixRoutes>();
    RadixRoutes parameter = null;
    while (from < to) {
      int next = from + 1;
      char label = paths[from].charAt(end);
      while (next < to && paths[next].charAt(end) == label) {
        next++;
      }
      var child = build(routes, paths, from, next, end, List.of());
      if (label == HttpCharacters.OPEN_CURLY_BRACE) {
        parameter = child;
      } else {
        children.add(child);
      }

      from = next;
    }
    return new RadixRoutes(
        parameterEdge ? null : first.substring(offset, end),
        endpoints,
        children.toArray(RadixRoutes[]::new),
        parameter,
        staticFiles);
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

  /** Precompiled endpoint metadata shared by all requests matching this registration. */
  record Endpoint(
      HttpMethods method,
      String pattern,
      String routePattern,
      Handler handler,
      Map<String, Integer> parameters,
      int firstParameterOffset,
      int firstParameterSegment,
      @Nullable BiFunction<Request, ServerUpgradeResponse, Session.Listener> websocketFactory) {
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
     * Extracts one parameter lazily from an already matched raw path and decodes UTF-8 escapes
     * once. Literal plus signs are preserved because path segments do not use form-encoding
     * semantics.
     *
     * @param path raw path that matched this endpoint
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
      for (int i = firstParameterSegment; i < segment; i++) {
        start = path.indexOf(HttpCharacters.PATH_SEPARATOR, start) + 1;
      }
      int end = path.indexOf(HttpCharacters.PATH_SEPARATOR, start);
      String value = path.substring(start, end < 0 ? path.length() : end);
      return value.indexOf(HttpCharacters.PERCENT_SIGN) < 0
          ? value
          : URLDecoder.decode(
              value.replace(HttpCharacters.PLUS_SIGN_STRING, HttpCharacters.PERCENT_ENCODED_PLUS),
              StandardCharsets.UTF_8);
    }
  }
}
