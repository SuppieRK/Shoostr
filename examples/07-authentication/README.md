# Route-group authentication and authorization

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
fixtures, not a production identity store. Basic requires TLS in deployment;
Direct runs bind all IPv4 interfaces; use only a trusted development environment or
configure an explicit loopback `Options` host. Compose publishes only host loopback.
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
`docker compose -f examples/07-authentication/compose.yaml up -d`.
The same curl sequence works; stop with that Compose command ending in `down`.
Set `APP_PORT=8081` to change the host port.
