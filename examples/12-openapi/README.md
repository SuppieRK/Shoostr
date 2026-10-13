# OpenAPI example

Run `cmdshape ./gradlew :openapi-example:run` with Java 25. Open
`http://127.0.0.1:8080/docs/` and answer the browser's HTTP Basic challenge with
**demo / local-only**. These are public local-development fixture credentials, not a production
identity store. The default bind is all IPv4 interfaces (`0.0.0.0`), not loopback.
Use a trusted development environment. Production Basic authentication requires TLS;
replace this authenticator with a real identity provider.

Page, CSS, bundle, initializer and both specs all require authentication. Browser bootstrap
authentication is separate from Swagger UI's Authorize button; after opening the page,
use Authorize → basic for protected API operations. Credentials are not embedded in UI resources
and Swagger authorization persistence is disabled. API Try it out requests omit cached browser
Basic credentials and cookies, so the docs login alone does not authorize them. Missing Swagger
authorization returns 401; explicit Authorize headers work normally. Cookie/session-based API
authentication is not supported by this UI policy. Startup prints the demo username and password;
use a fresh browser profile for a clean challenge check.

| Route                                    | Behavior                                                                   |
| ---------------------------------------- | -------------------------------------------------------------------------- |
| `GET /health`                            | Public plain text; Try it out returns `healthy`.                           |
| `GET /users/`                            | Public plain text beside the JSON route.                                   |
| `GET /users/{id:[0-9]+}`                 | Public explicit JSON bytes; group defaults and a constrained path.         |
| `POST /users`                            | Basic-protected typed JSON, explicit User schema; returns 201.             |
| `GET /me`                                | Basic-protected principal; returns `demo`.                                 |
| `GET /admin/ping`                        | Protected, documented only by the independent Operations API.              |
| `GET /dev-only`                          | Conditional endpoint documented only in the Operations API.                |
| `GET /docs/` and `GET /docs/{*resource}` | Explicit page/allowlisted resources; protected and undocumented.           |
| `GET /openapi.json`                      | Protected Users API specification; safely documents its own serving route. |
| `GET /internal-openapi.json`             | Protected separate Operations API specification.                           |

Select POST `/users` in the UI, authorize basic credentials, then send `{"name":"Alice"}`.
Try it out should return 201 and the same JSON. Missing credentials return 401; a malformed name
returns 400. `/users/alice` returns 404. Group response overrides preserve unrelated defaults.

`OpenApiExample.application()` returns an unstarted app, allowing the test harness to
choose an ephemeral port. Its dependencies and schemas are application-owned configuration.
The example codec converts explicit JSON trees without scanning application models; ordinary
byte/text handlers remain available in the same app. OpenAPI does not discover codec types.

Run `cmdshape ./gradlew :openapi-example:test` for live HTTP checks. The root build includes
example compilation and these checks; browser verification additionally needs a real browser
to confirm challenge/bootstrap, rendering and Try it out.

The app factory defines routes inline. `OpenApiConfiguration` defines documents and schemas;
`OpenApiAuthentication` defines the local identity policy;
`User` and `UserCodec` own the payload contract and conversion. No document-schema setup or
credential verification is hidden in main.

For a Dockerfile-free local container:

```sh
cmdshape ./gradlew :openapi-example:jibDockerBuild
cmdshape docker compose -f examples/12-openapi/compose.yaml up -d
cmdshape docker compose -f examples/12-openapi/compose.yaml down
```

Open the same `/docs/` URL and use the same fixture credentials. Java25 runs as a nonroot
container user; the listener binds all IPv4 interfaces, while Compose publishes
host loopback. `APP_PORT=8081` changes the published port. The core owns JVM shutdown.
