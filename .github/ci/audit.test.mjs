import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const revision = '0123456789abcdef0123456789abcdef01234567';
const audit = fileURLToPath(new URL('audit.mjs', import.meta.url));

test('accepts a completed full-project analysis for the proposed revision with zero open issues', () => {
  const result = runAudit();
  assert.equal(result.status, 0, result.stderr);
});

test('rejects open issues and missing or malformed issue counts', () => {
  for (const issues of [{ total: 1 }, {}, { total: '0' }, { total: -1 }]) {
    const result = runAudit({ issues });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /zero open issues/);
  }
});

test('rejects failed tasks, stale revisions and a different completed analysis', () => {
  for (const override of [
    { task: { task: { status: 'FAILED', analysisId: 'analysis-one' } } },
    { task: { task: { status: 'PENDING' } } },
    { task: { task: { status: 'SUCCESS' } } },
    { analyses: { analyses: [{ key: 'another-analysis', revision }] } },
    { analyses: { analyses: [{ key: 'analysis-one', revision: 'a'.repeat(40) }] } },
    { analyses: {} },
  ]) {
    const result = runAudit(override);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /proposed revision/);
  }
});

function runAudit(overrides = {}, branch = `branch-${revision}-audit`) {
  const directory = mkdtempSync(join(tmpdir(), 'shoostr-ci-audit-'));
  try {
    const values = {
      task: { task: { status: 'SUCCESS', analysisId: 'analysis-one' } },
      analyses: { analyses: [{ key: 'analysis-one', revision }] },
      issues: { total: 0 },
      ...overrides,
    };
    const env = { ...process.env, EXPECTED_REVISION: revision, SONAR_AUDIT_BRANCH: branch };
    for (const [name, value] of Object.entries(values)) {
      const path = join(directory, `${name}.json`);
      writeFileSync(path, JSON.stringify(value));
      env[`SONAR_${name.toUpperCase()}_FILE`] = path;
    }
    return spawnSync(process.execPath, [audit], { encoding: 'utf8', env });
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

test('rejects a historical matching analysis when current issues belong to another revision', () => {
  const result = runAudit({
    analyses: {
      analyses: [
        { key: 'newer-analysis', revision: 'b'.repeat(40) },
        { key: 'analysis-one', revision },
      ],
    },
    issues: { total: 0 },
  });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /proposed revision/);
});

test('requires an immutable audit branch for the proposed revision even on main', () => {
  for (const branch of ['main', '', `branch-${'b'.repeat(40)}-audit`]) {
    const result = runAudit({}, branch);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /immutable audit branch/);
  }
});
