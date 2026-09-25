package io.github.suppierk.shoostr.http;

import java.util.Optional;

/**
 * Named HTTP status codes from the IANA registry, retrieved 2026-09-18. Unassigned and unused codes
 * are excluded. Historical and temporary registrations retain their documented status; inclusion is
 * not a recommendation to send them.
 *
 * @see <a href="https://www.iana.org/assignments/http-status-codes/">IANA HTTP Status Codes</a>
 */
public enum HttpStatusCodes {
  /**
   * HTTP 100: Continue.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  CONTINUE(100, "Continue"),
  /**
   * HTTP 101: Switching Protocols.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  SWITCHING_PROTOCOLS(101, "Switching Protocols"),
  /**
   * HTTP 102: Processing.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2518">Status specification</a>
   */
  PROCESSING(102, "Processing"),
  /**
   * HTTP 103: Early Hints.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc8297">Status specification</a>
   */
  EARLY_HINTS(103, "Early Hints"),
  /**
   * HTTP 104: Upload Resumption Supported. Temporary registration, expiring 2026-11-13.
   *
   * @see <a
   *     href="https://datatracker.ietf.org/doc/html/draft-ietf-httpbis-resumable-upload-05">Status
   *     specification</a>
   */
  UPLOAD_RESUMPTION_SUPPORTED(104, "Upload Resumption Supported"),
  /**
   * HTTP 200: OK.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  OK(200, "OK"),
  /**
   * HTTP 201: Created.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  CREATED(201, "Created"),
  /**
   * HTTP 202: Accepted.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  ACCEPTED(202, "Accepted"),
  /**
   * HTTP 203: Non-Authoritative Information.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  NON_AUTHORITATIVE_INFORMATION(203, "Non-Authoritative Information"),
  /**
   * HTTP 204: No Content.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  NO_CONTENT(204, "No Content"),
  /**
   * HTTP 205: Reset Content.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  RESET_CONTENT(205, "Reset Content"),
  /**
   * HTTP 206: Partial Content.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  PARTIAL_CONTENT(206, "Partial Content"),
  /**
   * HTTP 207: Multi-Status.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4918">Status specification</a>
   */
  MULTI_STATUS(207, "Multi-Status"),
  /**
   * HTTP 208: Already Reported.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc5842">Status specification</a>
   */
  ALREADY_REPORTED(208, "Already Reported"),
  /**
   * HTTP 226: IM Used.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3229">Status specification</a>
   */
  IM_USED(226, "IM Used"),
  /**
   * HTTP 300: Multiple Choices.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  MULTIPLE_CHOICES(300, "Multiple Choices"),
  /**
   * HTTP 301: Moved Permanently.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  MOVED_PERMANENTLY(301, "Moved Permanently"),
  /**
   * HTTP 302: Found.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  FOUND(302, "Found"),
  /**
   * HTTP 303: See Other.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  SEE_OTHER(303, "See Other"),
  /**
   * HTTP 304: Not Modified.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  NOT_MODIFIED(304, "Not Modified"),
  /**
   * HTTP 305: Use Proxy.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   * @deprecated Deprecated by RFC 9110, section 15.4.6.
   */
  @Deprecated
  USE_PROXY(305, "Use Proxy"),
  /**
   * HTTP 307: Temporary Redirect.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  TEMPORARY_REDIRECT(307, "Temporary Redirect"),
  /**
   * HTTP 308: Permanent Redirect.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  PERMANENT_REDIRECT(308, "Permanent Redirect"),
  /**
   * HTTP 400: Bad Request.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  BAD_REQUEST(400, "Bad Request"),
  /**
   * HTTP 401: Unauthorized.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  UNAUTHORIZED(401, "Unauthorized"),
  /**
   * HTTP 402: Payment Required.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  PAYMENT_REQUIRED(402, "Payment Required"),
  /**
   * HTTP 403: Forbidden.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  FORBIDDEN(403, "Forbidden"),
  /**
   * HTTP 404: Not Found.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  NOT_FOUND(404, "Not Found"),
  /**
   * HTTP 405: Method Not Allowed.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  METHOD_NOT_ALLOWED(405, "Method Not Allowed"),
  /**
   * HTTP 406: Not Acceptable.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  NOT_ACCEPTABLE(406, "Not Acceptable"),
  /**
   * HTTP 407: Proxy Authentication Required.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  PROXY_AUTHENTICATION_REQUIRED(407, "Proxy Authentication Required"),
  /**
   * HTTP 408: Request Timeout.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  REQUEST_TIMEOUT(408, "Request Timeout"),
  /**
   * HTTP 409: Conflict.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  CONFLICT(409, "Conflict"),
  /**
   * HTTP 410: Gone.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  GONE(410, "Gone"),
  /**
   * HTTP 411: Length Required.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  LENGTH_REQUIRED(411, "Length Required"),
  /**
   * HTTP 412: Precondition Failed.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  PRECONDITION_FAILED(412, "Precondition Failed"),
  /**
   * HTTP 413: Content Too Large.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  CONTENT_TOO_LARGE(413, "Content Too Large"),
  /**
   * HTTP 414: URI Too Long.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  URI_TOO_LONG(414, "URI Too Long"),
  /**
   * HTTP 415: Unsupported Media Type.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  UNSUPPORTED_MEDIA_TYPE(415, "Unsupported Media Type"),
  /**
   * HTTP 416: Range Not Satisfiable.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  RANGE_NOT_SATISFIABLE(416, "Range Not Satisfiable"),
  /**
   * HTTP 417: Expectation Failed.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  EXPECTATION_FAILED(417, "Expectation Failed"),
  /**
   * HTTP 421: Misdirected Request.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  MISDIRECTED_REQUEST(421, "Misdirected Request"),
  /**
   * HTTP 422: Unprocessable Content.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  UNPROCESSABLE_CONTENT(422, "Unprocessable Content"),
  /**
   * HTTP 423: Locked.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4918">Status specification</a>
   */
  LOCKED(423, "Locked"),
  /**
   * HTTP 424: Failed Dependency.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4918">Status specification</a>
   */
  FAILED_DEPENDENCY(424, "Failed Dependency"),
  /**
   * HTTP 425: Too Early.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc8470">Status specification</a>
   */
  TOO_EARLY(425, "Too Early"),
  /**
   * HTTP 426: Upgrade Required.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  UPGRADE_REQUIRED(426, "Upgrade Required"),
  /**
   * HTTP 428: Precondition Required.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc6585">Status specification</a>
   */
  PRECONDITION_REQUIRED(428, "Precondition Required"),
  /**
   * HTTP 429: Too Many Requests.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc6585">Status specification</a>
   */
  TOO_MANY_REQUESTS(429, "Too Many Requests"),
  /**
   * HTTP 431: Request Header Fields Too Large.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc6585">Status specification</a>
   */
  REQUEST_HEADER_FIELDS_TOO_LARGE(431, "Request Header Fields Too Large"),
  /**
   * HTTP 451: Unavailable For Legal Reasons.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc7725">Status specification</a>
   */
  UNAVAILABLE_FOR_LEGAL_REASONS(451, "Unavailable For Legal Reasons"),
  /**
   * HTTP 500: Internal Server Error.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  INTERNAL_SERVER_ERROR(500, "Internal Server Error"),
  /**
   * HTTP 501: Not Implemented.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  NOT_IMPLEMENTED(501, "Not Implemented"),
  /**
   * HTTP 502: Bad Gateway.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  BAD_GATEWAY(502, "Bad Gateway"),
  /**
   * HTTP 503: Service Unavailable.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  SERVICE_UNAVAILABLE(503, "Service Unavailable"),
  /**
   * HTTP 504: Gateway Timeout.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  GATEWAY_TIMEOUT(504, "Gateway Timeout"),
  /**
   * HTTP 505: HTTP Version Not Supported.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110">Status specification</a>
   */
  HTTP_VERSION_NOT_SUPPORTED(505, "HTTP Version Not Supported"),
  /**
   * HTTP 506: Variant Also Negotiates.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2295">Status specification</a>
   */
  VARIANT_ALSO_NEGOTIATES(506, "Variant Also Negotiates"),
  /**
   * HTTP 507: Insufficient Storage.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4918">Status specification</a>
   */
  INSUFFICIENT_STORAGE(507, "Insufficient Storage"),
  /**
   * HTTP 508: Loop Detected.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc5842">Status specification</a>
   */
  LOOP_DETECTED(508, "Loop Detected"),
  /**
   * HTTP 510: Not Extended.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2774">Status specification</a>
   * @deprecated Obsoleted in the IANA registry; retained for historical recognition.
   */
  @Deprecated
  NOT_EXTENDED(510, "Not Extended"),
  /**
   * HTTP 511: Network Authentication Required.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc6585">Status specification</a>
   */
  NETWORK_AUTHENTICATION_REQUIRED(511, "Network Authentication Required");

