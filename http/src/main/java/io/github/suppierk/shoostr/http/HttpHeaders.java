package io.github.suppierk.shoostr.http;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * HTTP field names from the IANA registry, updated 2026-08-28.
 *
 * <p>Values preserve the registered spelling; HTTP field-name comparison is case-insensitive. The
 * inventory includes provisional and historical registrations, whose status is documented on each
 * constant. Registration does not imply browser support or suitability for new protocols. Arbitrary
 * application-defined names can be created with {@link #of(String)}; HTTP/2 or HTTP/3
 * pseudo-headers remain outside this inventory.
 *
 * @see <a href="https://www.iana.org/assignments/http-fields/">IANA HTTP Field Name Registry</a>
 */
public final class HttpHeaders {

  /**
   * Lists acceptable instance manipulations for delta-encoded responses.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3229.html">RFC 3229</a>
   */
  public static final HttpHeaders A_IM = new HttpHeaders("A-IM");

  /**
   * Lists the media types acceptable in a response.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-12.5.1">RFC 9110, section
   *     12.5.1</a>
   */
  public static final HttpHeaders ACCEPT = new HttpHeaders("Accept");

  /**
   * Lists acceptable additions to a beverage in HTCPCP.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2324.html">RFC 2324</a>
   */
  public static final HttpHeaders ACCEPT_ADDITIONS = new HttpHeaders("Accept-Additions");

  /**
   * Requests client hints on subsequent requests to the origin.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc8942.html#section-3.1">RFC 8942, section
   *     3.1</a>
   */
  public static final HttpHeaders ACCEPT_CH = new HttpHeaders("Accept-CH");

  /**
   * Lists acceptable character encodings for a response.
   *
   * <p>IANA status: deprecated.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-12.5.2">RFC 9110, section
   *     12.5.2</a>
   */
  public static final HttpHeaders ACCEPT_CHARSET = new HttpHeaders("Accept-Charset");

  /**
   * Requests a historical resource representation for a specified datetime.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc7089.html">RFC 7089</a>
   */
  public static final HttpHeaders ACCEPT_DATETIME = new HttpHeaders("Accept-Datetime");

  /**
   * Lists acceptable content codings for a response.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-12.5.3">RFC 9110, section
   *     12.5.3</a>
   */
  public static final HttpHeaders ACCEPT_ENCODING = new HttpHeaders("Accept-Encoding");

  /**
   * Advertises feature preferences for transparent content negotiation.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2295.html">RFC 2295</a>
   */
  public static final HttpHeaders ACCEPT_FEATURES = new HttpHeaders("Accept-Features");

  /**
   * Lists preferred natural languages for a response.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-12.5.4">RFC 9110, section
   *     12.5.4</a>
   */
  public static final HttpHeaders ACCEPT_LANGUAGE = new HttpHeaders("Accept-Language");

  /**
   * Advertises media types accepted in PATCH request content.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc5789.html">RFC 5789</a>
   */
  public static final HttpHeaders ACCEPT_PATCH = new HttpHeaders("Accept-Patch");

  /**
   * Advertises media types accepted in POST request content.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.w3.org/TR/ldp">Specification cited by IANA</a>
   */
  public static final HttpHeaders ACCEPT_POST = new HttpHeaders("Accept-Post");

  /**
   * Advertises media types accepted in QUERY request content.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc10008.html#section-3">RFC 10008, section 3</a>
   */
  public static final HttpHeaders ACCEPT_QUERY = new HttpHeaders("Accept-Query");

  /**
   * Advertises the range units supported for a resource.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-14.3">RFC 9110, section
   *     14.3</a>
   */
  public static final HttpHeaders ACCEPT_RANGES = new HttpHeaders("Accept-Ranges");

  /**
   * Requests a message signature with specified parameters.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9421.html#section-5.1">RFC 9421, section
   *     5.1</a>
   */
  public static final HttpHeaders ACCEPT_SIGNATURE = new HttpHeaders("Accept-Signature");

  /**
   * Carries the historical cross-site access policy predating modern CORS.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a
   *     href="https://www.w3.org/TR/2007/WD-access-control-20071126/#access-control0">Specification
   *     cited by IANA</a>
   */
  @Deprecated public static final HttpHeaders ACCESS_CONTROL = new HttpHeaders("Access-Control");

  /**
   * Indicates whether a cross-origin response can be shared with credentials.
   *
   * <p>IANA status: permanent.
   *
   * @see <a
   *     href="https://fetch.spec.whatwg.org/#http-access-control-allow-credentials">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders ACCESS_CONTROL_ALLOW_CREDENTIALS =
      new HttpHeaders("Access-Control-Allow-Credentials");

  /**
   * Lists request headers permitted by a CORS preflight response.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://fetch.spec.whatwg.org/#http-access-control-allow-headers">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders ACCESS_CONTROL_ALLOW_HEADERS =
      new HttpHeaders("Access-Control-Allow-Headers");

  /**
   * Lists request methods permitted by a CORS preflight response.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://fetch.spec.whatwg.org/#http-access-control-allow-methods">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders ACCESS_CONTROL_ALLOW_METHODS =
      new HttpHeaders("Access-Control-Allow-Methods");

  /**
   * Identifies the origin allowed to access a cross-origin response.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://fetch.spec.whatwg.org/#http-access-control-allow-origin">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders ACCESS_CONTROL_ALLOW_ORIGIN =
      new HttpHeaders("Access-Control-Allow-Origin");

  /**
   * Lists response headers exposed to cross-origin scripts.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://fetch.spec.whatwg.org/#http-access-control-expose-headers">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders ACCESS_CONTROL_EXPOSE_HEADERS =
      new HttpHeaders("Access-Control-Expose-Headers");

  /**
   * Specifies how long a CORS preflight result can be cached.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://fetch.spec.whatwg.org/#http-access-control-max-age">Specification cited
   *     by IANA</a>
   */
  public static final HttpHeaders ACCESS_CONTROL_MAX_AGE =
      new HttpHeaders("Access-Control-Max-Age");

  /**
   * Lists the headers proposed by a CORS preflight request.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://fetch.spec.whatwg.org/#http-access-control-request-headers">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders ACCESS_CONTROL_REQUEST_HEADERS =
      new HttpHeaders("Access-Control-Request-Headers");

  /**
   * Identifies the method proposed by a CORS preflight request.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://fetch.spec.whatwg.org/#http-access-control-request-method">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders ACCESS_CONTROL_REQUEST_METHOD =
      new HttpHeaders("Access-Control-Request-Method");

  /**
   * Requests activation of an existing storage-access permission.
   *
   * <p>IANA status: provisional.
   *
   * @see <a href="https://privacycg.github.io/storage-access-headers">Specification cited by
   *     IANA</a>
   */
  public static final HttpHeaders ACTIVATE_STORAGE_ACCESS =
      new HttpHeaders("Activate-Storage-Access");

  /**
   * Reports the estimated response age in seconds.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9111.html#section-5.1">RFC 9111, section
   *     5.1</a>
   */
  public static final HttpHeaders AGE = new HttpHeaders("Age");

  /**
   * Lists the methods supported by the target resource.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.1">RFC 9110, section
   *     10.2.1</a>
   */
  public static final HttpHeaders ALLOW = new HttpHeaders("Allow");

  /**
   * Identifies application protocols expected inside a CONNECT tunnel.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc7639.html#section-2">RFC 7639, section 2</a>
   */
  public static final HttpHeaders ALPN = new HttpHeaders("ALPN");

  /**
   * Advertises alternative services that can serve an origin.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc7838.html">RFC 7838</a>
   */
  public static final HttpHeaders ALT_SVC = new HttpHeaders("Alt-Svc");

  /**
   * Identifies the alternative service used for a request.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc7838.html">RFC 7838</a>
   */
  public static final HttpHeaders ALT_USED = new HttpHeaders("Alt-Used");

  /**
   * Describes available representations for transparent content negotiation.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2295.html">RFC 2295</a>
   */
  public static final HttpHeaders ALTERNATES = new HttpHeaders("Alternates");

  /**
   * Requests an AMP cache transformation and its supported version.
   *
   * <p>IANA status: provisional.
   *
   * @see <a
   *     href="https://github.com/ampproject/amphtml/blob/main/docs/spec/amp-cache-transform.md">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders AMP_CACHE_TRANSFORM = new HttpHeaders("AMP-Cache-Transform");

  /**
   * Controls whether a WebDAV method applies to a redirect reference itself.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4437.html">RFC 4437</a>
   */
  public static final HttpHeaders APPLY_TO_REDIRECT_REF = new HttpHeaders("Apply-To-Redirect-Ref");

  /**
   * Conveys server instructions for interactive HTTP authentication.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc8053.html#section-4">RFC 8053, section 4</a>
   */
  public static final HttpHeaders AUTHENTICATION_CONTROL =
      new HttpHeaders("Authentication-Control");

  /**
   * Carries additional origin-server authentication information.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-11.6.3">RFC 9110, section
   *     11.6.3</a>
   */
  public static final HttpHeaders AUTHENTICATION_INFO = new HttpHeaders("Authentication-Info");

  /**
   * Carries credentials for authenticating with the origin server.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-11.6.2">RFC 9110, section
   *     11.6.2</a>
   */
  public static final HttpHeaders AUTHORIZATION = new HttpHeaders("Authorization");

  /**
   * Identifies a compression dictionary available to the client.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9842.html#section-2.2">RFC 9842, section
   *     2.2</a>
   */
  public static final HttpHeaders AVAILABLE_DICTIONARY = new HttpHeaders("Available-Dictionary");

  /**
   * Acknowledges fulfillment of hop-by-hop mandatory HTTP extensions.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2774.html">RFC 2774</a>
   */
  @Deprecated public static final HttpHeaders C_EXT = new HttpHeaders("C-Ext");

  /**
   * Declares mandatory hop-by-hop HTTP extensions.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2774.html">RFC 2774</a>
   */
  @Deprecated public static final HttpHeaders C_MAN = new HttpHeaders("C-Man");

  /**
   * Declares optional hop-by-hop HTTP extensions.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2774.html">RFC 2774</a>
   */
  @Deprecated public static final HttpHeaders C_OPT = new HttpHeaders("C-Opt");

  /**
   * Declares hop-by-hop extensions in the historical PEP protocol.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.w3.org/TR/WD-http-pep">Specification cited by IANA</a>
   */
  @Deprecated public static final HttpHeaders C_PEP = new HttpHeaders("C-PEP");

  /**
   * Provides hop-by-hop extension information in the historical PEP protocol.
   *
   * <p>IANA status: deprecated.
   *
   * @see <a href="https://www.w3.org/TR/WD-http-pep">Specification cited by IANA</a>
   */
  public static final HttpHeaders C_PEP_INFO = new HttpHeaders("C-PEP-Info");

  /**
   * Carries caching directives for requests and responses.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9111.html#section-5.2">RFC 9111, section
   *     5.2</a>
   */
  public static final HttpHeaders CACHE_CONTROL = new HttpHeaders("Cache-Control");

  /**
   * Requests invalidation of named HTTP cache groups.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9875.html">RFC 9875</a>
   */
  public static final HttpHeaders CACHE_GROUP_INVALIDATION =
      new HttpHeaders("Cache-Group-Invalidation");

  /**
   * Associates a response with named HTTP cache groups.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9875.html">RFC 9875</a>
   */
  public static final HttpHeaders CACHE_GROUPS = new HttpHeaders("Cache-Groups");

  /**
   * Describes how caches handled a response.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9211.html">RFC 9211</a>
   */
  public static final HttpHeaders CACHE_STATUS = new HttpHeaders("Cache-Status");

  /**
   * Identifies a managed CalDAV attachment.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc8607.html#section-5.1">RFC 8607, section
   *     5.1</a>
   */
  public static final HttpHeaders CAL_MANAGED_ID = new HttpHeaders("Cal-Managed-ID");

  /**
   * Controls inclusion of VTIMEZONE components in returned CalDAV calendar data.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc7809.html#section-7.1">RFC 7809, section
   *     7.1</a>
   */
  public static final HttpHeaders CALDAV_TIMEZONES = new HttpHeaders("CalDAV-Timezones");

  /**
   * Indicates use of the HTTP capsule protocol.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9297.html">RFC 9297</a>
   */
  public static final HttpHeaders CAPSULE_PROTOCOL = new HttpHeaders("Capsule-Protocol");

  /**
   * Carries cache directives targeted at content delivery networks.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9213.html">RFC 9213</a>
   */
  public static final HttpHeaders CDN_CACHE_CONTROL = new HttpHeaders("CDN-Cache-Control");

  /**
   * Records CDN traversal information for detecting forwarding loops.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc8586.html">RFC 8586</a>
   */
  public static final HttpHeaders CDN_LOOP = new HttpHeaders("CDN-Loop");

  /**
   * Reports the end of a STAR certificate validity interval.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc8739.html#section-3.3">RFC 8739, section
   *     3.3</a>
   */
  public static final HttpHeaders CERT_NOT_AFTER = new HttpHeaders("Cert-Not-After");

  /**
   * Reports the start of a STAR certificate validity interval.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc8739.html#section-3.3">RFC 8739, section
   *     3.3</a>
   */
  public static final HttpHeaders CERT_NOT_BEFORE = new HttpHeaders("Cert-Not-Before");

  /**
   * Requests clearing selected categories of browser data for an origin.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://w3.org/TR/clear-site-data/#header">Specification cited by IANA</a>
   */
  public static final HttpHeaders CLEAR_SITE_DATA = new HttpHeaders("Clear-Site-Data");

  /**
   * Forwards the client certificate from a trusted TLS-terminating proxy.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9440.html#section-2">RFC 9440, section 2</a>
   */
  public static final HttpHeaders CLIENT_CERT = new HttpHeaders("Client-Cert");

  /**
   * Forwards the client certificate chain from a trusted TLS-terminating proxy.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9440.html#section-2">RFC 9440, section 2</a>
   */
  public static final HttpHeaders CLIENT_CERT_CHAIN = new HttpHeaders("Client-Cert-Chain");

  /**
   * Reserves the connection-closing option name; it must not be emitted as a field.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9112.html#section-9.6">RFC 9112, section
   *     9.6</a>
   */
  public static final HttpHeaders CLOSE = new HttpHeaders("Close");

  /**
   * Carries Common Media Client Data describing a media object.
   *
   * <p>IANA status: provisional.
   *
   * @see <a
   *     href="https://cta-wave.github.io/Resources/common-media-client-data--cta-5004-b.html">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders CMCD_OBJECT = new HttpHeaders("CMCD-Object");

  /**
   * Carries Common Media Client Data describing a media request.
   *
   * <p>IANA status: provisional.
   *
   * @see <a
   *     href="https://cta-wave.github.io/Resources/common-media-client-data--cta-5004-b.html">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders CMCD_REQUEST = new HttpHeaders("CMCD-Request");

  /**
   * Carries Common Media Client Data describing a playback session.
   *
   * <p>IANA status: provisional.
   *
   * @see <a
   *     href="https://cta-wave.github.io/Resources/common-media-client-data--cta-5004-b.html">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders CMCD_SESSION = new HttpHeaders("CMCD-Session");

  /**
   * Carries Common Media Client Data describing playback status.
   *
   * <p>IANA status: provisional.
   *
   * @see <a
   *     href="https://cta-wave.github.io/Resources/common-media-client-data--cta-5004-b.html">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders CMCD_STATUS = new HttpHeaders("CMCD-Status");

  /**
   * Carries Common Media Server Data that can change between responses.
   *
   * <p>IANA status: provisional.
   *
   * @see <a
   *     href="https://cdn.cta.tech/cta/media/media/resources/standards/pdfs/cta-5006-final.pdf">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders CMSD_DYNAMIC = new HttpHeaders("CMSD-Dynamic");

  /**
   * Carries Common Media Server Data that remains static across responses.
   *
   * <p>IANA status: provisional.
   *
   * @see <a
   *     href="https://cdn.cta.tech/cta/media/media/resources/standards/pdfs/cta-5006-final.pdf">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders CMSD_STATIC = new HttpHeaders("CMSD-Static");

  /**
   * Forwards TLS key exporter output to a trusted concealed-authentication backend.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9729.html">RFC 9729</a>
   */
  public static final HttpHeaders CONCEALED_AUTH_EXPORT = new HttpHeaders("Concealed-Auth-Export");

  /**
   * Identifies the OSLC configuration in which a resource is resolved.
   *
   * <p>IANA status: provisional.
   *
   * @see <a
   *     href="https://docs.oasis-open-projects.org/oslc-op/config/v1.0/psd01/config-resources.html#configcontext">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders CONFIGURATION_CONTEXT = new HttpHeaders("Configuration-Context");

  /**
   * Negotiates bound-UDP mode between an HTTP UDP proxy and its client.
   *
   * <p>IANA status: permanent.
   *
   * @see <a
   *     href="https://datatracker.ietf.org/doc/html/draft-ietf-masque-connect-udp-listen-16">IETF
   *     specification cited by IANA</a>
   */
  public static final HttpHeaders CONNECT_UDP_BIND = new HttpHeaders("Connect-UDP-Bind");

  /**
   * Lists connection-specific options and fields for the current hop.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-7.6.1">RFC 9110, section
   *     7.6.1</a>
   */
  public static final HttpHeaders CONNECTION = new HttpHeaders("Connection");

  /**
   * Specifies the historical base URI for resolving relative content references.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2068.html">RFC 2068</a>
   */
  @Deprecated public static final HttpHeaders CONTENT_BASE = new HttpHeaders("Content-Base");

  /**
   * Carries integrity digests of HTTP message content.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9530.html#section-2">RFC 9530, section 2</a>
   */
  public static final HttpHeaders CONTENT_DIGEST = new HttpHeaders("Content-Digest");

  /**
   * Describes content presentation, including a suggested download filename.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc6266.html">RFC 6266</a>
   */
  public static final HttpHeaders CONTENT_DISPOSITION = new HttpHeaders("Content-Disposition");

  /**
   * Lists content codings applied to a representation.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-8.4">RFC 9110, section
   *     8.4</a>
   */
  public static final HttpHeaders CONTENT_ENCODING = new HttpHeaders("Content-Encoding");

  /**
   * Identifies content in the historical distribution and replication protocol.
   *
   * <p>IANA status: deprecated.
   *
   * @see <a href="https://www.w3.org/TR/NOTE-drp">Specification cited by IANA</a>
   */
  public static final HttpHeaders CONTENT_ID = new HttpHeaders("Content-ID");

  /**
   * Identifies the natural languages intended for a representation's audience.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-8.5">RFC 9110, section
   *     8.5</a>
   */
  public static final HttpHeaders CONTENT_LANGUAGE = new HttpHeaders("Content-Language");

  /**
   * States the content length in octets, subject to HTTP framing rules.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-8.6">RFC 9110, section
   *     8.6</a>
   */
  public static final HttpHeaders CONTENT_LENGTH = new HttpHeaders("Content-Length");

  /**
   * Identifies a resource corresponding to the enclosed representation.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-8.7">RFC 9110, section
   *     8.7</a>
   */
  public static final HttpHeaders CONTENT_LOCATION = new HttpHeaders("Content-Location");

  /**
   * Carries a historical MD5 integrity check for the entity body.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2616.html#section-14.15">RFC 2616, section
   *     14.15</a>
   */
  @Deprecated public static final HttpHeaders CONTENT_MD5 = new HttpHeaders("Content-MD5");

  /**
   * Describes the range conveyed in a partial representation.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-14.4">RFC 9110, section
   *     14.4</a>
   */
  public static final HttpHeaders CONTENT_RANGE = new HttpHeaders("Content-Range");

  /**
   * Specifies the default scripting language for an HTML document.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.w3.org/TR/html401">Specification cited by IANA</a>
   */
  @Deprecated
  public static final HttpHeaders CONTENT_SCRIPT_TYPE = new HttpHeaders("Content-Script-Type");

  /**
   * Defines the content security policy enforced for a representation.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.w3.org/TR/CSP/#csp-header">Specification cited by IANA</a>
   */
  public static final HttpHeaders CONTENT_SECURITY_POLICY =
      new HttpHeaders("Content-Security-Policy");

  /**
   * Requests reporting of content security policy violations without enforcement.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.w3.org/TR/CSP/#cspro-header">Specification cited by IANA</a>
   */
  public static final HttpHeaders CONTENT_SECURITY_POLICY_REPORT_ONLY =
      new HttpHeaders("Content-Security-Policy-Report-Only");

  /**
   * Specifies the default style-sheet language for an HTML document.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.w3.org/TR/html401">Specification cited by IANA</a>
   */
  @Deprecated
  public static final HttpHeaders CONTENT_STYLE_TYPE = new HttpHeaders("Content-Style-Type");

  /**
   * Identifies the media type of a representation.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-8.3">RFC 9110, section
   *     8.3</a>
   */
  public static final HttpHeaders CONTENT_TYPE = new HttpHeaders("Content-Type");

  /**
   * Labels a historical version of an entity body.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2068.html">RFC 2068</a>
   */
  @Deprecated public static final HttpHeaders CONTENT_VERSION = new HttpHeaders("Content-Version");

  /**
   * Carries applicable cookies from the user agent to the server.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://datatracker.ietf.org/doc/html/draft-ietf-httpbis-rfc6265bis-22">IETF
   *     specification cited by IANA</a>
   */
  public static final HttpHeaders COOKIE = new HttpHeaders("Cookie");

  /**
   * Advertises support for the obsolete cookie version negotiation mechanism.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2965.html">RFC 2965</a>
   */
  @Deprecated public static final HttpHeaders COOKIE2 = new HttpHeaders("Cookie2");

  /**
   * Controls whether a document can embed cross-origin resources.
   *
   * <p>IANA status: permanent.
   *
   * @see <a
   *     href="https://html.spec.whatwg.org/multipage/origin.html#cross-origin-embedder-policy">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders CROSS_ORIGIN_EMBEDDER_POLICY =
      new HttpHeaders("Cross-Origin-Embedder-Policy");

  /**
   * Reports cross-origin embedder policy violations without enforcement.
   *
   * <p>IANA status: permanent.
   *
   * @see <a
   *     href="https://html.spec.whatwg.org/multipage/origin.html#cross-origin-embedder-policy-report-only">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders CROSS_ORIGIN_EMBEDDER_POLICY_REPORT_ONLY =
      new HttpHeaders("Cross-Origin-Embedder-Policy-Report-Only");

  /**
   * Controls cross-origin sharing of a browsing context group.
   *
   * <p>IANA status: permanent.
   *
   * @see <a
   *     href="https://html.spec.whatwg.org/multipage/origin.html#cross-origin-opener-policy-2">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders CROSS_ORIGIN_OPENER_POLICY =
      new HttpHeaders("Cross-Origin-Opener-Policy");

  /**
   * Reports cross-origin opener policy violations without enforcement.
   *
   * <p>IANA status: permanent.
   *
   * @see <a
   *     href="https://html.spec.whatwg.org/multipage/origin.html#cross-origin-opener-policy-report-only">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders CROSS_ORIGIN_OPENER_POLICY_REPORT_ONLY =
      new HttpHeaders("Cross-Origin-Opener-Policy-Report-Only");

  /**
   * Restricts cross-origin access to a resource in no-CORS requests.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://fetch.spec.whatwg.org/#cross-origin-resource-policy-header">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders CROSS_ORIGIN_RESOURCE_POLICY =
      new HttpHeaders("Cross-Origin-Resource-Policy");

  /**
   * Names CTA's provisionally registered common access token field; IANA lists contacts only.
   *
   * <p>IANA status: provisional.
   *
   * @see <a href="https://www.iana.org/assignments/http-fields/">IANA registration (no
   *     specification listed)</a>
   */
  public static final HttpHeaders CTA_COMMON_ACCESS_TOKEN =
      new HttpHeaders("CTA-Common-Access-Token");

  /**
   * Advertises supported WebDAV search grammars.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc5323.html">RFC 5323</a>
   */
  public static final HttpHeaders DASL = new HttpHeaders("DASL");

  /**
   * Records the date and time at which the message originated.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-6.6.1">RFC 9110, section
   *     6.6.1</a>
   */
  public static final HttpHeaders DATE = new HttpHeaders("Date");

  /**
   * Advertises WebDAV compliance classes supported by the resource.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4918.html">RFC 4918</a>
   */
  public static final HttpHeaders DAV = new HttpHeaders("DAV");

  /**
   * Selects the preferred named style sheet for an HTML document.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.w3.org/TR/html401">Specification cited by IANA</a>
   */
  @Deprecated public static final HttpHeaders DEFAULT_STYLE = new HttpHeaders("Default-Style");

  /**
   * Identifies the base representation used for delta encoding.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3229.html">RFC 3229</a>
   */
  public static final HttpHeaders DELTA_BASE = new HttpHeaders("Delta-Base");

  /**
   * Communicates when a resource becomes or became deprecated.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9745.html#section-2">RFC 9745, section 2</a>
   */
  public static final HttpHeaders DEPRECATION = new HttpHeaders("Deprecation");

  /**
   * Specifies the depth of a WebDAV operation over a resource hierarchy.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4918.html">RFC 4918</a>
   */
  public static final HttpHeaders DEPTH = new HttpHeaders("Depth");

  /**
   * Identifies the historical entity version from which an update was derived.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2068.html">RFC 2068</a>
   */
  @Deprecated public static final HttpHeaders DERIVED_FROM = new HttpHeaders("Derived-From");

  /**
   * Specifies the destination URI for a WebDAV COPY or MOVE operation.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4918.html">RFC 4918</a>
   */
  public static final HttpHeaders DESTINATION = new HttpHeaders("Destination");

  /**
   * Carries a detached JWS proof for a GNAP request.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9635.html">RFC 9635</a>
   */
  public static final HttpHeaders DETACHED_JWS = new HttpHeaders("Detached-JWS");

  /**
   * Identifies a differential update in the historical replication protocol.
   *
   * <p>IANA status: deprecated.
   *
   * @see <a href="https://www.w3.org/TR/NOTE-drp">Specification cited by IANA</a>
   */
  public static final HttpHeaders DIFFERENTIAL_ID = new HttpHeaders("Differential-ID");

  /**
   * Echoes the server-provided identifier of an available compression dictionary.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9842.html#section-2.3">RFC 9842, section
   *     2.3</a>
   */
  public static final HttpHeaders DICTIONARY_ID = new HttpHeaders("Dictionary-ID");

  /**
   * Carries an obsolete instance digest, superseded by modern digest fields.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3230.html">RFC 3230</a>
   */
  @Deprecated public static final HttpHeaders DIGEST = new HttpHeaders("Digest");

  /**
   * Carries a proof of possession for an OAuth request.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9449.html">RFC 9449</a>
   */
  public static final HttpHeaders DPOP = new HttpHeaders("DPoP");

  /**
   * Supplies a server nonce for subsequent DPoP proofs.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9449.html">RFC 9449</a>
   */
  public static final HttpHeaders DPOP_NONCE = new HttpHeaders("DPoP-Nonce");

  /**
   * Indicates that a request was forwarded in TLS early data.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc8470.html">RFC 8470</a>
   */
  public static final HttpHeaders EARLY_DATA = new HttpHeaders("Early-Data");

  /**
   * Advertises capabilities for EDIINT message exchange.
   *
   * <p>IANA status: provisional.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc6017.html">RFC 6017</a>
   */
  public static final HttpHeaders EDIINT_FEATURES = new HttpHeaders("EDIINT-Features");

  /**
   * Identifies a selected representation with an opaque entity tag.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-8.8.3">RFC 9110, section
   *     8.8.3</a>
   */
  public static final HttpHeaders ETAG = new HttpHeaders("ETag");

  /**
   * States expectations that the server must satisfy when handling the request.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-10.1.1">RFC 9110, section
   *     10.1.1</a>
   */
  public static final HttpHeaders EXPECT = new HttpHeaders("Expect");

  /**
   * Carries the historical Certificate Transparency enforcement and reporting policy.
   *
   * <p>IANA status: deprecated.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9163.html">RFC 9163</a>
   */
  public static final HttpHeaders EXPECT_CT = new HttpHeaders("Expect-CT");

  /**
   * Specifies the time after which a response is considered stale.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9111.html#section-5.3">RFC 9111, section
   *     5.3</a>
   */
  public static final HttpHeaders EXPIRES = new HttpHeaders("Expires");

  /**
   * Acknowledges fulfillment of mandatory end-to-end HTTP extensions.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2774.html">RFC 2774</a>
   */
  @Deprecated public static final HttpHeaders EXT = new HttpHeaders("Ext");

  /**
   * Records proxy forwarding information such as original client and host.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc7239.html">RFC 7239</a>
   */
  public static final HttpHeaders FORWARDED = new HttpHeaders("Forwarded");

  /**
   * Provides an email address for the human responsible for the requesting agent.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-10.1.2">RFC 9110, section
   *     10.1.2</a>
   */
  public static final HttpHeaders FROM = new HttpHeaders("From");

  /**
   * Requests profile information in the historical Open Profiling Standard protocol.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.w3.org/TR/NOTE-OPS-OverHTTP">Specification cited by IANA</a>
   */
  @Deprecated public static final HttpHeaders GETPROFILE = new HttpHeaders("GetProfile");

  /**
   * Reports the result of an HTTP Origin-Bound Authentication registration.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc7486.html#section-6.1.1">RFC 7486, section
   *     6.1.1</a>
   */
  public static final HttpHeaders HOBAREG = new HttpHeaders("Hobareg");

  /**
   * Identifies the target host and optional port.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-7.2">RFC 9110, section
   *     7.2</a>
   */
  public static final HttpHeaders HOST = new HttpHeaders("Host");

  /**
   * Carries settings for the obsolete HTTP/1.1 upgrade to HTTP/2 mechanism.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.rfc-editor.org/rfc/rfc7540.html#section-3.2.1">RFC 7540, section
   *     3.2.1</a>
   */
  @Deprecated public static final HttpHeaders HTTP2_SETTINGS = new HttpHeaders("HTTP2-Settings");

  /**
   * Expresses WebDAV conditions involving lock tokens and entity tags.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4918.html">RFC 4918</a>
   */
  public static final HttpHeaders IF = new HttpHeaders("If");

  /**
   * Makes the request conditional on a matching current entity tag.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.1">RFC 9110, section
   *     13.1.1</a>
   */
  public static final HttpHeaders IF_MATCH = new HttpHeaders("If-Match");

  /**
   * Makes retrieval conditional on modification after the specified time.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.3">RFC 9110, section
   *     13.1.3</a>
   */
  public static final HttpHeaders IF_MODIFIED_SINCE = new HttpHeaders("If-Modified-Since");

  /**
   * Makes the request conditional on no matching current entity tag.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.2">RFC 9110, section
   *     13.1.2</a>
   */
  public static final HttpHeaders IF_NONE_MATCH = new HttpHeaders("If-None-Match");

  /**
   * Requests a range only when the supplied validator still matches.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.5">RFC 9110, section
   *     13.1.5</a>
   */
  public static final HttpHeaders IF_RANGE = new HttpHeaders("If-Range");

  /**
   * Makes a CalDAV scheduling request conditional on its schedule tag.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc6638.html">RFC 6638</a>
   */
  public static final HttpHeaders IF_SCHEDULE_TAG_MATCH = new HttpHeaders("If-Schedule-Tag-Match");

  /**
   * Makes the request conditional on no modification after the specified time.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.4">RFC 9110, section
   *     13.1.4</a>
   */
  public static final HttpHeaders IF_UNMODIFIED_SINCE = new HttpHeaders("If-Unmodified-Since");

  /**
   * Lists instance manipulations applied to a response.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3229.html">RFC 3229</a>
   */
  public static final HttpHeaders IM = new HttpHeaders("IM");

  /**
   * Requests inclusion of a referred token binding on a redirected request.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc8473.html">RFC 8473</a>
   */
  public static final HttpHeaders INCLUDE_REFERRED_TOKEN_BINDING_ID =
      new HttpHeaders("Include-Referred-Token-Binding-ID");

  /**
   * Indicates whether an HTTP message is intended for incremental forwarding.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc10036.html">RFC 10036</a>
   */
  public static final HttpHeaders INCREMENTAL = new HttpHeaders("Incremental");

  /**
   * Requests OData transaction isolation for processing a request.
   *
   * <p>IANA status: provisional.
   *
   * @see <a
   *     href="http://docs.oasis-open.org/odata/odata/v4.01/odata-v4.01-part1-protocol.html#sec_HeaderIsolationODataIsolation">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders ISOLATION = new HttpHeaders("Isolation");

  /**
   * Carries parameters for historical persistent HTTP connections.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2068.html">RFC 2068</a>
   */
  public static final HttpHeaders KEEP_ALIVE = new HttpHeaders("Keep-Alive");

  /**
   * Selects a WebDAV version by its label.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3253.html">RFC 3253</a>
   */
  public static final HttpHeaders LABEL = new HttpHeaders("Label");

  /**
   * Identifies the last received server-sent event when reconnecting.
   *
   * <p>IANA status: permanent.
   *
   * @see <a
   *     href="https://html.spec.whatwg.org/multipage/server-sent-events.html#last-event-id">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders LAST_EVENT_ID = new HttpHeaders("Last-Event-ID");

  /**
   * Records the last modification time of the selected representation.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-8.8.2">RFC 9110, section
   *     8.8.2</a>
   */
  public static final HttpHeaders LAST_MODIFIED = new HttpHeaders("Last-Modified");

  /**
   * Expresses typed links to other resources.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc8288.html">RFC 8288</a>
   */
  public static final HttpHeaders LINK = new HttpHeaders("Link");

  /**
   * Expresses links whose targets are URI templates.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9652.html">RFC 9652</a>
   */
  public static final HttpHeaders LINK_TEMPLATE = new HttpHeaders("Link-Template");

  /**
   * Identifies a redirect target or the URI of a newly created resource.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.2">RFC 9110, section
   *     10.2.2</a>
   */
  public static final HttpHeaders LOCATION = new HttpHeaders("Location");

  /**
   * Carries the lock token associated with a WebDAV lock.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4918.html">RFC 4918</a>
   */
  public static final HttpHeaders LOCK_TOKEN = new HttpHeaders("Lock-Token");

  /**
   * Declares mandatory end-to-end HTTP extensions.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2774.html">RFC 2774</a>
   */
  @Deprecated public static final HttpHeaders MAN = new HttpHeaders("Man");

  /**
   * Limits the number of intermediary hops for TRACE and OPTIONS.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-7.6.2">RFC 9110, section
   *     7.6.2</a>
   */
  public static final HttpHeaders MAX_FORWARDS = new HttpHeaders("Max-Forwards");

  /**
   * Identifies the datetime of a historical resource representation.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc7089.html">RFC 7089</a>
   */
  public static final HttpHeaders MEMENTO_DATETIME = new HttpHeaders("Memento-Datetime");

  /**
   * Carries hit-metering and usage-limiting directives for caches.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2227.html">RFC 2227</a>
   */
  public static final HttpHeaders METER = new HttpHeaders("Meter");

  /**
   * Requests a historical cross-site method permission check.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a
   *     href="https://www.w3.org/TR/2007/WD-access-control-20071126/#method-check">Specification
   *     cited by IANA</a>
   */
  @Deprecated public static final HttpHeaders METHOD_CHECK = new HttpHeaders("Method-Check");

  /**
   * Specifies expiration of a historical cross-site method permission check.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a
   *     href="https://www.w3.org/TR/2007/WD-access-control-20071126/#method-check-expires">Specification
   *     cited by IANA</a>
   */
  @Deprecated
  public static final HttpHeaders METHOD_CHECK_EXPIRES = new HttpHeaders("Method-Check-Expires");

  /**
   * Identifies the MIME version used by a message.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9112.html">RFC 9112</a>
   */
  public static final HttpHeaders MIME_VERSION = new HttpHeaders("MIME-Version");

  /**
   * Advertises support for transparent content negotiation.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2295.html">RFC 2295</a>
   */
  public static final HttpHeaders NEGOTIATE = new HttpHeaders("Negotiate");

  /**
   * Configures a policy for reporting network errors.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.w3.org/TR/network-error-logging/">Specification cited by IANA</a>
   */
  public static final HttpHeaders NEL = new HttpHeaders("NEL");

  /**
   * Identifies the entity affected by an OData operation.
   *
   * <p>IANA status: permanent.
   *
   * @see <a
   *     href="http://docs.oasis-open.org/odata/odata/v4.01/csprd05/part1-protocol/odata-v4.01-csprd05-part1-protocol.html#_Toc14172735">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders ODATA_ENTITYID = new HttpHeaders("OData-EntityId");

  /**
   * Requests OData transaction isolation for processing a request.
   *
   * <p>IANA status: permanent.
   *
   * @see <a
   *     href="http://docs.oasis-open.org/odata/odata/v4.01/odata-v4.01-part1-protocol.html#sec_HeaderIsolationODataIsolation">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders ODATA_ISOLATION = new HttpHeaders("OData-Isolation");

  /**
   * Specifies the maximum OData protocol version accepted by a client.
   *
   * <p>IANA status: permanent.
   *
   * @see <a
   *     href="http://docs.oasis-open.org/odata/odata/v4.01/odata-v4.01-part1-protocol.html#sec_HeaderODataMaxVersion">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders ODATA_MAXVERSION = new HttpHeaders("OData-MaxVersion");

  /**
   * Specifies the OData protocol version used by a message.
   *
   * <p>IANA status: permanent.
   *
   * @see <a
   *     href="http://docs.oasis-open.org/odata/odata/v4.01/odata-v4.01-part1-protocol.html#sec_HeaderODataVersion">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders ODATA_VERSION = new HttpHeaders("OData-Version");

  /**
   * Declares optional end-to-end HTTP extensions.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2774.html">RFC 2774</a>
   */
  @Deprecated public static final HttpHeaders OPT = new HttpHeaders("Opt");

  /**
   * Offers optional authentication without requiring an authentication failure response.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc8053.html#section-3">RFC 8053, section 3</a>
   */
  public static final HttpHeaders OPTIONAL_WWW_AUTHENTICATE =
      new HttpHeaders("Optional-WWW-Authenticate");

  /**
   * Specifies the ordering semantics of a WebDAV collection.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3648.html">RFC 3648</a>
   */
  public static final HttpHeaders ORDERING_TYPE = new HttpHeaders("Ordering-Type");

  /**
   * Identifies the origin that caused a request.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc6454.html">RFC 6454</a>
   */
  public static final HttpHeaders ORIGIN = new HttpHeaders("Origin");

  /**
   * Requests an origin-keyed agent cluster for a document.
   *
   * <p>IANA status: permanent.
   *
   * @see <a
   *     href="https://html.spec.whatwg.org/multipage/origin.html#origin-agent-cluster">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders ORIGIN_AGENT_CLUSTER = new HttpHeaders("Origin-Agent-Cluster");

  /**
   * Carries object-security parameters when transporting OSCORE over HTTP.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc8613.html#section-11.1">RFC 8613, section
   *     11.1</a>
   */
  public static final HttpHeaders OSCORE = new HttpHeaders("OSCORE");

  /**
   * Identifies the OSLC Core protocol version used by a message.
   *
   * <p>IANA status: permanent.
   *
   * @see <a
   *     href="https://docs.oasis-open-projects.org/oslc-op/core/v3.0/oslc-core.html">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders OSLC_CORE_VERSION = new HttpHeaders("OSLC-Core-Version");

  /**
   * Controls whether a WebDAV operation may replace an existing destination.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4918.html">RFC 4918</a>
   */
  public static final HttpHeaders OVERWRITE = new HttpHeaders("Overwrite");

  /**
   * Advertises a historical Platform for Privacy Preferences policy.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.w3.org/TR/P3P">Specification cited by IANA</a>
   */
  @Deprecated public static final HttpHeaders P3P = new HttpHeaders("P3P");

  /**
   * Declares end-to-end extensions in the historical PEP protocol.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="http://www.w3.org/TR/WD-http-pep">Specification cited by IANA</a>
   */
  @Deprecated public static final HttpHeaders PEP = new HttpHeaders("PEP");

  /**
   * Provides end-to-end extension information in the historical PEP protocol.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="http://www.w3.org/TR/WD-http-pep">Specification cited by IANA</a>
   */
  @Deprecated public static final HttpHeaders PEP_INFO = new HttpHeaders("PEP-Info");

  /**
   * Controls which browser features a document and its frames may use.
   *
   * <p>IANA status: provisional.
   *
   * @see <a href="https://w3c.github.io/webappsec-permissions-policy">Specification cited by
   *     IANA</a>
   */
  public static final HttpHeaders PERMISSIONS_POLICY = new HttpHeaders("Permissions-Policy");

  /**
   * Carries content-rating labels in the historical PICS system.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.w3.org/TR/REC-PICS-labels-961031">Specification cited by IANA</a>
   */
  @Deprecated public static final HttpHeaders PICS_LABEL = new HttpHeaders("PICS-Label");

  /**
   * Identifies the document containing a followed hyperlink for hyperlink auditing.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://html.spec.whatwg.org/multipage/links.html#ping-from">Specification cited
   *     by IANA</a>
   */
  public static final HttpHeaders PING_FROM = new HttpHeaders("Ping-From");

  /**
   * Identifies the followed hyperlink target for hyperlink auditing.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://html.spec.whatwg.org/multipage/links.html#ping-to">Specification cited by
   *     IANA</a>
   */
  public static final HttpHeaders PING_TO = new HttpHeaders("Ping-To");

  /**
   * Specifies a member's position in an ordered WebDAV collection.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3648.html">RFC 3648</a>
   */
  public static final HttpHeaders POSITION = new HttpHeaders("Position");

  /**
   * Carries legacy HTTP/1.0 cache directives.
   *
   * <p>IANA status: deprecated.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9111.html#section-5.4">RFC 9111, section
   *     5.4</a>
   */
  public static final HttpHeaders PRAGMA = new HttpHeaders("Pragma");

  /**
   * Expresses client preferences for how a request should be processed.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc7240.html">RFC 7240</a>
   */
  public static final HttpHeaders PREFER = new HttpHeaders("Prefer");

  /**
   * Reports which client preferences the server applied.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc7240.html">RFC 7240</a>
   */
  public static final HttpHeaders PREFERENCE_APPLIED = new HttpHeaders("Preference-Applied");

  /**
   * Communicates extensible HTTP request or response priority parameters.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9218.html">RFC 9218</a>
   */
  public static final HttpHeaders PRIORITY = new HttpHeaders("Priority");

  /**
   * Carries profile information in the historical Open Profiling Standard protocol.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.w3.org/TR/NOTE-OPS-OverHTTP">Specification cited by IANA</a>
   */
  @Deprecated public static final HttpHeaders PROFILEOBJECT = new HttpHeaders("ProfileObject");

  /**
   * Declares protocol extensions in the historical PICS extension mechanism.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.w3.org/TR/REC-PICS-labels-961031">Specification cited by IANA</a>
   */
  @Deprecated public static final HttpHeaders PROTOCOL = new HttpHeaders("Protocol");

  /**
   * Advertises protocol information in the historical electronic payment initiative.
   *
   * <p>IANA status: deprecated.
   *
   * @see <a href="https://www.w3.org/TR/NOTE-jepi">Specification cited by IANA</a>
   */
  public static final HttpHeaders PROTOCOL_INFO = new HttpHeaders("Protocol-Info");

  /**
   * Queries protocol capabilities in the historical electronic payment initiative.
   *
   * <p>IANA status: deprecated.
   *
   * @see <a href="https://www.w3.org/TR/NOTE-jepi">Specification cited by IANA</a>
   */
  public static final HttpHeaders PROTOCOL_QUERY = new HttpHeaders("Protocol-Query");

  /**
   * Requests protocol extensions in the historical PICS extension mechanism.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.w3.org/TR/REC-PICS-labels-961031">Specification cited by IANA</a>
   */
  @Deprecated
  public static final HttpHeaders PROTOCOL_REQUEST = new HttpHeaders("Protocol-Request");

  /**
   * Carries authentication challenges from a proxy.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-11.7.1">RFC 9110, section
   *     11.7.1</a>
   */
  public static final HttpHeaders PROXY_AUTHENTICATE = new HttpHeaders("Proxy-Authenticate");

  /**
   * Carries additional proxy authentication information.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-11.7.3">RFC 9110, section
   *     11.7.3</a>
   */
  public static final HttpHeaders PROXY_AUTHENTICATION_INFO =
      new HttpHeaders("Proxy-Authentication-Info");

  /**
   * Carries credentials for authenticating with a proxy.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-11.7.2">RFC 9110, section
   *     11.7.2</a>
   */
  public static final HttpHeaders PROXY_AUTHORIZATION = new HttpHeaders("Proxy-Authorization");

  /**
   * Advertises capabilities in the historical proxy-cache notification protocol.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.w3.org/TR/WD-proxy.html">Specification cited by IANA</a>
   */
  @Deprecated public static final HttpHeaders PROXY_FEATURES = new HttpHeaders("Proxy-Features");

  /**
   * Carries instructions in the historical proxy-cache notification protocol.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.w3.org/TR/WD-proxy.html">Specification cited by IANA</a>
   */
  @Deprecated
  public static final HttpHeaders PROXY_INSTRUCTION = new HttpHeaders("Proxy-Instruction");

  /**
   * Reports the public address allocated by a bound UDP proxy.
   *
   * <p>IANA status: permanent.
   *
   * @see <a
   *     href="https://datatracker.ietf.org/doc/html/draft-ietf-masque-connect-udp-listen-16">IETF
   *     specification cited by IANA</a>
   */
  public static final HttpHeaders PROXY_PUBLIC_ADDRESS = new HttpHeaders("Proxy-Public-Address");

  /**
   * Reports proxy handling details, including errors.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9209.html">RFC 9209</a>
   */
  public static final HttpHeaders PROXY_STATUS = new HttpHeaders("Proxy-Status");

  /**
   * Advertises the methods historically supported by a server.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2068.html">RFC 2068</a>
   */
  @Deprecated public static final HttpHeaders PUBLIC = new HttpHeaders("Public");

  /**
   * Defines a certificate public-key pinning policy.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc7469.html">RFC 7469</a>
   */
  public static final HttpHeaders PUBLIC_KEY_PINS = new HttpHeaders("Public-Key-Pins");

  /**
   * Reports public-key pinning violations without enforcing the policy.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc7469.html">RFC 7469</a>
   */
  public static final HttpHeaders PUBLIC_KEY_PINS_REPORT_ONLY =
      new HttpHeaders("Public-Key-Pins-Report-Only");

  /**
   * Requests one or more ranges of the selected representation.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-14.2">RFC 9110, section
   *     14.2</a>
   */
  public static final HttpHeaders RANGE = new HttpHeaders("Range");

  /**
   * Identifies the target of a WebDAV redirect reference.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4437.html">RFC 4437</a>
   */
  public static final HttpHeaders REDIRECT_REF = new HttpHeaders("Redirect-Ref");

  /**
   * Identifies the referring resource; the field name retains its historical spelling.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-10.1.3">RFC 9110, section
   *     10.1.3</a>
   */
  public static final HttpHeaders REFERER = new HttpHeaders("Referer");

  /**
   * Carries the historical cross-site request origin root.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a
   *     href="https://www.w3.org/TR/2007/WD-access-control-20071126/#referer-root">Specification
   *     cited by IANA</a>
   */
  @Deprecated public static final HttpHeaders REFERER_ROOT = new HttpHeaders("Referer-Root");

  /**
   * Controls how much referrer information a browser sends.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.w3.org/TR/referrer-policy/#referrer-policy-header">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders REFERRER_POLICY = new HttpHeaders("Referrer-Policy");

  /**
   * Requests a timed refresh or navigation of a document.
   *
   * <p>IANA status: permanent.
   *
   * @see <a
   *     href="https://html.spec.whatwg.org/multipage/browsing-the-web.html#refresh">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders REFRESH = new HttpHeaders("Refresh");

  /**
   * Identifies a client participating in repeatable requests.
   *
   * <p>IANA status: provisional.
   *
   * @see <a
   *     href="https://docs.oasis-open.org/odata/repeatable-requests/v1.0/cs01/repeatable-requests-v1.0-cs01.html">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders REPEATABILITY_CLIENT_ID =
      new HttpHeaders("Repeatability-Client-ID");

  /**
   * Records when a repeatable request was first sent.
   *
   * <p>IANA status: provisional.
   *
   * @see <a
   *     href="https://docs.oasis-open.org/odata/repeatable-requests/v1.0/cs01/repeatable-requests-v1.0-cs01.html">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders REPEATABILITY_FIRST_SENT =
      new HttpHeaders("Repeatability-First-Sent");

  /**
   * Identifies a request so repeated submissions can be recognized.
   *
   * <p>IANA status: provisional.
   *
   * @see <a
   *     href="https://docs.oasis-open.org/odata/repeatable-requests/v1.0/cs01/repeatable-requests-v1.0-cs01.html">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders REPEATABILITY_REQUEST_ID =
      new HttpHeaders("Repeatability-Request-ID");

  /**
   * Reports the server's handling of a repeatable request.
   *
   * <p>IANA status: provisional.
   *
   * @see <a
   *     href="https://docs.oasis-open.org/odata/repeatable-requests/v1.0/cs01/repeatable-requests-v1.0-cs01.html">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders REPEATABILITY_RESULT = new HttpHeaders("Repeatability-Result");

  /**
   * Provides a nonce to prevent replay of ACME requests.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc8555.html#section-6.5.1">RFC 8555, section
   *     6.5.1</a>
   */
  public static final HttpHeaders REPLAY_NONCE = new HttpHeaders("Replay-Nonce");

  /**
   * Defines named endpoints for browser-generated reports.
   *
   * <p>IANA status: provisional.
   *
   * @see <a href="https://w3c.github.io/reporting/#header">Specification cited by IANA</a>
   */
  public static final HttpHeaders REPORTING_ENDPOINTS = new HttpHeaders("Reporting-Endpoints");

  /**
   * Carries integrity digests of the selected representation.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9530.html#section-3">RFC 9530, section 3</a>
   */
  public static final HttpHeaders REPR_DIGEST = new HttpHeaders("Repr-Digest");

  /**
   * Indicates how long the client should wait before a follow-up request.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3">RFC 9110, section
   *     10.2.3</a>
   */
  public static final HttpHeaders RETRY_AFTER = new HttpHeaders("Retry-After");

  /**
   * Indicates historical assertions about the safety of repeating a request.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2310.html">RFC 2310</a>
   */
  @Deprecated public static final HttpHeaders SAFE = new HttpHeaders("Safe");

  /**
   * Controls scheduling replies when deleting a CalDAV scheduling object.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc6638.html">RFC 6638</a>
   */
  public static final HttpHeaders SCHEDULE_REPLY = new HttpHeaders("Schedule-Reply");

  /**
   * Identifies the scheduling state of a CalDAV scheduling object.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc6638.html">RFC 6638</a>
   */
  public static final HttpHeaders SCHEDULE_TAG = new HttpHeaders("Schedule-Tag");

  /**
   * Identifies the destination type of a fetch request.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.w3.org/TR/fetch-metadata/#sec-fetch-dest-header">Specification cited
   *     by IANA</a>
   */
  public static final HttpHeaders SEC_FETCH_DEST = new HttpHeaders("Sec-Fetch-Dest");

  /**
   * Identifies the mode of a fetch request.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.w3.org/TR/fetch-metadata/#sec-fetch-mode-header">Specification cited
   *     by IANA</a>
   */
  public static final HttpHeaders SEC_FETCH_MODE = new HttpHeaders("Sec-Fetch-Mode");

  /**
   * Describes the relationship between the initiating site and target site.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.w3.org/TR/fetch-metadata/#sec-fetch-site-header">Specification cited
   *     by IANA</a>
   */
  public static final HttpHeaders SEC_FETCH_SITE = new HttpHeaders("Sec-Fetch-Site");

  /**
   * Reports the request's storage-access status.
   *
   * <p>IANA status: provisional.
   *
   * @see <a href="https://privacycg.github.io/storage-access-headers">Specification cited by
   *     IANA</a>
   */
  public static final HttpHeaders SEC_FETCH_STORAGE_ACCESS =
      new HttpHeaders("Sec-Fetch-Storage-Access");

  /**
   * Indicates user activation associated with a navigation request.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.w3.org/TR/fetch-metadata/#sec-fetch-user-header">Specification cited
   *     by IANA</a>
   */
  public static final HttpHeaders SEC_FETCH_USER = new HttpHeaders("Sec-Fetch-User");

  /**
   * Communicates a user's Global Privacy Control preference.
   *
   * <p>IANA status: provisional.
   *
   * @see <a href="https://privacycg.github.io/gpc-spec/">Specification cited by IANA</a>
   */
  public static final HttpHeaders SEC_GPC = new HttpHeaders("Sec-GPC");

  /**
   * Identifies a request's special purpose, such as prefetching.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://fetch.spec.whatwg.org/#sec-purpose-header">Specification cited by
   *     IANA</a>
   */
  public static final HttpHeaders SEC_PURPOSE = new HttpHeaders("Sec-Purpose");

  /**
   * Carries token-binding information for an HTTPS connection.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc8473.html">RFC 8473</a>
   */
  public static final HttpHeaders SEC_TOKEN_BINDING = new HttpHeaders("Sec-Token-Binding");

  /**
   * Confirms the server side of a WebSocket opening handshake.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc6455.html">RFC 6455</a>
   */
  public static final HttpHeaders SEC_WEBSOCKET_ACCEPT = new HttpHeaders("Sec-WebSocket-Accept");

  /**
   * Negotiates WebSocket protocol extensions.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc6455.html">RFC 6455</a>
   */
  public static final HttpHeaders SEC_WEBSOCKET_EXTENSIONS =
      new HttpHeaders("Sec-WebSocket-Extensions");

  /**
   * Carries the client's nonce for a WebSocket opening handshake.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc6455.html">RFC 6455</a>
   */
  public static final HttpHeaders SEC_WEBSOCKET_KEY = new HttpHeaders("Sec-WebSocket-Key");

  /**
   * Negotiates the WebSocket application subprotocol.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc6455.html">RFC 6455</a>
   */
  public static final HttpHeaders SEC_WEBSOCKET_PROTOCOL =
      new HttpHeaders("Sec-WebSocket-Protocol");

  /**
   * Identifies supported or requested WebSocket protocol versions.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc6455.html">RFC 6455</a>
   */
  public static final HttpHeaders SEC_WEBSOCKET_VERSION = new HttpHeaders("Sec-WebSocket-Version");

  /**
   * Identifies the security scheme in historical Secure HTTP.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2660.html">RFC 2660</a>
   */
  @Deprecated public static final HttpHeaders SECURITY_SCHEME = new HttpHeaders("Security-Scheme");

  /**
   * Identifies software used by the origin server.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.4">RFC 9110, section
   *     10.2.4</a>
   */
  public static final HttpHeaders SERVER = new HttpHeaders("Server");

  /**
   * Reports server-side performance metrics to clients.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.w3.org/TR/server-timing/">Specification cited by IANA</a>
   */
  public static final HttpHeaders SERVER_TIMING = new HttpHeaders("Server-Timing");

  /**
   * Instructs the user agent to store or update a cookie.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://datatracker.ietf.org/doc/html/draft-ietf-httpbis-rfc6265bis-22">IETF
   *     specification cited by IANA</a>
   */
  public static final HttpHeaders SET_COOKIE = new HttpHeaders("Set-Cookie");

  /**
   * Sets a cookie using the obsolete versioned cookie mechanism.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2965.html">RFC 2965</a>
   */
  @Deprecated public static final HttpHeaders SET_COOKIE2 = new HttpHeaders("Set-Cookie2");

  /**
   * Carries a transaction identifier for the SCIM profile of Security Event Tokens.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9967.html#section-3">RFC 9967, section 3</a>
   */
  public static final HttpHeaders SET_TXN = new HttpHeaders("Set-Txn");

  /**
   * Requests updates to a user profile in the historical Open Profiling Standard protocol.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.w3.org/TR/NOTE-OPS-OverHTTP">Specification cited by IANA</a>
   */
  @Deprecated public static final HttpHeaders SETPROFILE = new HttpHeaders("SetProfile");

  /**
   * Carries cryptographic HTTP message signatures.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9421.html#section-4.2">RFC 9421, section
   *     4.2</a>
   */
  public static final HttpHeaders SIGNATURE = new HttpHeaders("Signature");

  /**
   * Describes the covered components and parameters of HTTP message signatures.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9421.html#section-4.1">RFC 9421, section
   *     4.1</a>
   */
  public static final HttpHeaders SIGNATURE_INPUT = new HttpHeaders("Signature-Input");

  /**
   * Suggests a resource identifier when creating an Atom publishing resource.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc5023.html">RFC 5023</a>
   */
  public static final HttpHeaders SLUG = new HttpHeaders("SLUG");

  /**
   * Identifies the intent of a SOAP 1.1 HTTP request.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.w3.org/TR/2000/NOTE-SOAP-20000508">Specification cited by IANA</a>
   */
  public static final HttpHeaders SOAPACTION = new HttpHeaders("SoapAction");

  /**
   * Reports resource status information for a historical WebDAV operation.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2518.html">RFC 2518</a>
   */
  public static final HttpHeaders STATUS_URI = new HttpHeaders("Status-URI");

  /**
   * Requires future connections to an origin to use HTTPS.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc6797.html">RFC 6797</a>
   */
  public static final HttpHeaders STRICT_TRANSPORT_SECURITY =
      new HttpHeaders("Strict-Transport-Security");

  /**
   * Communicates when a resource is expected to become unavailable.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc8594.html">RFC 8594</a>
   */
  public static final HttpHeaders SUNSET = new HttpHeaders("Sunset");

  /**
   * Advertises processing capabilities of an HTTP surrogate.
   *
   * <p>IANA status: provisional.
   *
   * @see <a href="https://www.w3.org/TR/edge-arch">Specification cited by IANA</a>
   */
  public static final HttpHeaders SURROGATE_CAPABILITY = new HttpHeaders("Surrogate-Capability");

  /**
   * Carries response-processing and caching directives for HTTP surrogates.
   *
   * <p>IANA status: provisional.
   *
   * @see <a href="https://www.w3.org/TR/edge-arch">Specification cited by IANA</a>
   */
  public static final HttpHeaders SURROGATE_CONTROL = new HttpHeaders("Surrogate-Control");

  /**
   * Describes the result of transparent content negotiation.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2295.html">RFC 2295</a>
   */
  public static final HttpHeaders TCN = new HttpHeaders("TCN");

  /**
   * Advertises acceptable transfer codings and support for trailers.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-10.1.4">RFC 9110, section
   *     10.1.4</a>
   */
  public static final HttpHeaders TE = new HttpHeaders("TE");

  /**
   * Requests or reports the lifetime of a WebDAV lock.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc4918.html">RFC 4918</a>
   */
  public static final HttpHeaders TIMEOUT = new HttpHeaders("Timeout");

  /**
   * Identifies origins allowed to access detailed resource timing information.
   *
   * <p>IANA status: provisional.
   *
   * @see <a href="https://www.w3.org/TR/resource-timing-1/#timing-allow-origin">Specification cited
   *     by IANA</a>
   */
  public static final HttpHeaders TIMING_ALLOW_ORIGIN = new HttpHeaders("Timing-Allow-Origin");

  /**
   * Identifies a Web Push topic so a newer message can replace an outstanding one.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc8030.html#section-5.4">RFC 8030, section
   *     5.4</a>
   */
  public static final HttpHeaders TOPIC = new HttpHeaders("Topic");

  /**
   * Carries the distributed tracing parent identifier and trace flags.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.w3.org/TR/trace-context/#traceparent-header">Specification cited by
   *     IANA</a>
   */
  public static final HttpHeaders TRACEPARENT = new HttpHeaders("Traceparent");

  /**
   * Carries vendor-specific distributed tracing state.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.w3.org/TR/trace-context/#tracestate-header">Specification cited by
   *     IANA</a>
   */
  public static final HttpHeaders TRACESTATE = new HttpHeaders("Tracestate");

  /**
   * Lists fields expected in the trailer section of a message.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-6.6.2">RFC 9110, section
   *     6.6.2</a>
   */
  public static final HttpHeaders TRAILER = new HttpHeaders("Trailer");

  /**
   * Lists transfer codings applied to frame an HTTP/1.1 message body.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9112.html#section-6.1">RFC 9112, section
   *     6.1</a>
   */
  public static final HttpHeaders TRANSFER_ENCODING = new HttpHeaders("Transfer-Encoding");

  /**
   * Specifies how long a Web Push service may retain a message.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc8030.html#section-5.2">RFC 8030, section
   *     5.2</a>
   */
  public static final HttpHeaders TTL = new HttpHeaders("TTL");

  /**
   * Carries integrity digests of content before content codings are applied.
   *
   * <p>IANA status: permanent.
   *
   * @see <a
   *     href="https://datatracker.ietf.org/doc/html/draft-ietf-httpbis-unencoded-digest-05#section-3">IETF
   *     specification cited by IANA</a>
   */
  public static final HttpHeaders UNENCODED_DIGEST = new HttpHeaders("Unencoded-Digest");

  /**
   * Advertises or selects a different protocol on the current connection.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-7.8">RFC 9110, section
   *     7.8</a>
   */
  public static final HttpHeaders UPGRADE = new HttpHeaders("Upgrade");

  /**
   * Specifies the delivery urgency of a Web Push message.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc8030.html#section-5.3">RFC 8030, section
   *     5.3</a>
   */
  public static final HttpHeaders URGENCY = new HttpHeaders("Urgency");

  /**
   * Lists historical alternative identifiers for a resource.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2068.html">RFC 2068</a>
   */
  @Deprecated public static final HttpHeaders URI = new HttpHeaders("URI");

  /**
   * Offers response content for use as a compression dictionary.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9842.html#section-2.1">RFC 9842, section
   *     2.1</a>
   */
  public static final HttpHeaders USE_AS_DICTIONARY = new HttpHeaders("Use-As-Dictionary");

  /**
   * Identifies the software originating a request.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-10.1.5">RFC 9110, section
   *     10.1.5</a>
   */
  public static final HttpHeaders USER_AGENT = new HttpHeaders("User-Agent");

  /**
   * Describes request fields affecting selection of a negotiated variant.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc2295.html">RFC 2295</a>
   */
  public static final HttpHeaders VARIANT_VARY = new HttpHeaders("Variant-Vary");

  /**
   * Lists request fields that affected selection of the response representation.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-12.5.5">RFC 9110, section
   *     12.5.5</a>
   */
  public static final HttpHeaders VARY = new HttpHeaders("Vary");

  /**
   * Records intermediaries and protocol versions traversed by a message.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-7.6.3">RFC 9110, section
   *     7.6.3</a>
   */
  public static final HttpHeaders VIA = new HttpHeaders("Via");

  /**
   * Expresses preferences for integrity digests of message content.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9530.html#section-4">RFC 9530, section 4</a>
   */
  public static final HttpHeaders WANT_CONTENT_DIGEST = new HttpHeaders("Want-Content-Digest");

  /**
   * Requests obsolete instance digests, superseded by modern digest preferences.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.rfc-editor.org/rfc/rfc3230.html">RFC 3230</a>
   */
  @Deprecated public static final HttpHeaders WANT_DIGEST = new HttpHeaders("Want-Digest");

  /**
   * Expresses preferences for integrity digests of the selected representation.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9530.html#section-4">RFC 9530, section 4</a>
   */
  public static final HttpHeaders WANT_REPR_DIGEST = new HttpHeaders("Want-Repr-Digest");

  /**
   * Expresses preferences for integrity digests of unencoded content.
   *
   * <p>IANA status: permanent.
   *
   * @see <a
   *     href="https://datatracker.ietf.org/doc/html/draft-ietf-httpbis-unencoded-digest-05#section-4">IETF
   *     specification cited by IANA</a>
   */
  public static final HttpHeaders WANT_UNENCODED_DIGEST = new HttpHeaders("Want-Unencoded-Digest");

  /**
   * Carries obsolete cache-related warnings about a response.
   *
   * <p>IANA status: obsoleted.
   *
   * @deprecated Obsoleted in the IANA registry; retained for recognizing historical fields.
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9111.html#section-5.5">RFC 9111, section
   *     5.5</a>
   */
  @Deprecated public static final HttpHeaders WARNING = new HttpHeaders("Warning");

  /**
   * Carries authentication challenges from the origin server.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-11.6.1">RFC 9110, section
   *     11.6.1</a>
   */
  public static final HttpHeaders WWW_AUTHENTICATE = new HttpHeaders("WWW-Authenticate");

  /**
   * Controls MIME type sniffing by user agents.
   *
   * <p>IANA status: permanent.
   *
   * @see <a href="https://fetch.spec.whatwg.org/#x-content-type-options-header">Specification cited
   *     by IANA</a>
   */
  public static final HttpHeaders X_CONTENT_TYPE_OPTIONS =
      new HttpHeaders("X-Content-Type-Options");

  /**
   * Restricts whether a document may be displayed in a frame.
   *
   * <p>IANA status: permanent.
   *
   * @see <a
   *     href="https://html.spec.whatwg.org/multipage/browsing-the-web.html#x-frame-options">Specification
   *     cited by IANA</a>
   */
  public static final HttpHeaders X_FRAME_OPTIONS = new HttpHeaders("X-Frame-Options");

  private static final Map<String, HttpHeaders> BY_VALUE = index();

  private final String value;

  /**
   * Validates and stores an HTTP field name.
   *
   * @param value field name preserving its outbound spelling
   * @throws NullPointerException if value is null
   * @throws IllegalArgumentException if value is not an HTTP field-name token
   */
  private HttpHeaders(String value) {
    Objects.requireNonNull(value);
    if (!isFieldName(value)) {
      throw new IllegalArgumentException("HTTP field name must be an RFC 9110 token");
    }

    this.value = value;
  }

  /**
   * Creates an immutable application-defined HTTP field name. The name must be an RFC 9110 token:
   * one or more ASCII letters, digits, or {@code !#$%&'*+-.^_`|~}. No registry lookup, whitespace
   * trimming, or normalization is performed; the supplied spelling is used on the wire. Equivalent
   * spellings compare and hash without case sensitivity.
   *
   * @param value HTTP field name
   * @return immutable field name
   * @throws NullPointerException if value is null
   * @throws IllegalArgumentException if value is not an HTTP field-name token
   */
  public static HttpHeaders of(String value) {
    return new HttpHeaders(value);
  }

  /**
   * Finds a registered HTTP field name using ASCII case-insensitive comparison.
   *
   * <p>Matches wire names such as {@code Content-Type}, not Java constant identifiers such as
   * {@code CONTENT_TYPE}. Unknown names, null, and non-ASCII input return an empty result.
   * Whitespace is not trimmed. Historical and reserved entries remain recognizable.
   *
   * @param value HTTP field name, or null
   * @return the matching header, or an empty result when unrecognized
   */
  public static Optional<HttpHeaders> httpHeader(@Nullable String value) {
    if (value == null) {
      return Optional.empty();
    }

    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) > 127) {
        return Optional.empty();
      }
    }
    return Optional.ofNullable(BY_VALUE.get(value.toLowerCase(Locale.ROOT)));
  }

  /**
   * Returns the registered spelling for use as an HTTP field name.
   *
   * @return field name, for example {@code Content-Type}
   */
  public String value() {
    return value;
  }

  /**
   * Compares this header's wire name with a possibly null name, ignoring ASCII case.
   *
   * @param name HTTP field name, or null; whitespace is not trimmed
   * @return true for the same wire name, or false for null, non-ASCII, or a different name
   */
  public boolean equalsIgnoreCase(@Nullable String name) {
    if (name == null) {
      return false;
    }

    for (int i = 0; i < name.length(); i++) {
      if (name.charAt(i) > 127) {
        return false;
      }
    }
    return value.equalsIgnoreCase(name);
  }

  /**
   * Compares field names without case sensitivity, as required by HTTP.
   *
   * @param other candidate value
   * @return true when both values have equivalent field names
   */
  @Override
  public boolean equals(Object other) {
    return other instanceof HttpHeaders header && value.equalsIgnoreCase(header.value);
  }

  /**
   * Hashes the field name without case sensitivity.
   *
   * @return hash consistent with {@link #equals(Object)}
   */
  @Override
  public int hashCode() {
    return value.toLowerCase(Locale.ROOT).hashCode();
  }

  /**
   * Builds the immutable built-in wire-name index once using locale-independent lowercase keys.
   *
   * @return registered headers indexed by normalized wire name
   */
  private static Map<String, HttpHeaders> index() {
    var result = new HashMap<String, HttpHeaders>();
    for (var header :
        new HttpHeaders[] {
          A_IM,
          ACCEPT,
          ACCEPT_ADDITIONS,
          ACCEPT_CH,
          ACCEPT_CHARSET,
          ACCEPT_DATETIME,
          ACCEPT_ENCODING,
          ACCEPT_FEATURES,
          ACCEPT_LANGUAGE,
          ACCEPT_PATCH,
          ACCEPT_POST,
          ACCEPT_QUERY,
          ACCEPT_RANGES,
          ACCEPT_SIGNATURE,
          ACCESS_CONTROL,
          ACCESS_CONTROL_ALLOW_CREDENTIALS,
          ACCESS_CONTROL_ALLOW_HEADERS,
          ACCESS_CONTROL_ALLOW_METHODS,
          ACCESS_CONTROL_ALLOW_ORIGIN,
          ACCESS_CONTROL_EXPOSE_HEADERS,
          ACCESS_CONTROL_MAX_AGE,
          ACCESS_CONTROL_REQUEST_HEADERS,
          ACCESS_CONTROL_REQUEST_METHOD,
          ACTIVATE_STORAGE_ACCESS,
          AGE,
          ALLOW,
          ALPN,
          ALT_SVC,
          ALT_USED,
          ALTERNATES,
          AMP_CACHE_TRANSFORM,
          APPLY_TO_REDIRECT_REF,
          AUTHENTICATION_CONTROL,
          AUTHENTICATION_INFO,
          AUTHORIZATION,
          AVAILABLE_DICTIONARY,
          C_EXT,
          C_MAN,
          C_OPT,
          C_PEP,
          C_PEP_INFO,
          CACHE_CONTROL,
          CACHE_GROUP_INVALIDATION,
          CACHE_GROUPS,
          CACHE_STATUS,
          CAL_MANAGED_ID,
          CALDAV_TIMEZONES,
          CAPSULE_PROTOCOL,
          CDN_CACHE_CONTROL,
          CDN_LOOP,
          CERT_NOT_AFTER,
          CERT_NOT_BEFORE,
          CLEAR_SITE_DATA,
          CLIENT_CERT,
          CLIENT_CERT_CHAIN,
          CLOSE,
          CMCD_OBJECT,
          CMCD_REQUEST,
          CMCD_SESSION,
          CMCD_STATUS,
          CMSD_DYNAMIC,
          CMSD_STATIC,
          CONCEALED_AUTH_EXPORT,
          CONFIGURATION_CONTEXT,
          CONNECT_UDP_BIND,
          CONNECTION,
          CONTENT_BASE,
          CONTENT_DIGEST,
          CONTENT_DISPOSITION,
          CONTENT_ENCODING,
          CONTENT_ID,
          CONTENT_LANGUAGE,
          CONTENT_LENGTH,
          CONTENT_LOCATION,
          CONTENT_MD5,
          CONTENT_RANGE,
          CONTENT_SCRIPT_TYPE,
          CONTENT_SECURITY_POLICY,
          CONTENT_SECURITY_POLICY_REPORT_ONLY,
          CONTENT_STYLE_TYPE,
          CONTENT_TYPE,
          CONTENT_VERSION,
          COOKIE,
          COOKIE2,
          CROSS_ORIGIN_EMBEDDER_POLICY,
          CROSS_ORIGIN_EMBEDDER_POLICY_REPORT_ONLY,
          CROSS_ORIGIN_OPENER_POLICY,
          CROSS_ORIGIN_OPENER_POLICY_REPORT_ONLY,
          CROSS_ORIGIN_RESOURCE_POLICY,
          CTA_COMMON_ACCESS_TOKEN,
          DASL,
          DATE,
          DAV,
          DEFAULT_STYLE,
          DELTA_BASE,
          DEPRECATION,
          DEPTH,
          DERIVED_FROM,
          DESTINATION,
          DETACHED_JWS,
          DIFFERENTIAL_ID,
          DICTIONARY_ID,
          DIGEST,
          DPOP,
          DPOP_NONCE,
          EARLY_DATA,
          EDIINT_FEATURES,
          ETAG,
          EXPECT,
          EXPECT_CT,
          EXPIRES,
          EXT,
          FORWARDED,
          FROM,
          GETPROFILE,
          HOBAREG,
          HOST,
          HTTP2_SETTINGS,
          IF,
          IF_MATCH,
          IF_MODIFIED_SINCE,
          IF_NONE_MATCH,
          IF_RANGE,
          IF_SCHEDULE_TAG_MATCH,
          IF_UNMODIFIED_SINCE,
          IM,
          INCLUDE_REFERRED_TOKEN_BINDING_ID,
          INCREMENTAL,
          ISOLATION,
          KEEP_ALIVE,
          LABEL,
          LAST_EVENT_ID,
          LAST_MODIFIED,
          LINK,
          LINK_TEMPLATE,
          LOCATION,
          LOCK_TOKEN,
          MAN,
          MAX_FORWARDS,
          MEMENTO_DATETIME,
          METER,
          METHOD_CHECK,
          METHOD_CHECK_EXPIRES,
          MIME_VERSION,
          NEGOTIATE,
          NEL,
          ODATA_ENTITYID,
          ODATA_ISOLATION,
          ODATA_MAXVERSION,
          ODATA_VERSION,
          OPT,
          OPTIONAL_WWW_AUTHENTICATE,
          ORDERING_TYPE,
          ORIGIN,
          ORIGIN_AGENT_CLUSTER,
          OSCORE,
          OSLC_CORE_VERSION,
          OVERWRITE,
          P3P,
          PEP,
          PEP_INFO,
          PERMISSIONS_POLICY,
          PICS_LABEL,
          PING_FROM,
          PING_TO,
          POSITION,
          PRAGMA,
          PREFER,
          PREFERENCE_APPLIED,
          PRIORITY,
          PROFILEOBJECT,
          PROTOCOL,
          PROTOCOL_INFO,
          PROTOCOL_QUERY,
          PROTOCOL_REQUEST,
          PROXY_AUTHENTICATE,
          PROXY_AUTHENTICATION_INFO,
          PROXY_AUTHORIZATION,
          PROXY_FEATURES,
          PROXY_INSTRUCTION,
          PROXY_PUBLIC_ADDRESS,
          PROXY_STATUS,
          PUBLIC,
          PUBLIC_KEY_PINS,
          PUBLIC_KEY_PINS_REPORT_ONLY,
          RANGE,
          REDIRECT_REF,
          REFERER,
          REFERER_ROOT,
          REFERRER_POLICY,
          REFRESH,
          REPEATABILITY_CLIENT_ID,
          REPEATABILITY_FIRST_SENT,
          REPEATABILITY_REQUEST_ID,
          REPEATABILITY_RESULT,
          REPLAY_NONCE,
          REPORTING_ENDPOINTS,
          REPR_DIGEST,
          RETRY_AFTER,
          SAFE,
          SCHEDULE_REPLY,
          SCHEDULE_TAG,
          SEC_FETCH_DEST,
          SEC_FETCH_MODE,
          SEC_FETCH_SITE,
          SEC_FETCH_STORAGE_ACCESS,
          SEC_FETCH_USER,
          SEC_GPC,
          SEC_PURPOSE,
          SEC_TOKEN_BINDING,
          SEC_WEBSOCKET_ACCEPT,
          SEC_WEBSOCKET_EXTENSIONS,
          SEC_WEBSOCKET_KEY,
          SEC_WEBSOCKET_PROTOCOL,
          SEC_WEBSOCKET_VERSION,
          SECURITY_SCHEME,
          SERVER,
          SERVER_TIMING,
          SET_COOKIE,
          SET_COOKIE2,
          SET_TXN,
          SETPROFILE,
          SIGNATURE,
          SIGNATURE_INPUT,
          SLUG,
          SOAPACTION,
          STATUS_URI,
          STRICT_TRANSPORT_SECURITY,
          SUNSET,
          SURROGATE_CAPABILITY,
          SURROGATE_CONTROL,
          TCN,
          TE,
          TIMEOUT,
          TIMING_ALLOW_ORIGIN,
          TOPIC,
          TRACEPARENT,
          TRACESTATE,
          TRAILER,
          TRANSFER_ENCODING,
          TTL,
          UNENCODED_DIGEST,
          UPGRADE,
          URGENCY,
          URI,
          USE_AS_DICTIONARY,
          USER_AGENT,
          VARIANT_VARY,
          VARY,
          VIA,
          WANT_CONTENT_DIGEST,
          WANT_DIGEST,
          WANT_REPR_DIGEST,
          WANT_UNENCODED_DIGEST,
          WARNING,
          WWW_AUTHENTICATE,
          X_CONTENT_TYPE_OPTIONS,
          X_FRAME_OPTIONS
        }) {
      result.put(header.value.toLowerCase(Locale.ROOT), header);
    }
    return Map.copyOf(result);
  }

  /**
   * Checks the RFC 9110 field-name token grammar without allocating a normalized copy.
   *
   * @param value candidate field name
   * @return true when value contains one or more valid field-name characters
   */
  private static boolean isFieldName(String value) {
    if (value.isEmpty()) {
      return false;
    }

    for (var index = 0; index < value.length(); index++) {
      var character = value.charAt(index);
      if (!(character >= '0' && character <= '9')
          && !(character >= 'A' && character <= 'Z')
          && !(character >= 'a' && character <= 'z')
          && "!#$%&'*+-.^_`|~".indexOf(character) < 0) {
        return false;
      }
    }
    return true;
  }
}
