import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const revision = '0123456789abcdef0123456789abcdef01234567';
const project = 'test-project';
const cli = fileURLToPath(new URL('sonar-gate.mjs', import.meta.url));

test('rejects an analyzed pull request with open issues even when its quality gate passed', async () => {
  const result = await runGate({ total: 1 });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Sonar analysis has 1 open issue/);
  assert.equal(result.stdout, '');
});

for (const eventName of ['pull_request', 'push', 'workflow_dispatch']) {
  test(`${eventName} passes only for an analyzed revision with zero issues`, async () => {
    const result = await runGate({ eventName });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, new RegExp(`zero open issues for ${revision}`));
    assert.match(result.stdout, /Sonar analysis warnings: 0/);
    assert(result.requests.every(request => request.authorization === 'Bearer fixture-token'));
    const search = result.requests.find(request => request.path === '/api/issues/search');
    assert.equal(search.query.componentKeys, project);
    assert.equal(search.query.resolved, 'false');
    assert.equal(search.query.ps, '1');
    if (eventName === 'pull_request') {
      assert.equal(search.query.pullRequest, '54');
      assert.equal(search.query.branch, undefined);
    } else {
      assert.equal(search.query.branch, eventName === 'push' ? 'main' : 'feature');
      assert.equal(search.query.pullRequest, undefined);
    }
  });
}

test('rejects a zero-issue result if a different revision replaces the analysis during verification', async () => {
  const result = await runGate({ advanceOnIssues: true });
  assert.equal(result.status, 1, result.stdout);
  assert.match(result.stderr, /analysis does not match/);
});

for (const count of [-1, 0.5, '0']) {
  test(`rejects an invalid issue count: ${JSON.stringify(count)}`, async () => {
    const result = await runGate({ issueResponse: { total: count } });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /invalid issue count/);
  });
}

test('rejects a response with no issue count instead of assuming zero', async () => {
  const result = await runGate({ issueResponse: {} });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /invalid issue count/);
});

for (const path of ['/api/ce/task', '/api/project_pull_requests/list', '/api/issues/search']) {
  test(`rejects HTTP failure from ${path}`, async () => {
    const result = await runGate({ failurePath: path });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /HTTP 503/);
    assert(!result.stderr.includes('fixture-token'));
  });
  test(`rejects invalid JSON from ${path}`, async () => {
    const result = await runGate({ invalidJsonPath: path });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /invalid JSON/);
  });
}

for (const key of ['projectKey', 'serverUrl', 'dashboardUrl', 'ceTaskId']) {
  test(`rejects missing scanner metadata: ${key}`, async () => {
    const result = await runGate({ metadata: { [key]: undefined } });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /missing/);
    assert.deepEqual(result.requests, []);
  });
}

for (const selector of ['', '&pullRequest=55', '&pullRequest=54&branch=main', '&pullRequest=54&pullRequest=54']) {
  test(`rejects missing, incorrect or ambiguous PR selection: ${selector}`, async () => {
    const result = await runGate({ selector });
    assert.equal(result.status, 1);
    assert.deepEqual(result.requests, []);
  });
}

for (const status of ['PENDING', 'IN_PROGRESS', 'FAILED', 'CANCELED']) {
  test(`rejects a scanner task that is ${status}`, async () => {
    const result = await runGate({ responses: { '/api/ce/task': { task: { id: 'task-1', componentKey: project, status, analysisId: 'analysis-1' } } } });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /not a completed analysis/);
    assert.equal(result.requests.length, 1);
  });
}

for (const task of [{ id: 'other', componentKey: project, status: 'SUCCESS', analysisId: 'analysis-1' }, { id: 'task-1', componentKey: 'other', status: 'SUCCESS', analysisId: 'analysis-1' }, { id: 'task-1', componentKey: project, status: 'SUCCESS' }]) {
  test(`rejects task identity, project or missing analysis ID: ${JSON.stringify(task)}`, async () => {
    const result = await runGate({ responses: { '/api/ce/task': { task } } });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /not a completed analysis/);
  });
}

test('rejects a stale PR analysis', async () => {
  const result = await runGate({ responses: { '/api/project_pull_requests/list': { pullRequests: [{ key: '54', commit: { sha: 'b'.repeat(40) } }] } } });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /analysis does not match/);
});

for (const analysis of [{ key: 'analysis-1', revision: 'b'.repeat(40) }, { key: 'other', revision }, undefined]) {
  test(`rejects a stale or missing branch analysis: ${JSON.stringify(analysis)}`, async () => {
    const result = await runGate({ eventName: 'workflow_dispatch', responses: { '/api/project_analyses/search': { analyses: analysis ? [analysis] : [] } } });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /analysis does not match/);
  });
}

