package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.ForwardedHeaders;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.jspecify.annotations.Nullable;

/** Applies explicit proxy trust without wrapping the underlying transport request. */
final class TrustedProxy {
  private static final int MAX_FORWARDING_ELEMENTS = 64;
  private static final String X_FORWARDED_FOR = "X-Forwarded-For";
  private static final String X_FORWARDED_HOST = "X-Forwarded-Host";
  private static final String X_FORWARDED_PORT = "X-Forwarded-Port";
  private static final String X_FORWARDED_PROTO = "X-Forwarded-Proto";
  private static final String PROTO_FIELD = "proto";
  private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
  private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");
  private static final Pattern OBFUSCATED = Pattern.compile("_[A-Za-z0-9._-]+");
  private static final Pattern DIGITS = Pattern.compile("\\d+");
  private static final QuotedStringTokenizer PARAMETERS =
      QuotedStringTokenizer.builder()
          .delimiters(";")
          .returnDelimiters()
          .returnQuotes()
          .allowEmbeddedQuotes()
          .ignoreOptionalWhiteSpace()
          .build();
  private static final QuotedStringTokenizer ELEMENTS =
      QuotedStringTokenizer.builder()
          .delimiters(",")
          .returnDelimiters()
          .returnQuotes()
          .allowEmbeddedQuotes()
          .ignoreOptionalWhiteSpace()
          .build();
  private final Predicate<InetAddress> trusted;
  private final ForwardedHeaders headers;

  /**
   * Stores immutable startup configuration.
   *
   * @param trusted application-owned thread-safe peer predicate
   * @param headers selected forwarding header family
   */
  TrustedProxy(Predicate<InetAddress> trusted, ForwardedHeaders headers) {
    this.trusted = trusted;
    this.headers = headers;
  }

  /**
   * Validates the physical peer before inspecting forwarding input.
   *
   * @param request live request on the handler thread
   * @throws BadRequestException if trusted forwarding metadata is malformed
   */
  void apply(Request request) {
    if (!(request.remoteAddress() instanceof InetSocketAddress peer)
        || !trusted.test(peer.getAddress())) {
      return;
    }

    if (headers != ForwardedHeaders.RFC7239) {
      applyLegacy(request);
      return;
    }

    applyRfc(request);
  }

  /**
   * Applies the RFC 7239 chain from a trusted physical peer.
   *
   * @param request live request on the handler thread
   * @throws BadRequestException if forwarding metadata is malformed
   */
  private void applyRfc(Request request) {
    var forwarding = request.headers(HttpHeaders.FORWARDED.value());
    if (forwarding.isEmpty()) {
      return;
    }

    List<Map<String, String>> chain = new ArrayList<>();
    List<InetSocketAddress> addresses = new ArrayList<>();

    try {
      parseForwarding(forwarding, chain, addresses);
    } catch (IllegalArgumentException _) {
      throw new BadRequestException();
    }

    int selected = chain.size() - 1;
    while (selected > 0
        && addresses.get(selected) != null
        && trusted.test(addresses.get(selected).getAddress())) {
      selected--;
    }
    var fields = chain.get(selected);

    try {
      var uri = HttpURI.build(request.fullUrl());
      if (fields.containsKey(PROTO_FIELD)) {
        uri.scheme(fields.get(PROTO_FIELD).toLowerCase(Locale.ROOT));
      }

      if (fields.containsKey("host")) {
        var host = host(fields.get("host"));
        uri.authority(host.getHost(), host.getPort());
      }

      request.forwarded(addresses.get(selected), uri.asString());
    } catch (IllegalArgumentException _) {
      throw new BadRequestException();
    }
  }

  /**
   * Parses each physical header line into matching field and address entries.
   *
   * @param forwarding raw Forwarded header lines
   * @param chain destination for parameter maps
   * @param addresses destination for node addresses
   * @throws IllegalArgumentException if a forwarding element or address is malformed
   */
  private static void parseForwarding(
      List<String> forwarding, List<Map<String, String>> chain, List<InetSocketAddress> addresses) {
    for (var header : forwarding) {
      var elements = ELEMENTS.tokenize(header);
      boolean elementExpected = true;
      while (elements.hasNext()) {
        var element = elements.next();
        if (",".equals(element)) {
          if (elementExpected) {
            throw new IllegalArgumentException("Empty forwarding element");
          }

          elementExpected = true;
          continue;
        }

        if (!elementExpected) {
          throw new IllegalArgumentException("Invalid forwarding element");
        }

        var fields = parameters(element);
        if (chain.size() == MAX_FORWARDING_ELEMENTS) {
          throw new IllegalArgumentException("Too many forwarding elements");
        }

        chain.add(fields);
        addresses.add(node(fields.get("for")));
        elementExpected = false;
      }

      if (elementExpected) {
        throw new IllegalArgumentException("Empty forwarding element");
      }
    }
  }

