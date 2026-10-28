# 14 — Security

## Run with Docker

Use Docker Engine with Compose v2 or later and Buildx. Run from the repository root;
Java and Gradle are provided by the build image. Start one example at a time:

```sh
docker compose -f examples/14-security/compose.yaml up --build -d
```

Startup is asynchronous. Check diagnostics with `docker compose -f examples/14-security/compose.yaml logs -f app`.
Open `http://127.0.0.1:8080/public`. Expect public.
Retry the request if the listener is not ready yet. Set `APP_PORT=8081` before the command if port 8080 is occupied, and use that port in requests.
Compose exposes the app on host loopback. These credentials and unauthenticated fixtures
are for trusted local development. Direct Java runs use the default wildcard listener.

To build with Jib inside Docker instead, stop the stack and export/load its image:

```sh
docker compose -f examples/14-security/compose.yaml down --timeout 20
docker buildx build -f examples/14-security/Dockerfile --target jib --output type=local,dest=build/authentication-example-jib .
docker load --input build/authentication-example-jib/jib-image.tar
docker compose -f examples/14-security/compose.yaml pull --ignore-buildable
docker compose -f examples/14-security/compose.yaml up --no-build --pull never --force-recreate -d
```

Both paths use the same local image tag. Stop and remove the stack with
`docker compose -f examples/14-security/compose.yaml down --timeout 20 --volumes`.
Use `docker compose -f examples/14-security/compose.yaml logs` for diagnostics.
Optional `./gradlew` commands below require a local Java 21 installation;
container validation is described in [CONTRIBUTING](../../CONTRIBUTING.md#example-container-validation).

## Recipe and direct-run notes

Consumer recipes cover [authentication](../../site/src/content/docs/security/authentication.mdx),
[pac4j](../../site/src/content/docs/security/pac4j.mdx),
[sessions](../../site/src/content/docs/security/sessions.mdx),
[CSRF](../../site/src/content/docs/security/csrf.mdx),
[login and logout](../../site/src/content/docs/security/login-logout.mdx),
[CORS](../../site/src/content/docs/security/cors.mdx) and
[trusted proxies](../../site/src/content/docs/security/proxies.mdx).

The consumer recipes use seven self-contained factories with matching tests:

- [RouteAuthenticationExample](src/main/java/io/github/suppierk/shoostr/example/authentication/RouteAuthenticationExample.java) — route-selected principals and permission checks.
- [Pac4jAuthenticationExample](src/main/java/io/github/suppierk/shoostr/example/authentication/Pac4jAuthenticationExample.java) — direct credential validation and role authorization.
- [SessionExample](src/main/java/io/github/suppierk/shoostr/example/authentication/SessionExample.java) — cookie policy, identifier renewal, invalidation, and an explicit file store.
- [CsrfProtectionExample](src/main/java/io/github/suppierk/shoostr/example/authentication/CsrfProtectionExample.java) — session-bound tokens, strict source checks and selected write protection.
- [LoginLogoutExample](src/main/java/io/github/suppierk/shoostr/example/authentication/LoginLogoutExample.java) — application login/logout with native session transitions and fresh CSRF token delivery.
- [CorsExample](src/main/java/io/github/suppierk/shoostr/example/authentication/CorsExample.java) — exact-origin response sharing and preflight.
- [TrustedProxyExample](src/main/java/io/github/suppierk/shoostr/example/authentication/TrustedProxyExample.java) — physical-peer trust and effective metadata.

Their complete tests live beside the original launcher tests in `src/test/java`.
The original launcher and container instructions below remain available for repository maintenance.

Run `./gradlew :authentication-example:run`.

```sh
curl -i http://127.0.0.1:8080/public
curl -i http://127.0.0.1:8080/private/me
curl -i -u demo:local-only http://127.0.0.1:8080/private/me
curl -i -u demo:local-only http://127.0.0.1:8080/private/admin
curl -i -u admin:local-only http://127.0.0.1:8080/private/admin
```

The public endpoint returns200 without credentials. The protected requests return
401 with a Basic challenge,200 with `demo`,403 for the authenticated
reader, and200 with `admin operation allowed` for the admin identity.
Both passwords are **local-only**, printed at startup. These are public local
fixtures, not a production identity store. Basic requires TLS in deployment.
Direct runs use Jetty's wildcard listener. Use a trusted development environment or
set the default `ServerConnector` host to `127.0.0.1` through `modifyServer`.
Compose publishes only host loopback.
Do not expose this demo to an untrusted network.

[AuthenticationExample](src/main/java/io/github/suppierk/shoostr/example/authentication/AuthenticationExample.java)
wires the app and defines routes inline, selecting authentication for the whole
`/private` group. [DemoAuthentication](src/main/java/io/github/suppierk/shoostr/example/authentication/DemoAuthentication.java)
owns credential verification and its native Pac4j role policy. Authentication
establishes identity; authorization separately decides whether an operation is allowed.
Installation alone does not protect any endpoint. No wrapper around each handler,
global authentication chain or exception-handling extension is necessary.

Run live checks with `./gradlew :authentication-example:test`.

Docker: `./gradlew :authentication-example:jibDockerBuild`, then
`docker compose -f examples/14-security/compose.yaml up -d`.
The same curl sequence works; stop with that Compose command ending in `down`.
Set `APP_PORT=8081` to change the host port.
