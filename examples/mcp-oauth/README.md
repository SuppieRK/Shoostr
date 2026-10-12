# External OAuth fixture

Preparation for the MCP OAuth example. The pinned external authorization server and
realm fixture can run now. An application-owned token verifier and live-HTTP
verification tests are present; the Shoostr MCP adapter, authorization-code client
flow and end-to-end OAuth/MCP tests are not implemented yet. Starting Keycloak or
validating synthetic test tokens alone is not an OAuth interoperability result.

Run these commands from this directory with Docker Engine, Compose and OpenSSL installed:

```sh
cmdshape mkdir -p .local
cmdshape openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 2 \
  -keyout .local/fixture-key.pem -out .local/fixture-cert.pem \
  -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1'
cmdshape docker compose -p shoostr-mcp-oauth up -d keycloak
cmdshape curl --fail --retry 30 --retry-delay 2 --retry-connrefused \
  --max-time 5 --retry-max-time 120 http://127.0.0.1:9000/health/ready
cmdshape curl --fail --cacert .local/fixture-cert.pem \
  https://localhost:8443/realms/shoostr-mcp/.well-known/openid-configuration
cmdshape curl --fail --cacert .local/fixture-cert.pem \
  https://localhost:8443/realms/shoostr-mcp/protocol/openid-connect/certs
```

Use the final discovery and JWKS requests to check realm readiness and certificate
trust, not just an accepting socket. Certificates are local, short-lived fixture
material, ignored by Git. Do not disable TLS verification or use this configuration
in production. Recreating a certificate requires restarting Keycloak and reloading
the certificate in clients.

Demo login: **`mcp-demo` / `mcp-demo-local-only`**. This account is only for the
isolated test realm. No bootstrap administrator, client secret, password grant or
dynamic client registration is configured.

- Issuer: `https://localhost:8443/realms/shoostr-mcp`.
- Intended MCP resource: `https://localhost:9443/mcp` (not running yet).
- Pre-registered public client: `shoostr-mcp-example-client`.
- Callback: `http://localhost:8765/callback`.
- Authorization code flow requires S256 PKCE; optional grants are `mcp:read` and
  `mcp:tools`. Token audience binding and granted scopes still need live flow tests.

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

## Token-verification tests

The example's `KeycloakAuthenticator` uses explicitly configured Nimbus JOSE + JWT
10.9.1 through a native Pac4j direct client. It accepts signed RS256 `at+jwt` access
tokens only, with the exact configured issuer and resource audience, required
expiry/issue-time/identity/scope claims and a 30-second clock skew. Required identity
strings must be nonblank; scope syntax is validated before retaining it. The native
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
production tokens. The separate Docker fixture still needs authorization-code,
PKCE, resource binding and native MCP client tests before this example is complete.
The verifier follows the [Nimbus access-token validation example](https://connect2id.com/products/nimbus-jose-jwt/examples/validating-jwt-access-tokens),
with audience and Keycloak-specific claim checks rather than its different-provider
claim names, and the [OAuth scope grammar](https://www.rfc-editor.org/rfc/rfc6749.html#section-3.3).
