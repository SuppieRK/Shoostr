# MCP with external OAuth

The runnable application installs separate Pac4j policies for Basic REST and Bearer
MCP, explicitly registers public discovery, and delegates native protocol features
to the MCP SDK. The app owns the MCP server and verifier key source; core handles
shutdown, without an extra example hook. The external authorization server is not
part of Shoostr. The browser test uses the official TypeScript MCP client against
the same application factory, not a mock authorization server or password grant.

Run these commands from this directory with Docker Engine, Compose and OpenSSL installed:

```sh
SHOOSTR_OAUTH_KEYSTORE_PASSWORD="$(cmdshape openssl rand -hex 24)"
export SHOOSTR_OAUTH_KEYSTORE_PASSWORD
cmdshape mkdir -p .local
cmdshape openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 2 \
  -keyout .local/fixture-ca.key -out .local/fixture-ca.pem \
  -subj '/CN=Shoostr local fixture CA' \
  -addext 'basicConstraints=critical,CA:TRUE' \
  -addext 'keyUsage=critical,keyCertSign,cRLSign'
cmdshape openssl req -new -newkey rsa:2048 -sha256 -nodes \
  -keyout .local/fixture-key.pem -out .local/fixture.csr \
  -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' \
  -addext 'basicConstraints=critical,CA:FALSE' \
  -addext 'keyUsage=critical,digitalSignature,keyEncipherment' \
  -addext 'extendedKeyUsage=serverAuth'
cmdshape openssl x509 -req -in .local/fixture.csr \
  -CA .local/fixture-ca.pem -CAkey .local/fixture-ca.key -CAcreateserial \
  -out .local/fixture-cert.pem -days 2 -sha256 -copy_extensions copy
cmdshape openssl pkcs12 -export -in .local/fixture-cert.pem \
  -inkey .local/fixture-key.pem -certfile .local/fixture-ca.pem \
  -out .local/fixture.p12 -name localhost -passout env:SHOOSTR_OAUTH_KEYSTORE_PASSWORD
```

Keep `SHOOSTR_OAUTH_KEYSTORE_PASSWORD` exported in the same shell for the application,
Gradle OAuth verification and container commands below. It must match the password
used to create `.local/fixture.p12`; the application rejects an absent or blank value
without a fallback. If using another shell, supply that same value rather than generating
a new one for an existing keystore. Do not commit or log it. This remains a disposable
local fixture: the unencrypted PEM private keys also require the file permissions below.

On Linux, the pinned Keycloak container runs as UID1000. After generating PKCS12,
grant that process access to only its disposable server key, especially when your
host account has a different UID. Keep the private key owner-readable, not
world-readable; the CA key and PKCS12 file remain owned by your account. Docker
Desktop uses different bind-mount permission handling and does not need this step.

```bash
cmdshape sudo chown 1000 .local/fixture-key.pem
```

Then start the fixture:

```bash
cmdshape docker compose -p shoostr-mcp-oauth up -d keycloak
cmdshape curl --fail --retry 30 --retry-delay 2 --retry-connrefused --retry-all-errors \
  --max-time 5 --retry-max-time 120 http://127.0.0.1:9000/health/ready
cmdshape curl --fail --cacert .local/fixture-ca.pem \
  https://localhost:8443/realms/shoostr-mcp/.well-known/openid-configuration
cmdshape curl --fail --cacert .local/fixture-ca.pem \
  https://localhost:8443/realms/shoostr-mcp/protocol/openid-connect/certs
```

Use the final discovery and JWKS requests to check realm readiness and certificate
trust, not just an accepting socket. Certificates are local, short-lived fixture
material, ignored by Git. Do not disable TLS verification or use this configuration
in production. Recreating a certificate requires restarting Keycloak and reloading
the certificate in clients. The server certificate is a distinct CA-signed leaf:
do not serve the CA certificate itself, which the strict Firefox test rejected.
Never import this fixture CA into an ordinary browser profile or system-wide trust store.
The Inspector walkthrough below uses only a disposable, dedicated browser profile.

Demo login: **`mcp-demo` / `mcp-demo-local-only`**. This account is only for the
isolated test realm. No bootstrap administrator, client secret, password grant or
dynamic client registration is configured.

