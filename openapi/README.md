# OpenAPI

Optional `io.github.suppierk:shoostr-openapi` (Gradle project `:openapi`) generates
OpenAPI 3.1.1 JSON and contains locally packaged Swagger UI. Core-only consumers
receive neither this module nor its Swagger/Jackson/UI dependencies.

```java
var docs = new OpenApi("Users API", "1.0");
var user = docs.schema("User", userSchema); // Explicit Swagger Schema<?> / JsonSchema.
app.extensions(docs).routes(routes -> {
  routes.get("/users/{id}", getUser, e -> e.get(docs)
      .summary("Find a user")
      .pathParameter("id", "User identifier")
      .response(200, "User found", MediaType.APPLICATION_JSON, user));
  routes.get("/openapi.json", docs.handler());
});
```

Import `io.github.suppierk.shoostr.openapi.OpenApi`. Use explicit Swagger 3.1
`JsonSchema` or `Schema<>(SpecVersion.V31)` models. For example, `types(Set.of("string", "null"))`
expresses nullable strings; the old OpenAPI 3.0 `nullable` property is not its replacement.
Use explicit native `Parameter`, `RequestBody` and `ApiResponse` models for less common
declarations instead of learning another model DSL. Media declarations may omit schemas.
String and Shoostr `MediaType` response/request-body overloads are available.

No application annotations, class/model scanning, codec-derived schemas, handler invocation,
request validation or serialization are added. Swagger serializes its own library models;
this is not a claim of reflection-free dependency internals. Supplied documents, import/merge,
YAML and server-side remote-reference fetching are not supported.

## Configuration and inheritance

Configure document title/version in the constructor, then description, servers, named schemas,
tags and security schemes on the extension. Select its exact installed instance with `e.get(docs)`.
Operations support summary/description/operationId/tags, explicit path/query/header parameters,
request body, responses and security. No operation ID, success response, payload schema or
security requirement is guessed. Different installed instances compile independent documents.

Groups configure once. Child singular fields override only that field; tags accumulate uniquely;
parameters replace by name/location and responses replace the complete entry for their status.
Unrelated defaults remain and siblings cannot mutate each other's model graphs. `security(...)`
replaces inherited documentation security; schemes in a native `SecurityRequirement` are
conjunctive. `anonymous()` clears the documented requirement, not actual authentication.
`hidden()` excludes a scope and its descendants, including inherited documentation.

Bindings below `when` are documented normally when this document instance is selected. Conditions
are never evaluated at startup or on documentation requests, so a documented endpoint may return
404 when unavailable. Use instance selection or `hidden()` to decide which document exposes an
endpoint. Runtime 404/405/CORS behavior is unchanged; documentation is not access control.

Selected operations are bound to actual final HTTP/SSE registrations. Constrained segments
normalize to named OpenAPI parameters with their string restrictions. Explicit path parameters
must match the template and be required; missing declarations, included catch-alls, unsupported
HTTP methods, normalized collisions and duplicate explicit operation IDs fail startup. The short
`pathParameter(name, description)` form explicitly describes a string; use native `Parameter`
for a different schema on unconstrained routes. Constrained parameters require an inline schema
with exactly the string type; content-based, referenced and non-string declarations fail startup
instead of publishing conflicting semantics. An existing pattern is intersected with the route
constraint. Declare
schemas explicitly: this validates known structure, not business behavior or arbitrary prose.
Known component references are checked against this document; external and other JSON Schema
references are preserved without fetching or claiming complete JSON Schema validation.

Configuration uses independent detached library models. Startup collects facts, validates,
serializes once, and freezes serving bytes before the listener starts. Configuration after startup
is rejected. Mutating a borrowed native model cannot change served output. Documentation adds
no runtime lifecycle callback or wrapper to ordinary endpoints. Large documents remain subject
to the application's finite-response limit; UI assets use existing bounded streaming.

## Explicit local UI and authentication

```java
var ui = docs.uiHandler("/docs/", "/openapi.json");
app.authentication(security).extensions(docs).routes(routes -> {
  routes.path("/docs", group -> {
    group.get("/", ui);
    group.get("/{*resource}", ui);
  }, e -> {
    e.get(security).enabled();
    e.get(docs).hidden(); // Also opts out if documentation defaults are inherited.
  });
  routes.get("/openapi.json", docs.handler(), e -> e.get(security).enabled());
});
```

There is no auto-mount or resource-registration helper. The page's trailing slash is explicit;
`/docs` is not silently redirected. The same handler serves only four exact prepared URLs:
the page, `swagger-ui.css`, `swagger-ui-bundle.js`, and `swagger-initializer.js`. Its asset
allowlist uses configured paths, not the catch-all capture name or a filesystem lookup.
Unknown assets return 404. Spec-only apps do not load UI assets during startup.

Protect **page, all assets and spec** with normal route authentication. `securityScheme` and
operation security declarations only document requirements. Swagger UI's Authorize button
cannot unlock an initial protected specification; authenticate browser navigation first, then
authorize protected API operations. Specification loading retains same-origin browser credentials,
but API requests explicitly omit browser-managed cookies and cached HTTP authentication. Explicit
headers supplied through Authorize still work; logging into the docs is not enough to authorize
Try it out. This UI policy does not support cookie/session-based API authentication. No credentials
are embedded or persisted in shared
resources; the online validator is disabled. UI initialization uses fixed same-origin paths,
not a request's Host header.

See the [runnable example](../examples/11-openapi/README.md) for Pac4j Basic authentication,
typed JSON, ordinary text, group overrides and independent documents.

## Provenance

Generation uses [Swagger Core 2.2.55](https://github.com/swagger-api/swagger-core/tree/v2.2.55)
models and its OpenAPI 3.1 `Json31` serializer. That dependency includes Jackson and other
library dependencies; this optional artifact is not dependency-free.

UI CSS/bundle are extracted at build time from
[`org.webjars:swagger-ui:5.33.2`](https://repo.maven.apache.org/maven2/org/webjars/swagger-ui/5.33.2/),
with SHA-256 `f7816953f7df2c01757a89d73579596374d5bff1e85210eb98f1dca81e0cbf6d`
enforced before packaging. The WebJar is build-only, not a runtime or published dependency.
No browser CDN or runtime downloads are required. The own minimal page uses only the normal
bundle, not the standalone preset. Upstream Apache LICENSE/NOTICE and bundle third-party notices
are preserved under `META-INF/licenses/swagger-ui-5.33.2/`.
