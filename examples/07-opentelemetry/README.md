# Request traces with Jaeger and Grafana

[TelemetryExample](src/main/java/io/github/suppierk/shoostr/example/tracing/TelemetryExample.java)
composes one app with inline routes. `TelemetryConfiguration` owns an explicit SDK,
OTLP HTTP exporter, batch processor and W3C propagation. The installed
`OpenTelemetryTracing(...).allRequests()` borrows the SDK. No global SDK, Java agent,
Collector or second Shoostr app is needed.

```sh
./gradlew :opentelemetry-example:jibDockerBuild
docker compose -f examples/07-opentelemetry/compose.yaml up -d
curl --fail --retry 60 --retry-delay 2 --retry-connrefused --retry-all-errors \
  --max-time 3 --retry-max-time 180 http://127.0.0.1:16686/ > /dev/null
curl --fail --retry 60 --retry-delay 2 --retry-connrefused --retry-all-errors \
  --max-time 3 --retry-max-time 180 http://127.0.0.1:3001/api/health
curl --fail --retry 60 --retry-delay 2 --retry-connrefused --retry-all-errors \
  --max-time 3 --retry-max-time 180 http://127.0.0.1:8080/hello/ready > /dev/null
curl -i http://127.0.0.1:8080/hello/Ada
```

The readiness request generates its own separate trace; use the ID from the subsequent
`/hello/Ada` request for the demonstration.

Copy the `X-Trace-Id` response header. Open [Grafana Explore](http://127.0.0.1:3001/explore),
login **demo / local-only**, select **Shoostr Jaeger**, choose **Trace ID**, paste the ID
and run the query. The trace shows a SERVER span named `GET /hello/{name}` for service
`shoostr-tracing-example`, with `http.route` and response status (Jaeger's query API
translates the exported `http.response.status_code` to `http.status_code`). The same ID is visible
in [Jaeger](http://127.0.0.1:16686). Grafana's datasource is provisioned automatically;
its pinned signed Jaeger plugin requires Internet access on first startup.

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
docker compose -f examples/07-opentelemetry/compose.yaml down --volumes
```

The separately invoked Docker test sends a unique sampled W3C trace, checks its actual
SERVER span in Jaeger, then retrieves that same trace through Grafana. It builds with
Jib and removes only its uniquely named stack. Logs are under `build/docker/compose.log`;
JUnit XML is under `build/test-results/dockerTest/`. Ordinary `build` excludes Docker.

For a host-run app, start the backends separately and set
`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` to a reachable OTLP HTTP `/v1/traces` endpoint before
`./gradlew :opentelemetry-example:run`; the default is `http://127.0.0.1:4318/v1/traces`.
The Compose recipe keeps exporter ingress private to its network.