- Issuer: `https://localhost:8443/realms/shoostr-mcp`.
- MCP resource: `https://localhost:9443/mcp`.
- Pre-registered public client: `shoostr-mcp-example-client`.
- Callback: `http://localhost:8765/callback`.
- Inspector callback: `http://localhost:6274/oauth/callback` (the same preregistered public client).
- Authorization code flow requires S256 PKCE; optional grants are `mcp:read` and
  `mcp:tools`. All MCP requests require `mcp:read`; `increment` additionally requires
  `mcp:tools`, checked before native dispatch. The challenge carries the complete
  required scope set. The explicit native subject mapper keeps the required `sub`
  claim even though broad default client scopes are not installed.
- REST: `https://localhost:9443/api/profile`, Basic login
  `rest-demo` / `rest-demo-local-only`. These credentials do not authenticate MCP;
  a Bearer token does not authenticate REST.

The image is pinned to Keycloak **26.8.0** and its manifest digest. Resource indicators
are an explicitly enabled experimental Keycloak feature. The fixture uses `start`
with HTTPS only and local caching, rather than the insecure listener and clustering
defaults encountered during setup. Only HTTPS and health are published, both on
host loopback. The container has a 1 GiB memory limit and a 512 MiB maximum JVM heap.
The embedded file database is disposable fixture state, not a production database.

Inspect logs and stop only this example's project:

```sh
cmdshape docker compose -p shoostr-mcp-oauth logs keycloak
cmdshape docker compose -p shoostr-mcp-oauth down --timeout 20
```

No named database volume is created. `down` removes this fixture's container and
disposable realm database; a subsequent `up` imports the checked-in realm again.
Use a different project name and nonconflicting ports for parallel fixtures, updating
the canonical issuer, resource and callback consistently. Do not run `down` against
another user's project.

