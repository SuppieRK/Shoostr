# Shoostr

A Java web framework for HTTP APIs.

Define routes and handle requests through a fluent Java API. Build on Jetty and configure
HTTP behavior in application code. Shoostr requires Java 21.

- Typed handlers and scoped integration configuration keep API behavior in your code.
- Serve HTTP, multipart uploads, static files, streaming responses, SSE, and WebSockets.
- Performance work measures latency, allocation, and retained memory.

Start with the [complete Java example and its HTTP test](https://suppierk.github.io/Shoostr/start/first-request/).
Application code does not need framework annotations. Platform threads are the default;
virtual threads are opt-in.

Your application chooses focused libraries and connects them through ordinary Java calls.
Authentication, metrics, tracing, OpenAPI, and MCP integrations are optional. The
[stream admission example](https://suppierk.github.io/Shoostr/lifecycle/executors/#compose-stream-admission)
shows how an application uses Resilience4j's admission API and defines its HTTP failure
response. Your team owns configuration, security updates, and compatibility checks for
the libraries it selects.

The public interfaces can change.

## Scope

Shoostr's feature scope is HTTP serving: routing, protocol handling, request and response
ownership, and transport lifecycle. Behavior that an existing external library can provide
through composition is outside that scope.

Shoostr adds serving contracts where composition requires them. Integrations connect those
contracts to existing libraries without recreating their features or introducing parallel
configuration models.

[Documentation](https://suppierk.github.io/Shoostr/) · [Contribute](CONTRIBUTING.md) · [Apache License 2.0](LICENSE)