  private static final HttpStatusCodes[] LOOKUP = lookup();

  private final int value;
  private final String reasonPhrase;

  /**
   * Stores the registered numeric code and safe default response text.
   *
   * @param value numeric HTTP status
   * @param reasonPhrase registered reason phrase
   */
  HttpStatusCodes(int value, String reasonPhrase) {
    this.value = value;
    this.reasonPhrase = reasonPhrase;
  }

  /**
   * Returns the numeric HTTP status code.
   *
   * @return the three-digit status code
   */
  public int value() {
    return value;
  }

  /**
   * Returns the registered description, without registry lifecycle notes.
   *
   * @return the standard reason phrase
   */
  public String reasonPhrase() {
    return reasonPhrase;
  }

  /**
   * Looks up a numeric status without throwing for an unrecognized code.
   *
   * @param value the numeric status code
   * @return the recognized status, or empty for unused, unassigned or out-of-range input
   */
  public static Optional<HttpStatusCodes> httpStatusCode(int value) {
    if (value < 0 || value >= LOOKUP.length) {
      return Optional.empty();
    }

    return Optional.ofNullable(LOOKUP[value]);
  }

  /**
   * Identifies informational responses.
   *
   * @return true for a 1xx status
   */
  public boolean isInformational() {
    return value >= 100 && value < 200;
  }

  /**
   * Identifies successful responses.
   *
   * @return true for a 2xx status
   */
  public boolean isSuccess() {
    return value >= 200 && value < 300;
  }

  /**
   * Identifies redirection responses.
   *
   * @return true for a 3xx status
   */
  public boolean isRedirection() {
    return value >= 300 && value < 400;
  }

  /**
   * Identifies client errors.
   *
   * @return true for a 4xx status
   */
  public boolean isClientError() {
    return value >= 400 && value < 500;
  }

  /**
   * Identifies server errors.
   *
   * @return true for a 5xx status
   */
  public boolean isServerError() {
    return value >= 500 && value < 600;
  }

  /**
   * Identifies client or server errors.
   *
   * @return true for a 4xx or 5xx status
   */
  public boolean isError() {
    return isClientError() || isServerError();
  }

  /**
   * Builds the numeric index once; unassigned entries remain null to avoid hashing or boxing on
   * lookup.
   *
   * @return status codes indexed by their numeric value
   */
  private static HttpStatusCodes[] lookup() {
    var statuses = new HttpStatusCodes[600];
    for (var status : values()) {
      statuses[status.value] = status;
    }
    return statuses;
  }
}
