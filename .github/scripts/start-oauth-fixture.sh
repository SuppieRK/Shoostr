#!/usr/bin/env bash
set -euo pipefail

# CI-only: run from examples/13-mcp-oauth on the Ubuntu GitHub Actions runner.
# GITHUB_ENV carries the generated password to subsequent workflow steps.
: "${GITHUB_ENV:?This script requires the GitHub Actions environment}"
SHOOSTR_OAUTH_KEYSTORE_PASSWORD="$(openssl rand -hex 24)"
export SHOOSTR_OAUTH_KEYSTORE_PASSWORD
echo "::add-mask::$SHOOSTR_OAUTH_KEYSTORE_PASSWORD"
printf 'SHOOSTR_OAUTH_KEYSTORE_PASSWORD=%s\n' "$SHOOSTR_OAUTH_KEYSTORE_PASSWORD" >> "$GITHUB_ENV"
mkdir -p .local
openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 2 \
  -keyout .local/fixture-ca.key -out .local/fixture-ca.pem \
  -subj '/CN=Shoostr local fixture CA' \
  -addext 'basicConstraints=critical,CA:TRUE' \
  -addext 'keyUsage=critical,keyCertSign,cRLSign'
openssl req -new -newkey rsa:2048 -sha256 -nodes \
  -keyout .local/fixture-key.pem -out .local/fixture.csr \
  -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' \
  -addext 'basicConstraints=critical,CA:FALSE' \
  -addext 'keyUsage=critical,digitalSignature,keyEncipherment' \
  -addext 'extendedKeyUsage=serverAuth'
openssl x509 -req -in .local/fixture.csr \
  -CA .local/fixture-ca.pem -CAkey .local/fixture-ca.key -CAcreateserial \
  -out .local/fixture-cert.pem -days 2 -sha256 -copy_extensions copy
openssl pkcs12 -export -in .local/fixture-cert.pem \
  -inkey .local/fixture-key.pem -certfile .local/fixture-ca.pem \
  -out .local/fixture.p12 -name localhost -passout env:SHOOSTR_OAUTH_KEYSTORE_PASSWORD
# The pinned image runs as UID1000; the hosted runner owns files as UID1001.
# Transfer only this disposable server key, without making it world-readable.
sudo chown 1000 .local/fixture-key.pem
docker compose -p shoostr-mcp-oauth-ci up -d keycloak
curl --fail --retry 60 --retry-delay 2 --retry-connrefused --retry-all-errors \
  --max-time 5 --retry-max-time 180 http://127.0.0.1:9000/health/ready
curl --fail --cacert .local/fixture-ca.pem \
  https://localhost:8443/realms/shoostr-mcp/.well-known/openid-configuration
