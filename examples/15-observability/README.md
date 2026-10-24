# 15 — Observability

Consumer recipes: [logging](../../site/src/content/docs/observability/logging.mdx),
[metrics](../../site/src/content/docs/observability/metrics.mdx) and
[tracing](../../site/src/content/docs/observability/tracing.mdx).

| Recipe | Example | Test | Gradle project |
| --- | --- | --- | --- |
| Access logs | [AccessLoggingExample](micrometer/src/main/java/io/github/suppierk/shoostr/example/metrics/AccessLoggingExample.java) | [Test](micrometer/src/test/java/io/github/suppierk/shoostr/example/metrics/AccessLoggingExampleTest.java) | `micrometer-example` |
| Request metrics | [RequestMetricsExample](micrometer/src/main/java/io/github/suppierk/shoostr/example/metrics/RequestMetricsExample.java) | [Test](micrometer/src/test/java/io/github/suppierk/shoostr/example/metrics/RequestMetricsExampleTest.java) | `micrometer-example` |
| Request traces | [RequestTracingExample](opentelemetry/src/main/java/io/github/suppierk/shoostr/example/tracing/RequestTracingExample.java) | [Test](opentelemetry/src/test/java/io/github/suppierk/shoostr/example/tracing/RequestTracingExampleTest.java) | `opentelemetry-example` |

The [metrics fixture](micrometer/README.md) and [tracing fixture](opentelemetry/README.md)
retain the real-backend Docker checks and diagnostics for maintainers.
