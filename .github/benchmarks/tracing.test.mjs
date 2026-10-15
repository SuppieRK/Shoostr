import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(new URL('./tracing.sh', import.meta.url));

function fixture(t, membership) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'shoostr-tracing-metadata-'));
  t.after(() => fs.rmSync(directory, {recursive: true, force: true}));
  const root = path.join(directory, 'cgroup');
  const member = path.join(directory, 'membership');
  fs.mkdirSync(root);
  fs.writeFileSync(member, membership);
  return {
    root,
    capture: () => execFileSync('bash', ['-euo', 'pipefail', '-c',
      'source "$1"; capture_process_cgroup "$2" "$3"', 'bash', script, root, member], {
      encoding: 'utf8', timeout: 5000
    })
  };
}

test('missing root controller files are reported without aborting metadata capture', t => {
  const {capture} = fixture(t, '0::/\n');
  const output = capture();
  assert.ok(output.includes('cpu.max: unavailable'));
  assert.ok(output.includes('memory.max: unavailable'));
});

test('metadata records the process cgroup and its visible ancestor limits', t => {
  const {root, capture} = fixture(t, '0::/parent/worker\n');
  const parent = path.join(root, 'parent');
  const worker = path.join(parent, 'worker');
  fs.mkdirSync(worker, {recursive: true});
  fs.writeFileSync(path.join(worker, 'cpu.max'), '50000 100000\n');
  fs.writeFileSync(path.join(worker, 'memory.max'), 'max\n');
  fs.writeFileSync(path.join(parent, 'cpu.max'), '200000 100000\n');
  fs.writeFileSync(path.join(parent, 'memory.max'), '2147483648\n');
  const output = capture();
  assert.ok(output.includes(`${worker}/cpu.max: 50000 100000`));
  assert.ok(output.includes(`${worker}/memory.max: max`));
  assert.ok(output.includes(`${parent}/cpu.max: 200000 100000`));
  assert.ok(output.includes(`${parent}/memory.max: 2147483648`));
  assert.ok(output.includes(`${root}/memory.max: unavailable`));
});

test('unavailable cgroup v2 membership is reported without inventing limits', t => {
  const {capture} = fixture(t, '1:cpu:/legacy\n');
  assert.ok(capture().includes('unavailable or outside the visible namespace'));
});

test('namespace paths outside the visible hierarchy are not traversed', t => {
  const {capture} = fixture(t, '0::/../../hidden\n');
  assert.ok(capture().includes('unavailable or outside the visible namespace'));
});
