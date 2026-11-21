# Shoostr

[![SonarQube Cloud quality gate](https://sonarcloud.io/api/project_badges/measure?project=SuppieRK_Shoostr&metric=alert_status)](https://sonarcloud.io/dashboard?id=SuppieRK_Shoostr)
[![SonarQube Cloud coverage](https://sonarcloud.io/api/project_badges/measure?project=SuppieRK_Shoostr&metric=coverage)](https://sonarcloud.io/dashboard?id=SuppieRK_Shoostr)
[![shoostr-core on Maven Central](https://img.shields.io/maven-central/v/io.github.suppierk/shoostr-core?label=shoostr-core)](https://central.sonatype.com/artifact/io.github.suppierk/shoostr-core)
[![shoostr-test on Maven Central](https://img.shields.io/maven-central/v/io.github.suppierk/shoostr-test?label=shoostr-test)](https://central.sonatype.com/artifact/io.github.suppierk/shoostr-test)
[![shoostr-core Javadoc](https://www.javadocs.dev/io.github.suppierk/shoostr-core/badge.svg)](https://www.javadocs.dev/io.github.suppierk/shoostr-core/latest)

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
