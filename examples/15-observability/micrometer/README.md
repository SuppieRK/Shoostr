# Request metrics with Prometheus and Grafana

## Run with Docker

Use Docker Engine with Compose v2 or later and Buildx. Run from the repository root;
Java and Gradle are provided by the build image. Start one example at a time:

```sh
docker compose -f examples/15-observability/micrometer/compose.yaml up --build -d
```

Startup is asynchronous. Check diagnostics with `docker compose -f examples/15-observability/micrometer/compose.yaml logs -f app`.
Open `http://127.0.0.1:8080/hello/Ada`. Expect Hello, Ada!; backend checks are below.
Retry the request if the listener is not ready yet. Set `APP_PORT=8081` before the command if port 8080 is occupied, and use that port in requests.
Compose exposes the app on host loopback. These credentials and unauthenticated fixtures
are for trusted local development. Direct Java runs use the default wildcard listener.

To build with Jib inside Docker instead, stop the stack and export/load its image:

```sh
docker compose -f examples/15-observability/micrometer/compose.yaml down --timeout 20
docker buildx build -f examples/15-observability/micrometer/Dockerfile --target jib --output type=local,dest=build/micrometer-example-jib .
docker load --input build/micrometer-example-jib/jib-image.tar
docker compose -f examples/15-observability/micrometer/compose.yaml pull --ignore-buildable
docker compose -f examples/15-observability/micrometer/compose.yaml up --no-build --pull never --force-recreate -d
```

Both paths use the same local image tag. Stop and remove the stack with
`docker compose -f examples/15-observability/micrometer/compose.yaml down --timeout 20 --volumes`.
Use `docker compose -f examples/15-observability/micrometer/compose.yaml logs` for diagnostics.
Optional `./gradlew` commands below require a local Java 21 installation;
container validation is described in [CONTRIBUTING](../../../CONTRIBUTING.md#example-container-validation).

## Recipe and direct-run notes

Maintainer backend fixture for the [request metrics recipe](../../../site/src/content/docs/observability/metrics.mdx).

The recipe uses [RequestMetricsExample](src/main/java/io/github/suppierk/shoostr/example/metrics/RequestMetricsExample.java),
with complete tests in this module.
These fixtures verify local instrumentation; the backend lane below separately
checks actual Prometheus ingestion and Grafana queries.

One Shoostr app exports its own `/metrics`; no second metrics server or DI container is
needed. [MetricsExample](src/main/java/io/github/suppierk/shoostr/example/metrics/MetricsExample.java)
defines its routes inline and constructs the Prometheus registry explicitly.
`MicrometerMetrics` borrows that caller-configured registry and enables observation
with `allRequests()`. The example registers `app.afterClose(registry::close)` to
release the registry after request drain and extension cleanup; the registry is
not itself an extension. Core handles listener startup failures and JVM shutdown.

```sh
./gradlew :micrometer-example:jibDockerBuild
docker compose -f examples/15-observability/micrometer/compose.yaml up -d
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

Open [Grafana](http://127.0.0.1:3000), login **demo / local-only**; the request dashboard
opens automatically and refreshes every five seconds. The
[direct dashboard link](http://127.0.0.1:3000/d/shoostr-requests) also works.
It shows completed requests, rate, mean duration, server errors and active requests.
Prometheus scrapes every two seconds; rate panels need multiple samples and return to zero
when greeting traffic stops. Before any greeting requests, greeting panels can show no data.
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
docker compose -f examples/15-observability/micrometer/compose.yaml down --volumes
```

The Docker check builds with Jib, verifies default dashboard landing, actual Prometheus
ingestion and the provisioned dashboard query through Grafana,
then removes its uniquely named stack. Diagnostics: `examples/15-observability/micrometer/build/docker/compose.log`
and `build/test-results/dockerTest/`. Ordinary `build` excludes Docker checks. Running with
`./gradlew :micrometer-example:run` exposes metrics without creating any backend.
