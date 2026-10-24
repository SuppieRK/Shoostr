# 10 — Streaming

Consumer recipes: [finite output](../../site/src/content/docs/streaming/output.mdx)
and [server-sent events](../../site/src/content/docs/streaming/sse.mdx).

| Recipe | Example | Test | Gradle project |
| --- | --- | --- | --- |
| Finite output | [FiniteStreamingExample](finite/src/main/java/io/github/suppierk/shoostr/example/streaming/FiniteStreamingExample.java) | [Test](finite/src/test/java/io/github/suppierk/shoostr/example/streaming/FiniteStreamingExampleTest.java) | `static-streaming-example` |
| Server-sent events | [SseFeedExample](sse/src/main/java/io/github/suppierk/shoostr/example/sse/SseFeedExample.java) | [Test](sse/src/test/java/io/github/suppierk/shoostr/example/sse/SseFeedExampleTest.java) | `sse-example` |

The [finite-stream fixture](finite/README.md) and [SSE fixture](sse/README.md)
retain launcher, container and browser checks for maintainers.
