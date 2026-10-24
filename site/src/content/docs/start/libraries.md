---
title: Choose your libraries
description: Choose the Shoostr libraries for your HTTP application and tests.
---

Shoostr is an experimental Java 21 HTTP framework with explicit route registration and optional integrations; its public interfaces can change.

Start with `shoostr-core` for your application and `shoostr-test` for its HTTP tests.

## Libraries

| Library | Purpose |
| --- | --- |
| `shoostr-core` | HTTP applications, routes, requests, responses, streaming, and browser policies. |
| `shoostr-http` | HTTP methods, statuses, headers, media types, cookies, and exceptions. Already included by `shoostr-core`. |
| `shoostr-test` | `TestServer`, a real HTTP fixture for test code. Keep it in your test dependencies. |
| `shoostr-pac4j` | Optional authentication with configured pac4j direct clients. |
| `shoostr-micrometer` | Optional request metrics with Micrometer. |
| `shoostr-opentelemetry` | Optional request tracing with OpenTelemetry. |
| `shoostr-openapi` | Explicit OpenAPI documents and documentation UI. |
| `shoostr-mcp` | MCP SDK integration with explicitly mounted HTTP transport. |

Adding an optional library does not enable its integration automatically; configure each integration explicitly when you need it.

Although the first-request test uses JUnit Jupiter, `TestServer` does not require a particular assertion framework.

## Dependency examples

The declarations below use the project's current coordinates, but remain tentative: they do not establish that these artifacts are published.
Replace `SHOOSTR_VERSION` with an available version, keeping both libraries on the same version.
Keep JUnit Jupiter and logging configured in your existing project.

### Maven

Add these declarations to your existing `pom.xml`, keeping `shoostr-test` in test scope.

```xml
<properties>
    <shoostr.version>SHOOSTR_VERSION</shoostr.version>
</properties>

<dependencies>
    <dependency>
        <groupId>io.github.suppierk</groupId>
        <artifactId>shoostr-core</artifactId>
        <version>${shoostr.version}</version>
    </dependency>
    <dependency>
        <groupId>io.github.suppierk</groupId>
        <artifactId>shoostr-test</artifactId>
        <version>${shoostr.version}</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### Gradle (Groovy)

Add these declarations to your existing `build.gradle`, keeping `shoostr-test` in `testImplementation`.

```groovy
def shoostrVersion = 'SHOOSTR_VERSION'

dependencies {
    implementation "io.github.suppierk:shoostr-core:${shoostrVersion}"
    testImplementation "io.github.suppierk:shoostr-test:${shoostrVersion}"
}
```

## Next step

[Handle your first HTTP request](../first-request/) with these libraries, without optional integrations.