for (const options of [
  { env: { SONAR_TOKEN: '' } },
  { env: { EXPECTED_REVISION: 'invalid' } },
  { event: { number: 54, pull_request: { head: { sha: 'b'.repeat(40) } } } },
  { eventName: 'workflow_dispatch', selector: '&branch=other' },
  { eventName: 'workflow_dispatch', selector: '&pullRequest=54' },
  { eventName: 'workflow_dispatch', selector: '' },
  { eventName: 'release' },
  { metadata: { dashboardUrl: 'https://other.example/dashboard?id=test-project' } },
  { metadata: { dashboardUrl: 'invalid' } },
  { suffix: '\nprojectKey=duplicate' },
  { suffix: '\ninvalid-entry' },
  { metadata: { serverUrl: 'https://user:password@sonar.example/' } },
  { metadata: { serverUrl: 'http://sonar.example/' } },
]) {
  test(`rejects invalid local inputs before sending credentials: ${JSON.stringify(options)}`, async () => {
    const result = await runGate(options);
    assert.equal(result.status, 1);
    assert.deepEqual(result.requests, []);
    assert(!result.stderr.includes('fixture-token'));
  });
}

test('rejects provider-controlled warning text before it can inject a CI log command', async () => {
  const result = await runGate({ responses: { '/api/ce/task': { task: {
    id: 'task-1', componentKey: project, status: 'SUCCESS', analysisId: 'analysis-1',
    warningCount: '0\r\n::error::injected',
  } } } });
  assert.equal(result.status, 1, result.stdout);
  assert.match(result.stderr, /invalid analysis warning count/);
  assert(!result.stdout.includes('::error::'));
});

async function runGate(options = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'shoostr-sonar-gate-'));
  const requests = [];
  let queriedIssues = false;
  const server = createServer((request, response) => {
    const url = new URL(request.url, 'http://localhost');
    requests.push({ path: url.pathname, query: Object.fromEntries(url.searchParams), authorization: request.headers.authorization });
    const routes = {
      '/api/ce/task': { task: { id: 'task-1', componentKey: project, status: 'SUCCESS', analysisId: 'analysis-1', warningCount: 0 } },
      '/api/project_pull_requests/list': { pullRequests: [{ key: '54', branch: 'feature', commit: { sha: options.advanceOnIssues && queriedIssues ? 'b'.repeat(40) : revision } }] },
      '/api/project_analyses/search': { analyses: [{ key: 'analysis-1', revision }] },
      '/api/issues/search': options.issueResponse ?? { total: options.total ?? 0 },
    };
    response.setHeader('Content-Type', 'application/json');
    if (options.failurePath === url.pathname) { response.writeHead(503).end('Unavailable'); return; }
    if (options.invalidJsonPath === url.pathname) { response.end('not json'); return; }
    if (Object.hasOwn(options.responses ?? {}, url.pathname)) routes[url.pathname] = options.responses[url.pathname];
    if (!(url.pathname in routes)) { response.writeHead(404).end(); return; }
    if (url.pathname === '/api/issues/search') queriedIssues = true;
    response.end(JSON.stringify(routes[url.pathname]));
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  try {
    const origin = `http://127.0.0.1:${server.address().port}`;
    const report = join(directory, 'report-task.txt');
    const eventName = options.eventName ?? 'pull_request';
    const branch = eventName === 'push' ? 'main' : 'feature';
    const selector = options.selector ?? (eventName === 'pull_request' ? '&pullRequest=54' : branch === 'main' ? '' : `&branch=${branch}`);
    const metadata = { projectKey: project, serverUrl: origin, dashboardUrl: `${origin}/dashboard?id=${project}${selector}`, ceTaskId: 'task-1', ...options.metadata };
    await writeFile(report, Object.entries(metadata).filter(([, value]) => value !== undefined).map(([key, value]) => `${key}=${value}`).join('\n') + (options.suffix ?? ''));
    const event = join(directory, 'event.json');
    await writeFile(event, JSON.stringify(options.event ?? { number: 54, pull_request: { head: { ref: 'feature', sha: revision } } }));
    const child = spawn(process.execPath, [cli, report], {
      env: { ...process.env, SONAR_TOKEN: 'fixture-token', EXPECTED_REVISION: revision, GITHUB_EVENT_NAME: eventName, GITHUB_EVENT_PATH: event, GITHUB_REF_NAME: eventName === 'pull_request' ? '54/merge' : branch, ...options.env },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let stdout = '';
    let stderr = '';
    child.stdout.setEncoding('utf8').on('data', chunk => { stdout += chunk; });
    child.stderr.setEncoding('utf8').on('data', chunk => { stderr += chunk; });
    const [status] = await once(child, 'close');
    return { status, stdout, stderr, requests };
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    await rm(directory, { recursive: true, force: true });
  }
}
