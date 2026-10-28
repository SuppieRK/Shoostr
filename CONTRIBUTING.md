# Contribution workflow

Consumer documentation belongs in the [Starlight site](site). Repository documentation is for maintainers.
Shoostr uses [Apache License 2.0](LICENSE). Retain third-party license and notice files.

For every tracked repository change, including documentation and benchmark fixtures:

1. Create a separate branch from the current `main`.
2. Commit and push the branch to the remote, then open a pull request targeting `main`.
3. Fix failures on that branch and push again until CI passes for the pull request's latest commit. The Gradle build and test validation must succeed. Trusted runs also require a passing Sonar quality gate and zero open issues.
4. Leave the pull request unmerged for review unless the user authorizes merging it.

Check available Gradle dependency and plugin updates with
`./gradlew dependencyUpdates --reject-pre-releases --output-formatter plain,json`.
The root task covers this build's projects and writes `build/dependencyUpdates/report.txt`
and `build/dependencyUpdates/report.json`; the separate `buildSrc` build is excluded. Review proposed upgrades
and verify vulnerability fixes separately. Use `./gradlew buildEnvironment` to inspect
transitive buildscript dependencies when investigating build-tool findings.

## CI and external contributions

The required `build` check verifies successful validation and example-container checks for the proposed revision for
internal, fork and Dependabot pull requests. Failed, cancelled or skipped validation
cannot pass this check, and the validated revision must match the triggering event.
External contributions do not need a replacement pull request on a maintainer branch.

Trusted pull requests and CI runs in `SuppieRK/Shoostr` must pass the Sonar quality gate
and have zero open issues. A missing analysis token, scanner failure, failed quality gate,
API error or nonzero issue count fails validation and the required `build` check.
Fork and Dependabot pull requests run validation without Sonar; repository secrets remain
restricted to trusted analysis.

The aggregate `build` job runs even when validation fails or is skipped. Main's ruleset
requires `build` from GitHub Actions and an up-to-date branch; the existing administrator
bypass is retained.

# Documentation

The Starlight site in `site/` is for library consumers.
The root README provides a short product overview. Other repository documents are for maintainers.
Keep contribution procedures, build diagnostics, provenance, and agent instructions out of the consumer site.
Show focused library composition through public Shoostr interfaces, with caller-owned
configuration and resources. Keep dependency-wiring, dependency-injection and general service
architecture guides out of the consumer site.
Library dependency declarations are separate from application dependency wiring.

### Build and preview

Use Node.js 22.22.0 or later. Run these commands from the repository root:

```sh
npm --prefix site ci
npm --prefix site run build
npm --prefix site run preview
```

Open the local address printed by the preview command. Press Ctrl+C to stop it.
The preview serves the production build, including the search index.
`npm --prefix site run dev` supports editing, but its search box does not run Pagefind.

Test repository-path hosting with the same commands and these build inputs:

```sh
SITE_URL=https://example.invalid BASE_PATH=/Shoostr/ npm --prefix site run build
SITE_URL=https://example.invalid BASE_PATH=/Shoostr/ npm --prefix site run preview
```

Open `/Shoostr/` on the printed local origin. Check navigation, assets, copy buttons, and search results in both themes.
The example origin is not a published site. Set the real origin and repository path before publication.
The output in `site/dist/` is a static GitHub Pages artifact. Publishing requires separate authorization and repository configuration.
There is no deployment workflow in this foundation.

Build the library and verify examples separately:

```sh
./gradlew clean spotlessApply build
```

Node tooling is independent of the Gradle build and the library runtime.

### Add a consumer topic

Give each task one owner under `site/src/content/docs/`. Add its completed page to the sidebar in `site/astro.config.mjs`.
Keep navigation shallow. A reader must reach a task from the landing page within three clicks, including mobile menus.
Check search with task phrases, not only exact page titles. Do not publish empty topic pages or planned-content navigation.

Search indexes each page's frontmatter description alongside its title and full content.
Write the description as a concise summary of the task, making request and response scope clear where needed.
Keep that summary in one place; `SearchHead.astro` supplies it to Pagefind without changing Starlight's ranking defaults.
The same adapter replaces ASCII hyphens with spaces in search-title metadata to prevent compound titles from outranking the task pages in the checked queries.
Search-result labels therefore use spaces, while page headings and browser titles retain their original punctuation.

