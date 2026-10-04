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

Issue 76 T01 adds independently authored raw HTTP regressions in
`StaticResourcesTest.rejectsUnsafeEncodedPathsUnderFilesystemMount` and
`rejectsUnsafeEncodedPathsUnderClasspathMount`, inspired by pinned Javalin 7.2.3
`TestStaticFilesPathTraversal`. Each mount first serves a known public asset;
double-encoded traversal, encoded backslash traversal and NUL then receive 400
without exposing a real resource outside that mount. These assertions retain
Shoostr's default Jetty URI policy, not Javalin-specific response bodies or statuses.

Issue 76 T15 adds `CsrfTest.rejectsFileOnlyMultipartWithoutCsrfTokenBeforeInvokingHandler`,
inspired by pinned Jooby 4.5.4 `Issue1815`. A live request first establishes and resumes
the same CSRF-token session. A correctly framed file-only multipart POST with its cookie
and a same-origin Origin, but no token, must return 403 before business logic runs.

Issue 76 T07 adds mounted-file compression cases in `StaticResourcesTest`:
`compressesMountedFilesWhenGzipIsAccepted`,
`servesMountedFilesUnencodedWhenGzipIsNotAccepted` and
`returnsNotModifiedForMountedFileValidatorsWithCompressionEnabled`.
These independently adapt pinned Javalin 7.2.3 `TestCompression` and Jooby 4.5.4
`Issue1656`: exact UTF-8 bytes after gzip decoding, explicit identity/gzip exclusion,
Accept-Encoding variation, and bodyless 304 using the returned ETag or Last-Modified
under both gzip and identity. They do not introduce dynamic-body auto-ETags.

Issue 76 T02 independently adapts pinned Javalin 7.2.3 `TestBeforeAfterMatched`
in `StaticResourcesTest.runsMatchedHooksInOrderWithTheStaticMountPattern`,
`runsMatchedHooksInOrderWithTheSpaFallbackMountPattern` and
`skipsMatchedHooksWhenNoMountedResourceMatches`. Live responses expose callback
order and the mount route pattern for ordinary files and SPA fallback. Misses
inside and outside the mount invoke neither callback, with a real static hit
first proving that the callbacks are installed.

Issue 76 T03 adds `TransportTest.admitsATrustedClientCertificateWhenMutualTlsIsRequired`,
`rejectsAnAbsentClientCertificateDuringMutualTlsHandshake` and
`rejectsAnUntrustedClientCertificateDuringMutualTlsHandshake`, independently
adapting pinned Javalin 7.2.3 SSL trust/certificate scenarios through native
`Shoostr.tls` configuration. Trusted client identity succeeds over HTTPS; absent
or distinct untrusted identity fails a real TLS 1.2 handshake, with a trusted
HTTPS request using the same TLS version/listener as a positive control. The
successful response also verifies the presented client chain. A timeout is not
accepted as rejection. The JDK creates the temporary untrusted client identity;
its certificate differs from the trusted fixture and its key manager selects it
for the configured trusted issuer name. No certificate-loader convenience is added.

Issue 76 T04 independently adapts pinned Javalin 7.2.3 `TestWsContext` and
`TestWsRouting` in `WebSocketRoutesTest.passesHandshakeMetadataAndExistingSessionToTheListenerFactory`
and `rejectsWebSocketRoutesWithWrongLiteralCaseBeforeCallingTheFactory`.
A live session cookie survives an upgrade; the listener uses eagerly captured
query/cookie/session/header/authority values rather than retaining Request.
The case-sensitive miss has a successful upgrade/message positive control and
must leave the listener-factory count unchanged.

Issue 76 T05 independently adapts pinned Javalin 7.2.3 `TestWsLogging` in
`AccessLogTest.logsBoundedAcceptedWebSocketUpgradesWithoutHandshakeSecrets` and
`logsBoundedDeniedWebSocketUpgradesWithoutHandshakeOrFailureSecrets`.
Live upgrades with long credentials and path identifiers produce single bounded
101/403 records using the configured route template. Neither record includes
handshake secrets; denial also excludes exception details and skips the listener
factory. These tests cover Shoostr's terminal access log, not message-payload logging.

Issue 76 T06 independently adapts pinned Javalin 7.2.3 `TestStaticFilesEdgeCases`
in `StaticResourcesTest.servesExactMountedFileBytesOverTheConfiguredProtocol`,
`returnsMountedFileHeadMetadataWithoutBytesOverTheConfiguredProtocol` and
`returnsNotModifiedForAnUnchangedMountedFileAcrossResponseDates`.
Each uses real h2c, HTTPS/HTTP1.1 and TLS/HTTP2 requests, verifies the negotiated
version, and checks exact file bytes, bodyless HEAD metadata or ETag-based 304.
The conditional test controls response dates across the file modification time:
an unchanged file must retain its ETag even while Last-Modified is date-clamped.
This exposed a descriptor-relative ETag bug; its fix uses actual file metadata
for the tag without removing the Last-Modified clamp.

