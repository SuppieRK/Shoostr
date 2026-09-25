package io.github.suppierk.shoostr.http;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Named HTTP methods from the IANA registry, retrieved 2026-09-18. Method tokens are
 * case-sensitive; the reserved wildcard is not a method.
 *
 * @see <a href="https://www.iana.org/assignments/http-methods/">IANA HTTP Method Registry</a>
 */
public enum HttpMethods {
  /**
   * The registered ACL method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3744">RFC 3744</a>
   */
  ACL("ACL"),
  /**
   * The registered BASELINE-CONTROL method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3253">RFC 3253</a>
   */
  BASELINE_CONTROL("BASELINE-CONTROL"),
  /**
   * The registered BIND method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc5842">RFC 5842</a>
   */
  BIND("BIND"),
  /**
   * The registered CHECKIN method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3253">RFC 3253</a>
   */
  CHECKIN("CHECKIN"),
  /**
   * The registered CHECKOUT method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3253">RFC 3253</a>
   */
  CHECKOUT("CHECKOUT"),
  /**
   * The registered CONNECT method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">RFC 9110</a>
   */
  CONNECT("CONNECT"),
  /**
   * The registered COPY method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4918">RFC 4918</a>
   */
  COPY("COPY"),
  /**
   * The registered DELETE method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">RFC 9110</a>
   */
  DELETE("DELETE"),
  /**
   * The registered GET method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">RFC 9110</a>
   */
  GET("GET"),
  /**
   * The registered HEAD method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">RFC 9110</a>
   */
  HEAD("HEAD"),
  /**
   * The registered LABEL method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3253">RFC 3253</a>
   */
  LABEL("LABEL"),
  /**
   * The registered LINK method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2068">RFC 2068</a>
   */
  LINK("LINK"),
  /**
   * The registered LOCK method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4918">RFC 4918</a>
   */
  LOCK("LOCK"),
  /**
   * The registered MERGE method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3253">RFC 3253</a>
   */
  MERGE("MERGE"),
  /**
   * The registered MKACTIVITY method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3253">RFC 3253</a>
   */
  MKACTIVITY("MKACTIVITY"),
  /**
   * The registered MKCALENDAR method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4791">RFC 4791</a>
   */
  MKCALENDAR("MKCALENDAR"),
  /**
   * The registered MKCOL method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4918">RFC 4918</a>
   */
  MKCOL("MKCOL"),
  /**
   * The registered MKREDIRECTREF method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4437">RFC 4437</a>
   */
  MKREDIRECTREF("MKREDIRECTREF"),
  /**
   * The registered MKWORKSPACE method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3253">RFC 3253</a>
   */
  MKWORKSPACE("MKWORKSPACE"),
  /**
   * The registered MOVE method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4918">RFC 4918</a>
   */
  MOVE("MOVE"),
  /**
   * The registered OPTIONS method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">RFC 9110</a>
   */
  OPTIONS("OPTIONS"),
  /**
   * The registered ORDERPATCH method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3648">RFC 3648</a>
   */
  ORDERPATCH("ORDERPATCH"),
  /**
   * The registered PATCH method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc5789">RFC 5789</a>
   */
  PATCH("PATCH"),
  /**
   * The registered POST method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">RFC 9110</a>
   */
  POST("POST"),
  /**
   * HTTP/2 connection preface token; not an ordinary request method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9113">RFC 9113</a>
   */
  PRI("PRI"),
  /**
   * The registered PROPFIND method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4918">RFC 4918</a>
   */
  PROPFIND("PROPFIND"),
  /**
   * The registered PROPPATCH method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4918">RFC 4918</a>
   */
  PROPPATCH("PROPPATCH"),
  /**
   * The registered PUT method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">RFC 9110</a>
   */
  PUT("PUT"),
  /**
   * The registered QUERY method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc10008">RFC 10008</a>
   */
  QUERY("QUERY"),
  /**
   * The registered REBIND method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc5842">RFC 5842</a>
   */
  REBIND("REBIND"),
  /**
   * The registered REPORT method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3253">RFC 3253</a>
   */
  REPORT("REPORT"),
  /**
   * The registered SEARCH method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc5323">RFC 5323</a>
   */
  SEARCH("SEARCH"),
  /**
   * The registered TRACE method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">RFC 9110</a>
   */
  TRACE("TRACE"),
  /**
   * The registered UNBIND method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc5842">RFC 5842</a>
   */
  UNBIND("UNBIND"),
  /**
   * The registered UNCHECKOUT method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3253">RFC 3253</a>
   */
  UNCHECKOUT("UNCHECKOUT"),
  /**
   * The registered UNLINK method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2068">RFC 2068</a>
   */
  UNLINK("UNLINK"),
  /**
   * The registered UNLOCK method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4918">RFC 4918</a>
   */
  UNLOCK("UNLOCK"),
  /**
   * The registered UPDATE method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3253">RFC 3253</a>
   */
  UPDATE("UPDATE"),
  /**
   * The registered UPDATEREDIRECTREF method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4437">RFC 4437</a>
   */
  UPDATEREDIRECTREF("UPDATEREDIRECTREF"),
  /**
   * The registered VERSION-CONTROL method.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3253">RFC 3253</a>
   */
  VERSION_CONTROL("VERSION-CONTROL");

  private static final Map<String, HttpMethods> LOOKUP = lookup();
  private final String value;

  /**
   * Returns this method's exact wire token.
   *
   * @return the case-sensitive HTTP method token
   */
  public String value() {
    return value;
  }

  /**
   * Looks up a wire token without throwing for an unknown method.
   *
   * @param value case-sensitive token; no trimming or normalization is performed
   * @return the recognized method, or empty for null or unknown input
   */
  public static Optional<HttpMethods> httpMethod(@Nullable String value) {
    return value == null ? Optional.empty() : Optional.ofNullable(LOOKUP.get(value));
  }

  /**
   * Stores the case-sensitive wire token for this registered method.
   *
   * @param value registered HTTP method token
   */
  HttpMethods(String value) {
    this.value = value;
  }

  /**
   * Builds the immutable exact-token index once without normalizing method case.
   *
   * @return methods indexed by wire token
   */
  private static Map<String, HttpMethods> lookup() {
    var methods = new HashMap<String, HttpMethods>();
    for (var method : values()) {
      methods.put(method.value, method);
    }
    return Map.copyOf(methods);
  }
}