  /**
   * Applies strict same-index legacy forwarding assertions from a trusted peer.
   *
   * @param request live request on the handler thread
   * @throws BadRequestException if trusted forwarding metadata is malformed
   */
  private void applyLegacy(Request request) {
    var forwarded = values(request.headers(X_FORWARDED_FOR));
    if (forwarded.isEmpty()) {
      return;
    }

    var hosts = aligned(request.headers(X_FORWARDED_HOST), forwarded.size());
    var protocols = aligned(request.headers(X_FORWARDED_PROTO), forwarded.size());
    var ports = aligned(request.headers(X_FORWARDED_PORT), forwarded.size());
    var addresses = new ArrayList<InetSocketAddress>();

    try {
      for (var value : forwarded) {
        addresses.add(legacyNode(value));
      }

      validateLegacyOrigins(hosts, protocols, ports);
    } catch (IllegalArgumentException _) {
      throw new BadRequestException();
    }

    int selected = addresses.size() - 1;
    while (selected > 0
        && addresses.get(selected) != null
        && trusted.test(addresses.get(selected).getAddress())) {
      selected--;
    }

    try {
      var uri = HttpURI.build(request.fullUrl());
      if (!protocols.isEmpty()) {
        uri.scheme(protocols.get(selected).toLowerCase(Locale.ROOT));
      }

      if (!hosts.isEmpty()) {
        var parsed =
            host(authority(hosts.get(selected), ports.isEmpty() ? null : ports.get(selected)));
        uri.authority(parsed.getHost(), parsed.getPort());
      }

      request.forwarded(addresses.get(selected), uri.asString());
    } catch (IllegalArgumentException _) {
      throw new BadRequestException();
    }
  }

  /**
   * Validates every supplied legacy origin element before selecting a trusted boundary.
   *
   * @param hosts aligned host values, or an empty list
   * @param protocols aligned scheme values, or an empty list
   * @param ports aligned port values, or an empty list
   * @throws IllegalArgumentException if any entry is malformed or inconsistent
   */
  private static void validateLegacyOrigins(
      List<String> hosts, List<String> protocols, List<String> ports) {
    if (!ports.isEmpty() && hosts.isEmpty()) {
      throw new IllegalArgumentException("Forwarded port requires forwarded host");
    }

    for (var protocol : protocols) {
      if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
        throw new IllegalArgumentException("Unsupported forwarding scheme");
      }
    }

