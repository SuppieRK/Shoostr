import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const revision = '0123456789abcdef0123456789abcdef01234567';
const gate = fileURLToPath(new URL('gate.mjs', import.meta.url));

test('passes a trusted proposed revision only with successful validation and completed analysis', () => {
  const result = runGate();
  assert.equal(result.status, 0, result.stderr);
});

test('does not accept missing or skipped analysis as a successful build gate', () => {
  for (const analysis of ['', 'false', 'skipped']) {
    const result = runGate({ ANALYSIS_COMPLETED: analysis });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /full-project analysis has not completed/);
  }
});

test('requires reviewed promotion for fork and Dependabot pull requests', () => {
  const fork = event();
  fork.pull_request.head.repo.full_name = 'contributor/Shoostr';
  const dependabot = event();
  dependabot.pull_request.user.login = 'dependabot[bot]';
  for (const payload of [fork, dependabot]) {
    const result = runGate({}, payload);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /maintainer-reviewed branch promotion/);
  }
});

test('rejects analysis from another or missing proposed revision', () => {
  for (const override of [
    { ANALYZED_REVISION: 'a'.repeat(40) },
    { ANALYZED_REVISION: '' },
    { EXPECTED_REVISION: '' },
    { EXPECTED_REVISION: 'not-a-revision' },
    { EXPECTED_REVISION: 'b'.repeat(40), ANALYZED_REVISION: 'b'.repeat(40) },
  ]) {
    const result = runGate(override);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /proposed revision/);
  }
});

test('fails closed when validation fails or is canceled or skipped', () => {
  for (const validation of ['failure', 'cancelled', 'skipped', '']) {
    const result = runGate({ VALIDATION_RESULT: validation });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /validation did not succeed/);
  }
});

test('permits trusted main pushes and explicit workflow dispatches with matching analysis', () => {
  for (const name of ['push', 'workflow_dispatch']) {
    const result = runGate({ GITHUB_EVENT_NAME: name, GITHUB_SHA: revision });
    assert.equal(result.status, 0, result.stderr);
  }
});

function event() {
  return {
    repository: { full_name: 'SuppieRK/Shoostr' },
    pull_request: {
      head: { sha: revision, repo: { full_name: 'SuppieRK/Shoostr' } },
      user: { login: 'contributor' },
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
        VALIDATION_RESULT: 'success',
        ANALYSIS_COMPLETED: 'true',
        ANALYZED_REVISION: revision,
        EXPECTED_REVISION: revision,
        ...overrides,
      },
    });
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}