Verify ranking through the production search dialog, because a bare Pagefind import does not use Starlight's configured ranking.
Use task phrases such as “request header limit” or “response charset” and check that the topic comes first.
For shared identifiers or broad terms such as “cookies”, check the relevant topics among the first three distinct pages instead of assigning an artificial single owner.
Keep complete examples and tests searchable, and repeat the checks at both hosting paths in light and dark themes.
Do not add page-specific weights or keyword lists to compensate for a ranking failure.

The final build hook indexes `/` and `+` as technical token characters, so searches
such as `application/problem+json` retain the same punctuation as the indexed code.
It rebuilds the index after Starlight while retaining the native search dialog and
ranking defaults; indexing errors fail the build rather than keeping a stale index.

Use this page sequence:

1. Description: result, any additional library the task requires, essential limits, and ownership.
2. Example: complete classes, including package, imports, and all required supporting classes.
3. Test: complete test classes, including package, imports, fixtures, and assertions for the documented behavior.

Assume readers already have Shoostr, their own source code, and their own build system.
After getting started, assume the base libraries and Java version are already configured.
Do not repeat setup prerequisites or repository example numbering in recipe prose.
Mention an additional library only when the task requires it.
Consumer recipes provide code for a task and code to test it. Omit cURL, build commands,
repository-checkout instructions, and assumptions about the reader's project layout.
Build and preview commands belong in maintainer documentation only.

The library-selection reference may show Maven and Gradle dependency declarations.
Label tentative coordinates and version placeholders clearly. Keep test libraries in test scope.
This exception does not permit build commands, checkout instructions, or build-system setup in consumer recipes.

Import complete compiled Java files with `?raw` and render them with Starlight's `Code` component.
Keep the source and its tests under the example project. Do not maintain a second Java copy in documentation.
Adjust or split examples when the topic requires it. Existing examples do not determine the documentation scope.
Use JDK annotations only in application examples. Tests may also use JUnit 5 annotations.

Add short in-code comments at the steps that need explanation: scope selection, ordering,
ownership transfers, protocol choices, and failure handling. Explain why the example takes a
step and what changes for the caller; do not narrate obvious Java syntax or add a comment quota.
Use the fluent `Routes` and `TypedRoutes` methods for adjacent registrations on the same scope.
Keep child callbacks and setup boundaries clear, and preserve registration order. A chain returns
the current scope, not a builder for the last endpoint; a scope-wide setting can affect earlier routes.

Show `new Shoostr()` and the application's fluent configuration in the example. Do not wrap
that definition in setup `try`/`catch` blocks, cleanup flags, or equivalent scaffolding, and do
not replace it with a route-only definition. Remove catches that only rethrow an exception or
duplicate lifecycle cleanup. Keep task-specific error handling and ordinary test
try-with-resources where they belong.

Verify the rendered classes match their source files. Run their Gradle tests and the static site build.
Check desktop and mobile navigation, keyboard focus, code copying, and a production search result.
Repeat hosting checks for `/` and the repository base path. Keep private research and maintainer files out of the site output.

### STE-based writing profile

This project uses a profile based on Simplified Technical English.
It does not claim formal ASD-STE100 compliance or enforce its complete dictionary.

