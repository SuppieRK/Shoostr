# HTTP primitives

A dependency-free Java library containing HTTP headers, methods, media types, cookies, URL punctuation, and status codes in `io.github.suppierk.shoostr.http`, with error exceptions in `io.github.suppierk.shoostr.http.exceptions`.

## Media types

`MediaType` constructs immutable outbound Content-Type values:

```java
MediaType.APPLICATION_JSON
MediaType.APPLICATION_OCTET_STREAM
MediaType.APPLICATION_FORM_URLENCODED
MediaType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8)
MediaType.of("application/vnd.example+json")
```

`of(String)` accepts a bare `type/subtype` and normalizes it with locale-independent lowercase. It uses the [RFC 6838 §4.2](https://www.rfc-editor.org/rfc/rfc6838.html#section-4.2) lexical subset: each component starts with an ASCII letter/digit, contains only ASCII letters/digits or `!#$&-^_.+`, and is 1–127 characters long. Parameters, surrounding whitespace, ranges, and wildcards are rejected rather than parsed or trimmed. This constructor is deliberately narrower than HTTP's general token grammar; it does not consult IANA or certify a type's registration. Null input throws `NullPointerException`; malformed input throws `IllegalArgumentException`.

`value()` returns the header spelling. `withCharset(Charset)` creates a new value, replaces any previous charset, and uses the charset's canonical name, for example `text/plain; charset=UTF-8`. Equality and hashing ignore case for the name and charset. The base value and constants stay unchanged. Provider-defined charset names that require HTTP quoting (such as names containing `:`) are rejected; no general parameter quoting or parsing API is provided.

Charset composition is metadata only: it does not encode bytes, inspect payloads, or validate type-specific parameter semantics. `APPLICATION_JSON` has no charset by default; [RFC 8259 §11](https://www.rfc-editor.org/rfc/rfc8259.html#section-11) defines no charset parameter for JSON. A bare multipart name does not supply its required boundary. Use core's retained String content-type overloads for arbitrary parameters and multipart boundaries.

Core accepts `MediaType` in `Response.body(mediaType, bytes)` and `startStream(mediaType)`. Byte output remains unchanged; `text(String)` and `Stream.write(String)` still encode UTF-8. Applications own serialization/deserialization and must ensure declared metadata matches their bytes.

The library includes all **258 named entries** from the [IANA HTTP Field Name Registry](https://www.iana.org/assignments/http-fields/), last updated **2026-08-28** and retrieved **2026-09-18**. Every constant has a short description, registry status, and specification reference. Values preserve the registry spelling; field names are case-insensitive on the wire.

This includes provisional, deprecated, obsoleted, and reserved named entries for a complete inventory. In particular, `CLOSE` is reserved and must not be sent as a field. The registry's `*` reservation is excluded because it is a wildcard marker rather than a usable field name. Application-specific unregistered names and HTTP/2 or HTTP/3 pseudo-headers are outside this inventory. Inclusion is not a recommendation to use historical fields.

`CTA_COMMON_ACCESS_TOKEN` links to IANA because its registration lists contacts without a specification. Other constants link to the specification cited by IANA; CMCD links use the current first-party CTA-5004-B publication because the registered PDF URL returns HTTP 404. Draft references are preserved as drafts rather than guessing eventual RFC numbers.

Constant identifiers uppercase the registered name and replace hyphens with underscores, for example `Content-Type` becomes `CONTENT_TYPE`.

`HttpHeaders` is an immutable value class. Use `HttpHeaders.CONTENT_TYPE.value()` to obtain the registered wire name (`Content-Type`), or declare a reusable application field with `static final HttpHeaders TENANT = HttpHeaders.of("X-Tenant")`. `of(String)` accepts the RFC 9110 token grammar only: ASCII letters, digits, and `!#$%&'*+-.^_`|~`; null throws `NullPointerException` and malformed names throw `IllegalArgumentException`. It does not trim, register, or normalize supplied names, so `value()` preserves the supplied outbound spelling.

Use `HttpHeaders.httpHeader("content-TYPE")` to look up a built-in wire name with ASCII case-insensitive matching; it returns `Optional<HttpHeaders>`. Unknown names, null, empty strings, and non-ASCII input return `Optional.empty()`. Input is not trimmed, and Java constant identifiers such as `CONTENT_TYPE` are not wire names. Application-defined values are intentionally not registered by `of(String)`.

All 39 entries marked **IANA status: obsoleted** carry the standard `@Deprecated` annotation and a Javadoc deprecation notice. They remain available through lookup for recognizing historical traffic. Entries with other IANA statuses retain their documented status without that annotation.

For a known header, use `HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)`. It compares the wire name without creating a normalized string and returns `false` for null, non-ASCII input, or a different name. Whitespace is not trimmed. `equals(Object)` and `hashCode()` compare header names without case sensitivity, so differently cased custom values work as map or set keys.

## HTTP methods

`HttpMethods` contains the named entries in the [IANA HTTP Method Registry](https://www.iana.org/assignments/http-methods/), retrieved 2026-09-18. Each constant links to its specification; the reserved `*` entry is excluded. `PRI` identifies the HTTP/2 preface token and is not an ordinary application request method.

Use `HttpMethods.GET`, `HttpMethods.PATCH`, or another constant when registering routes. `value()` returns its wire spelling, including hyphens for `BASELINE_CONTROL` and `VERSION_CONTROL`. `httpMethod(String)` returns an `Optional`, with empty results for null or unrecognized tokens. Methods are **case-sensitive**: `GET` is recognized, `get` is not. No trimming or normalization is performed. Java's generated `valueOf` retains its normal enum-identifier behavior.

## HTTP status codes

`HttpStatusCodes` contains all **62 named codes** from the [IANA HTTP Status Code Registry](https://www.iana.org/assignments/http-status-codes/), last updated **2025-09-15** and retrieved **2026-09-18**. Each constant links to its specification. Unassigned codes and the unused reservations **306** and **418** are excluded. Temporary **104 Upload Resumption Supported** is included with its registration expiry of **2026-11-13** documented. **305 Use Proxy**, deprecated by RFC 9110, and **510 Not Extended**, obsoleted in IANA, are retained with `@Deprecated`.

Use `HttpStatusCodes.NOT_FOUND.value()` for `404`, and `reasonPhrase()` for `Not Found`. `httpStatusCode(int)` returns `Optional<HttpStatusCodes>`, empty for any unknown or out-of-range integer. Java's generated `valueOf(String)` keeps its normal enum-identifier behavior. Classification is available through `isInformational()`, `isSuccess()`, `isRedirection()`, `isClientError()`, `isServerError()`, and `isError()`.

Names follow the current registry, including `CONTENT_TOO_LARGE` (413) and `UNPROCESSABLE_CONTENT` (422). Registry lifecycle notes are documented separately from `reasonPhrase()`.

## HTTP exceptions

Each of the **39 named error codes** has a public, top-level exception class in `io.github.suppierk.shoostr.http.exceptions`. The abstract base classes provide three levels of catching:

```text
RuntimeException
└── HttpException
    ├── HttpClientException
    │   ├── BadRequestException             (400)
    │   ├── NotFoundException               (404)
    │   └── ...one class for every named 4xx status
    └── HttpServerException
        ├── InternalServerErrorException   (500)
        ├── ServiceUnavailableException    (503)
        └── ...one class for every named 5xx status
```

Catch specific errors before their families, and the common base last:

```java
try {
  loadOrder();
} catch (NotFoundException exception) {
  // Handle a missing order.
} catch (HttpClientException exception) {
  // Handle other client errors.
} catch (HttpServerException exception) {
  // Handle server errors.
}
```

Alternatively, `catch (HttpException exception)` handles either family. `statusCode()` returns the fixed enum value. Java's single inheritance prevents these classes from also extending unrelated JDK exceptions such as `IllegalArgumentException`; the HTTP hierarchy takes precedence.

Every concrete class provides `()`, `(String message)`, `(Throwable cause)`, and `(String message, Throwable cause)` constructors. A null message selects the status reason phrase; an empty message stays empty. Causes, normal stack traces, suppressed exceptions, and Java serialization are preserved. Messages and causes are **diagnostic information**, not automatically safe response bodies. A cause-only constructor uses the reason phrase instead of the cause's message.

The status classes do not add response rendering, header maps, or transport dependencies. Unless an application-wide custom handler matches, core maps an uncaught `HttpException` to its status and standard reason phrase before response commitment, discarding staged headers and body. Built-in responses do not expose diagnostic messages or causes. If the reason phrase exceeds the configured response-body limit, the status is sent with an empty body. An exception after streaming has committed aborts the stream without replacing the status or flushing pending data. Custom handlers write their own headers and body through Response. See the [core response contract](../README.md) for lifecycle details, including application-owned authentication challenges.

## Cookies

`Cookie` is an immutable value for a single outbound Set-Cookie field. It has no Jetty dependency:

```java
var cookie = Cookie.secure("__Host-session", "already-encoded-value")
    .withHttpOnly(true)
    .withSameSite(Cookie.SameSite.LAX)
    .withMaxAge(3600);
response.cookie(cookie);
response.removeCookie(cookie); // Keeps its scope and security attributes.
```

`new Cookie(name, value)` defaults to Path=/, host-only, session lifetime, no Secure/HttpOnly flag and no SameSite attribute. `Cookie.secure(name, value)` enables Secure at construction, allowing security-prefixed names without constructing an invalid intermediate value. Fluent `with...` methods return validated copies; the canonical constructor accepts all attributes together.

Names are HTTP tokens. Values accept RFC6265 cookie octets, optionally enclosed in explicit outer quotes, with no automatic URL/base64 encoding. Paths must be ASCII, root-relative, without controls, semicolons or trailing whitespace. Domains must be ASCII DNS labels; one leading dot is removed and case is normalized. Convert internationalized names explicitly before construction. These syntax checks do not prove domain ownership or browser acceptance. [RFC6265](https://www.rfc-editor.org/rfc/rfc6265.html#section-4.1).

`maxAge=-1` omits Max-Age; nonnegative long values are supported and browsers can cap retention. Positive ages are emitted without calculating an absolute date. `withExpires(Instant)` sets an independent GMT date with seconds precision, within years1601–9999. Max-Age takes precedence when both are set. Zero age always emits Max-Age=0 and an epoch Expires, even if a future explicit date was supplied. `expired()` also empties the value. Nullable Domain/SameSite/Expires attributes can be cleared by passing null.

SameSite=None requires Secure. The `__Secure-` prefix requires Secure; `__Host-` additionally requires Path=/ and no Domain. Prefix recognition is case-insensitive, matching the browser storage rules in [cookie draft22](https://datatracker.ietf.org/doc/html/draft-ietf-httpbis-rfc6265bis-22#section-5.7). These draft constraints are distinguished from the published RFC6265 grammar. Flags describe client policy; the primitive does not infer a connection scheme or provide authentication/session/CSRF protection.

`headerValue()` produces one field value. Core's `Response.cookie` replaces matching name/domain/path fields and preserves other scopes. No response-level Expires or Cache-Control header is added. See the [request/response cookie API](../README.md#cookies) and its real-HTTP tests.
