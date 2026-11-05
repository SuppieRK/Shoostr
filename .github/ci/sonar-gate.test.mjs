import assert from 'node:assert/strict';
import test from 'node:test';
import { verify } from './sonar-gate.mjs';

const revision = 'a'.repeat(40);
const input = { revision, taskId: 'task', pullRequest: '74' };

function responses(overrides = {}) {
  return {
    'ce/task': { task: { id: 'task', componentKey: 'SuppieRK_Shoostr', status: 'SUCCESS', analysisId: 'analysis', warningCount: 0 } },
    'project_pull_requests/list': { pullRequests: [{ key: '74', commit: { sha: revision } }] },
    'project_analyses/search': { analyses: [{ key: 'analysis', revision }] },
    'issues/search': { total: 0 }, ...overrides,
  };
}

for (const target of [{ pullRequest: '74' }, { branch: 'main' }, { branch: 'feature' }]) {
  test(`accepts a completed analysis with zero issues for ${JSON.stringify(target)}`, async () => {
    const queries = [];
    await verify({ revision, taskId: 'task', ...target }, async (endpoint, query) => {
      queries.push({ endpoint, query });
      return responses()[endpoint];
    });
    assert.equal(queries.length, 4);
    assert.deepEqual(queries[2].query, { componentKeys: 'SuppieRK_Shoostr', ...target, resolved: 'false', ps: '1' });
  });
}

for (const total of [1, undefined, '0', -1]) {
  test(`rejects a nonzero or missing issue count (${total})`, async () => {
    await assert.rejects(verify(input, async endpoint => responses({ 'issues/search': { total } })[endpoint]), /zero open issues/);
  });
}

test('rejects an analysis replaced by a newer revision during the issue query', async () => {
  const data = responses();
  await assert.rejects(verify(input, async endpoint => {
    if (endpoint === 'issues/search') data['project_pull_requests/list'].pullRequests[0].commit.sha = 'b'.repeat(40);
    return data[endpoint];
  }), /expected revision/);
});

for (const task of [{ status: 'FAILED' }, { id: 'other' }, { componentKey: 'other' }, { analysisId: undefined }, { warningCount: 'invalid' }]) {
  test(`rejects incorrect scanner task metadata: ${JSON.stringify(task)}`, async () => {
    const data = responses();
    Object.assign(data['ce/task'].task, task);
    await assert.rejects(verify(input, async endpoint => data[endpoint]));
  });
}

test('rejects stale branch results and a mismatched analysis ID', async () => {
  for (const analysis of [{ key: 'other', revision }, { key: 'analysis', revision: 'b'.repeat(40) }]) {
    await assert.rejects(verify({ revision, taskId: 'task', branch: 'main' }, async endpoint =>
      responses({ 'project_analyses/search': { analyses: [analysis] } })[endpoint]), /expected revision/);
  }
});

test('rejects failed requests and invalid JSON from the fixed Sonar endpoint', async t => {
  const previous = process.env.SONAR_TOKEN;
  process.env.SONAR_TOKEN = 'fixture-token';
  t.after(() => { if (previous === undefined) delete process.env.SONAR_TOKEN; else process.env.SONAR_TOKEN = previous; });
  const fetch = t.mock.method(globalThis, 'fetch', async url => {
    assert.equal(url.origin, 'https://sonarcloud.io');
    return new Response(JSON.stringify(responses()[url.pathname.replace('/api/', '')]));
  });
  await verify(input);
  fetch.mock.mockImplementation(async () => new Response(null, { status: 503 }));
  await assert.rejects(verify(input), /HTTP 503/);
  fetch.mock.mockImplementation(async () => new Response('invalid JSON'));
  await assert.rejects(verify(input), SyntaxError);
});
