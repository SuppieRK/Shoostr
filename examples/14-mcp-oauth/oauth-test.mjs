import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { resolve } from 'node:path';
import test from 'node:test';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { UnauthorizedError } from '@modelcontextprotocol/sdk/client/auth.js';
import { firefox } from 'playwright';

const resource = 'https://localhost:9443/mcp';
const issuer = 'https://localhost:8443/realms/shoostr-mcp';
const callback = 'http://localhost:8765/callback';
const clientId = 'shoostr-mcp-example-client';
const metadataUrl = 'https://localhost:9443/.well-known/oauth-protected-resource/mcp';

test('obtains an audience-bound PKCE grant and authorizes a tool after scope escalation',
  { timeout: 180_000 }, async (t) => {
    const certificate = process.env.NODE_EXTRA_CA_CERTS;
    assert.ok(certificate, 'Provide fixture trust before starting Node');
    const output = resolve('build/oauth');
    await mkdir(output, { recursive: true });
    const policies = resolve(output, 'firefox-policies.json');
    await writeFile(policies, JSON.stringify({
      policies: { Certificates: { Install: [resolve(certificate)] } }
    }));
    const browser = await firefox.launch({
      headless: true,
      env: { ...process.env, PLAYWRIGHT_FIREFOX_POLICIES_JSON: policies }
    });
    t.after(() => browser.close());
    const page = await browser.newPage();
    page.setDefaultTimeout(30_000);

    let tokens;
    let discovery;
    let verifier;
    let expectedState;
    let authorizationUrl;
    let authorizationReply;
    let subject;
    let grantedScopes = new Set();
    let tokenRequests = 0;
    let scopeDenials = 0;
    const authorizedMethods = new Set();
    const listening = Promise.withResolvers();
    const scopeSet = value => new Set(value.split(' ').filter(Boolean));

    const provider = {
      redirectUrl: callback,
      clientMetadata: {
        redirect_uris: [callback],
        token_endpoint_auth_method: 'none',
        grant_types: ['authorization_code', 'refresh_token'],
        response_types: ['code'],
        scope: 'mcp:read'
      },
      clientInformation: () => ({ client_id: clientId, issuer }),
      tokens: () => tokens,
      saveTokens(value) {
        assert.equal(value.issuer, issuer);
        assert.ok(value.scope, 'The fixture must report its granted scopes');
        const [encodedHeader, encodedClaims] = value.access_token.split('.');
        const header = JSON.parse(Buffer.from(encodedHeader, 'base64url').toString());
        const claims = JSON.parse(Buffer.from(encodedClaims, 'base64url').toString());
        assert.equal(header.typ, 'at+jwt');
        assert.equal(header.alg, 'RS256');
        assert.equal(claims.iss, issuer);
        assert.ok([claims.aud].flat().includes(resource), 'Token must target the exact resource');
        assert.equal(claims.azp, clientId);
        assert.ok(typeof claims.sub === 'string' && claims.sub.length > 0);
        assert.ok(claims.exp > Date.now() / 1000);
        tokens = value;
        subject = claims.sub;
        grantedScopes = scopeSet(value.scope);
      },
      state() {
        expectedState = randomBytes(32).toString('base64url');
        return expectedState;
      },
      saveCodeVerifier(value) { verifier = value; },
      codeVerifier() { assert.ok(verifier); return verifier; },
      redirectToAuthorization(url) {
        const requested = scopeSet(url.searchParams.get('scope') ?? '');
        url.searchParams.set('scope', [...new Set([...grantedScopes, ...requested])].join(' '));
        authorizationUrl = url;
      },
      saveDiscoveryState(value) {
        assert.equal(value.authorizationServerUrl, issuer);
        assert.equal(value.resourceMetadataUrl, metadataUrl);
        assert.equal(value.resourceMetadata.resource, resource);
        assert.deepEqual(value.resourceMetadata.authorization_servers, [issuer]);
        const metadata = value.authorizationServerMetadata;
        assert.equal(metadata.issuer, issuer);
        assert.equal(metadata.authorization_endpoint, `${issuer}/protocol/openid-connect/auth`);
        assert.equal(metadata.token_endpoint, `${issuer}/protocol/openid-connect/token`);
        assert.equal(metadata.jwks_uri, `${issuer}/protocol/openid-connect/certs`);
        assert.ok(metadata.code_challenge_methods_supported?.includes('S256'));
        discovery = value;
      },
      discoveryState: () => discovery,
      invalidateCredentials(kind) {
        if (kind === 'client') throw new Error('The fixed client registration must not change');
        if (kind === 'tokens' || kind === 'all') tokens = undefined;
        if (kind === 'verifier' || kind === 'all') verifier = undefined;
        if (kind === 'discovery' || kind === 'all') discovery = undefined;
      }
    };

    const receiver = createServer((request, response) => {
      const url = new URL(request.url, callback);
      const state = url.searchParams.getAll('state');
      const codes = url.searchParams.getAll('code');
      const errors = url.searchParams.getAll('error');
      const hasCode = codes.length === 1 && codes[0] && errors.length === 0;
      const hasError = errors.length === 1 && errors[0] && codes.length === 0;
      if (request.method !== 'GET' || url.pathname !== '/callback'
          || state.length !== 1 || !expectedState || state[0] !== expectedState
          || (!hasCode && !hasError) || !authorizationReply) {
        response.writeHead(400, { 'Content-Type': 'text/plain' }).end('Invalid OAuth callback');
        return;
      }
      expectedState = undefined;
      response.writeHead(200, { 'Content-Type': 'text/plain' }).end('Authorization received');
      authorizationReply.resolve(hasCode ? { code: codes[0] } : { error: errors[0] });
    });
    await new Promise((accept, reject) => {
      receiver.once('error', reject);
      receiver.listen(8765, '127.0.0.1', accept);
    });
    t.after(() => new Promise((accept, reject) => {
      receiver.close(error => error ? reject(error) : accept());
      receiver.closeAllConnections();
    }));

    async function observedFetch(input, init) {
      const url = new URL(input instanceof Request ? input.url : input.toString());
      const headers = new Headers(init?.headers);
      const method = init?.method ?? 'GET';
      if (url.href === `${issuer}/protocol/openid-connect/token`) {
        const form = new URLSearchParams(init.body.toString());
        assert.equal(method, 'POST');
        assert.equal(form.get('grant_type'), 'authorization_code');
        assert.equal(form.get('resource'), resource);
        assert.equal(form.get('client_id'), clientId);
        assert.equal(form.get('redirect_uri'), callback);
        assert.ok(form.get('code_verifier') === verifier, 'Use the retained original PKCE verifier');
        assert.ok(!form.has('client_secret'));
        assert.ok(!headers.has('Authorization'));
        tokenRequests++;
      }
      if (url.href === resource && headers.has('Authorization')) {
        assert.ok(headers.get('Authorization') === `Bearer ${tokens?.access_token}`,
          'Every authenticated HTTP method must use the current provider token');
        authorizedMethods.add(method);
      }
      const response = await fetch(input, init);
      if (url.href === resource && response.status === 403) {
        assert.equal(response.headers.get('WWW-Authenticate'),
          `Bearer error="insufficient_scope", scope="mcp:read mcp:tools", resource_metadata="${metadataUrl}"`);
        scopeDenials++;
        // Preserve the actual response, but force a new code grant rather than a refresh
        // that cannot expand the existing grant. Previously granted scopes remain retained.
        provider.invalidateCredentials('tokens');
      }
      if (url.href === resource && method === 'GET' && response.status === 200) listening.resolve();
      return response;
    }

    async function authorize(transport, required) {
      assert.ok(authorizationUrl, 'The native SDK must initiate authorization');
      assert.equal(authorizationUrl.searchParams.get('resource'), resource);
      assert.equal(authorizationUrl.searchParams.get('client_id'), clientId);
      assert.equal(authorizationUrl.searchParams.get('redirect_uri'), callback);
      assert.equal(authorizationUrl.searchParams.get('code_challenge_method'), 'S256');
      assert.ok(authorizationUrl.searchParams.get('code_challenge')
        === createHash('sha256').update(verifier).digest('base64url'), 'S256 must bind the original verifier');
      assert.deepEqual(scopeSet(authorizationUrl.searchParams.get('scope')), new Set(required));
      const state = authorizationUrl.searchParams.get('state');
      const result = await receiveBrowserAuthorization(authorizationUrl);
      assert.ok(result.code && !result.error, 'The valid grant must return an authorization code');
      try {
        await transport.finishAuth(result.code);
        const replayedCallback = await fetch(`${callback}?${new URLSearchParams({ state, code: result.code })}`);
        assert.equal(replayedCallback.status, 400, 'A valid callback must consume its state once');
        const replayedCode = await fetch(`${issuer}/protocol/openid-connect/token`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams({
            grant_type: 'authorization_code', client_id: clientId, redirect_uri: callback,
            resource, code: result.code, code_verifier: verifier
          })
        });
        assert.equal(replayedCode.status, 400, 'The authorization server must consume codes once');
        assert.equal((await replayedCode.json()).error, 'invalid_grant');
      } finally {
        verifier = undefined;
        authorizationUrl = undefined;
      }
    }

    async function receiveBrowserAuthorization(url) {
      const state = url.searchParams.get('state');
      expectedState = state;
      authorizationReply = Promise.withResolvers();
      for (const parameters of [
        new URLSearchParams({ state: 'incorrect', code: 'invalid' }),
        new URLSearchParams([['state', state], ['state', state], ['code', 'invalid']]),
        new URLSearchParams([['state', state], ['code', 'invalid'], ['code', 'invalid']]),
        new URLSearchParams({ state, code: 'invalid', error: 'access_denied' })
      ]) {
        const rejected = await fetch(`${callback}?${parameters}`);
        assert.equal(rejected.status, 400, 'Malformed callbacks must not consume pending state');
      }
      const timeout = setTimeout(() => authorizationReply.reject(new Error('OAuth callback timed out')), 60_000);
      try {
        try {
          await page.goto(url.href);
          if (await page.locator('input[name="username"]').isVisible()) {
            await page.locator('input[name="username"]').fill('mcp-demo');
            await page.locator('input[name="password"]').fill('mcp-demo-local-only');
            await page.locator('input[type="submit"], button[type="submit"]').click();
          }
        } catch {
          // Playwright's exception contains the authorization URL/state. Do not publish it.
          throw new Error('Browser authorization failed; check fixture TLS trust and login');
        }
        return await authorizationReply.promise;
      } finally {
        clearTimeout(timeout);
        authorizationReply = undefined;
        expectedState = undefined;
      }
    }

    const rejectedCallback = await fetch(`${callback}?state=unsolicited&code=invalid`);
    assert.equal(rejectedCallback.status, 400);
    const firstTransport = new StreamableHTTPClientTransport(new URL(resource), {
      authProvider: provider, fetch: observedFetch
    });
    const firstClient = new Client({ name: 'shoostr-oauth-example', version: '1.0' });
    t.after(() => firstClient.close());
    await assert.rejects(firstClient.connect(firstTransport), UnauthorizedError);
    await authorize(firstTransport, ['mcp:read']);
    await firstClient.close();

    const transport = new StreamableHTTPClientTransport(new URL(resource), {
      authProvider: provider, fetch: observedFetch
    });
    const client = new Client({ name: 'shoostr-oauth-example', version: '1.0' });
    t.after(async () => {
      try {
        if (transport.sessionId) await transport.terminateSession();
      } finally {
        await client.close();
      }
    });
    await client.connect(transport);
    assert.equal(transport.protocolVersion, '2025-11-25');
    await listening.promise;
    const listed = await client.listTools();
    assert.ok(listed.tools.some(tool => tool.name === 'increment'));
    await assert.rejects(client.callTool({ name: 'increment', arguments: {} }), UnauthorizedError);
    assert.equal(scopeDenials, 1);
    await authorize(transport, ['mcp:read', 'mcp:tools']);
    const invoked = await client.callTool({ name: 'increment', arguments: {} });
    assert.equal(invoked.isError, false);
    assert.equal(invoked.content[0].text, `Tool invocation 1 by ${subject}`);
    assert.equal(tokenRequests, 2);

    const rest = await fetch('https://localhost:9443/api/profile', {
      headers: { Authorization: `Basic ${Buffer.from('rest-demo:rest-demo-local-only').toString('base64')}` }
    });
    assert.equal(rest.status, 200);
    assert.equal(await rest.text(), 'rest-demo');
    const unrelated = await fetch('https://localhost:9443/api/profile', {
      headers: { Authorization: `Bearer ${tokens.access_token}` }
    });
    assert.equal(unrelated.status, 401);
    assert.ok(unrelated.headers.get('WWW-Authenticate').startsWith('Basic '));

    const session = transport.sessionId;
    assert.ok(session);
    await transport.terminateSession();
    await client.close();
    const stale = await fetch(resource, {
      method: 'DELETE', headers: { Authorization: `Bearer ${tokens.access_token}`, 'Mcp-Session-Id': session }
    });
    assert.equal(stale.status, 404);
    assert.deepEqual(authorizedMethods, new Set(['POST', 'GET', 'DELETE']));

    for (const invalid of ['wrong-verifier', 'missing-verifier', 'redirect', 'missing-resource', 'changed-resource']) {
      const originalVerifier = randomBytes(32).toString('base64url');
      const state = randomBytes(32).toString('base64url');
      const url = new URL(discovery.authorizationServerMetadata.authorization_endpoint);
      url.search = new URLSearchParams({
        client_id: clientId, redirect_uri: callback, response_type: 'code', scope: 'mcp:read', state,
        code_challenge_method: 'S256',
        code_challenge: createHash('sha256').update(originalVerifier).digest('base64url')
      }).toString();
      const requestedResource = invalid === 'changed-resource' ? `${resource}/other` : resource;
      if (invalid !== 'missing-resource') url.searchParams.set('resource', requestedResource);
      const authorized = await receiveBrowserAuthorization(url);
      if (authorized.error) {
        assert.ok(invalid.endsWith('resource'), 'Verifier and redirect probes need their own valid code');
        assert.equal(authorized.error, 'invalid_target');
        continue;
      }
      assert.ok(authorized.code, 'Each probe must obtain an independent authorization code');
      const form = new URLSearchParams({
        grant_type: 'authorization_code', client_id: clientId, redirect_uri: callback,
        code: authorized.code, code_verifier: originalVerifier
      });
      if (invalid === 'wrong-verifier') form.set('code_verifier', randomBytes(32).toString('base64url'));
      if (invalid === 'missing-verifier') form.delete('code_verifier');
      if (invalid === 'redirect') form.set('redirect_uri', `${callback}?invalid=1`);
      if (invalid !== 'missing-resource') form.set('resource', requestedResource);
      const denied = await fetch(`${issuer}/protocol/openid-connect/token`, {
        method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: form
      });
      if (invalid.endsWith('resource') && denied.status === 200) {
        const unboundToken = await denied.json();
        assert.ok(unboundToken.access_token);
        const rejected = await fetch(resource, {
          method: 'POST', headers: {
            Authorization: `Bearer ${unboundToken.access_token}`,
            'Content-Type': 'application/json', Accept: 'application/json, text/event-stream'
          },
          body: JSON.stringify({ jsonrpc: '2.0', id: 100, method: 'initialize', params: {
            protocolVersion: '2025-11-25', capabilities: {},
            clientInfo: { name: 'resource-negative-control', version: '1.0' }
          } })
        });
        assert.equal(rejected.status, 401, 'An unbound resource grant must not authenticate MCP');
        assert.ok(!rejected.headers.has('Mcp-Session-Id'));
      } else {
        assert.equal(denied.status, 400, `The ${invalid} grant must be rejected`);
        const error = (await denied.json()).error;
        assert.ok(['invalid_grant', 'invalid_request', 'invalid_target'].includes(error),
          'The authorization server must return a grant/resource validation error');
      }
    }
    console.log('Verified PKCE/resource binding, scope escalation without a denied side effect, separate REST auth, Bearer POST/GET/DELETE, and five independent negative OAuth grants.');
  });
