package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.ForwardedHeaders;
import io.github.suppierk.shoostr.http.HttpCharacters;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.jspecify.annotations.Nullable;

/**
 * Applies explicit proxy trust without wrapping the underlying transport request. Equality uses the
 * predicate object's own equality contract and the selected forwarding header family.
 */
final class TrustedProxy {
  private static final int MAX_FORWARDING_ELEMENTS = 64;
  private static final String X_FORWARDED_FOR = "X-Forwarded-For";
  private static final String X_FORWARDED_HOST = "X-Forwarded-Host";
  private static final String X_FORWARDED_PORT = "X-Forwarded-Port";
  private static final String X_FORWARDED_PROTO = "X-Forwarded-Proto";
  private static final String PROTO_FIELD = "proto";
  private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");
  private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]+");
  private static final Pattern OBFUSCATED = Pattern.compile("_[A-Za-z0-9._-]+");
  private static final Pattern DIGITS = Pattern.compile("\\d+");
  private static final QuotedStringTokenizer PARAMETERS =
      forwardingTokenizer(HttpCharacters.SEMICOLON_SIGN_STRING);
  private static final QuotedStringTokenizer ELEMENTS =
      forwardingTokenizer(HttpCharacters.COMMA_SIGN_STRING);

  private final Predicate<InetAddress> trusted;
  private final ForwardedHeaders headers;

  /**
   * Stores immutable startup configuration.
   *
   * @param trusted application-owned thread-safe peer predicate
   * @param headers selected forwarding header family
   * @throws NullPointerException if either configuration value is null
   */
  TrustedProxy(Predicate<InetAddress> trusted, ForwardedHeaders headers) {
    this.trusted = Objects.requireNonNull(trusted);
    this.headers = Objects.requireNonNull(headers);
  }

  /**
   * Parses one physical Forwarded field without resetting the chain-wide size limit.
   *
   * @param header raw field value
   * @param chain destination for parameter maps
   * @param addresses destination for node addresses
   * @throws IllegalArgumentException if an element or address is malformed
   */
  private static void parseForwardingHeader(
      String header, List<Map<String, String>> chain, List<@Nullable InetSocketAddress> addresses) {
    final var elements = ELEMENTS.tokenize(header);
    var elementExpected = true;
    while (elements.hasNext()) {
      final var element = elements.next();
      if (HttpCharacters.COMMA_SIGN_STRING.equals(element)) {
        if (elementExpected) {
          throw new IllegalArgumentException("Empty forwarding element");
        }

        elementExpected = true;
        continue;
      }

      if (!elementExpected) {
        throw new IllegalArgumentException("Invalid forwarding element");
      }

      final var fields = parameters(element);
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

  /**
   * Builds forwarding tokenization rules for the supplied delimiter, retaining quotes and
   * delimiters for validation.
   *
   * @param delimiter delimiter character
   * @return immutable shared tokenization rules
   */
  private static QuotedStringTokenizer forwardingTokenizer(String delimiter) {
    return QuotedStringTokenizer.builder()
        .delimiters(delimiter)
        .returnDelimiters()
        .returnQuotes()
        .allowEmbeddedQuotes()
        .ignoreOptionalWhiteSpace()
        .build();
  }

  /**
   * Reads one element using Jetty's quote-aware lexical handling.
   *
   * @param value complete element
   * @return parameter values
   * @throws IllegalArgumentException if a parameter, delimiter, origin, or node is malformed
   */
  private static Map<String, String> parameters(String value) {
    final var fields = new HashMap<String, String>();
    final var tokens = PARAMETERS.tokenize(value);
    while (tokens.hasNext()) {
      final var token = tokens.next();
      final var equals = token.indexOf(HttpCharacters.EQUALS_SIGN);
      if (equals < 1) {
        throw new IllegalArgumentException("Invalid forwarding parameter");
      }

      final var name = token.substring(0, equals).toLowerCase(Locale.ROOT);
      final var raw = token.substring(equals + 1);
      if (!HttpCharacters.isValidHttpToken(name)
          || !(HttpCharacters.isValidHttpToken(raw) || validQuoted(raw))
          || fields.putIfAbsent(name, PARAMETERS.unquote(raw)) != null) {
        throw new IllegalArgumentException("Invalid forwarding parameter");
      }

      if (tokens.hasNext()
          && (!HttpCharacters.SEMICOLON_SIGN_STRING.equals(tokens.next()) || !tokens.hasNext())) {
        throw new IllegalArgumentException("Invalid forwarding delimiter");
      }
    }

    if (fields.containsKey(PROTO_FIELD) && !Origins.isHttpScheme(fields.get(PROTO_FIELD))) {
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
    if (value.length() < 2
        || value.charAt(0) != HttpCharacters.DOUBLE_QUOTE
        || value.charAt(value.length() - 1) != HttpCharacters.DOUBLE_QUOTE) {
      return false;
    }

    final var end = value.length() - 1;
    var index = 1;
    while (index < end) {
      final var character = value.charAt(index);
      if (character == HttpCharacters.BACKSLASH) {
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
    return character == HttpCharacters.TAB
        || (character >= HttpCharacters.SPACE
            && character <= HttpCharacters.MAX_UNSIGNED_BYTE_VALUE
            && character != HttpCharacters.DELETE_CONTROL
            && (escaped
                || (character != HttpCharacters.DOUBLE_QUOTE
                    && character != HttpCharacters.BACKSLASH)));
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

    for (final var protocol : protocols) {
      if (!Origins.isHttpScheme(protocol)) {
        throw new IllegalArgumentException("Unsupported forwarding scheme");
      }
    }

    for (var index = 0; index < hosts.size(); index++) {
      authority(hosts.get(index), ports.isEmpty() ? null : ports.get(index));
    }
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
    final var values = values(lines);
    if (!values.isEmpty() && values.size() != expected) {
      throw new BadRequestException();
    }

    return values;
  }

  /**
   * Parses a legacy client identity, including an unbracketed IPv6 literal without a port.
   *
   * @param value legacy client identity
   * @return literal client address, or null for an undisclosed identity
   * @throws IllegalArgumentException if the value is malformed
   */
  private static @Nullable InetSocketAddress legacyNode(String value) {
    if (value.indexOf(HttpCharacters.COLON_SIGN) != value.lastIndexOf(HttpCharacters.COLON_SIGN)
        && value.charAt(0) != HttpCharacters.OPEN_SQUARE_BRACKET) {
      if (value.indexOf(HttpCharacters.PERCENT_SIGN) >= 0) {
        throw new IllegalArgumentException("Invalid forwarding address");
      }

      return new InetSocketAddress(literalAddress(value), 0);
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
  private static HostPort authority(String host, @Nullable String forwardedPort) {
    final var parsed = host(host);
    if (forwardedPort == null) {
      return parsed;
    }

    final var port = port(forwardedPort);
    if (parsed.hasPort()) {
      if (parsed.getPort() != port) {
        throw new IllegalArgumentException("Conflicting forwarded ports");
      }

      return parsed;
    }

    if (port == 0) {
      throw new IllegalArgumentException("Invalid forwarding host port");
    }

    return new HostPort(parsed.getHost(), port);
  }

  /**
   * Splits repeated legacy field lines into a nonempty ordered list.
   *
   * @param lines repeated header lines
   * @return ordered trimmed values
   * @throws BadRequestException if a value is empty or exceeds the work bound
   */
  private static List<String> values(List<String> lines) {
    final var result = new ArrayList<String>();
    for (final var line : lines) {
      for (final var value : line.split(HttpCharacters.COMMA_SIGN_STRING, -1)) {
        final var trimmed = value.trim();
        if (trimmed.isEmpty() || result.size() == MAX_FORWARDING_ELEMENTS) {
          throw new BadRequestException();
        }

        result.add(trimmed);
      }
    }
    return result;
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

    final var separator =
        value.startsWith(HttpCharacters.OPEN_SQUARE_BRACKET_STRING)
            ? value.indexOf(HttpCharacters.CLOSE_SQUARE_BRACKET) + 1
            : value.indexOf(HttpCharacters.COLON_SIGN);
    var name = value;
    var sourcePort = 0;
    if (separator >= 0 && separator < value.length()) {
      if (value.charAt(separator) != HttpCharacters.COLON_SIGN) {
        throw new IllegalArgumentException("Invalid forwarding node");
      }

      name = value.substring(0, separator);
      final var disclosedPort = value.substring(separator + 1);
      if (!OBFUSCATED.matcher(disclosedPort).matches()) {
        sourcePort = port(disclosedPort);
      }
    }

    if ("unknown".equalsIgnoreCase(name) || OBFUSCATED.matcher(name).matches()) {
      return null;
    }

    if (name.indexOf(HttpCharacters.PERCENT_SIGN) >= 0
        || (!name.startsWith(HttpCharacters.OPEN_SQUARE_BRACKET_STRING)
            && !IPV4.matcher(name).matches())) {
      throw new IllegalArgumentException("Invalid forwarding address");
    }

    return new InetSocketAddress(literalAddress(name), sourcePort);
  }

  /**
   * Parses literal addresses without allowing malformed numeric input to fall through to DNS.
   *
   * @param value IPv4 or IPv6 literal, optionally bracketed for IPv6
   * @return decoded address
   * @throws IllegalArgumentException if the literal is malformed
   */
  private static InetAddress literalAddress(String value) {
    try {
      if (IPV4.matcher(value).matches()) {
        final var octets = value.split("\\.", -1);
        final var bytes = new byte[4];
        for (var index = 0; index < bytes.length; index++) {
          final var octet = Integer.parseInt(octets[index]);
          if (octet > 255) {
            throw new IllegalArgumentException("Invalid forwarding address");
          }

          bytes[index] = (byte) octet;
        }

        return InetAddress.getByAddress(bytes);
      }

      var literal = value;
      if (literal.startsWith(HttpCharacters.OPEN_SQUARE_BRACKET_STRING)
          && literal.endsWith(HttpCharacters.CLOSE_SQUARE_BRACKET_STRING)) {
        literal = literal.substring(1, literal.length() - 1);
      }

      if (literal.indexOf(HttpCharacters.COLON_SIGN) < 0 || !IPV6.matcher(literal).matches()) {
        throw new IllegalArgumentException("Invalid forwarding address");
      }

      // URI validates the IPv6 grammar before a parser that can also resolve names is called.
      final var host = URI.create("//[" + literal + "]").getHost();
      if (host == null) {
        throw new IllegalArgumentException("Invalid forwarding address");
      }

      return InetAddress.getByName(host);
    } catch (UnknownHostException failure) {
      throw new IllegalArgumentException("Invalid forwarding address", failure);
    }
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
        || value.endsWith(HttpCharacters.COLON_SIGN_STRING)
        || value.indexOf(HttpCharacters.PERCENT_SIGN) >= 0
        || value.chars().anyMatch(character -> "/?#@\\".indexOf(character) >= 0)
        || (!value.startsWith(HttpCharacters.OPEN_SQUARE_BRACKET_STRING)
            && value.indexOf(HttpCharacters.COLON_SIGN)
                != value.lastIndexOf(HttpCharacters.COLON_SIGN))) {
      throw new IllegalArgumentException("Invalid forwarding host");
    }

    final var parsed = new HostPort(value);
    final var separator =
        value.startsWith(HttpCharacters.OPEN_SQUARE_BRACKET_STRING)
            ? value.indexOf(HttpCharacters.CLOSE_SQUARE_BRACKET) + 1
            : value.indexOf(HttpCharacters.COLON_SIGN);
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

    final var port = Integer.parseInt(value);
    if (port > 65535) {
      throw new IllegalArgumentException("Invalid forwarding port");
    }

    return port;
  }

  /**
   * Compares the configured predicate and forwarding header family.
   *
   * @param other object to compare
   * @return whether both configurations have equal predicates and the same header family
   */
  @Override
  public boolean equals(@Nullable Object other) {
    return other instanceof TrustedProxy proxy
        && trusted.equals(proxy.trusted)
        && headers == proxy.headers;
  }

  /**
   * Computes a hash from the trust predicate and header family.
   *
   * @return hash of both configuration values
   */
  @Override
  public int hashCode() {
    return Objects.hash(trusted, headers);
  }

  /**
   * Validates the physical peer before inspecting forwarding input.
   *
   * @param request live request on the handler thread
   * @throws BadRequestException if trusted forwarding metadata is malformed
   */
  void apply(Request request) {
    if (!(request.remoteAddress().orElse(null) instanceof InetSocketAddress peer)
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
    final var forwarding = request.headers(HttpHeaders.FORWARDED.value());
    if (forwarding.isEmpty()) {
      return;
    }

    final var chain = new ArrayList<Map<String, String>>();
    final var addresses = new ArrayList<@Nullable InetSocketAddress>();

    try {
      for (final var header : forwarding) {
        parseForwardingHeader(header, chain, addresses);
      }
    } catch (IllegalArgumentException ignored) {
      throw new BadRequestException();
    }

    final var selected = boundaryIndex(addresses);
    final var fields = chain.get(selected);

    try {
      final var uri = HttpURI.build(request.fullUrl());
      var protocol = fields.get(PROTO_FIELD);
      if (protocol != null) {
        protocol = protocol.toLowerCase(Locale.ROOT);
        uri.scheme(protocol);
      }

      if (fields.containsKey("host")) {
        final var host = host(fields.get("host"));
        uri.authority(host.getHost(), host.getPort());
      }

      request.forwarded(addresses.get(selected), uri.asString(), protocol);
    } catch (IllegalArgumentException ignoredFailure) {
      throw new BadRequestException();
    }
  }

  /**
   * Applies strict same-index legacy forwarding assertions from a trusted peer.
   *
   * @param request live request on the handler thread
   * @throws BadRequestException if trusted forwarding metadata is malformed
   */
  private void applyLegacy(Request request) {
    final var forwarded = values(request.headers(X_FORWARDED_FOR));
    if (forwarded.isEmpty()) {
      return;
    }

    final var hosts = aligned(request.headers(X_FORWARDED_HOST), forwarded.size());
    final var protocols = aligned(request.headers(X_FORWARDED_PROTO), forwarded.size());
    final var ports = aligned(request.headers(X_FORWARDED_PORT), forwarded.size());
    final var addresses = new ArrayList<@Nullable InetSocketAddress>();

    try {
      for (final var value : forwarded) {
        addresses.add(legacyNode(value));
      }

      validateLegacyOrigins(hosts, protocols, ports);
    } catch (IllegalArgumentException ignoredFailure) {
      throw new BadRequestException();
    }

    final var selected = boundaryIndex(addresses);

    try {
      final var uri = HttpURI.build(request.fullUrl());
      String protocol = null;
      if (!protocols.isEmpty()) {
        protocol = protocols.get(selected).toLowerCase(Locale.ROOT);
        uri.scheme(protocol);
      }

      if (!hosts.isEmpty()) {
        final var parsed =
            authority(hosts.get(selected), ports.isEmpty() ? null : ports.get(selected));
        uri.authority(parsed.getHost(), parsed.getPort());
      }

      request.forwarded(addresses.get(selected), uri.asString(), protocol);
    } catch (IllegalArgumentException ignoredFailure) {
      throw new BadRequestException();
    }
  }

  /**
   * Stops at the first untrusted or undisclosed address when walking toward the client.
   *
   * @param addresses parsed nonempty forwarding chain
   * @return index of the selected trust boundary
   */
  private int boundaryIndex(List<@Nullable InetSocketAddress> addresses) {
    var selected = addresses.size() - 1;
    while (selected > 0) {
      final var address = addresses.get(selected);
      if (address == null || !trusted.test(address.getAddress())) {
        break;
      }

      selected--;
    }

    return selected;
  }
}
