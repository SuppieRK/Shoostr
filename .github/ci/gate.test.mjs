import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const revision = '0123456789abcdef0123456789abcdef01234567';
const gate = fileURLToPath(new URL('gate.mjs', import.meta.url));
const origins = [
  { name: 'internal pull request', payload: event() },
  { name: 'fork pull request', payload: event('contributor/Shoostr') },
  { name: 'Dependabot pull request', payload: event('SuppieRK/Shoostr', 'dependabot[bot]'),
    env: { GITHUB_ACTOR: 'dependabot[bot]' } },
  { name: 'maintainer rerun of a Dependabot pull request',
    payload: event('SuppieRK/Shoostr', 'dependabot[bot]') },
  { name: 'bot-initiated pull request', payload: event(), env: { GITHUB_ACTOR: 'dependabot[bot]' } },
  { name: 'main push', payload: {}, env: { GITHUB_EVENT_NAME: 'push' } },
  { name: 'workflow dispatch', payload: {}, env: { GITHUB_EVENT_NAME: 'workflow_dispatch' } },
  { name: 'push in a fork repository', payload: {},
    env: { GITHUB_EVENT_NAME: 'push', GITHUB_REPOSITORY: 'contributor/Shoostr' } },
];

for (const { name, payload, env = {} } of origins) {
  test(`${name} passes with successful validation of the proposed revision`, () => {
    const result = runGate(env, payload);
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, new RegExp(`CI validation succeeded for ${revision}`));
  });

  test(`${name} rejects failed, cancelled, skipped and missing validation`, () => {
    for (const validation of ['failure', 'cancelled', 'skipped', '']) {
      const result = runGate({ ...env, VALIDATION_RESULT: validation }, payload);
      assert.equal(result.status, 1, validation);
      assert.match(result.stderr, /validation did not succeed/);
    }
  });

  test(`${name} rejects missing, malformed and mismatched expected revisions`, () => {
    for (const expected of ['', 'not-a-revision', 'b'.repeat(40)]) {
      const result = runGate({ ...env, EXPECTED_REVISION: expected }, payload);
      assert.equal(result.status, 1, expected);
      assert.match(result.stderr, /proposed revision/);
    }
  });

  test(`${name} rejects missing, malformed and mismatched event revisions`, () => {
    for (const proposed of [undefined, '', 'not-a-revision', 'b'.repeat(40)]) {
      const modified = structuredClone(payload);
      const overrides = { ...env };
      if (modified.pull_request) modified.pull_request.head.sha = proposed;
      else overrides.GITHUB_SHA = proposed ?? '';
      const result = runGate(overrides, modified);
      assert.equal(result.status, 1, proposed);
      assert.match(result.stderr, /proposed revision/);
    }
  });
}

function event(repository = 'SuppieRK/Shoostr', author = 'contributor') {
  return {
    pull_request: {
      head: { sha: revision, repo: { full_name: repository } },
      user: { login: author },
    },
  };
}

function runGate(overrides = {}, payload = event()) {
  const directory = mkdtempSync(join(tmpdir(), 'shoostr-ci-gate-'));
  try {
    const path = join(directory, 'event.json');
    writeFileSync(path, JSON.stringify(payload));
    return spawnSync(process.execPath, [gate], {
      encoding: 'utf8',
      env: {
        ...process.env,
        GITHUB_EVENT_PATH: path,
        GITHUB_EVENT_NAME: 'pull_request',
        GITHUB_REPOSITORY: 'SuppieRK/Shoostr',
        GITHUB_ACTOR: 'maintainer',
        GITHUB_SHA: revision,
        VALIDATION_RESULT: 'success',
        EXPECTED_REVISION: revision,
        ...overrides,
      },
    });
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}
