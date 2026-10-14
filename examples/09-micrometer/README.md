# Request metrics with Prometheus and Grafana

One Shoostr app exports its own `/metrics`; no second metrics server or DI container is
needed. [MetricsExample](src/main/java/io/github/suppierk/shoostr/example/metrics/MetricsExample.java)
defines its routes inline and constructs the Prometheus registry explicitly.
`MicrometerMetrics` borrows that caller-configured registry and enables observation
with `allRequests()`. The example registers `app.afterClose(registry::close)` to
release the registry after request drain and extension cleanup; the registry is
not itself an extension. Factory/startup failures still close the app immediately
instead of waiting for JVM shutdown.

```sh
./gradlew :micrometer-example:jibDockerBuild
docker compose -f examples/09-micrometer/compose.yaml up -d
curl --fail --retry 60 --retry-delay 2 --retry-connrefused --retry-all-errors \
  --max-time 3 --retry-max-time 180 http://127.0.0.1:8080/metrics > /dev/null
curl --fail --retry 60 --retry-delay 2 --retry-connrefused --retry-all-errors \
  --max-time 3 --retry-max-time 180 http://127.0.0.1:9090/-/ready
curl --fail --retry 60 --retry-delay 2 --retry-connrefused --retry-all-errors \
  --max-time 3 --retry-max-time 180 http://127.0.0.1:3000/api/health
curl http://127.0.0.1:8080/hello/Ada
curl http://127.0.0.1:8080/hello/Grace
curl http://127.0.0.1:8080/hello/Linus
```

Open [Grafana's provisioned dashboard](http://127.0.0.1:3000/d/shoostr-requests),
login **demo / local-only**. It shows completed requests, rate, mean duration, server errors
and active requests. Prometheus scrapes every two seconds; rate panels need multiple samples.
The bounded readiness requests above finish before generating the three greetings.
The completed-request count advances by three, grouped by the route template, not user input:

```promql
http_server_requests_seconds_count{route="/hello/{name}",status="200"}
```

[Prometheus](http://127.0.0.1:9090) can run the same query. `/metrics` and readiness traffic
are also instrumented; filter by the greeting template as above. The active-request gauge
includes the scrape request itself. Grafana installs its pinned, signed Prometheus plugin
on startup, requiring Internet access on first boot. Datasource and dashboard files are
provisioned read-only; no manual dashboard setup is necessary.

`APP_PORT`, `PROMETHEUS_PORT` and `GRAFANA_PORT` change published ports. All host bindings
are loopback; these credentials, unsecured scrape endpoint and disposable backend storage
are development fixtures, not a production monitoring security policy.

```sh
./gradlew :micrometer-example:test
./gradlew :micrometer-example:dockerTest
docker compose -f examples/09-micrometer/compose.yaml down --volumes
```

The Docker check builds with Jib, verifies actual Prometheus ingestion and Grafana queries,
then removes its uniquely named stack. Diagnostics: `examples/09-micrometer/build/docker/compose.log`
and `build/test-results/dockerTest/`. Ordinary `build` excludes Docker checks. Running with
`./gradlew :micrometer-example:run` exposes metrics without creating any backend.
