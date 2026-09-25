# HTTP contract and Javalin compatibility checklist

Reviewed 2026-09-18 against local `6d666d9`, Javalin **7.2.3** (`javalin-parent-7.2.3`, commit `8c12b9f8a5ecf889b54db7d6b2a7539490ceb86b`) and our Jetty **12.1.11** runtime. The original source audit is followed by the lifecycle implementation described below. This is not a differential competitor runtime run or a performance comparison. [Published Javalin metadata](https://repo.maven.apache.org/maven2/io/javalin/javalin/maven-metadata.xml), [pinned source tree](https://github.com/javalin/javalin/tree/8c12b9f8a5ecf889b54db7d6b2a7539490ceb86b).

The target is complete framework feature coverage relative to Javalin and Jooby, preserving the explicitly selected contracts and exclusions. The implementation currently covers only a subset; this document does not claim source, binary, or full behavioral compatibility. Preserve the explicit request/response pair, literal-segment route precedence, Java 25, annotation-free production API, and framework-owned streaming lifetime. Feature gaps remain open until implementation and executable evidence close them; prior prioritization deferrals do not exclude them from the completion target.

**Aligned** means the stated subset agrees. **Different** describes a current local contract to preserve until an explicit change is selected. **Gap** means absent functionality worth prioritizing. **Excluded** records a deliberate scope decision. **Deferred** records unfinished work, unless the row explicitly identifies a chosen contract to preserve. Existing tests are evidence for our implementation; proposed tests below are not implemented assertions.

## Routes and dispatch

| Contract | Current implementation and evidence | Javalin 7.2.3 comparison | Disposition |
| --- | --- | --- | --- |
| Endpoint precedence | Literal segments take priority at each branch, with parameter fallback on suffix or method mismatch. `RouteCompositionTest.prefersLiteralRoutesAndContinuesAfterAPathOrMethodMismatch`, `RoutePatternTest.prefersLiteralSegmentsAtEachDepthAndFallsBackAfterMethodOrSuffixMismatch`. | Endpoint registration order determines selection. [Routing tests][routing] | Deliberate difference: literal-first precedence. |
| `path` composition | Nested explicit Routes scopes, inherited parameter names, reusable groups and pathless endpoints. `RouteCompositionTest.composesAndReusesGroupsWithOptionalBoundarySlashes`. | ApiBuilder supports nested path groups and pathless endpoints. [Builder tests][builder] | Aligned experience; our scope object differs from Javalin's builder mechanism. |
| Registration lifetime | Synchronized registration freezes at startup; retained scopes reject mutations; runtime matching is immutable. `RoutesConcurrencyTest`, `ShoostrLifecycleTest`. | Version 7 configuration defines routes before startup. [Migration guide][migration] | Aligned static-runtime direction; not a claim of identical concurrency semantics. |
| Duplicate definitions | Reject equivalent normalized shape for a method, even `/a/{x}` versus `/a/{y}`. `RoutePatternTest.rejectsEquivalentPatternsForTheSameMethod`. | Duplicate detection compares method plus supplied path spelling. [PathMatcher][matcher] | Different: retain the stricter ambiguity rejection. |
| Route grammar | Literals and whole nonempty segment `{name}` only. Mixed segments and catch-all syntax rejected. `RoutePatternTest.rejectsUnsupportedOrAmbiguousSyntax`. | Wildcards, slash-capturing `<name>`, and mixed-segment patterns. [Parser][parser] | Deferred until a concrete use case requires broader patterns. |
| Case and trailing slash | Literal case and trailing slash matter. Group joining normalizes registration boundaries only. `HttpCompatibilityTest.preservesParameterValuesAtTheHttpBoundary`, `RoutePatternTest.distinguishesEmptySegmentsTrailingSlashAndMethodFailures`. | Case-sensitive by default; ignores trailing slashes by default. Optional repeated-slash normalization is off. [Router configuration][routerconfig] | Case aligned; trailing slash different. Do not silently enable normalization. |
| Encoded paths | Jetty canonical encoded path is matched; selected parameter decoding happens once and preserves literal `+`. Ambiguous `%25`/double-encoding and repeated slashes can be rejected before core. `HttpCompatibilityTest.rejectsEncodedPercentBeforeInvokingTheHandler`, `RouteCompositionTest.retainsTransportRejectionOfAmbiguousPaths`. | Routing uses a path parser and configured Jetty/Servlet URI policy. [Parser][parser], [routing tests][routing] | Partial overlap; do not infer wire acceptance from matcher-only tests or loosen Jetty URI compliance. |
| HTTP method registration | Enum or exact known String token; unknown/lowercase registration rejected. Incoming method remains raw; no method-override header. `RouteCompositionTest.rejectsUnrecognizedWireMethodsDuringRegistration`. | Extensible HandlerType; Context method consults `X-HTTP-Method-Override`. [HandlerType][methods], [Context][context] | Different: custom token support is a possible later feature; method override is not adopted. |
| HEAD | Explicit HEAD handler executes; Jetty suppresses wire body. GET alone does not register HEAD, so matching path/wrong method produces HTTP 405. `HttpCompatibilityTest.dispatchesEveryExplicitlyRegisteredVerb`; Shoostr dispatch source. | Explicit HEAD wins. Without one, a matching GET produces an implicit success without invoking GET; ordinary hooks can still change status. [DefaultTasks][tasks], [routing HEAD test][routing] | Different: preserve explicit HEAD. Do not describe Javalin's fallback as executing GET. Add a dedicated HTTP negative test for GET-only registration. |
| OPTIONS | Explicit OPTIONS works when CORS is disabled. With `Shoostr.cors(...)`, recognized preflights run application-wide header admission and CORS policy, then answer without scoped authentication or route-handler execution; the actual request still runs both. `CorsTest` covers enabled and disabled behavior. | Automatic method discovery is an opt-in plugin. [HttpAllowedMethodsPlugin][allowed] | Explicit OPTIONS and opt-in CORS preflight have distinct dispatch paths. |
| 404/405 | Unknown path404; known path/wrong method405 with union Allow across matching patterns. `RouteCompositionTest.distinguishesNotFoundFromMethodNotAllowedAndIncludesAllMatchingMethods`. | Default wrong-method404;405 is configurable. [HttpConfig][httpconfig], [DefaultTasks][tasks] | Different default; retain standards-oriented405/Allow behavior. |

## Input and output

| Contract | Current implementation and evidence | Javalin comparison | Disposition |
| --- | --- | --- | --- |
| Query/form value shape | First/null, repeated ordered values, case-sensitive names, separate query/form maps; deeply immutable returned collections. `RequestParametersTest`. | Similar first/list/map accessors. [Context][context], [request tests][requesttests] | Aligned basic API; local immutability is our own guarantee. |
| Query/form decoding | UTF-8, `+` → space, decode once, first equals delimiter; empty values/names preserved; empty `&` pairs ignored. | Request charset can influence decoding; empty split segments retained. [Context][context], [servlet utilities][servletutil], [encoding tests][encodingtests] | Different edge cases; retain deterministic UTF-8 and ignored empty pairs. |
| Malformed parameters | Bad percent or UTF-8 rejects the entire accessed collection with400; no partial maps. | Malformed percent pairs can be omitted while other pairs survive. [Request tests][requesttests], [servlet utilities][servletutil] | Different intentional strictness. |
| Form representations | UTF-8 URL-encoded and multipart text fields; `Upload` exposes repeated file parts and explicit persistence. Multipart and raw body access are exclusive; temporary parts close at transport completion. | Multipart supported; strict-content-type setting affects form handling. [Context][context], [HttpConfig][httpconfig] | Local bounded multipart contract; no built-in object conversion. |
| Resource bounds | 1MiB default buffered body;1000 pairs each for query/form including repeats, separately overridable;413 byte overflow,400 pair overflow. Retry after oversized chunked input remains rejected. `RequestParametersTest`, `HttpCompatibilityTest.acceptsTheByteLimitAndRejectsOneAdditionalByte`. | Own configurable request-size behavior. [Size tests][sizetests] | Different defaults/policies; preserve our tested bounds. |
| Headers | First header or immutable list of raw repeated fields; case-insensitive name lookup, no comma splitting. Response setter replaces; framework owns framing headers. `RequestParametersTest.readsImmutableRepeatedHeadersCaseInsensitively`, `HttpCompatibilityTest.readsAndReplacesHeadersCaseInsensitively`. | Context header/headerMap exposes first values; raw Servlet request supplies repetitions. [Context][context], [response tests][responsetests] | Comparable capability, different convenience API. ResponseMetadataTest covers inspection, immutable case-insensitive snapshots, append/remove and distinct Set-Cookie fields; framing remains framework-owned. |
| Raw body and serialization | Cached defensive bytes; UTF-8 text; serializers, converters, and business validation application-owned. `HttpCompatibilityTest.cachesRequestBytesWithoutExposingMutableStorage`. | Broader object/validator API. [Context][context] | Intentional scope exclusion, not codec work waiting to happen. |
| Finite output | Staged until handler succeeds, then asynchronously submitted. Bodyless statuses enforced; HEAD suppressed. `HttpContractTest.stagesFiniteBodyUntilHandlerReturnsAndClosesApplicationAccess`, `completesFiniteResponsesAsynchronously`. | Context result handling coexists with direct Servlet output and async APIs. [Response tests][responsetests], [servlet context][servletcontext] | Different ownership/API; keep explicit semantics. |
| Streaming | startStream commits during the handler; bounded writes/flushes; framework ends or aborts at handler exit; no retained writer. `HttpContractTest.streamsBeforeHandlerReturnsAndFlushesRemainderOnReturn`, `abortsCommittedStreamOnFailure`. | Servlet streaming, futures/async, SSE and WebSocket APIs support other lifetimes. [Servlet context][servletcontext], [SSE tests][ssetests] | Deliberate lifecycle difference; SSE and WebSocket now have dedicated APIs with their own documented lifetimes. |
| Seekable files | `Response.file(Path, contentType)` streams a selected Jetty resource, supports one `bytes` range and conditional validators, and remains framework-owned until terminal callback completion. `RangeConditionalResponseTest` covers 200/206/304/412/416, HEAD, suffix/open-ended ranges, validator precedence, and single-range fallback policy. | Resource handlers can add multipart ranges and mounted static trees. | Deliberately one range only; malformed/multiple fields are ignored as a full 200 response. |
| Media types | Extensible immutable outbound MediaType plus retained String overloads; metadata does not transcode bytes. Response.negotiate selects supplied representations from Accept, adds Vary: Accept and uses 406 only when a valid explicit preference excludes every candidate. `MediaTypeResponseTest`. | Content type supplied through Context APIs. [Context][context] | Local typed convenience plus strict RFC9110 Accept selection; no codecs or generic object rendering. |

## Errors and lifecycle

| Contract | Current implementation and evidence | Javalin comparison | Disposition |
| --- | --- | --- | --- |
| Functional exception handling | Application-wide callbacks; most-specific directly thrown superclass; no cause unwrapping; request stays readable until finalization. `ExceptionHandlerTest`. | Functional exception mapping, CompletionException unwrapping, a built-in HTTP exception mapper, and independent status-based error mapping. [Exception mapper][exceptionmapper], [DefaultTasks][tasks] | Aligned mapping subset. Our broad Exception callback can catch HTTP errors; Javalin’s more-specific built-in mapper takes precedence over a broad callback. No cause unwrapping or path-scoped handlers here. |
| Default errors | Clear staged success body/headers; safe status reason text, no diagnostics; committed failure aborts. Mappers cannot reopen committed output. `HttpExceptionHandlingTest`, `ExceptionHandlerTest`. | Different default response negotiation and fatal-error configuration. [Exception tests][exceptiontests], [RouterConfig][routerconfig] | Preserve sanitization and ownership; do not copy error representations wholesale. |
| Router misses | Core generates404/405 directly; these are not exceptions and do not invoke exception callbacks. | Unmatched routing passes through its exception/error tasks. [DefaultTasks][tasks] | Different; `afterRequest` observes misses and application-wide status handlers can customize generated errors. |
| Matched route metadata | Request.routePattern() exposes the original composed named template; retained once at registration. LifecycleHooksTest covers route and global error handling. | Matched endpoint metadata is available. [Context][context] | Implemented foundation for bounded metric labels and route-aware policy. |
| Auth gates/middleware | Shoostr.beforeRouteHandler runs ordered matched-route gates; rejection skips business logic and uses global exception mapping. Request.principal carries identity; the optional pac4j adapter supports configured direct clients and authorizers. LifecycleHooksTest, RoutePolicyTest and Pac4jTest cover behavior. | Before/matched/wrapper APIs and role metadata support policies. [DefaultTasks][tasks], [handler wrapper][wrapper] | Shared gate and optional provider adapter implemented; credentials and identity stores remain application-owned. |
| Completion metrics/logging | Shoostr.afterRequest publishes RequestOutcome after terminal transport observation. Optional Micrometer and OpenTelemetry adapters, plus AccessLog, use this lifecycle. LifecycleHooksTest and adapter tests cover finite/streaming output, misses, errors and disconnects. | Request logger is separate from ordinary after handlers. [Request logger config][logger] | Completion summary and optional integrations implemented; applications own registries, exporters and providers. |
| Native server configuration | Ordered Shoostr.modifyHttpConfiguration and Shoostr.modifyServer callbacks apply after defaults, before startup. Additional connectors and handler wrappers retain Shoostr ownership and dispatch. ShoostrLifecycleTest exercises real listeners and failure cleanup. | Native Server/HttpConfiguration consumers, connector factories and other servlet-specific settings. [JettyConfig](https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/main/java/io/javalin/config/JettyConfig.kt) | Aligned callback model; Jetty remains the selected backend. No servlet configuration or engine adapter is implied. |
| Consumer testing | Separate `shoostr-test-support` artifact supplies `TestServer.start(Consumer<Shoostr>)`, an ephemeral-port fixture closed by try-with-resources. `TestServerTest` checks isolation, cleanup and stop errors; `consumerSmoke` publishes base and optional artifacts and runs standalone Java 25 JUnit tests for pac4j, Micrometer and OpenTelemetry. | Javalin documents programmatic test servers; Jooby provides an annotation-based JUnit extension. [Javalin testing](https://javalin.io/documentation#testing), [Jooby integration testing](https://jooby.io/v3/#testing) | The separate fixture keeps testing APIs out of `core`; the external consumer verifies actual publication and base dependency isolation. |
| Graceful shutdown | Native GracefulHandler rejects new work during drain and tracks admitted responses; five-second project grace and native one-second shutdown idle defaults, independently overridable. ShoostrLifecycleTest covers HTTP/1.1 finite/streaming drain and native deadline exhaustion. TransportTest covers TLS/HTTP/2 finite/streaming drain, forced cancellation, h2c session cleanup and failed-start cleanup. | The pinned Javalin server does not set a positive native stop timeout. Jooby's optional GracefulShutdown has a distinct application drain phase. [Javalin server](https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/main/java/io/javalin/jetty/JettyServer.kt), [Jooby extension](https://github.com/jooby-project/jooby/blob/v4.5.4/jooby/src/main/java/io/jooby/GracefulShutdown.java) | Explicit local policy. Native idle and stop timers differ; arbitrary lifecycle components or ignored interruption cannot be given a strict termination guarantee. Liveness and readiness remain application-defined routes. |
| Other everyday HTTP features | Response inspection, redirects, cookies, negotiation, multipart uploads, streaming input/file output, ranges and static file serving are exercised by public HTTP tests. | Available across Context and optional features. [Context][context] | Chosen byte/UTF-8 serialization, strict routing and resource ownership contracts remain distinct. Welcome files and SPA fallback are explicit static-mount options; OpenAPI and MCP are separate open gaps. |
| SSE | `Routes.sse` registers a GET endpoint; `Response.startEventStream` frames UTF-8 events and comments through the bounded handler-owned stream. `ServerSentEventsTest` covers wire framing, Last-Event-ID, disconnect and shutdown. | Javalin supports SSE and optional detached keep-alive. [SSE tests][ssetests] | Different lifetime: the handler remains active and its return ends the stream; replay remains application-owned. |
| WebSocket/MCP | `Routes.websocket` uses a per-upgrade `BiFunction<Request, ServerUpgradeResponse, Session.Listener>` after existing route admission. Jetty owns successful handshake, callbacks, frames and session lifetime. The container defaults to 32 pending outgoing frames per session, with native container/session overrides. `WebSocketRoutesTest` covers JDK client interoperation, route composition, GET coexistence, subprotocol selection, authentication/Origin denial, factory exception mapping, explicit demand, message limits, slow-peer outgoing-frame rejection, callback-driven concurrent producer ordering and shutdown. MCP remains absent. | Javalin supplies WebSocket APIs; MCP is a separate research question here. | WebSocket support uses the native Jetty listener instead of a framework callback wrapper. Applications bound producer queues and sequence concurrent sends. No delivery/performance inference from ordinary HTTP tests. |

## Executable follow-up checklist

The tests linked above already exercise our listener or explicitly identified routing unit seams. A source-derived claim is not presented as a new runtime result. Keep upstream inspiration independently implemented using our APIs; preserve attribution if copying any test text. [Existing test provenance](src/test/README.md).

| Priority | Scenario and expected local result | Coverage/action |
| --- | --- | --- |
| P0 | Literal-segment precedence with parameter fallback on suffix or method mismatch, path groups, strict slash/case, malformed parameter400, body limit413, safe errors and stream lifetime remain unchanged | Existing regression suites; run them with each implementation slice. |
| P1 | GET-only route receives HEAD →405 with Allow:GET; GET handler is not invoked. Explicit HEAD runs and returns no wire body. | Covered by LifecycleHooksTest.preservesMethodDispatchDefaults alongside the explicit HEAD case. |
| P1 | GET-only route receives OPTIONS →405; an explicit OPTIONS route can provide its own response. | Covered by LifecycleHooksTest.preservesMethodDispatchDefaults; no automatic CORS assumed. |
| P1 | POST with X-HTTP-Method-Override:GET remains POST and cannot bypass method-specific registration/auth policy. | Covered by LifecycleHooksTest.preservesMethodDispatchDefaults. |
| P1 | Expose original composed route template; ordered auth gates reject before endpoint invocation; one terminal summary after actual completion | Implemented through TDD and real HTTP/socket tests, including404/405, errors and disconnects. |
| P2 | Response append/remove and repeated Set-Cookie preserve distinct fields; redirects use selected status and validated Location | Implemented in ResponseMetadataTest, including invalid input atomicity, thread/lifetime confinement, streaming rejection, HEAD and error cleanup. CookieTest covers validated values, scopes, deletion and client-store interoperability. |
| P2 | Negotiation, multipart/resource cleanup and file/range output | Implemented and covered by MediaTypeResponseTest, MultipartRequestTest, StreamingInputOutputTest and RangeConditionalResponseTest. |
| Separate | SSE event framing and cancellation; WebSocket upgrade/auth/messages/backpressure; MCP transport/lifetime | SSE and WebSocket have separate HTTP/client tests and documented lifetime contracts. MCP remains an open integration gap; research alone is not runtime support. |

## Next slice and remaining decisions

The lifecycle stages `onRequestHeaders`, `onRouteMatched`, `beforeRouteHandler`, `afterRouteHandler`, `beforeResponseFlush`, `afterResponseFlush` and `afterRequest` are implemented with the documented request/response lifetime. Gates reject via existing exceptions and global mapping; completion uses a compact read-only record rather than a retained Request/Response.

Chosen differences remain: known HTTP method tokens, whole-segment route parameters, strict path matching, explicit HEAD/OPTIONS, global error mapping and handler-owned streams. Conversion hooks remain deprioritized. OpenAPI (issue 35) and MCP integration (issue 36) remain unimplemented under the user's hold; issue 09 is feasibility research only. A separately resumed k6 comparison is required before any performance ranking.

[routing]: https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/test/java/io/javalin/TestRouting.kt
[builder]: https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/test/java/io/javalin/TestApiBuilder.kt
[matcher]: https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/main/java/io/javalin/router/matcher/PathMatcher.kt
[parser]: https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/main/java/io/javalin/router/matcher/PathParser.kt
[routerconfig]: https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/main/java/io/javalin/config/RouterConfig.kt
[httpconfig]: https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/main/java/io/javalin/config/HttpConfig.kt
[tasks]: https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/main/java/io/javalin/http/servlet/DefaultTasks.kt
[methods]: https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/main/java/io/javalin/http/HandlerType.java
[allowed]: https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/main/java/io/javalin/plugin/bundled/HttpAllowedMethodsPlugin.kt
[context]: https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/main/java/io/javalin/http/Context.kt
[requesttests]: https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/test/java/io/javalin/TestRequest.kt
[encodingtests]: https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/test/java/io/javalin/TestEncoding.kt
[sizetests]: https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/test/java/io/javalin/TestMaxRequestSize.kt
[responsetests]: https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/test/java/io/javalin/TestResponse.kt
[servletutil]: https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/main/java/io/javalin/http/servlet/JavalinServletContext.kt
[servletcontext]: https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/main/java/io/javalin/http/servlet/JavalinServletContext.kt
[ssetests]: https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/test/java/io/javalin/TestSse.kt
[exceptionmapper]: https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/main/java/io/javalin/router/exception/ExceptionMapper.kt
[exceptiontests]: https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/test/java/io/javalin/TestHttpResponseExceptions.kt
[wrapper]: https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/main/java/io/javalin/router/HandlerWrapper.kt
[logger]: https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/main/java/io/javalin/config/RequestLoggerConfig.kt
[migration]: https://javalin.io/migration-guide-javalin-6-to-7

## Response metadata and redirects

`ResponseMetadataTest` verifies the handler-owned inspection and mutation API over real HTTP. Header lists retain distinct field occurrences (not comma-split members), and case-insensitive header maps are deeply immutable snapshots. Reading is allowed during an active stream; mutation requires uncommitted output. Initial status inspection reports the effective default 200 even before Jetty assigns its internal status. General header values reject HTTP controls except horizontal tab; the transport can normalize tabs to spaces. This follows [RFC 9110 field syntax](https://www.rfc-editor.org/rfc/rfc9110.html#section-5.5), without promising arbitrary field-specific semantic validation.

Redirects default to302; explicit navigation codes300/301/302/303/307/308 are accepted. They replace the previous body or selected file, clear Content-Type, Content-Length and Content-Range, and remain staged until handler return. Replacing a file also removes its ETag, Last-Modified, Accept-Ranges and Content-Disposition fields; unrelated headers remain. Invalid locations/statuses leave output unchanged. Empty references are rejected as a helper policy, although the URI grammar permits them; relative/query/fragment references and non-HTTP schemes remain supported. Existing escapes are preserved and Unicode components are ASCII-escaped by JDK URI. Network destinations require a host, no userinfo and a valid port; destination authorization is application-owned. See [RFC 9110 Location](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.2). CookieTest now covers the dedicated cookie APIs in addition to raw repeated Set-Cookie support.

## Cookie contract

`Request.cookie`, `cookies` and `cookieMap` copy Jetty parsed values into request-local immutable collections. First-value lookup and map behavior intentionally agree, unlike Javalin7.2.3's first-value accessor/last-value map inconsistency. Names remain case-sensitive and values are not URL-decoded. The existing Jetty compatibility parser can discard malformed input; this is not advertised as strict request-cookie validation.

`http.Cookie` supplies validated immutable attributes, including explicit expiry, SameSite and protected prefixes. Core stages separate fields, replacing only matching name/domain/path scopes; malformed raw cookie fields are preserved. Cookie construction rejects syntax/control injection before output mutation. Response-level cache headers are unchanged. Tests exercise quoted values, normalized domains, expiry-only and zero/large ages, scope-aware deletion, secure prefixes, invalid-attribute atomicity, snapshots across connection reuse, thread/lifetime confinement, HEAD and error-handler cleanup. A JDK CookieManager also proves a set/read/delete round trip through the HTTP listener. These checks establish server formatting and that client interoperability case; they do not claim a complete browser SameSite/CSRF test suite.

Prefix and SameSite invariants follow [draft6265bis22](https://datatracker.ietf.org/doc/html/draft-ietf-httpbis-rfc6265bis-22#section-5.7); ordinary syntax follows [RFC6265](https://www.rfc-editor.org/rfc/rfc6265.html). The API rejects trailing path whitespace because parsers trim it, otherwise breaking explicit scope matching. Other selected strictness and defaults are documented in [HTTP primitives](../http/README.md#cookies).

## Request metadata and application state

`RequestMetadataTest` adds real HTTP/socket coverage for raw query spelling, immutable complete header/path snapshots, logical authority versus direct socket addresses, URI normalization/IPv6, legacy Host fallback, malformed-query inspection, existing encoded-path rejection, and request-local attributes/Principal. Tests coordinate eight concurrent handlers to prove isolation and verify metadata/state through gates, errors, streaming, foreign-thread rejection and post-handler rejection. This closes inventory P03; trusted-proxy behavior is described below and optional authentication-provider integration is described in the README.

`url/fullUrl/scheme/authority/serverName/serverPort` expose Jetty's parsed request URI metadata, which can incorporate client-supplied Host or an absolute target. Default ports may be removed and legacy missing Host uses the listener authority. `isSecure` instead reads the physical endpoint: Jetty's request-level flag is derived from URI scheme, which the absolute-target test demonstrated can say HTTPS on plain TCP. Direct local/remote addresses likewise bypass metadata wrappers. Verified against pinned [HttpConnection](https://github.com/jetty/jetty.project/blob/jetty-12.1.11/jetty-core/jetty-server/src/main/java/org/eclipse/jetty/server/internal/HttpConnection.java), [ChannelRequest](https://github.com/jetty/jetty.project/blob/jetty-12.1.11/jetty-core/jetty-server/src/main/java/org/eclipse/jetty/server/internal/HttpChannelState.java), and [ConnectionMetaData](https://github.com/jetty/jetty.project/blob/jetty-12.1.11/jetty-core/jetty-server/src/main/java/org/eclipse/jetty/server/ConnectionMetaData.java). These HTTP-only metadata tests are supplemented by native TLS coverage below.

Attributes are application-owned Objects with null-as-removal; map snapshots freeze bindings without copying values. Principal is an explicitly assigned JDK Principal, null by default and clearable. Both are thread/lifetime confined and released by request finalization. Neither state nor identity is inferred from headers by these accessors; optional authentication and tracing adapters have separate contracts.

## Trusted reverse-proxy metadata

Forwarding is disabled by default. Configure a thread-safe, nonblocking predicate before `start()`
when the listener is reachable only through known reverse proxies:

```java
app.trustedProxies(InetAddress::isLoopbackAddress, ForwardedHeaders.RFC7239);
```

The one-argument overload selects `RFC7239`. `io.github.suppierk.shoostr.http.ForwardedHeaders.X_FORWARDED` is an explicit legacy
alternative; the two families are never mixed or used as fallbacks for one another. Registration is
single-use and frozen by startup. The predicate sees the physical socket peer before any header is
read. A rejected peer leaves forwarding input unparsed, so malformed client-supplied fields cannot
produce a 400 response or alter request metadata. Predicate failures are application failures, not
client errors.

`remoteAddress`, `localAddress`, `isSecure`, `url`, `fullUrl`, `scheme`, and `authority` remain
direct transport/request values. Successful forwarding instead exposes `isForwarded`,
`clientAddress`, and `effectiveUrl`. Without a selected assertion, `isForwarded` is false,
`clientAddress` is the direct IP peer when available, and `effectiveUrl` is `fullUrl`. The effective
URL keeps the original encoded path and raw query, substituting only selected scheme/authority
fields; it does not prove TLS or establish a safe redirect origin. These values are available to
route gates and application-wide exception handlers under the normal request lifetime rules.

RFC 7239 elements are parsed in arrival order, including repeated field lines. Starting with the
rightmost assertion from the trusted physical peer, the framework walks left only through trusted
literal predecessors and selects client identity and origin from the same boundary element.
Missing, `unknown`, or obfuscated identities stop traversal and yield a null client address. A
literal client without a supplied source port uses port 0; it never borrows the proxy's port.
Malformed configured fields, duplicate parameters, unsupported schemes, empty elements, and more
than 64 elements return 400 before gates and handlers. Header input is parsed as literals with
`InetAddress.ofLiteral`; it never performs DNS lookup.

Legacy `X-Forwarded-For`, `X-Forwarded-Host`, `X-Forwarded-Proto`, and `X-Forwarded-Port` use the
same boundary rule when `X-Forwarded-For` is present. Without that address chain, legacy origin
fields are ignored and direct fallback applies. Each present origin list must have exactly the same
length as `X-Forwarded-For`; a singleton origin alongside a multi-hop address chain is rejected
rather than guessed. Every supplied chain element validates before selection. An explicit port in
Host must match its corresponding `X-Forwarded-Port`; a port without Host is rejected. This is
deliberately stricter than deployments that rely on a proxy to sanitize a singleton origin
assertion.

Forwarding headers are not proxy authentication. Deployments must prevent direct access to the
listener and ensure trusted proxies remove attacker-controlled values or append truthful entries.
The contract follows [RFC 7239](https://www.rfc-editor.org/rfc/rfc7239.html) element semantics and
[RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html) origin metadata rules. The executable
coverage is `TrustedProxyTest`; it does not prove firewall or reverse-proxy configuration.

## Sessions and browser CSRF

Sessions are disabled until `Shoostr.sessions()` or `Shoostr.sessions(Consumer<SessionHandler>)` is called
before startup. The Jetty core `ContextHandler → SessionHandler` chain wraps the application's
handler, and Shoostr owns its lifecycle. `Request.session(false)` returns null without creating a
session; `session(true)` creates one before response commitment. Jetty's `Session` owns ID, attributes, per-session idle
expiry and invalidation. `Request.renewSessionId()` uses the current transport response and must
run before commitment. The old ID stops resolving. Invalidating server state does not itself
delete the browser cookie; the application also calls `Response.removeCookie` with the configured
cookie name and path. New/renewed session cookies survive framework error resets, while unrelated
staged cookies are discarded.

The default Jetty cache/store pair is `DefaultSessionCache` with `NullSessionDataStore`: live
sessions are shared across concurrent requests on one app instance and disappear on shutdown.
The native configuration callback can install an established Jetty `SessionDataStore` or factory;
the public HTTP test reloads attributes from Jetty's file store after restart. Attribute access
is safe for concurrent requests, but compound operations and mutable values in attributes are
application synchronization concerns, and cross-node atomicity is not promised. Default cookie
settings are HttpOnly, SameSite=Lax, root path, no Domain or browser Max-Age, and Secure when the
direct connection is secure; the native callback may override them. Server-side idle expiry is
30 minutes by default. URL session-ID tracking is disabled.

`Csrf` is an explicit route policy for cookie-authenticated browser operations, registered through
`Routes.protect`. GET, HEAD, OPTIONS and TRACE are safe under RFC 9110; all other methods require
an existing session, one session-bound synchronizer token in `X-CSRF-Token` or `_csrf` form input,
and a trustworthy source origin. A single `Origin` must match the effective target origin or an
explicitly configured trusted origin; if absent, one `Referer` supplies that comparison. Missing,
ambiguous, opaque or mismatched sources and tokens yield 403 before the business handler.
`Csrf.token` creates a session lazily when the application exposes a token on a safe response;
tokens remain stable within the session and rotate after ID renewal. They are not placed in
cookies or URLs. CORS preflight remains CORS-only; actual requests reach this policy. Routes
outside a protected scope, including bearer-token APIs, have no implied CSRF guarantee.

The pinned [Jetty 12.1.11 session source](https://github.com/jetty/jetty.project/blob/jetty-12.1.11/jetty-core/jetty-session/src/main/java/org/eclipse/jetty/session/SessionHandler.java)
and [OWASP CSRF guidance](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)
provide the provenance. SessionTest and CsrfTest exercise the public HTTP boundary.

## TLS, HTTP/2 and compression

`Shoostr.tls(Consumer<SslContextFactory.Server>)` configures the default native Jetty connector
before startup. `Shoostr.http2()` enables ALPN HTTP/2 with HTTP/1.1 fallback over TLS, or h2c
beside HTTP/1.1 on a plain connector. The default remains plain HTTP/1.1. Shoostr owns all
configured connector and TLS lifetimes and closes them on startup failure. Native
`modifyServer` and `modifyHttpConfiguration` callbacks remain available in their documented
order; the default connector and application handler must be preserved.

`Shoostr.compression()` installs Jetty's gzip `CompressionHandler` with its standard response
method/media exclusions and explicitly disables inbound decompression. For eligible GET/POST
responses, acceptable gzip is selected from `Accept-Encoding`; `Vary: Accept-Encoding` is
present even when the request omits that field. `Response.disableCompression()` selects
identity before commitment, including for streams; it returns an empty 406 when identity is
explicitly forbidden. Framework-managed 206 byte ranges also
select identity so `Content-Range` continues to describe the sent bytes, or return empty 406
when identity is rejected. An explicit
`gzip;q=0` exclusion also wins over an acceptable wildcard. Error recovery retains
compression's `Vary: Accept-Encoding`. Fatal application errors abort only their HTTP/2
stream, so unrelated concurrent streams remain usable. If neither gzip nor identity is
acceptable, a successful unencoded response becomes an empty 406 with `Vary: Accept-Encoding`.
The same empty 406 applies when identity is forbidden but Jetty's active method/path/media
exclusion or minimum-size threshold prevents its selected gzip coding; eligible bodies
still use gzip when accepted. Bodyless responses and explicit application content codings
are not replaced. HEAD has no body;
when compression is enabled it varies on `Accept-Encoding` and omits `Content-Length`,
whose uncompressed value would not equal a compressed GET representation. Bodyless statuses
are not compressed. Application-owned request decoding remains outside this feature.

`Request.isSecure()`, `remoteAddress()` and `localAddress()` describe the direct transport.
`scheme()` and `fullUrl()` describe the parsed request URI; an absolute request target can
supply its scheme, so neither value proves TLS. A trusted proxy's external HTTPS assertion
changes only `effectiveUrl()` and `clientAddress()`; it does not turn a clear-text backend
connection into native TLS. `TransportTest` covers
real HTTPS, ALPN, same-connection concurrent HTTP/2 streams, h2c, proxy metadata, gzip
wire bytes, ranges, HEAD and startup cleanup. Sources: [Jetty server guide](https://jetty.org/docs/jetty/12.1/programming-guide/server/http.html)
and [RFC 9110 section 8.6](https://www.rfc-editor.org/rfc/rfc9110.html#section-8.6).
