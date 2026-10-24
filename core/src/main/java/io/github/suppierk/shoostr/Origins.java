package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpCharacters;
import java.net.URI;
import org.jspecify.annotations.Nullable;

/** Shared exact origin parsing for independent browser policies. */
final class Origins {
  /** Prevents utility-class construction. */
  private Origins() {}

  /**
   * Compares scheme, host and effective port using only already-established proxy trust.
   *
   * @param origin validated incoming origin
   * @param url effective request URL, possibly with an unparseable raw path or query
   * @return whether this is ordinary same-origin traffic
   */
  static boolean sameOrigin(String origin, String url) {
    if ("null".equals(origin)) {
      return false;
    }

    var source = URI.create(origin);
    var target = effectiveOrigin(url);
    if (target == null) {
      return false;
    }

    return source.getScheme().equalsIgnoreCase(target.getScheme())
        && source.getHost().equalsIgnoreCase(target.getHost())
        && effectivePort(source) == effectivePort(target);
  }

  /**
   * Parses only the effective scheme and authority, leaving preserved raw path/query untouched.
   *
   * @param url effective URL from the request or trusted proxy
   * @return origin tuple, or null if the URL has no absolute authority
   */
  static @Nullable URI effectiveOrigin(String url) {
    int schemeEnd = url.indexOf(HttpCharacters.COLON_SIGN);
    if (schemeEnd < 0
        || url.length() < schemeEnd + 3
        || url.charAt(schemeEnd + 1) != HttpCharacters.PATH_SEPARATOR
        || url.charAt(schemeEnd + 2) != HttpCharacters.PATH_SEPARATOR) {
      return null;
    }

    int end = url.length();
    for (int index = schemeEnd + 3; index < url.length(); index++) {
      char value = url.charAt(index);
      if (value == HttpCharacters.PATH_SEPARATOR
          || value == HttpCharacters.QUERY_SEPARATOR
          || value == HttpCharacters.FRAGMENT_SEPARATOR) {
        end = index;
        break;
      }
    }

    return URI.create(url.substring(0, end));
  }

  /**
   * Expands an omitted HTTP(S) default port for origin comparison.
   *
   * @param uri origin or request URL
   * @return explicit port or scheme default
   */
  private static int effectivePort(URI uri) {
    if (uri.getPort() >= 0) {
      return uri.getPort();
    }

    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  /**
   * Checks origin tuple syntax without DNS lookups or URL repair; matching remains exact.
   *
   * @param origin configured origin or supported special value
   * @throws IllegalArgumentException if origin is not an HTTP(S) tuple, null or wildcard
   */
  static void validateOrigin(String origin) {
    if ("null".equals(origin) || HttpCharacters.ASTERISK_STRING.equals(origin)) {
      return;
    }

    var uri = URI.create(origin);
    if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || !uri.getRawPath().isEmpty()
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || uri.getPort() > 65535
        || origin.indexOf(HttpCharacters.PERCENT_SIGN) >= 0
        || !origin.equals(
            uri.getScheme()
                + "://"
                + uri.getHost()
                + (uri.getPort() < 0 ? "" : HttpCharacters.COLON_SIGN_STRING + uri.getPort()))) {
      throw new IllegalArgumentException("Expected an exact HTTP(S) origin without a path");
    }
  }
}
