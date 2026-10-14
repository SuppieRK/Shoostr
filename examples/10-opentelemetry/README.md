# Request traces with Jaeger and Grafana

[TelemetryExample](src/main/java/io/github/suppierk/shoostr/example/tracing/TelemetryExample.java)
composes one app with inline routes. `TelemetryConfiguration` owns an explicit SDK,
OTLP HTTP exporter, batch processor and W3C propagation. The installed
`OpenTelemetryTracing(...).allRequests()` borrows the SDK. No global SDK, Java agent,
Collector or second Shoostr app is needed.

```sh
./gradlew :opentelemetry-example:jibDockerBuild
docker compose -f examples/10-opentelemetry/compose.yaml up -d
curl --fail --retry 60 --retry-delay 2 --retry-connrefused --retry-all-errors \
  --max-time 3 --retry-max-time 180 http://127.0.0.1:16686/ > /dev/null
curl --fail --retry 60 --retry-delay 2 --retry-connrefused --retry-all-errors \
  --max-time 3 --retry-max-time 180 http://127.0.0.1:3001/api/health
curl --fail --retry 60 --retry-delay 2 --retry-connrefused --retry-all-errors \
  --max-time 3 --retry-max-time 180 http://127.0.0.1:8080/hello/ready > /dev/null
curl -i http://127.0.0.1:8080/hello/Ada
```

Open [Grafana](http://127.0.0.1:3001), login **demo / local-only**; the trace dashboard
opens automatically. It lists up to 20 greeting traces from the last 15 minutes and
refreshes every five seconds. Click a **Trace ID** to inspect its spans; no copying an ID
or configuring a query is necessary. The
[direct dashboard link](http://127.0.0.1:3001/d/shoostr-traces) also works.
The readiness request generates a separate trace and appears in the same table.
This example displays traces, not numeric request metrics; use
[09-micrometer](../09-micrometer/README.md) for metrics.

The trace shows a SERVER span named `GET /hello/{name}` for service
`shoostr-tracing-example`, with `http.route` and `http.response.status_code`. The same ID is visible
in [Jaeger](http://127.0.0.1:16686). For a specific trace, you can still copy the
`X-Trace-Id` response header into [Grafana Explore](http://127.0.0.1:3001/explore),
select **Shoostr Jaeger** and choose **Trace ID**.
Grafana's datasource and dashboard are provisioned automatically; its pinned signed
Jaeger plugin requires Internet access on first startup.

The example pins Jaeger to 2.20.0 because Grafana's Jaeger plugin 13.0.5 uses the legacy
search API, [removed in Jaeger 2.21](https://github.com/jaegertracing/jaeger/releases/tag/v2.21.0).
This compatibility pin excludes fixes from subsequent Jaeger releases; it is a disposable
development fixture, not a production backend recommendation. Upgrade the pair together
once the plugin supports Jaeger's current search API.

To demonstrate continuation of a remote parent:

```sh
curl -i -H 'traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01' \
  http://127.0.0.1:8080/hello/Grace
```

Allow time for batch export (configured at 100 ms) and ingestion. The SDK closes after
request drain, flushing accepted spans. Jaeger uses disposable in-memory storage;
stopping the stack loses traces. `APP_PORT`, `JAEGER_PORT` and `GRAFANA_PORT` override
loopback-published ports. Fixture logins are not a production security policy.

```sh
./gradlew :opentelemetry-example:test
./gradlew :opentelemetry-example:dockerTest
docker compose -f examples/10-opentelemetry/compose.yaml down --volumes
```

The separately invoked Docker tests verify default dashboard landing, send unique sampled
W3C traces, discover one through the provisioned search query without supplying its ID,
and verify native trace links. They also check an actual SERVER span in Jaeger and retrieve
that same trace through Grafana. The check builds with
Jib and removes only its uniquely named stack. Logs are under `build/docker/compose.log`;
JUnit XML is under `build/test-results/dockerTest/`. Ordinary `build` excludes Docker.

For a host-run app, start the backends separately and set
`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` to a reachable OTLP HTTP `/v1/traces` endpoint before
`./gradlew :opentelemetry-example:run`; the default is `http://127.0.0.1:4318/v1/traces`.
The Compose recipe keeps exporter ingress private to its network.
