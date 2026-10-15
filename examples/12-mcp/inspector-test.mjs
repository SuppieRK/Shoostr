import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { firefox } from 'playwright';

let browser;
let page;

before(async () => {
  browser = await firefox.launch({ headless: true });
  page = await browser.newPage();
  page.setDefaultTimeout(30_000);
  await page.goto('http://localhost:6274');
  await page.getByRole('switch', { name: 'Connect or disconnect "shoostr-mcp"' }).press('Space');
});

after(async () => {
  await browser?.close();
});

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
