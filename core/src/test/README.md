# HTTP contract coverage from upstream scenarios

`HttpCompatibilityTest` adds black-box JUnit 5 cases for supported behavior identified while reviewing Javalin 7.2.3 and Jooby 4.5.4 tests. These are new implementations using this framework's API and the JDK HTTP client; no upstream test helpers, framework implementations, or test dependencies were copied.

| Local test | Upstream behavioral reference | Local adaptation |
| --- | --- | --- |
| `dispatchesEveryExplicitlyRegisteredVerb` | Javalin [`TestRouting`, mapped verbs](https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/test/java/io/javalin/TestRouting.kt) | Seven explicit verbs; HEAD verifies handler execution through a header and body suppression on the wire. No implicit HEAD fallback is assumed. |
| `matchesLiteralPunctuationWithoutInterpretingItAsPatternSyntax` | Javalin [`TestRouting`, literal colon and path matching](https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/test/java/io/javalin/TestRouting.kt) | Literal punctuation, query exclusion, and suffix misses through the actual listener. |
| `preservesParameterValuesAtTheHttpBoundary` | Javalin [`TestRouting`, UTF-8 and parameter casing](https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/test/java/io/javalin/TestRouting.kt) | Supported whole-segment parameter syntax, percent decoding, literal plus, and case-sensitive literal prefixes. |
| `rejectsEncodedPercentBeforeInvokingTheHandler` | Additional local transport-policy regression discovered while adapting parameter scenarios | Percent-encoded percent is rejected before the handler; no URI compliance setting is weakened to claim parity. |
| `cachesRequestBytesWithoutExposingMutableStorage` | Javalin [`TestMaxRequestSize`, repeated body reads](https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/test/java/io/javalin/TestMaxRequestSize.kt); Jooby [`ByteArrayBodyTest`, content access and empty bodies](https://github.com/jooby-project/jooby/blob/v4.5.4/jooby/src/test/java/io/jooby/internal/ByteArrayBodyTest.java) | Repeated bytes/text access, empty and UTF-8 bodies, plus our defensive-copy guarantee; assertions use a real HTTP request rather than mock Context objects. |
| `acceptsTheByteLimitAndRejectsOneAdditionalByte` | Javalin [`TestMaxRequestSize`, size boundaries](https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/test/java/io/javalin/TestMaxRequestSize.kt) | Exact byte limit and limit+1 using multibyte text, with known-length and unknown-length JDK publishers. |
| `readsAndReplacesHeadersCaseInsensitively` | Javalin [`TestResponse`, setting headers and overwriting values](https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/test/java/io/javalin/TestResponse.kt); Jooby [`DefaultContextTest`, response headers/status](https://github.com/jooby-project/jooby/blob/v4.5.4/jooby/src/test/java/io/jooby/DefaultContextTest.java) | Header spelling variants, missing request header, replacement rather than append, selected status, and UTF-8 Content-Length. |

Both upstream repositories carry Apache License 2.0: [Javalin license](https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/LICENSE), [Jooby license](https://github.com/jooby-project/jooby/blob/v4.5.4/LICENSE). Preserve applicable license/notice requirements if future work copies source instead of independently implementing scenarios.

This suite does not run Javalin or Jooby and is not proof of complete compatibility. Existing local tests remain authoritative for intentional differences: strict trailing slashes, explicit HEAD registration, 405 with Allow, duplicate normalized route shapes, and handler-scoped request/response ownership.

Query/form parsing and repeated raw headers now have 43 cases in `RequestParametersTest`; global mapper selection, reset/fallback behavior, and typed media output are covered by `ExceptionHandlerTest` and `MediaTypeResponseTest`. See the [current HTTP contract checklist](../../HTTP_CONTRACT.md) for tested behavior versus source-derived expectations. `ResponseMetadataTest` covers response inspection, append/remove, distinct Set-Cookie fields and redirects, including input validation and lifecycle boundaries. `CookieTest` covers request parsing/snapshots, validated response values, scoped replacement/deletion, error/lifetime boundaries, and a JDK CookieManager round trip. `SessionTest`, `CsrfTest`, `LifecycleHooksTest`, `ServerSentEventsTest`, `WebSocketRoutesTest` and `TransportTest` now exercise the later features through real listeners and clients. Do not add disabled tests or silently import different upstream defaults.

Run the focused suite with the repository wrapper:

```sh
cmdshape ./gradlew :core:test --tests io.github.suppierk.shoostr.HttpCompatibilityTest
```

Normal `build` also runs these tests with Checkstyle and Spotless. No extra test harness is required.

## Lifecycle completion coverage

`MediaTypeResponseTest` exercises the public Request/Response HTTP seam for Accept selection:
absent/repeated fields, comma lists, wildcards, q values, parameter precedence, malformed input,
406, Vary merging and streaming commitment. Candidate selection only supplies outbound metadata;
the tests retain raw caller bytes to prove that negotiation does not introduce serialization or
transcoding.

`MultipartRequestTest` uses real HTTP and a raw disconnecting socket to cover text fields, binary
and repeated uploads, empty filenames, raw-body exclusivity, explicit persistence, aggregate and
per-file limits, malformed boundaries, and temporary-file cleanup on success, handler failure and
disconnect.

`ShoostrLifecycleTest` also exercises ordered native Jetty configuration through actual listeners, configured header rejection, additional connectors and handler wrappers, startup failure/ownership guards, and immutable registration after startup. Graceful-shutdown cases hold finite and streaming responses with latches, observe 503 rejection during native drain, let quiet active work finish during Shoostr.close, and exhaust configured native budgets with a handler that ignores interruption. The latter verifies cancellation and cleanup without an unbounded executor wait; it does not claim Java can forcibly terminate arbitrary handler code. Test clients normally close before Shoostr; shutdown tests deliberately control the opposite order.

`LifecycleHooksTest` exercises the public Shoostr/Request callbacks and actual HTTP listener. It covers composed route templates, ordered gates, checked/HTTP/fatal errors, stream ownership, body bounds, startup races, observer isolation, generated misses, and HEAD/OPTIONS/method-override characterizations from the [contract checklist](../../HTTP_CONTRACT.md).

A small-window raw socket holds a 16MiB finite response in flight, then disconnects; the completion callback must remain pending until transport termination. A separate stream test holds the route active across a disconnect and verifies that application finalization is also required. These tests control the peer through the public HTTP seam; they do not mock internal completion classes. Fatal-abort tests use POST so the JDK client's automatic GET retry cannot be mistaken for duplicate observation of a single admitted request. Existing finite-transport tests remain regression evidence; no new transport-testing extension was added to the public API.

## Request metadata coverage

`RequestMetadataTest` uses the public Shoostr/Request API, JDK client and raw sockets for authority forms restricted by that client. It covers raw query/header/path snapshots, IPv6/default ports/legacy Host, URI scheme versus actual transport security, forwarding-header spoof resistance at default configuration, eight overlapping requests with independent attributes/principals, mapper access and lifetime boundaries. The absolute HTTPS target over plain TCP drove a failing test and a switch to direct endpoint security. These cases are independently authored from pinned Jetty semantics and the existing request contract; no internal adapter mocks are used.