Issue 76 T08 adds
`RequestParametersTest.preservesOriginalBodyBytesWhenFormFieldsAreParsedFirst`,
independently adapting pinned Javalin 7.2.3 `TestBodyReading` form/body access ordering.
A live URL-encoded POST is parsed before raw access; repeated and escaped UTF-8
values decode correctly while subsequent text access and the exact byte echo
retain the original representation, not a re-encoded form.

Issue 76 T09 adds
`LifecycleHooksTest.exposesDecodedPathParametersToTheMatchedHookBeforeTheHandler`,
independently adapting pinned Javalin 7.2.3 `TestBeforeAfterMatched`.
A live encoded UTF-8 path with a literal plus is decoded by `onRouteMatched`;
the endpoint returns the value that hook stored in the public request attributes.
This proves availability before endpoint execution without private route internals.

Issue 76 T13 independently adapts pinned Javalin 7.2.3 `MicrometerPluginTest`
in the micrometer module's `MicrometerMetricsTest`. Separate live cases check
the complete method/route/status/error tag set for explicit 200/404/500,
unmatched 404, thrown failure 500, unfollowed 302 and a real conditional-file 304.
Templates or UNMATCHED replace private path values, and failure details are not
labels. Every request gets one completed timer count; conditional validation uses
the returned file ETag. Javalin's optional URI/outcome-tag switches are not added.

Issue 76 T14 adds
`ServerSentEventsTest.emitsTheQueryParameterAsExactEventBytesForPositiveAcceptHeaders`,
independently adapting pinned Javalin 7.2.3 `TestSse` positive Accept/query cases.
Single and mixed positive Accept fields both receive an exact UTF-8 event frame
whose data is read from the query in the SSE handler, with the event-stream
content type. This does not introduce an Accept rejection gate or detached streams.

Issue 76 T16 adds
`StaticResourcesTest.servesUtf8NamedClasspathFilesThroughEncodedPathsWithoutEscapingTheMount`,
independently adapting pinned Jooby 4.5.4 `Issue3070`. An encoded URL resolves
the UTF-8-named asset to its exact bytes in an isolated classpath directory;
a UTF-8-named adjacent resource stays unavailable below the mount. The test
uses live HTTP and restores the thread context classloader after cleanup.

Issue 76 T17 adds separate `MultipartRequestTest` cases
`preservesALargeUtf8MultipartFieldAsTextAndCleansItsTemporaryStorage` and
`preservesASameSizedNamedMultipartPartAsAFileAndCleansItsTemporaryStorage`,
independently adapting pinned Jooby 4.5.4 `Issue3464`. The same 190 KiB UTF-8
payload is submitted with and without a filename, keeping representation
metadata alike. Text and file classification remain distinct, and complete
bytes round-trip through live HTTP. Both parts exceed the configured memory
threshold; tests observe temporary storage during handling and its removal
after bounded request completion.

Issue 76 T18 adds `CorsTest` cases
`preservesCorsSharingWhenFormParsingRejectsUnsupportedMedia` and
`sharesAcceptedFormResponsesOnlyWhenAnAllowedOriginIsPresent`, independently
adapting pinned Jooby 4.5.4 `Issue2649`. Both exercise the same /form endpoint:
its offered form parser rejects JSON with 415 and accepts URL-encoded input
with 200. Separate live cases with and without Origin assert exact Vary and
sharing fields on rejection and success. Jooby's declarative JSON-consumes API
and automatic JSON decoding remain unoffered; no parity feature is introduced.

Issue 76 T19 adds
`TransportTest.mappedErrorsAndNotFoundResponsesKeepTheTlsHttp2ConnectionUsable`,
independently adapting pinned Jooby 4.5.4 `Issue2399`. A mapped 400, unmatched
404 and subsequent healthy response complete with exact bodies over real
TLS/HTTP/2. The public request-header hook captures each peer address,
including the unmatched request; all three share one TCP peer rather than
merely using one client that could reconnect. No private transport seam is used.