    for (int index = 0; index < hosts.size(); index++) {
      host(authority(hosts.get(index), ports.isEmpty() ? null : ports.get(index)));
    }
  }

  /**
   * Parses a legacy client identity, including an unbracketed IPv6 literal without a port.
   *
   * @param value legacy client identity
   * @return literal client address, or null for an undisclosed identity
   * @throws IllegalArgumentException if the value is malformed
   */
  private static @Nullable InetSocketAddress legacyNode(String value) {
    if (value.indexOf(':') != value.lastIndexOf(':') && !value.startsWith("[")) {
      if (value.indexOf('%') >= 0) {
        throw new IllegalArgumentException("Invalid forwarding address");
      }

      return new InetSocketAddress(InetAddress.ofLiteral(value), 0);
    }

    return node(value);
  }

  /**
   * Combines an aligned legacy host and port while rejecting contradictory explicit ports.
   *
   * @param host forwarded authority
   * @param forwardedPort aligned port, or null when absent
   * @return validated authority with its selected port
   * @throws IllegalArgumentException if the host or port is malformed or conflicts
   */
  private static String authority(String host, @Nullable String forwardedPort) {
    var parsed = host(host);
    if (forwardedPort == null) {
      return host;
    }

    int port = port(forwardedPort);
    if (parsed.hasPort()) {
      if (parsed.getPort() != port) {
        throw new IllegalArgumentException("Conflicting forwarded ports");
      }

      return host;
    }

    return host + ':' + port;
  }

  /**
   * Splits repeated legacy field lines into a nonempty ordered list.
   *
   * @param lines repeated header lines
   * @return ordered trimmed values
   * @throws BadRequestException if a value is empty or exceeds the work bound
   */
  private static List<String> values(List<String> lines) {
    var result = new ArrayList<String>();
    for (var line : lines) {
      for (var value : line.split(",", -1)) {
        var trimmed = value.trim();
        if (trimmed.isEmpty() || result.size() == MAX_FORWARDING_ELEMENTS) {
          throw new BadRequestException();
        }

        result.add(trimmed);
      }
    }
    return result;
  }

  /**
   * Returns an absent legacy list or rejects one that cannot align with client identities.
   *
   * @param lines repeated header lines
   * @param expected required list length
   * @return an empty or exactly aligned list
   * @throws BadRequestException if supplied values do not align
   */
  private static List<String> aligned(List<String> lines, int expected) {
    var values = values(lines);
    if (!values.isEmpty() && values.size() != expected) {
      throw new BadRequestException();
    }

    return values;
  }

  /**
   * Reads one element using Jetty's quote-aware lexical handling.
   *
   * @param value complete element
   * @return parameter values
   * @throws IllegalArgumentException if a parameter, delimiter, origin, or node is malformed
   */
  private static Map<String, String> parameters(String value) {
    Map<String, String> fields = new HashMap<>();
    var tokens = PARAMETERS.tokenize(value);
    while (tokens.hasNext()) {
      var token = tokens.next();
      int equals = token.indexOf('=');
      if (equals < 1) {
        throw new IllegalArgumentException("Invalid forwarding parameter");
      }

      var name = token.substring(0, equals).toLowerCase(Locale.ROOT);
      var raw = token.substring(equals + 1);
      if (!TOKEN.matcher(name).matches()
          || !(TOKEN.matcher(raw).matches() || validQuoted(raw))
          || fields.putIfAbsent(name, PARAMETERS.unquote(raw)) != null) {
        throw new IllegalArgumentException("Invalid forwarding parameter");
      }

      if (tokens.hasNext() && (!";".equals(tokens.next()) || !tokens.hasNext())) {
        throw new IllegalArgumentException("Invalid forwarding delimiter");
      }
    }
    if (fields.containsKey(PROTO_FIELD)
        && !"http".equalsIgnoreCase(fields.get(PROTO_FIELD))
        && !"https".equalsIgnoreCase(fields.get(PROTO_FIELD))) {
      throw new IllegalArgumentException("Unsupported forwarding scheme");
    }

    if (fields.containsKey("host")) {
      host(fields.get("host"));
    }

    if (fields.containsKey("by")) {
      node(fields.get("by"));
    }

    return fields;
  }

  /**
   * Validates a quoted forwarding value in one pass, including its permitted escape sequences.
   *
   * @param value raw parameter value
   * @return whether the value follows the HTTP quoted-string grammar
   */
  private static boolean validQuoted(String value) {
    if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
      return false;
    }

    int end = value.length() - 1;
    int index = 1;
    while (index < end) {
      char character = value.charAt(index);
      if (character == '\\') {
        index++;
        if (index == end || !validQuotedOctet(value.charAt(index), true)) {
          return false;
        }
      } else if (!validQuotedOctet(character, false)) {
        return false;
      }

      index++;
    }

    return true;
  }

  /**
   * Checks a quoted-string octet, allowing quote and backslash only after an escape.
   *
   * @param character candidate octet
   * @param escaped whether the preceding octet was a backslash
   * @return whether the octet is valid in this position
   */
  private static boolean validQuotedOctet(char character, boolean escaped) {
    return character == '\t'
        || (character >= 0x20
            && character <= 0xff
            && character != 0x7f
            && (escaped || (character != '"' && character != '\\')));
  }

  /**
   * Validates a literal node and optional source port without hostname resolution.
   *
   * @param value unquoted RFC7239 node
   * @return literal socket address, or null for missing/unknown/obfuscated identity
   * @throws IllegalArgumentException if the node is not a permitted literal or port form
   */
  private static @Nullable InetSocketAddress node(@Nullable String value) {
    if (value == null) {
      return null;
    }

    int separator = value.startsWith("[") ? value.indexOf(']') + 1 : value.indexOf(':');
    String name = value;
    int sourcePort = 0;
    if (separator >= 0 && separator < value.length()) {
      if (value.charAt(separator) != ':') {
        throw new IllegalArgumentException("Invalid forwarding node");
      }

      name = value.substring(0, separator);
      var disclosedPort = value.substring(separator + 1);
      if (!OBFUSCATED.matcher(disclosedPort).matches()) {
        sourcePort = port(disclosedPort);
      }
    }

    if ("unknown".equalsIgnoreCase(name) || OBFUSCATED.matcher(name).matches()) {
      return null;
    }

    if (name.indexOf('%') >= 0 || (!name.startsWith("[") && !IPV4.matcher(name).matches())) {
      throw new IllegalArgumentException("Invalid forwarding address");
    }

    return new InetSocketAddress(InetAddress.ofLiteral(name), sourcePort);
  }

  /**
   * Validates an origin authority before passing it to the transport URI builder.
   *
   * @param value unquoted authority
   * @return parsed authority
   * @throws IllegalArgumentException if the authority is not a permitted host and port form
   */
  private static HostPort host(String value) {
    if (value.isEmpty()
        || value.endsWith(":")
        || value.indexOf('%') >= 0
        || value.chars().anyMatch(character -> "/?#@\\".indexOf(character) >= 0)
        || (!value.startsWith("[") && value.indexOf(':') != value.lastIndexOf(':'))) {
      throw new IllegalArgumentException("Invalid forwarding host");
    }

    var parsed = new HostPort(value);
    int separator = value.startsWith("[") ? value.indexOf(']') + 1 : value.indexOf(':');
    if (separator > 0 && separator < value.length() && port(value.substring(separator + 1)) == 0) {
      throw new IllegalArgumentException("Invalid forwarding host port");
    }

    return parsed;
  }

  /**
   * Parses a disclosed numeric TCP port without accepting signs or overflow.
   *
   * @param value numeric text
   * @return port including zero when explicitly supplied
   * @throws IllegalArgumentException if the value is not a TCP port in range
   */
  private static int port(String value) {
    if (!DIGITS.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid forwarding port");
    }

    int port = Integer.parseInt(value);
    if (port > 65535) {
      throw new IllegalArgumentException("Invalid forwarding port");
    }

    return port;
  }
}
