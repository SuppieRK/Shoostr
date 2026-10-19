# MCP

`io.github.suppierk:shoostr-mcp` is an optional Streamable HTTP adapter for the
official Java MCP SDK 2.0.1. Its selected protocol is 2025-11-25. Configure native
tools, resources, prompts, schemas, completion and client interactions through
the SDK; Shoostr supplies explicit routes, HTTP security and owned transport
lifetime. The SDK is not included in `shoostr-core`.

```java
var mcp = Mcp.sync(
    new McpHttpSecurity(
        Set.of("https://api.example"),
        request -> new McpIdentity("public", McpTransportContext.EMPTY, null),
        (identity, message) -> {}),
    transport -> McpServer.sync(transport)
        .serverInfo("example", "1.0")
        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
        .tools(McpServerFeatures.SyncToolSpecification.builder()
            .tool(McpSchema.Tool.builder("hello", Map.of("type", "object")).build())
            .callHandler((exchange, call) -> McpSchema.CallToolResult.builder()
                .addTextContent("Hello from Shoostr").build())
            .build())
        .build());

var app = new Shoostr()
    .extensions(mcp)
    .routes(routes -> routes.path("/mcp", mcp::routes))
    .start();
```

This example deliberately makes the endpoint public. Origin validation is not
authentication. For a protected deployment, select a managed authenticator on
the enclosing group and derive the identity from its verified claims. Authentication
and token verification remain application-owned; the adapter does not supply an
authorization server.

## Configuration and ownership

- `Mcp.sync` and `Mcp.async` call the native factory once, eagerly. The factory
  must bind the supplied transport and return its corresponding configured
  server; do not return a server owned elsewhere. Null and duplicate/unused
  transport bindings are rejected.
- `app.extensions(mcp)` installs the capability, but creates no route or
  authentication requirement. Mount it explicitly with `mcp::routes` on one
  literal path group. Each adapter supports one mount and one app owner.
- `mcp.server()` exposes borrowed native management access, such as `addTool`,
  `removeResource`, catalog notifications or elicitation completion. Do not close
  it independently. Close the app; it signals streams before graceful drain,
  then closes sessions and the expiry scheduler after the drain.
- Native SDK callbacks may run on SDK/Reactor workers, not the Shoostr handler
  thread. Never retain or pass a live Shoostr Request/Response into SDK callbacks.
  Use the detached `McpIdentity` and native transport context instead.
- To select an HTTP executor, mount with
  `routes.path("/mcp", group -> mcp.routes(group, executor))`. This applies to
  matched HTTP lifecycle and GET/POST/DELETE handlers, not native SDK callbacks.
  The caller owns the executor and must keep it available until the app closes.
  An open SSE connection occupies a handler worker; size platform-thread pools
  accordingly. Token expiry ends the stream without leaving its reused worker
  interrupted.
- The adapter's owner-thread pump alone writes HTTP/SSE. Native messages enter a
  bounded 64-frame mailbox and acknowledge only after their frame is flushed.
  Failed or full mailboxes fail delivery rather than silently dropping frames.

Session ownership is bounded per `Mcp` instance. Existing `sync` and `async` factories
use defaults of 1,024 simultaneous allocations, 32 per verified session-owner key,
and a 30-minute quiescent timeout. These are configurable operational defaults, not
measured capacity guarantees:

```java
var mcp = Mcp.sync(security,
    new McpSessionLimits(1024, 32, Duration.ofMinutes(30)),
    transport -> McpServer.sync(transport).build());
```

Capacity includes initialization and native cleanup. An exhausted owner receives
HTTP 429; exhausted instance capacity receives HTTP 503. Deletion, failed initialization,
idle expiry and shutdown release capacity after native cleanup. An initialization
whose response cannot be delivered is rolled back, including a disconnected client.
Shutdown also cancels a pending initialization result and awaits native cleanup. Native
factories must cooperate with cancellation: a factory blocked on a caller-owned executor
can outlive HTTP drain, but a native handle it returns afterward is closed without
publishing a session or subscribing to its result.

Accepted activity renews the idle interval. Pending adapter-owned native work prevents
idle eviction, including work that outlives its HTTP connection; its completion starts
a fresh idle interval. An open, quiet GET listener alone does not prevent expiry.
Expired sessions close their streams and return HTTP 404 on later requests. Rejected,
malformed and foreign-owner requests do not renew activity. Applications must keep
ownership of any additional SDK work they start independently.

Clients returning the same verified owner key share its quota. In particular, the
public example's shared owner aggregates anonymous clients. Applications that need
one larger anonymous pool can explicitly select matching total and owner capacities.

## HTTP security

`McpHttpSecurity` has three required inputs: allowed origins, current verified
identity extraction, and operation authorization before SDK dispatch. For OAuth
header-based discovery, supply the trusted metadata URI as an optional fourth input:
`new McpHttpSecurity(origins, identity, authorize, metadata)`. It is validated eagerly
as an absolute HTTPS URL without credentials or a fragment. No policy DSL,
authorization server or automatic discovery endpoint is added to core.

An absent Origin is allowed for non-browser clients. Every present Origin must
be well-formed, singular and exactly allowlisted; rejection occurs before managed
authentication, including preflight and unsupported methods.

Each GET/POST/DELETE obtains a fresh identity. A session's stable owner key must
match before its operation policy runs; foreign/deleted sessions return 404.
Use an unambiguous key containing the verified issuer, subject and client where
those distinguish owners. Scope escalation need not change that key.