See Keycloak's [container guide](https://www.keycloak.org/server/containers),
[health guide](https://www.keycloak.org/server/health), and
[MCP authorization-server guide](https://www.keycloak.org/securing-apps/mcp-authz-server).

## Run the application and browser test

Install Node.js 22 and the pinned client/browser tooling from this directory:

```sh
cmdshape npm ci --ignore-scripts
cmdshape node node_modules/playwright/cli.js install --with-deps firefox
```

With Keycloak ready, run the automated HTTPS browser test from the repository root:

```sh
cmdshape ./gradlew :mcp-oauth-example:oauthTest
```

The test owns the Shoostr app on loopback port 9443 and the temporary browser and
callback listener on port 8765. It leaves the separately started Keycloak container
running. It trusts only the local fixture CA for its Node process and temporary
Firefox profile; it does not change browser/system trust or disable HTTPS checks.
Reports are under `examples/13-mcp-oauth/build/test-results/oauthTest/` and the
sanitized driver output is `examples/13-mcp-oauth/build/oauth/runner.log`.

To run the example manually, build and start its installed script from this directory:

```sh
cmdshape ../../gradlew :mcp-oauth-example:installDist
cmdshape build/install/mcp-oauth-example/bin/mcp-oauth-example
```

To run the browser driver against that already-running app instead of starting
another app through Gradle:

```sh
NODE_EXTRA_CA_CERTS="$PWD/.local/fixture-ca.pem" cmdshape node --test oauth-test.mjs
```

The test verifies discovery, S256 PKCE, the exact token resource audience, login,
callback state/parameter rejection and single-use callbacks/codes. It first
obtains `mcp:read`, observes a tool permission denial without a side effect, then
obtains the union `mcp:read mcp:tools` and retries successfully. It also verifies
separate Basic REST authentication and Bearer POST/GET/DELETE with session deletion.
Independent browser grants also exercise wrong/missing PKCE verifiers, a mismatched
redirect URI, and missing/changed resource indicators. Those must produce an OAuth
grant/target error or a token rejected by the MCP resource server with401; no
negative resource grant may create an MCP session.
Decoded client-side claims are observations, not signature verification: successful
server operations additionally pass the actual verifier described below.

CI runs both the Docker-free signed-token tests and this explicit external test,
with an isolated pinned Keycloak fixture. It preserves sanitized diagnostics, not
tokens, browser traces, private keys or the fixture trust files.

## Run the application in a Jib-built container

Security/discovery configuration is in `OAuthConfiguration`, native MCP tools in
`OAuthMcpConfiguration`, and inline HTTP registration and startup wiring in
`McpOAuthExample`. The installed security configuration owns the verifier and is closed
after its borrowing adapters. The public application factory and existing HTTP behavior
remain the same.

After generating the TLS fixture above, build from the repository root:

```sh
cmdshape ./gradlew :mcp-oauth-example:jibDockerBuild
```

From this example directory, on Linux make the disposable PKCS12 server keystore readable
by the Jib app's UID1000, just as the Keycloak server key was above. Do not make it world-readable:

```sh
cmdshape sudo chown 1000 .local/fixture.p12
cmdshape docker compose -p shoostr-mcp-oauth -f compose.yaml -f compose.app.yaml up -d
cmdshape curl --fail --cacert .local/fixture-ca.pem \
  https://localhost:9443/.well-known/oauth-protected-resource/mcp
NODE_EXTRA_CA_CERTS="$PWD/.local/fixture-ca.pem" cmdshape node --test oauth-test.mjs
cmdshape docker compose -p shoostr-mcp-oauth -f compose.yaml -f compose.app.yaml down --timeout 20
```

Use the readiness/discovery checks above before the browser driver. The overlay shares
Keycloak's network namespace with the one Shoostr app and publishes9443 there. This
retains the exact HTTPS `localhost` issuer/resource for both browser and server without
disabling TLS verification or rewriting token audiences. Compose may recreate an existing
fixture to add that published port; run it only against your own disposable project.
The base recipe remains for a host-run app or Gradle `oauthTest`; stop the containerized
app before running that test, since both need port9443. The container image uses Java25,
nonroot UID1000 and a read-only keystore mount; no real secrets are built into the image.
The rest of `.local`, including private keys, is never uploaded as CI diagnostics.

## Explore authenticated MCP with the bundled Inspector

The optional Inspector overlay adds a development tool, not an OpenAPI document or
Java dependency. Generate the TLS fixture and export its keystore password as above,
then build the application image from the repository root:

```sh
cmdshape ./gradlew :mcp-oauth-example:jibDockerBuild
```

From this example directory, start the app, Keycloak and Inspector together:

```sh
cmdshape docker compose -p shoostr-mcp-oauth \
  -f compose.yaml -f compose.app.yaml -f compose.inspector.yaml \
  up -d --wait --wait-timeout 90
```

This first run may recreate the disposable Keycloak fixture to publish Inspector's
port and import the additional exact callback. Existing fixture sessions are then lost;
no named database volume is used. If reusing an older realm configuration, recreate the
complete stack with the same files rather than assuming a running Keycloak reloads imports.
The Linux PKCS12 ownership requirement from the container instructions still applies.

Use a **dedicated disposable Firefox profile** for the interactive login:

1. Open Firefox's `about:profiles`, create a profile named `Shoostr MCP fixture`, and
   launch that profile in a new browser. Do not set it as your default profile.
2. In that profile only, open Settings → Privacy & Security → Certificates → View
   Certificates → Authorities → Import. Select this example's `.local/fixture-ca.pem`
   and trust it to identify websites. Import the public CA, never a private key.
3. Open **http://localhost:6274**, enable the connection for **shoostr-mcp-oauth**, and
   complete the Keycloak login using **mcp-demo / mcp-demo-local-only**. Use `localhost`,
   not `127.0.0.1`: OAuth requires the exact registered callback origin.
4. In **Tools**, select **increment** and execute it. Its result begins with
   `Tool invocation` and identifies the authenticated subject. The configured grants
   are `mcp:read mcp:tools`; REST's Basic credentials do not authenticate MCP.
5. When finished, close that profile and remove only the `Shoostr MCP fixture` profile
   through `about:profiles`. Do not delete an existing profile or change system trust.

The profile is needed because the browser visits Keycloak's local HTTPS login page.
Inspector separately trusts the same public CA through `NODE_EXTRA_CA_CERTS`. Neither
path disables TLS validation. Do not bypass certificate warnings; regenerate expired
fixture certificates and recreate the containers instead. No host Node installation
is required for this manual walkthrough.

Inspector's checked-in configuration uses Streamable HTTP and the existing legacy
initialization protocol, the preregistered public client and required scopes. Dynamic
client registration remains disabled, PKCE remains required, and the original test
callback is retained. Inspector shares Keycloak's network namespace with the app, so
the exact `localhost` issuer/resource URLs work without hostname or audience rewriting.

The overlay pins official Inspector 2.11.0 by multiarchitecture digest, publishes only
host-loopback port 6274 and retains Inspector authentication. It mounts only the server
configuration and public CA, read-only; secrets are process-memory only and runtime
files use temporary filesystems. No repository, Docker socket, private key or persistent
Inspector state is mounted. This process-capable development tool must not be exposed
publicly. Run only one Inspector example at a time; both walkthroughs use port 6274.

Stop all three services with the same overlays:

```sh
cmdshape docker compose -p shoostr-mcp-oauth \
  -f compose.yaml -f compose.app.yaml -f compose.inspector.yaml down --timeout 20
```

To restart this bundle, use the same combined `up` command with `--force-recreate --wait`.
Do not use `docker compose restart`: the shared network namespace can leave Inspector
attached to an old listener. Recreate Keycloak, the app and Inspector together. Changing
the realm JSON alone does not update an already-running realm.
See the [official Inspector guide](https://modelcontextprotocol.io/docs/tools/inspector).

For the optional browser check against this running stack, use the existing Node22
tooling and pinned Playwright Firefox installation from the OAuth-test instructions:

```sh
MCP_INSPECTOR_EXAMPLE=oauth NODE_EXTRA_CA_CERTS="$PWD/.local/fixture-ca.pem" \
  cmdshape node --test inspector-test.mjs
```

The check creates a fresh temporary browser profile that trusts only the public fixture
CA in addition to Firefox's normal roots. It logs in and invokes `increment` through
Inspector's public UI, then removes its temporary trust configuration. It does not
change your ordinary browser or system trust. CI runs this check as well as the native
OAuth client check and never uploads Inspector's token-bearing startup logs.

## Token-verification tests

The example's `KeycloakAuthenticator` uses explicitly configured Nimbus JOSE + JWT
10.9.1 through a native Pac4j direct client. It accepts signed RS256 `at+jwt` access
tokens only, with the exact configured issuer and resource audience, required
expiry/issue-time/identity/scope claims and a 30-second clock skew. Token freshness
is enforced again against the exact token expiry before scope checks;
the verifier's permitted clock skew does not extend MCP access or open-stream lifetime.
The same trusted discovery URI is supplied to `McpHttpSecurity`, so an adapter
expiry check after successful authentication still returns a complete401 challenge
with `resource_metadata`. The adapter owns this failure; no example exception mapper
is required. Committed streams still terminate rather than replacing their status.
Required identity strings must be nonblank; scope syntax is validated before retaining it. The native
profile contains the verified subject and immutable issuer/client/scope strings,
not the raw token. Application callbacks still need to make permission decisions.

The trusted JWKS URL comes from the configured Keycloak issuer, never an unverified
token's issuer, `jku` or `x5u`. Retrieval has explicit two-second connect/read
timeouts, a 128 KiB response limit and caller-configured TLS trust. Native caching
is retained without background refresh threads; callers close the authenticator
after their requests finish. Invalid tokens produce authentication failure, while
key-service or internal cryptographic failures remain server errors.

Run from the repository root:

```sh
cmdshape ./gradlew :mcp-oauth-example:test
```

These tests exercise real Shoostr HTTP endpoints, the managed Pac4j policy and
HTTPS JWKS retrieval using test-owned RSA keys and certificate trust. They do not
require Docker, change global TLS trust, invoke private verifier methods or issue
production tokens. External OAuth/browser interoperability runs separately through
`oauthTest`; ordinary `test` does not require Docker or an installed browser.
The verifier follows the [Nimbus access-token validation example](https://connect2id.com/products/nimbus-jose-jwt/examples/validating-jwt-access-tokens),
with audience and Keycloak-specific claim checks rather than its different-provider
claim names, and the [OAuth scope grammar](https://www.rfc-editor.org/rfc/rfc6749.html#section-3.3).