| Rule                       | Requirement                                                                                                                                               |
| -------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| W01 — Reader task          | Use task-focused titles. State the result before background.                                                                                              |
| W02 — Direct steps         | Use active voice and imperative verbs. Give each instruction one main action.                                                                             |
| W03 — Natural flow         | Use concise, connected prose with varied sentence lengths. Keep related ideas together rather than splitting paragraphs into abrupt statements.           |
| W04 — Stable terms         | Use one term for one concept. Preserve exact identifiers, commands, protocol fields, and errors.                                                          |
| W05 — Complete recipe      | Show the task and its test as complete classes. State additional library requirements, essential constraints, and ownership. Do not repeat initial setup. |
| W06 — Essential limits     | State ownership, lifetime, security, and protocol limits where the reader acts.                                                                           |
| W07 — Evidence             | Display compiled source files. Link primary references for deeper concepts. Label uncertainty and historical evidence.                                    |
| W08 — Audience             | Keep consumer tasks in Starlight and maintainer procedures on GitHub. Keep READMEs short.                                                                 |
| W09 — Clean examples       | Keep Sonar-specific suppressions, rule IDs, and analyzer comments out of example source. Explain resource ownership in ordinary terms.                    |
| W10 — Java fluency         | Explain task-specific choices and non-obvious contracts, not assertions, standard Java syntax, or automatic resource closure.                             |
| W11 — In-code explanations | Comment meaningful choices, scope, ordering, ownership, and failure paths where the reader encounters them. Keep already-clear code concise.              |
| W12 — Fluent registration  | Chain adjacent registrations on the same route scope, preserving call order and child boundaries. Keep setup and unrelated operations separate.           |
| W13 — Application setup    | Show application construction and fluent configuration directly, without setup catch blocks, cleanup scaffolding, or route-only substitutes.              |

Review prose in context instead of treating sentence length or automated word counts as proof of clarity.
Use short sentences where they help, such as a clear warning, without making every paragraph read like a checklist.

### Documentation review

Use the knowledge-base architect before changing structure or canonical ownership.
Use the writing reviewer against this profile and the technical reviewer before documentation completion.
Record each review's scope, findings and outcome before delivery.