The operation callback receives the detached identity and native JSON-RPC message.
Throw `McpHttpSecurity.scopeDenied(requiredScopes, resourceMetadata)` to reject with
HTTP 403 before tool side effects or SSE commitment. MCP validates and constructs
the complete scope/metadata challenge; the returned shared `AuthorizationDeniedException`
belongs to `http.exceptions`. Core renders it and preserves its required challenge.
No user exception registration is needed. Optional route/group or app-wide exception
handlers may customize the HTTP error body without losing the challenge at status403.
Errors thrown inside native tool callbacks instead remain native MCP tool errors.

Authorized notifications and replies return HTTP202 after session-owned admission,
not after application callbacks finish. Processing uses detached identity/context;
asynchronous failures are logged. Deleting the session or closing the app cancels
its pending native processing, including roots-change callbacks.

For a verified access token, supply its exact expiry in `McpIdentity`. The adapter
records it before reading the request body, rejects expired initialization and finite
notification/reply admission, and checks freshness around operation authorization.
The deadline interrupts the still-owned handler and actively ends open streams;
expired output is rejected before flush, including finite responses.
This does not detect immediate revocation. A null expiry is for public/non-token
identities, not a way to bypass token lifetime validation.

Deadline interruption is cooperative, as with streaming handlers. A later application
flush hook that propagates its interruption follows the normal failure/abort path;
adapter-generated expiry rejection before commitment returns HTTP 401. Application
callbacks must not suppress cancellation and continue writing after expiry.

With metadata configured, adapter-generated expiry failures before commitment
return `Bearer error="invalid_token", resource_metadata="..."` through the shared
HTTP authentication exception. No caller exception mapping is needed; an optional
app or route renderer can customize the body while core preserves the challenge
at status401. The challenge is built once from trusted configuration, never from
request headers or native context values. The three-argument constructor, or a null
metadata argument, retains the existing generic `Bearer error="invalid_token"`
challenge. Already committed streams terminate at expiry rather than changing status.

POST must explicitly accept both `application/json` and `text/event-stream`;
GET must explicitly accept `text/event-stream`. Absent or unacceptable Accept
returns 406, using Shoostr's media-range and quality rules. An explicitly non-JSON
or ambiguous Content-Type returns 415; absent Content-Type is allowed. JSON media
type parameters are accepted, with MCP JSON decoded as UTF-8. DELETE does not
require Accept.

Invalid UTF-8, malformed JSON, trailing documents and duplicate fields return HTTP 400/JSON-RPC
`-32700`; invalid envelopes return HTTP 400/`-32600`. Input is checked before mapper coercion, session creation or
native dispatch. Invalid initialization parameters return HTTP400/`-32602` before
session allocation, rather than relying on coercion or reporting a server failure.
Invalid/unsupported explicit `MCP-Protocol-Version` headers
return 400; an absent legacy header remains accepted. Initialization body versions
are negotiated by the native SDK, rather than rejected as HTTP header errors.

## Compatibility and released-SDK limitations

The current verification boundary is explicit:

| Feature | Live verification |
| --- | --- |
| Initialization, tools, result content and native errors | Java clients and official dated suite |
| Resources, templates, prompts and JSON Schema | Official dated suite plus separate schema gate |
| Logging, progress, basic sampling and form elicitation | Official dated suite |
| Resource subscriptions and catalog notifications | Java clients, including delivery-failure and session-isolation cases |
| Roots changes, URL elicitation and parallel bidirectional correlation | Java clients |
| Prompt/resource argument completion and URL-elicitation completion notifications | Java clients, including completion after the tool response ends |
| Sessions, fresh identity, permission denial and token-expiry termination | Live HTTP and Java clients |
| External OAuth discovery, browser PKCE and scope escalation | No maintained external-provider end-to-end fixture |
| Startup ownership, explicit deletion and application shutdown | Live registration/client/lifecycle tests |

The [runnable public example](../examples/12-mcp/README.md) configures the official
suite's native catalog and runs the pinned dated requirements, session lifecycle
and JSON Schema checks. Its single Compose file also starts MCP Inspector for native
tool, resource and prompt interaction. External OAuth-provider login and browser PKCE
are not part of the maintained end-to-end fixtures; library authentication and transport
tests do not replace that external-provider verification.

Java SDK 2.0.1 does not implement SSE replay/resume despite its API placeholders.
The official polling scenario currently warns without exercising reconnect/replay;
that is not evidence of resume support. Do not rely on Last-Event-ID recovery.

The selected release also retains completed server-to-client exchanges until the
session ends, including successful ping, sampling, elicitation and roots requests.
The adapter does not fork or reach into private SDK state to remove them. Explicit
session deletion/app closure ends ownership; repeated bidirectional use of a
long-lived session can accumulate retained SDK state. See the
[released session implementation](https://github.com/modelcontextprotocol/java-sdk/blob/v2.0.1/mcp-core/src/main/java/io/modelcontextprotocol/spec/McpStreamableServerSession.java).
This limitation was accepted
for the pinned release, not fixed or measured away by compatibility tests.
Session-count limits and quiescent expiry reclaim abandoned sessions; they do not
bound completed-exchange retention inside a continuously active session.

Newer protocol revisions, Tasks, built-in pagination and sampling-with-tools are
not promised merely because newer specifications or schema types mention them.
Use the selected released SDK's actual configured capabilities.

The release has no implemented `notifications/cancelled` handler. Closing an HTTP
stream is not a protocol cancellation signal: detached native work remains owned
by its session until it completes or explicit session deletion/app closure cancels
it. Stdio, legacy HTTP+SSE and stateless transport modes are not supplied by this
Streamable HTTP adapter.