Issue 76 T20 adds separate `TransportTest` cases
`roundTripsANamedBinaryMultipartUploadOverTlsHttp2` and
`roundTripsRawJsonBytesOverTlsHttp2WithoutObjectConversion`, independently
adapting pinned Jooby 4.5.4 `Http2Test`. A real named 19 KiB binary upload retains
its filename and every byte; raw JSON retains whitespace, UTF-8 and escape
spelling. Both assert TLS and negotiated HTTP/2 through the live client.
Jooby's automatic JSON-to-object conversion remains unoffered.

Issue 76 T21 adds `ServerSentEventsTest`
`preservesLeadingWhitespaceInMultilineEventDataOnTheWire`, independently
adapting pinned Jooby 4.5.4 `Issue3479`. A live response asserts the exact UTF-8
frame for `café\n next`: the second data field contains the protocol separator
space and the original payload space. This is SSE data framing, not raw HTTP
body preservation.

Issue 76 T23 adds `CsrfTest`
`bypassesTokensForExplicitlyRegisteredSafeMethods` and
`rejectsMissingOrInvalidTokensForMutatingMethodsWithValidSessionAndOrigin`,
independently adapting pinned Jooby 4.5.4 `CsrfHandlerTest.testDefaultFilter`.
Live explicitly registered GET/HEAD/OPTIONS/TRACE handlers bypass tokens with
a real session and same-origin request. POST/PUT/PATCH/DELETE each have a
valid-token control followed by missing-token or invalid-token rejection before
business logic. This does not rely on implicit HEAD or OPTIONS handling.

Issue 76 T26 adds `ServerSentEventsTest`
`completesOneHundredLargeUtf8EventsWithoutLossDuplicationOrReordering`,
independently adapting pinned Jooby 4.5.4 `Issue2462`. The live handler sends
100 numbered events, each containing a 17 KiB UTF-8 payload. An independent
expected wire sequence checks the complete response's bytes, count and order
under default bounded streaming, with a request deadline and handler-owned
completion. This is correctness coverage, not a throughput benchmark.

Issue 76 T27 adds four separate `StaticResourcesTest` cases, independently
adapting pinned Jooby 4.5.4 `FeaturedTest.assets`:
`fallsBackBetweenFilesystemAndClasspathSourcesAtTheSamePrefix`,
`prefersTheFirstRegisteredStaticSourceWhenBothContainTheSamePath`,
`returnsNotFoundWhenNeitherComposedStaticSourceContainsThePath` and
`validatesTheFallbackResourceSelectedFromComposedStaticSources`.
Each runs with both registration orders and isolated temporary sources at one
URL prefix. Live requests cover source-exclusive content, first-registered
overlap selection, a controlled miss and bodyless conditional validation of the
resource found only in the second source. Jooby's per-path cache callback
remains unoffered.

Issue 76 T28 adds separate `Pac4jTest` cases
`authenticatesWithoutCreatingASessionWhenSessionsAreEnabled` and
`createsAndResumesASessionOnlyWhenTheAuthenticatedHandlerRequestsIt`,
independently adapting pinned Jooby 4.5.4 `Issue3633`. Live Basic authentication
exposes the validated identity without a session or cookie despite enabled
session support. Explicit handler creation emits one session cookie; a later
authenticated request resumes the handler's stored marker without a new cookie.
The configured direct client emits no provider cookies. Indirect clients remain
unsupported.

Issue 76 T29 adds `WebSocketRoutesTest`
`sendsAServerFirstMessageThenRepliesWithSuccessfulNativeSendCallbacks`,
independently adapting pinned Jooby 4.5.4 `Issue2858`. The live client receives
the opening message before sending any text, then sends a ping and receives its
exact reply. Both native send callbacks must complete successfully; callback
errors fail the awaited futures. The fixture uses a public listener class,
matching Jetty's reflective callback-access requirement, and explicitly aborts
the owned client socket during cleanup. No callback or protocol wrapper is added.

Query/form parsing and repeated raw headers are covered in `RequestParametersTest`; global mapper selection, reset/fallback behavior, and typed media output are covered by `ExceptionHandlerTest` and `MediaTypeResponseTest`. See the [current HTTP contract checklist](../../HTTP_CONTRACT.md) for tested behavior versus source-derived expectations. `ResponseMetadataTest` covers response inspection, append/remove, distinct Set-Cookie fields and redirects, including input validation and lifecycle boundaries. `CookieTest` covers request parsing/snapshots, validated response values, scoped replacement/deletion, error/lifetime boundaries, and a JDK CookieManager round trip. `SessionTest`, `CsrfTest`, `LifecycleHooksTest`, `ServerSentEventsTest`, `WebSocketRoutesTest` and `TransportTest` now exercise the later features through real listeners and clients. Do not add disabled tests or silently import different upstream defaults.

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