References: [Starlight setup](https://starlight.astro.build/manual-setup/),
[whole-file code imports](https://starlight.astro.build/components/code/),
[site search](https://starlight.astro.build/guides/site-search/), and
[Astro GitHub Pages configuration](https://docs.astro.build/en/guides/deploy/github/).

## Testing and provenance

Use JUnit 5 tests at public behavior boundaries. Independently implement upstream scenarios and preserve pinned references and license notices. Historical issue identifiers below record the origin of coverage, not a claim of cross-framework compatibility.

## HTTP contract coverage from upstream scenarios

`HttpCompatibilityTest` adds black-box JUnit 5 cases for supported behavior identified while reviewing Javalin 7.2.3 and Jooby 4.5.4 tests. These are new implementations using this framework's API and the JDK HTTP client; no upstream test helpers, framework implementations, or test dependencies were copied.

| Local test                                                      | Upstream behavioral reference                                                                                                                                                                                                                                                                                                                           | Local adaptation                                                                                                                                            |
| --------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `dispatchesEveryExplicitlyRegisteredVerb`                       | Javalin [`TestRouting`, mapped verbs](https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/test/java/io/javalin/TestRouting.kt)                                                                                                                                                                                                     | Seven explicit verbs; HEAD verifies handler execution through a header and body suppression on the wire. No implicit HEAD fallback is assumed.              |
| `matchesLiteralPunctuationWithoutInterpretingItAsPatternSyntax` | Javalin [`TestRouting`, literal colon and path matching](https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/test/java/io/javalin/TestRouting.kt)                                                                                                                                                                                  | Literal punctuation, query exclusion, and suffix misses through the actual listener.                                                                        |
| `preservesParameterValuesAtTheHttpBoundary`                     | Javalin [`TestRouting`, UTF-8 and parameter casing](https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/test/java/io/javalin/TestRouting.kt)                                                                                                                                                                                       | Supported whole-segment parameter syntax, percent decoding, literal plus, and case-sensitive literal prefixes.                                              |
| `rejectsEncodedPercentBeforeInvokingTheHandler`                 | Additional local transport-policy regression discovered while adapting parameter scenarios                                                                                                                                                                                                                                                              | Percent-encoded percent is rejected before the handler; no URI compliance setting is weakened to claim parity.                                              |
| `cachesRequestBytesWithoutExposingMutableStorage`               | Javalin [`TestMaxRequestSize`, repeated body reads](https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/test/java/io/javalin/TestMaxRequestSize.kt); Jooby [`ByteArrayBodyTest`, content access and empty bodies](https://github.com/jooby-project/jooby/blob/v4.5.4/jooby/src/test/java/io/jooby/internal/ByteArrayBodyTest.java) | Repeated bytes/text access, empty and UTF-8 bodies, plus our defensive-copy guarantee; assertions use a real HTTP request rather than mock Context objects. |
| `acceptsTheByteLimitAndRejectsOneAdditionalByte`                | Javalin [`TestMaxRequestSize`, size boundaries](https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/test/java/io/javalin/TestMaxRequestSize.kt)                                                                                                                                                                                    | Exact byte limit and limit+1 using multibyte text, with known-length and unknown-length JDK publishers.                                                     |
| `readsAndReplacesHeadersCaseInsensitively`                      | Javalin [`TestResponse`, setting headers and overwriting values](https://github.com/javalin/javalin/blob/javalin-parent-7.2.3/javalin/src/test/java/io/javalin/TestResponse.kt); Jooby [`DefaultContextTest`, response headers/status](https://github.com/jooby-project/jooby/blob/v4.5.4/jooby/src/test/java/io/jooby/DefaultContextTest.java)         | Header spelling variants, missing request header, replacement rather than append, selected status, and UTF-8 Content-Length.                                |

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
`RequestOutcomeIntegrationTest.reportsAcceptedWebSocketUpgradeWithItsRegisteredTemplate` and
`reportsDeniedWebSocketUpgradeWithoutInvokingTheListenerFactory`.
Live upgrades with long credentials and path identifiers produce one terminal
`RequestOutcome` with status 101 or 403 and the registered route template, without
substituting identifiers or query strings. Denial records the application failure
and skips the listener factory. These tests cover completion metadata rather than
formatted log output; failure fields retain diagnostic exceptions.

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

Issue 76 B02 adds `MultipartRequestTest`
`sendsContinueBeforeReceivingAndPreservingABinaryMultipartUpload`. An owned,
bounded socket sends only headers with `Expect: 100-continue`, uses native
`HttpTester` to observe the actual interim 100, then transmits multipart bytes.
The final 200 preserves named file metadata and exact binary content. It does
not mistake a client-hidden interim response or eager body transmission for
continue support. No native policy or production code is changed.

Issue 76 B01 was explicitly expanded to implement HTTP/2 WebSockets after a
negotiated live CONNECT returned404 without reaching the listener factory.
`WebSocketRoutesTest` now checks exact text-frame round trips over h2c and
TLS/ALPN, with working HTTP/1.1 controls; managed-authentication denial before
factory creation; other CONNECT protocols retaining ordinary routes; invalid
versions receiving426 without an HTTP/1 Upgrade header; matched-hook access to
decoded parameters with raw CONNECT method; and dynamic availability404 with
no-store. Public Jetty HTTP2Client handles negotiation, HPACK and flow control.
The short client/server frame literals are independent RFC fixtures, not a
framework parser. Native frame buffers are released and owned clients close
before the application. The client and ALPN provider are test-only dependencies.

Issue76 B04 adopts optional file retrieval for nonmultipart input, independently
inspired by pinned Javalin TestMultipartForms and selected by the user. Live
tests cover absent/text/JSON/form media types, buffered and streamed access orders,
and bodyless input; empty results must not claim or consume the ordinary body.
Malformed declared multipart and actual multipart/raw-body exclusivity remain
required controls. Test implementations are independently authored, not copied.

Query/form parsing and repeated raw headers are covered in `RequestParametersTest`; global mapper selection, reset/fallback behavior, and typed media output are covered by `ExceptionHandlerTest` and `MediaTypeResponseTest`. `ResponseMetadataTest` covers response inspection, append/remove, distinct Set-Cookie fields and redirects, including input validation and lifecycle boundaries. `CookieTest` covers request parsing/snapshots, validated response values, scoped replacement/deletion, error/lifetime boundaries, and a JDK CookieManager round trip. `SessionTest`, `CsrfTest`, `LifecycleHooksTest`, `ServerSentEventsTest`, `WebSocketRoutesTest` and `TransportTest` now exercise the later features through real listeners and clients. Do not add disabled tests or silently import different upstream defaults.

Run the focused suite with the repository wrapper:

```sh
cmdshape ./gradlew :core:test --tests io.github.suppierk.shoostr.HttpCompatibilityTest
```

Normal `build` also runs these tests with Checkstyle and Spotless. No extra test harness is required.

### Lifecycle completion coverage

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

`LifecycleHooksTest` exercises the public Shoostr/Request callbacks and actual HTTP listener. It covers composed route templates, ordered gates, checked/HTTP/fatal errors, stream ownership, body bounds, startup races, observer isolation, generated misses, and HEAD/OPTIONS/method-override characterizations.

A small-window raw socket holds a 16MiB finite response in flight, then disconnects; the completion callback must remain pending until transport termination. A separate stream test holds the route active across a disconnect and verifies that application finalization is also required. These tests control the peer through the public HTTP seam; they do not mock internal completion classes. Fatal-abort tests use POST so the JDK client's automatic GET retry cannot be mistaken for duplicate observation of a single admitted request. Existing finite-transport tests remain regression evidence; no new transport-testing extension was added to the public API.

### Request metadata coverage

`RequestMetadataTest` uses the public Shoostr/Request API, JDK client and raw sockets for authority forms restricted by that client. It covers raw query/header/path snapshots, IPv6/default ports/legacy Host, URI scheme versus actual transport security, forwarding-header spoof resistance at default configuration, eight overlapping requests with independent attributes/principals, mapper access and lifetime boundaries. The absolute HTTPS target over plain TCP drove a failing test and a switch to direct endpoint security. These cases are independently authored from pinned Jetty semantics and the existing request contract; no internal adapter mocks are used.

## Diagnostic mutation testing

Run the full eight-library diagnostic suite from the repository root with Java 21:

```sh
./gradlew mutationTest --console=plain
```

The root task delegates to `:core:pitest`, `:http:pitest`, `:micrometer:pitest`,
`:opentelemetry:pitest`, `:pac4j:pitest`, `:test:pitest`, `:openapi:pitest`, and
`:mcp:pitest`. The plugin generates
its standard HTML report (`index.html`) and XML (`mutations.xml`) under
`<module>/build/reports/pitest/<timestamp>/`. Open the HTML report to inspect surviving
changes and covering tests. Timestamped runs preserve earlier reports until `clean`;
copy the entire report directory outside `build` before cleaning when retaining a
baseline.

`mutationTest` is independent of `build`, `check`, and CI. Surviving mutations are
diagnostic findings: there is no mutation-score, coverage, test-strength, or surviving
mutation threshold. Invalid configuration, a failing unmutated test, or a tool error
must still fail execution. Existing Gradle `Test` tasks continue to use JUnit Platform;
PIT uses its maintained JUnit plugin to discover/run the same Jupiter test sources in
isolated child JVMs, without a project-defined runner or a second test programming model.

For a module-specific diagnostic rerun, use its task directly, for example
`./gradlew :http:pitest --console=plain`. This does not narrow the full task.

### Targets and exclusions

| Module          | Production targets and tests                      | Reason                                                                                                      |
| --------------- | ------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| `core`          | All module-local production classes and all tests | Routing, requests/responses, streaming, transport/lifecycle, files, browser security and session contracts. |
| `http`          | All module-local production classes and all tests | HTTP value validation, formatting, parsing and exception/status contracts.                                  |
| `micrometer`    | All module-local production classes and all tests | Metrics registration, request measurements and cleanup.                                                     |
| `opentelemetry` | All module-local production classes and all tests | Instrumentation scope, span completion and propagation.                                                     |
| `openapi`       | All module-local production classes and all tests | OpenAPI generation, document delivery, and UI configuration.                                                |
| `mcp`           | All module-local production classes and all tests | MCP transport, protocol dispatch, and session ownership.                                                    |
| `pac4j`         | All module-local production classes and all tests | Authentication/authorization adaptation and request context.                                                |
| `test`          | All module-local production classes and all tests | Consumer test application/client and resource ownership.                                                    |

Both class and test selectors are `io.github.suppierk.*`, including nested classes;
PIT's mutable code paths are each module's own production output. Dependency classes
are not mutated through their consumers. No client-facing class or test is explicitly
excluded. Examples, benchmark fixtures and build logic are outside the eight
library modules. Standard PIT bytecode filters still apply; generated/no-executable
code is not evidence of an untested user-visible contract.

Use PIT's `DEFAULTS` mutator set with two workers. No project-specific mutation
exclusions, hand-written mutators or score-driven test generation are added. The
existing tests include HTTP requests where the observable contract needs
them; add regression tests at the smallest established construction/routing/request
boundary that demonstrates a meaningful surviving fault.

### Tool versions and sources

Versions are pinned inline, following the repository's Gradle dependency convention:
Gradle plugin 1.19.0, PIT 1.30.1 and PIT JUnit plugin 1.2.3. Tests use the existing
repository Jupiter dependencies; do not downgrade them or introduce JUnit 4.

Sources checked on 2026-09-26:

- [Gradle plugin release](https://plugins.gradle.org/plugin/info.solidsoft.pitest).
- [Gradle plugin configuration and JUnit support](https://github.com/szpak/gradle-pitest-plugin).
- [PIT releases](https://github.com/hcoles/pitest/releases) and
  [change history](https://github.com/hcoles/pitest/blob/master/README.md).
- [PIT JUnit plugin requirements](https://github.com/pitest/pitest-junit5-plugin).
- [PIT options](https://pitest.org/quickstart/commandline/).


## Packaged OpenAPI UI provenance

Generation uses Swagger Core 2.2.55 models and its OpenAPI 3.1 `Json31` serializer.
UI CSS and bundle are extracted at build time from
[`org.webjars:swagger-ui:5.34.0`](https://repo.maven.apache.org/maven2/org/webjars/swagger-ui/5.34.0/).
The SHA-256 `a439f1bd0d0c69667e97fac5a8cdfb3cc599ed9bdb31fbf40602c648dd2e1a63`
is enforced before packaging. The WebJar is build-only; it is not published as a runtime dependency.
No CDN or runtime download is needed. Preserve the upstream Apache LICENSE/NOTICE and
bundle third-party notices under `META-INF/licenses/swagger-ui-5.34.0/`.

## Example container validation

The ten standalone `ExampleApplication.java` launchers execute in the required Docker matrix,
outside the JUnit JVM measured by JaCoCo. Their exact paths are excluded only from Sonar coverage
attribution. All source-analysis rules and the zero-open-issues check still apply to them;
factory and framework coverage remain subject to the existing quality gate. The Dockerfile and
Jib paths require observable startup, resource-configuration, HTTP/protocol and graceful-exit
checks. These are behavioral checks, not measured JaCoCo line or branch coverage.

Every runnable Gradle example has its own Dockerfile and Compose file. Jib is configured
only for example applications through `config/gradle/example-container.gradle`.
Dockerfiles build with a pinned Java 21 JDK and run with a pinned Java 21 JRE;
the default `runtime` target installs the Gradle application distribution. The optional
`jib` target exports the native `jibBuildTar` output for `docker load`, without a host JDK,
Docker socket mount or registry push. Each example README gives exact commands and credentials.

Run `node --test .github/ci/example-containers.test.mjs` from the root for the inventory check.
Set `EXAMPLE` to a registered example project and `BASE_URL` to its running listener for
bounded public HTTP/protocol checks. An empty or unknown selection fails. These checks
complement ordinary JUnit tests; they do not replace the observability backend tests,
MCP Inspector checks or official MCP conformance suite.

CI builds and launches all 22 examples twice: the distribution image and the Jib image.
It verifies the running image identity, nonroot/read-only configuration and documented
behavior, tears down between modes, and preserves failure logs. Metrics, traces and MCP
Inspector are checked against their real backends in both modes. The required `build`
check waits for this matrix as well as Java validation and trusted Sonar analysis.

References: [Jib image tarballs](https://github.com/GoogleContainerTools/jib/tree/master/jib-gradle-plugin#build-an-image-tarball),
[Docker multi-stage builds](https://docs.docker.com/build/building/multi-stage/),
and [local artifact export](https://docs.docker.com/build/exporters/local-tar/).
