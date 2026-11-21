package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpCharacters;
import io.github.suppierk.shoostr.http.HttpMethods;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Frozen compressed path patterns with literal, constrained, parameter and catch-all branches.
 * Equality compares the concrete node kind and stored route data, including child arrays by value.
 * Callback and resource components retain their own equality contracts.
 */
sealed class RadixRoutes {
  private static final String CATCH_ALL_EDGE = "{*}";
  private static final String CONSTRAINT_PREFIX = "{:";
  private static final char CONSTRAINT_LABEL = 1;

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
   * @throws NullPointerException if a required collection or child array is null
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
    this.endpoints = Objects.requireNonNull(endpoints);
    this.children = Objects.requireNonNull(children);
    this.parameter = parameter;
    this.staticFiles = Objects.requireNonNull(staticFiles);
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
    final var patterns = new TreeMap<String, Map<HttpMethods, Endpoint>>();
    final var registrationOrder = new IdentityHashMap<Endpoint, Integer>();
    var ordinal = 0;
    for (final var endpoint : registrations) {
      registrationOrder.put(endpoint, ordinal++);
      final var methods = patterns.computeIfAbsent(endpoint.pattern(), ignored -> new HashMap<>());
      if (methods.putIfAbsent(endpoint.method(), endpoint) != null) {
        throw new IllegalArgumentException("Duplicate route shape: " + endpoint.pattern());
      }
    }
    final var paths = patterns.keySet().toArray(String[]::new);

    return build(patterns, registrationOrder, paths, 0, paths.length, 0, List.copyOf(staticFiles));
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

    final var subtreeFrom = from;
    final var first = paths[from];
    final var last = paths[to - 1];
    final var branch = branchLabel(first, offset);
    final var end = edgeEnd(first, last, offset, branch);

    var endpoints = Map.<HttpMethods, Endpoint>of();
    if (first.length() == end) {
      endpoints = Map.copyOf(routes.get(first));
      from++;
    }

    if (branch == 0) {
      return new CatchAllRoutes(endpoints);
    }

    final var children = new ArrayList<RadixRoutes>();
    RadixRoutes parameter = null;
    while (from < to) {
      final var label = branchLabel(paths[from], end);
      final var next = nextBranchEnd(paths, from, to, end, label);
      final var child = build(routes, registrationOrder, paths, from, next, end, List.of());
      if (label == HttpCharacters.OPEN_CURLY_BRACE) {
        parameter = child;
      } else {
        children.add(child);
      }

      from = next;
    }
    children.sort(Comparator.comparingInt(node -> node.label));
    final var frozenChildren = children.toArray(RadixRoutes[]::new);
    if (branch == CONSTRAINT_LABEL) {
      return new ConstrainedRoutes(
          Constraint.compile(first.substring(offset + CONSTRAINT_PREFIX.length(), end - 1)),
          ranks(routes, registrationOrder, paths, subtreeFrom, to),
          endpoints,
          frozenChildren,
          parameter,
          staticFiles);
    }

    final var edge =
        branch == HttpCharacters.OPEN_CURLY_BRACE ? null : first.substring(offset, end);
    final var label = edge == null || edge.isEmpty() ? 0 : edge.charAt(0);

