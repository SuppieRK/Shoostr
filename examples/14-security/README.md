# 14 — Security

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
