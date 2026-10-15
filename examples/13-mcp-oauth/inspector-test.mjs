import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import process from 'node:process';
import { after, before, test } from 'node:test';
import { firefox } from 'playwright';

const oauth = process.env.MCP_INSPECTOR_EXAMPLE === 'oauth';
assert.ok(!process.env.MCP_INSPECTOR_EXAMPLE || oauth,
  'MCP_INSPECTOR_EXAMPLE must be absent for public MCP, or oauth');
let directory;
let browser;
let page;

before(async () => {
  let env = process.env;
  if (oauth) {
    assert.ok(process.env.NODE_EXTRA_CA_CERTS, 'Provide the public fixture CA before starting Node');
    directory = await mkdtemp(join(tmpdir(), 'shoostr-inspector-'));
    const policies = join(directory, 'firefox-policies.json');
    await writeFile(policies, JSON.stringify({
      policies: { Certificates: { Install: [resolve(process.env.NODE_EXTRA_CA_CERTS)] } }
    }));
    env = { ...process.env, PLAYWRIGHT_FIREFOX_POLICIES_JSON: policies };
  }
  browser = await firefox.launch({ headless: true, env });
  page = await browser.newPage();
  page.setDefaultTimeout(30_000);
  await page.goto('http://localhost:6274');
  if (!oauth) {
    await page.getByRole('switch', { name: 'Connect or disconnect "shoostr-mcp"' }).press('Space');
  }
});

after(async () => {
  try {
    await browser?.close();
  } finally {
    if (directory) await rm(directory, { recursive: true });
  }
});

if (oauth) {
  test('logs in and invokes a bearer-protected MCP tool through Inspector',
    { timeout: 120_000 }, async () => {
      try {
        await Promise.all([
          page.waitForURL(url => url.origin === 'https://localhost:8443'
            && url.pathname.endsWith('/protocol/openid-connect/auth')),
          page.getByRole('switch', { name: 'Connect or disconnect "shoostr-mcp-oauth"' }).press('Space')
        ]);
        await page.getByLabel('Username or email').fill('mcp-demo');
        await page.getByLabel('Password', { exact: true }).fill('mcp-demo-local-only');
        await page.getByRole('button', { name: 'Sign In', exact: true }).click();
        await page.waitForURL('http://localhost:6274/');
      } catch {
        // Browser exceptions may include OAuth codes and state in URLs; never publish them.
        throw new Error('Inspector OAuth login failed; check fixture TLS trust and client registration');
      }
      await page.locator('label[for$="-Tools"]').first().click();
      await page.getByRole('button', { name: 'increment', exact: true }).click();
      await page.getByRole('button', { name: 'Execute Tool', exact: true }).click();
      const result = page.getByText(/^Tool invocation [1-9][0-9]* by [a-f0-9-]{36}$/);
      await result.waitFor();
      assert.match(await result.innerText(), /^Tool invocation [1-9][0-9]* by [a-f0-9-]{36}$/);
    });
} else {
  test('invokes a native MCP tool through Inspector', { timeout: 60_000 }, async () => {
    await page.locator('label[for$="-Tools"]').first().click();
    await page.getByRole('button', { name: 'test_simple_text', exact: true }).click();
    await page.getByRole('button', { name: 'Execute Tool', exact: true }).click();
    const result = page.getByText('This is a simple text response for testing.', { exact: true });
    await result.waitFor();
    assert.equal(await result.innerText(), 'This is a simple text response for testing.');
  });

  test('reads native MCP resource content through Inspector', { timeout: 60_000 }, async () => {
    await page.locator('label[for$="-Resources"]').first().click();
    const uris = page.getByRole('button', { name: /^URIs/ });
    if ((await uris.getAttribute('aria-expanded')) !== 'true') await uris.click();
    await page.getByRole('button', { name: 'Static text', exact: true }).click();
    const result = page.getByText('Static text content', { exact: true });
    await result.waitFor();
    assert.equal(await result.innerText(), 'Static text content');
  });

  test('retrieves a native MCP prompt through Inspector', { timeout: 60_000 }, async () => {
    await page.locator('label[for$="-Prompts"]').first().click();
    await page.getByRole('button', { name: /^test_simple_prompt/ }).click();
    const result = page.getByText('A simple prompt', { exact: true });
    await result.waitFor();
    assert.equal(await result.innerText(), 'A simple prompt');
  });
}