    return new RadixRoutes(edge, label, endpoints, frozenChildren, parameter, staticFiles);
  }

  /**
   * Chooses the boundary of one normalized edge.
   *
   * @param first first path in the subtree
   * @param last last path in the subtree
   * @param offset already consumed prefix length
   * @param branch reserved or literal branch label
   * @return edge boundary
   */
  private static int edgeEnd(String first, String last, int offset, char branch) {
    return switch (branch) {
      case 0 -> offset + CATCH_ALL_EDGE.length();
      case CONSTRAINT_LABEL -> segmentEnd(first, offset);
      case HttpCharacters.OPEN_CURLY_BRACE -> offset + HttpCharacters.CURLY_BRACES.length();
      default -> prefixEnd(first, last, offset);
    };
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
    var next = from + 1;
    if (label == CONSTRAINT_LABEL) {
      final var branchEnd = segmentEnd(paths[from], offset);
      final var token = paths[from].substring(offset, branchEnd);
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
    var end = offset;
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
    final var ranks = new IdentityHashMap<Endpoint, Integer>();
    for (var index = from; index < to; index++) {
      for (final var endpoint : Objects.requireNonNull(routes.get(paths[index])).values()) {
        ranks.put(endpoint, registrationOrder.get(endpoint));
      }
    }
    return ranks;
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
    final var syntax = RouteSyntax.parse(path);

    return assembleEndpoint(method, path, syntax, handler, null);
  }

  /**
   * Prepares a WebSocket upgrade route with the same path matching rules as HTTP GET.
   *
   * @param path absolute route pattern
   * @param admission handshake handler; compiled behavior supplies admission before upgrade
   * @param factory creates one listener from the handshake request and response
   * @return compiled WebSocket endpoint metadata
   */
  static Endpoint websocketEndpoint(String path, Handler admission, WebSocket.Factory factory) {
    final var syntax = RouteSyntax.parse(path);
    Objects.requireNonNull(admission);

    return assembleEndpoint(
        HttpMethods.GET, path, syntax, admission, Objects.requireNonNull(factory));
  }

  /**
   * Assembles an endpoint from validated syntax while retaining callback-before-method validation.
   *
   * @param method registered HTTP method
   * @param path original route pattern
   * @param syntax validated route syntax and captures
   * @param handler application callback
   * @param factory optional per-upgrade listener factory
   * @return endpoint containing the supplied registration facts
   */
  private static Endpoint assembleEndpoint(
      HttpMethods method,
      String path,
      RouteSyntax syntax,
      Handler handler,
      WebSocket.@Nullable Factory factory) {
    return new Endpoint(
        method,
        syntax.pattern(),
        path,
        Objects.requireNonNull(handler),
        syntax.parameters(),
        syntax.firstParameterOffset(),
        syntax.firstParameterSegment(),
        factory);
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
    return RouteSyntax.parse(path).parameters();
  }

  /**
   * Compares the node kind and frozen route state without executing handlers.
   *
   * <p>EqualsGetClass is suppressed because node subclasses implement different matching behavior
   * and must remain unequal even when their shared fields match.
   *
   * @param other candidate node
   * @return whether both nodes have the same kind and stored state
   */
  @Override
  @SuppressWarnings("EqualsGetClass")
  public boolean equals(@Nullable Object other) {
    if (other == null || getClass() != other.getClass()) {
      return false;
    }

    final var node = (RadixRoutes) other;

    return label == node.label
        && Objects.equals(edge, node.edge)
        && endpoints.equals(node.endpoints)
        && Arrays.equals(children, node.children)
        && Objects.equals(parameter, node.parameter)
        && staticFiles.equals(node.staticFiles);
  }

  /**
   * Hashes the same node state used by equality, including child array contents.
   *
   * @return hash of the stored node state
   */
  @Override
  public int hashCode() {
    return Objects.hash(edge, label, endpoints, Arrays.hashCode(children), parameter, staticFiles);
  }

  /** Releases every static resource source, attempting all closures before reporting failures. */
  void close() {
    RuntimeException failure = null;
    for (final var files : staticFiles) {
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
    for (final var mount : staticFiles) {
      final var endpoint = mount.endpoint(path, method);
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
    for (final var mount : staticFiles) {
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
   * Searches literal, constrained, plain parameter, then catch-all branches. A partial literal
   * prefix or method mismatch cannot hide a less-specific complete match.
   *
   * @param path Jetty-canonical encoded request path
   * @param method requested method
   * @param offset first unconsumed character
   * @return preferred complete match in this subtree, or null
   */
  @Nullable Endpoint find(String path, HttpMethods method, int offset) {
    final var end = consume(path, offset);

    return end < 0 ? null : findAfterEdge(path, method, end);
  }

  /**
   * Continues matching after this node's edge has been consumed.
   *
   * @param path encoded request path
   * @param method requested method
   * @param end offset after the consumed edge
   * @return matching endpoint or null
   */
  private @Nullable Endpoint findAfterEdge(String path, HttpMethods method, int end) {
    if (end == path.length()) {
      return endpoints.get(method);
    }

    final var literal = child(path.charAt(end));
    if (literal != null) {
      final var match = literal.find(path, method, end);
      if (match != null) {
        return match;
      }
    }

    final var constrainedMatch = findConstrainedChildren(path, method, end);
    if (constrainedMatch != null) {
      return constrainedMatch;
    }

    if (parameter != null) {
      final var match = parameter.find(path, method, end);
      if (match != null) {
        return match;
      }
    }

    return children.length > 0 && children[0].label == 0
        ? ((CatchAllRoutes) children[0]).findCatchAll(path, method, end)
        : null;
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
    var bestRank = Integer.MAX_VALUE;
    for (var index = firstConstraint();
        index < children.length && children[index].label == CONSTRAINT_LABEL;
        index++) {
      final var child = (ConstrainedRoutes) children[index];
      final var match = child.findConstrainedSegment(path, method, offset);
      if (match != null) {
        final var rank = child.rank(match);
        if (rank < bestRank) {
          best = match;
          bestRank = rank;
        }
      }
    }
    return best;
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
    final var end = consume(path, offset);

    return end < 0 ? methods : collectAfterEdge(path, end, methods);
  }

  /**
   * Collects complete-path methods after this node's edge has been consumed.
   *
   * @param path encoded request path
   * @param end offset after the consumed edge
   * @param methods accumulated methods or null
   * @return updated accumulator or null
   */
  private @Nullable Set<HttpMethods> collectAfterEdge(
      String path, int end, @Nullable Set<HttpMethods> methods) {
    if (end == path.length()) {
      return collectEndpointMethods(methods);
    }

    final var literal = child(path.charAt(end));
    if (literal != null) {
      methods = literal.collectMethods(path, end, methods);
    }

    for (var index = firstConstraint();
        index < children.length && children[index].label == CONSTRAINT_LABEL;
        index++) {
      methods = ((ConstrainedRoutes) children[index]).collectConstrained(path, end, methods);
    }

    if (parameter != null) {
      methods = parameter.collectMethods(path, end, methods);
    }

    return children.length > 0 && children[0].label == 0
        ? ((CatchAllRoutes) children[0]).collectCatchAll(path, end, methods)
        : methods;
  }

  /**
   * Adds this node's endpoint methods, allocating an accumulator only when endpoints exist.
   *
   * @param methods accumulated methods, or null until the first match
   * @return updated accumulator, or the original value when this node has no endpoints
   */
  private @Nullable Set<HttpMethods> collectEndpointMethods(@Nullable Set<HttpMethods> methods) {
    if (!endpoints.isEmpty()) {
      if (methods == null) {
        methods = EnumSet.noneOf(HttpMethods.class);
      }

      methods.addAll(endpoints.keySet());
    }

    return methods;
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

    // Parents descend into parameter nodes only while input remains.
    if (path.charAt(offset) == HttpCharacters.PATH_SEPARATOR) {
      return -1;
    }

    return segmentEnd(path, offset);
  }

  /**
   * Finds the next path separator without allocating a segment substring.
   *
   * @param path normalized route pattern or encoded request path
   * @param offset segment start
   * @return next separator offset, or path length when no separator remains
   */
  private static int segmentEnd(String path, int offset) {
    final var end = path.indexOf(HttpCharacters.PATH_SEPARATOR, offset);

    return end < 0 ? path.length() : end;
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
   * Finds a literal child by its first character, using binary search for multiple branches.
   *
   * @param next next request-path character
   * @return matching literal child, or null
   */
  private @Nullable RadixRoutes child(char next) {
    if (children.length == 1) {
      final var candidate = children[0];

      return candidate.label == next ? candidate : null;
    }

    var low = 0;
    var high = children.length - 1;
    while (low <= high) {
      final var middle = (low + high) >>> 1;
      final var candidate = children[middle];
      final var comparison = Character.compare(next, candidate.label);
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

  /** A terminal named-tail branch without suffix children. */
  private static final class CatchAllRoutes extends RadixRoutes {
    /**
     * Creates a frozen catch-all branch.
     *
     * @param endpoints terminal route methods
     */
    private CatchAllRoutes(Map<HttpMethods, Endpoint> endpoints) {
      super(null, (char) 0, endpoints, new RadixRoutes[0], null, List.of());
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

    /**
     * Matches a nonempty tail and then resolves the requested method.
     *
     * @param path encoded request path
     * @param method requested method
     * @param offset tail start
     * @return matched endpoint or null
     */
    private @Nullable Endpoint findCatchAll(String path, HttpMethods method, int offset) {
      return validTail(path, offset) ? ((RadixRoutes) this).endpoints.get(method) : null;
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
          ? ((RadixRoutes) this).collectEndpointMethods(methods)
          : methods;
    }
  }

  /** Segment-local character-class matcher kept off the ordinary node's consume hot path. */
  private static final class ConstrainedRoutes extends RadixRoutes {
    private final Constraint constraint;
    private final Map<Endpoint, Integer> ranks;

    /**
     * Creates a frozen constrained branch.
     *
     * @param constraint compiled constraint
     * @param ranks endpoint registration positions in this subtree
     * @param endpoints terminal route methods
     * @param children sorted child branches
     * @param parameter plain parameter branch or null
     * @param staticFiles static mounts
     */
    private ConstrainedRoutes(
        Constraint constraint,
        Map<Endpoint, Integer> ranks,
        Map<HttpMethods, Endpoint> endpoints,
        RadixRoutes[] children,
        @Nullable RadixRoutes parameter,
        List<StaticFiles> staticFiles) {
      super(null, CONSTRAINT_LABEL, endpoints, children, parameter, staticFiles);
      this.constraint = Objects.requireNonNull(constraint);
      this.ranks = Map.copyOf(ranks);
    }

    /**
     * Compares the node state, compiled character constraint and endpoint ranks.
     *
     * @param other candidate node
     * @return whether the complete constrained node state matches
     */
    @Override
    public boolean equals(@Nullable Object other) {
      return other instanceof ConstrainedRoutes node
          && super.equals(node)
          && constraint.equals(node.constraint)
          && ranks.equals(node.ranks);
    }

    /**
     * Hashes the node state, compiled character constraint and endpoint ranks.
     *
     * @return hash of the complete constrained node state
     */
    @Override
    public int hashCode() {
      return 31 * (31 * super.hashCode() + constraint.hashCode()) + ranks.hashCode();
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
      final var end = segmentEnd(path, offset);

      return constraint.matches(path, offset, end)
          ? ((RadixRoutes) this).findAfterEdge(path, method, end)
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
      final var end = segmentEnd(path, offset);

      return constraint.matches(path, offset, end)
          ? ((RadixRoutes) this).collectAfterEdge(path, end, methods)
          : methods;
    }

    /**
     * Returns the original registration position of a matched endpoint.
     *
     * @param endpoint matched route endpoint
     * @return original registration position
     */
    private int rank(Endpoint endpoint) {
      return Objects.requireNonNull(ranks.get(endpoint));
    }
  }

  /** Registration-time normalized key and capture metadata. */
  private record RouteSyntax(
      String pattern,
      Map<String, Integer> parameters,
      int firstParameterOffset,
      int firstParameterSegment) {
    private static final Pattern PARAMETER_PATTERN =
        Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_-]*)}");
    private static final Pattern CATCH_ALL_PATTERN =
        Pattern.compile("\\{\\*([A-Za-z_][A-Za-z0-9_-]*)}");

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

      final var parameters = new HashMap<String, Integer>();
      final var pattern = new StringBuilder(path.length());
      pattern.append(HttpCharacters.PATH_SEPARATOR);
      final var segments = path.split(HttpCharacters.PATH_SEPARATOR_STRING, -1);
      var firstParameterSegment = 0;
      for (var index = 1; index < segments.length; index++) {
        if (index > 1) {
          pattern.append(HttpCharacters.PATH_SEPARATOR);
        }

        String segment = segments[index];
        appendSyntaxSegment(segment, index, segments.length, parameters, pattern);

        if (firstParameterSegment == 0 && !parameters.isEmpty()) {
          firstParameterSegment = index;
        }
      }
      return new RouteSyntax(
          parameters.isEmpty() ? path : pattern.toString(),
          parameters,
          path.indexOf(HttpCharacters.OPEN_CURLY_BRACE),
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
      final var parameter = PARAMETER_PATTERN.matcher(segment);
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

      final var name = segment.substring(2, segment.length() - 1);
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
      final var separator = segment.indexOf(HttpCharacters.COLON_SIGN);
      final var name = segment.substring(1, separator);
      if (!PARAMETER_PATTERN
          .matcher(
              HttpCharacters.OPEN_CURLY_BRACE_STRING
                  + name
                  + HttpCharacters.CLOSE_CURLY_BRACE_STRING)
          .matches()) {
        throw new IllegalArgumentException("Invalid constrained parameter name: " + name);
      }

      final var constraint = segment.substring(separator + 1, segment.length() - 1);
      Constraint.compile(constraint);
      addParameter(parameters, name, index);
      pattern.append(CONSTRAINT_PREFIX).append(constraint).append(HttpCharacters.CLOSE_CURLY_BRACE);
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
      return segment.startsWith(HttpCharacters.OPEN_CURLY_BRACE_STRING)
          && segment.endsWith(HttpCharacters.CLOSE_CURLY_BRACE_STRING)
          && segment.contains(HttpCharacters.COLON_SIGN_STRING);
    }

    /**
     * Rejects literal segments containing reserved syntax or ASCII control characters.
     *
     * @param segment route segment
     * @return whether the segment cannot be a literal
     */
    private static boolean invalidLiteralSegment(String segment) {
      for (var index = 0; index < segment.length(); index++) {
        if (invalidLiteralCharacter(segment.charAt(index))) {
          return true;
        }
      }
      return false;
    }

    /**
     * Identifies reserved syntax and ASCII control characters in literal segments.
     *
     * @param character route character
     * @return whether the character is invalid in a literal segment
     */
    private static boolean invalidLiteralCharacter(char character) {
      return switch (character) {
        case HttpCharacters.OPEN_CURLY_BRACE,
            HttpCharacters.CLOSE_CURLY_BRACE,
            HttpCharacters.ASTERISK,
            HttpCharacters.OPEN_ANGLE_BRACKET,
            HttpCharacters.CLOSE_ANGLE_BRACKET,
            HttpCharacters.QUERY_SEPARATOR,
            HttpCharacters.FRAGMENT_SEPARATOR ->
            true;
        default -> character < 32 || character == 127;
      };
    }
  }

  /** Immutable ASCII character-class matcher. */
  private record Constraint(long lowerBits, long upperBits, int minimum, int maximum) {
    private static final Pattern BOUNDED_REPETITION_PATTERN =
        Pattern.compile("\\{(\\d{1,5})(?:,(\\d{1,5}))?}");

    /**
     * Requires nonempty character membership and valid repetition bounds.
     *
     * @param lowerBits ASCII membership bits for characters below 64
     * @param upperBits ASCII membership bits for characters from 64 to 127
     * @param minimum minimum repetition count
     * @param maximum maximum repetition count
     * @throws IllegalArgumentException if the constraint is invalid
     */
    Constraint {
      if ((lowerBits | upperBits) == 0 || minimum < 0 || maximum < 1 || minimum > maximum) {
        throw new IllegalArgumentException("Invalid route constraint");
      }
    }

    /**
     * Validates a safe character-class constraint and compiles its ASCII lookup bits.
     *
     * @param constraint constraint expression
     * @return compiled constraint
     * @throws IllegalArgumentException if the expression is unsupported
     */
    private static Constraint compile(String constraint) {
      if (constraint.length() < 3 || constraint.charAt(0) != HttpCharacters.OPEN_SQUARE_BRACKET) {
        throw new IllegalArgumentException("Expected one character class in route constraint");
      }

      final var close = constraint.indexOf(HttpCharacters.CLOSE_SQUARE_BRACKET);
      if (close < 2) {
        throw new IllegalArgumentException(
            "Expected a nonempty character class in route constraint");
      }

      final var bits = classBits(constraint, close);
      final var bounds = repetitionBounds(constraint.substring(close + 1));

      return new Constraint(bits[0], bits[1], bounds[0], bounds[1]);
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
      final var bits = new long[2];
      var rangeStartAvailable = false;
      var index = 1;
      while (index < close) {
        final var character = constraint.charAt(index);
        if (!allowedClassCharacter(character)) {
          throw new IllegalArgumentException(
              "Unsupported route constraint character: " + character);
        }

        if (character == HttpCharacters.DASH
            && index > 1
            && constraint.charAt(index - 1) == HttpCharacters.DASH) {
          throw new IllegalArgumentException("Unsupported consecutive hyphens in route constraint");
        }

        if (character == HttpCharacters.DASH && rangeStartAvailable && index < close - 1) {
          includeRange(bits, constraint.charAt(index - 1), constraint.charAt(index + 1));
          index += 2;
          rangeStartAvailable = false;
        } else {
          includeCharacter(bits, character);
          index++;
          rangeStartAvailable = character != HttpCharacters.DASH;
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

      for (var value = first; value <= last; value++) {
        includeCharacter(bits, value);
      }
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
          || character == HttpCharacters.UNDERSCORE
          || character == HttpCharacters.DASH;
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
      return switch (repetition) {
        case "" -> new int[] {1, 1};
        case "+" -> new int[] {1, Integer.MAX_VALUE};
        case "*" -> new int[] {0, Integer.MAX_VALUE};
        case "?" -> new int[] {0, 1};
        default -> boundedRepetitionBounds(repetition);
      };
    }

    /**
     * Parses exact or ranged repetition after validating its complete syntax.
     *
     * @param repetition bounded repetition suffix
     * @return inclusive minimum and maximum repetition counts
     * @throws IllegalArgumentException if the bounded repetition is unsupported
     */
    private static int[] boundedRepetitionBounds(String repetition) {
      final var bounds = BOUNDED_REPETITION_PATTERN.matcher(repetition);
      if (bounds.matches()) {
        final var lower = Integer.parseInt(bounds.group(1));
        final var maximum = bounds.group(2);
        final var upper = maximum == null ? lower : Integer.parseInt(maximum);
        if (upper >= 1 && lower <= upper) {
          return new int[] {lower, upper};
        }
      }

      throw new IllegalArgumentException("Unsupported route constraint repetition: " + repetition);
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
      final var length = end - start;
      if (length == 0 || length < minimum || length > maximum) {
        return false;
      }

      for (var index = start; index < end; index++) {
        final var value = path.charAt(index);
        if (value >= 128
            || (value < 64
                ? (lowerBits & (1L << value)) == 0
                : (upperBits & (1L << (value - 64))) == 0)) {
          return false;
        }
      }
      return true;
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
      WebSocket.@Nullable Factory websocketFactory,
      @Nullable EndpointBehavior behavior,
      @Nullable ExecutorService executor) {
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
        WebSocket.@Nullable Factory websocketFactory) {
      this(
          method,
          pattern,
          routePattern,
          handler,
          parameters,
          firstParameterOffset,
          firstParameterSegment,
          websocketFactory,
          null,
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
     * @param executor borrowed matched-lifecycle executor, or null for default execution
     * @throws IllegalArgumentException if the first parameter position is invalid
     */
    Endpoint {
      Objects.requireNonNull(method);
      Objects.requireNonNull(pattern);
      Objects.requireNonNull(routePattern);
      Objects.requireNonNull(handler);
      if (executor != null && executor.isShutdown()) {
        throw new IllegalArgumentException("Endpoint executor must be usable");
      }

      if (executor instanceof ThreadPoolExecutor pool) {
        final var rejection = pool.getRejectedExecutionHandler().getClass();
        if (rejection == ThreadPoolExecutor.CallerRunsPolicy.class
            || rejection == ThreadPoolExecutor.DiscardPolicy.class
            || rejection == ThreadPoolExecutor.DiscardOldestPolicy.class) {
          throw new IllegalArgumentException(
              "Endpoint executor must reject without fallback or discard");
        }
      }

      parameters = Map.copyOf(parameters);
      final var literal = parameters.isEmpty();
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
      return behavior == null ? this : copy(behavior, executor);
    }

    /**
     * Selects a borrowed executor while preserving the endpoint's route and behavior.
     *
     * @param executor selected executor, or null for default execution
     * @return this endpoint when no executor is selected, otherwise its enriched copy
     */
    Endpoint withExecutor(@Nullable ExecutorService executor) {
      return executor == null ? this : copy(behavior, executor);
    }

    /**
     * Copies route metadata with selected runtime settings and revalidates the resulting endpoint.
     *
     * @param behavior selected local behavior, or null
     * @param executor borrowed executor, or null for default execution
     * @return validated endpoint copy
     */
    private Endpoint copy(@Nullable EndpointBehavior behavior, @Nullable ExecutorService executor) {
      return new Endpoint(
          method,
          pattern,
          routePattern,
          handler,
          parameters,
          firstParameterOffset,
          firstParameterSegment,
          websocketFactory,
          behavior,
          executor);
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
      final var segment = parameters.get(Objects.requireNonNull(name));
      if (segment == null) {
        throw new IllegalArgumentException("Unknown path parameter: " + name);
      }

      var start = firstParameterOffset;
      final var targetSegment = Math.abs(segment);
      for (var index = firstParameterSegment; index < targetSegment; index++) {
        start = path.indexOf(HttpCharacters.PATH_SEPARATOR, start) + 1;
      }
      final var end =
          segment < 0 ? path.length() : path.indexOf(HttpCharacters.PATH_SEPARATOR, start);
      final var value = path.substring(start, end < 0 ? path.length() : end);

      return value.indexOf(HttpCharacters.PERCENT_SIGN) < 0
          ? value
          : URLDecoder.decode(
              value.replace(HttpCharacters.PLUS_SIGN_STRING, HttpCharacters.PERCENT_ENCODED_PLUS),
              StandardCharsets.UTF_8);
    }
  }
}
