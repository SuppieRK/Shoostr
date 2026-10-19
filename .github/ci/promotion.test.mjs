import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const revision = '0123456789abcdef0123456789abcdef01234567';
const tree = 'a'.repeat(40);
const verifier = fileURLToPath(new URL('verify-promotion.mjs', import.meta.url));

test('verifies that the promoted commit and tree are exactly the reviewed current source', () => {
  assert.equal(verify().status, 0);
  for (const override of [
    { SOURCE_REVISION: 'b'.repeat(40) },
    { PROMOTED_REVISION: 'c'.repeat(40) },
    { PROMOTED_TREE: 'd'.repeat(40) },
    { REVIEWED_REVISION: '' },
    { REVIEWED_TREE: '' },
  ]) {
    const result = verify(override);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /reviewed/);
  }
});

function verify(overrides = {}) {
  return spawnSync(process.execPath, [verifier], {
    encoding: 'utf8',
    env: {
      ...process.env,
      REVIEWED_REVISION: revision,
      SOURCE_REVISION: revision,
      PROMOTED_REVISION: revision,
      REVIEWED_TREE: tree,
      PROMOTED_TREE: tree,
      ...overrides,
    },
  });
}
