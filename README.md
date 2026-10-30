# Shoostr

Build HTTP applications with ordinary Java and explicit configuration.

Shoostr is an HTTP serving library for Java 21, built on Jetty. Register routes with Java calls
and add integrations when you need them. Platform threads are the default; virtual threads are
opt-in. Application code does not need framework annotations.

- HTTP routing, multipart uploads, static files, streaming, SSE, and WebSockets.
- Optional authentication, Micrometer metrics, OpenTelemetry tracing, OpenAPI, and MCP integrations.
- Runnable examples with live HTTP tests.

The public interfaces can change.

## Scope

Shoostr focuses on HTTP serving: routing, protocol handling, request and response ownership,
and transport lifecycle. Behavior that an existing external library can provide through
composition is outside Shoostr's feature scope. Applications select and configure libraries
such as Resilience4j for bulkheads, retries, circuit breakers and other application policies.

Shoostr adds only the serving contracts required to make that composition work. Integrations
bridge those contracts to existing libraries; they do not recreate the libraries' features or
introduce parallel configuration models. The examples show explicit library composition.

[Documentation](https://suppierk.github.io/Shoostr/) · [Contribute](CONTRIBUTING.md) · [Apache License 2.0](LICENSE)
