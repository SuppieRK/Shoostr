import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
import { spawnSync } from 'node:child_process';
import { verifyHealth, verifyRecovery, verifyTiming, verifyMemory, verifyBodies, verifyRecoveryWindow } from './slow-client-check.mjs';

function health() {
  return { metrics: {
    health_completed: { count: 100 },
    health_elapsed: { max: 12 },
    checks: { value: 1, passes: 300, fails: 0 },
    http_req_failed: { value: 0 },
    dropped_iterations: { count: 0 },
  } };
}

test('requires every scheduled health probe instead of only successful-response percentiles', () => {
  assert.doesNotThrow(() => verifyHealth(health()));
  const incomplete = health();
  incomplete.metrics.health_completed.count = 99;
  assert.throws(() => verifyHealth(incomplete), /100.*health probes/);
});

test('validates every probe when native scheduling adds one duration-boundary iteration', () => {
  const summary = health();
  summary.metrics.health_completed.count = 101;
  summary.metrics.checks.passes = 303;
  assert.doesNotThrow(() => verifyHealth(summary));
  summary.metrics.checks.passes = 300;
  assert.throws(() => verifyHealth(summary), /status\/body/);
  summary.metrics.checks.passes = 303;
  summary.metrics.health_elapsed.max = 1001;
  assert.throws(() => verifyHealth(summary), /one second/);
  summary.metrics.health_elapsed.max = 6;
  summary.metrics.checks.fails = 1;
  assert.throws(() => verifyHealth(summary), /status\/body/);
  summary.metrics.checks.fails = 0;
  summary.metrics.http_req_failed.value = 1 / 102;
  assert.throws(() => verifyHealth(summary), /HTTP failure/);
  summary.metrics.http_req_failed.value = 0;
  summary.metrics.dropped_iterations.count = 1;
  assert.throws(() => verifyHealth(summary), /dropped/);
});

for (const count of [99, 102, undefined, 100.5]) {
  test(`rejects a missing or out-of-window native health count: ${count}`, () => {
    const summary = health();
    summary.metrics.health_completed.count = count;
    assert.throws(() => verifyHealth(summary), /health probes/);
  });
}

for (const [metric, field, value, message] of [
  ['health_elapsed', 'max', 1001, /one second/],
  ['health_elapsed', 'max', undefined, /one second/],
  ['checks', 'value', 0.99, /status\/body/],
  ['checks', 'passes', 299, /status\/body/],
  ['http_req_failed', 'value', 0.01, /HTTP failure/],
  ['dropped_iterations', 'count', 1, /dropped/],
]) {
  test(`rejects invalid health evidence: ${metric}.${field}=${value}`, () => {
    const summary = health();
    summary.metrics[metric][field] = value;
    assert.throws(() => verifyHealth(summary), message);
  });
}

function recovered() {
  return { active_bodies: 0, active_blocked: 0, active_exchanges: 0,
    peak_work: 32, admitted: 32, completed: 32, connections: 1 };
}

test('requires work and terminal exchanges to recover, not just an available listener', () => {
  assert.doesNotThrow(() => verifyRecovery(recovered(), 32));
  assert.throws(() => verifyRecovery({ ...recovered(), active_bodies: 1 }, 32), /outstanding/);
});

for (const [field, value, message] of [
  ['connections', 2, /connection/],
  ['admitted', 33, /completion/],
  ['completed', undefined, /completion/],
  ['peak_work', 31, /32 clients/],
  ['peak_work', 64, /32 clients/],
]) {
  test(`rejects incomplete recovery/exposure evidence: ${field}=${value}`, () => {
    assert.throws(() => verifyRecovery({ ...recovered(), [field]: value }, 32), message);
  });
}

const start = Date.parse('2026-10-19T00:00:00Z');
const phase = { start_ms: start + 1000, load_end_ms: start + 62000,
  recovery_end_ms: start + 92000 };
/** @type {number[]} */
const points = Array.from({ length: 100 }, (_, index) => start + index * 1000);

test('requires the full load and recovery phases with complete probe coverage', () => {
  assert.doesNotThrow(() => verifyTiming(phase, points));
  assert.throws(() => verifyTiming({ ...phase, load_end_ms: start + 60000 }, points), /60 seconds/);
  assert.throws(() => verifyTiming({ ...phase, recovery_end_ms: start + 91000 }, points), /30 seconds/);
});

test('rejects missing probe samples or an uncovered part of either phase', () => {
  assert.throws(() => verifyTiming(phase, points.slice(1)), /probe evidence/);
  assert.throws(() => verifyTiming(phase, points.map(value => value + 10000)), /coverage/);
  assert.throws(() => verifyTiming(phase, points.map(value => value - 10000)), /coverage/);
});

test('matches raw health evidence to the accepted native summary count without truncation', () => {
  const boundaryPoints = [...points, start + 100000];
  assert.doesNotThrow(() => verifyTiming(phase, boundaryPoints, 101));
  assert.throws(() => verifyTiming(phase, points, 101), /probe evidence/);
  assert.throws(() => verifyTiming(phase, boundaryPoints, 100), /probe evidence/);
  assert.throws(() => verifyTiming(phase, boundaryPoints, 102), /probe evidence/);
  assert.throws(() => verifyTiming(phase, boundaryPoints.map(value => value + 10000), 101), /coverage/);
});

test('rejects Java OOM and cgroup OOM events, not only OOM kills', () => {
  const events = 'low 0\nhigh 0\nmax 0\noom 0\noom_kill 0\noom_group_kill 0\n';
  assert.doesNotThrow(() => verifyMemory(events, '-XX:+HeapDumpOnOutOfMemoryError'));
  assert.throws(() => verifyMemory(events.replace('oom 0', 'oom 1'), ''), /OOM/);
  assert.throws(() => verifyMemory(events.replace('oom_kill 0', 'oom_kill 1'), ''), /OOM/);
  assert.throws(() => verifyMemory(events, 'java.lang.OutOfMemoryError: Java heap space'), /OOM/);
  assert.throws(() => verifyMemory('', ''), /evidence/);
});

test('requires achieved slow-body connections throughout the native measurement', () => {
  const rows = Array.from({ length: 62 }, (_, second) => `${second},0,0,32,32`);
  const csv = ['Seconds,Closed,Pending,Connected,Service Available', ...rows].join('\n');
  assert.doesNotThrow(() => verifyBodies(csv, 32));
  assert.throws(() => verifyBodies(csv.replace('20,0,0,32,32', '20,1,0,31,32'), 32), /exposure/);
  assert.throws(() => verifyBodies(csv.replace('20,0,0,32,32', '20,0,0,32,0'), 32), /probe/);
  assert.throws(() => verifyBodies(csv.replace('20,0,0,32,32', '20,0,0,32,1'), 32), /probe/);
  assert.throws(() => verifyBodies(csv.replace('20,0,0,32,32\n', ''), 32), /evidence/);
  assert.doesNotThrow(() => verifyBodies(csv.replaceAll(',32', ',128'), 128));
});

test('requires observed connection/work recovery within thirty seconds, not only at run end', () => {
  const sample = { ...recovered(), phase: 'recovery', timestamp_ms: phase.load_end_ms + 1000 };
  assert.equal(verifyRecoveryWindow([sample], phase, 32), 1000);
  assert.throws(() => verifyRecoveryWindow([{ ...sample,
    timestamp_ms: phase.load_end_ms + 30001 }], phase, 32), /within 30 seconds/);
  assert.throws(() => verifyRecoveryWindow([{ ...sample, connections: 2 }], phase, 32),
    /within 30 seconds/);
});

function blockedPoints() {
  const rows = Array.from({ length: 60 }, (_, second) => ({ type: 'Point', metric: 'vus',
    data: { time: new Date(phase.start_ms + 500 + second * 1000).toISOString(), value: 32 } }));
  for (let vu = 1; vu <= 32; vu++) {
    for (let end = 5; end <= 55; end += 5) {
      rows.push({ type: 'Point', metric: 'http_req_duration', data: {
        time: new Date(phase.start_ms + 500 + end * 1000).toISOString(), value: 5000,
        tags: { scenario: 'blocked', status: '200', method: 'GET' }, metadata: { vu: String(vu) },
      } });
    }
  }
  return rows;
}

test('the executable checker requires complete native artifacts', t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'shoostr-slow-client-check-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  for (const [file, value] of Object.entries({
    'health-summary.json': health(),
    'recovered-state.json': recovered(),
    'phase.json': { ...phase, kind: 'blocked' },
    'blocked-summary.json': { metrics: { checks: { value: 1, passes: 32, fails: 0 } } },
  })) fs.writeFileSync(path.join(dir, file), JSON.stringify(value));
  const raw = points.map(value => JSON.stringify({ type: 'Point', metric: 'health_completed',
    data: { time: new Date(value).toISOString(), value: 1 } })).join('\n');
  fs.writeFileSync(path.join(dir, 'health-metrics.json.gz'), gzipSync(raw));
  fs.writeFileSync(path.join(dir, 'memory.events'), 'oom 0\noom_kill 0\n');
  fs.writeFileSync(path.join(dir, 'state-samples.jsonl'), JSON.stringify({ ...recovered(),
    phase: 'recovery', timestamp_ms: phase.load_end_ms + 1000 }) + '\n');
  for (const file of ['server.log', 'java-errors.txt']) fs.writeFileSync(path.join(dir, file), '');
  for (const file of ['load-exit-code.txt', 'health-exit-code.txt']) {
    fs.writeFileSync(path.join(dir, file), '0\n');
  }
  const run = () => spawnSync(process.execPath,
    [fileURLToPath(new URL('./slow-client-check.mjs', import.meta.url)), dir, '32'],
    { encoding: 'utf8', timeout: 5000 });
  const missingBlocked = run();
  assert.notEqual(missingBlocked.status, 0);
  assert.match(missingBlocked.stderr, /blocked-metrics/);
  const writeBlocked = rows => fs.writeFileSync(path.join(dir, 'blocked-metrics.json.gz'),
    gzipSync(rows.map(row => JSON.stringify(row)).join('\n')));
  writeBlocked(blockedPoints());
  assert.equal(run().status, 0);
  const boundarySummary = health();
  boundarySummary.metrics.health_completed.count = 101;
  boundarySummary.metrics.checks.passes = 303;
  fs.writeFileSync(path.join(dir, 'health-summary.json'), JSON.stringify(boundarySummary));
  const rawMismatch = run();
  assert.notEqual(rawMismatch.status, 0);
  assert.match(rawMismatch.stderr, /probe evidence/);
  const boundaryPoint = { type: 'Point', metric: 'health_completed',
    data: { time: new Date(start + 100000).toISOString(), value: 1 } };
  const boundaryRaw = raw + '\n' + JSON.stringify(boundaryPoint);
  fs.writeFileSync(path.join(dir, 'health-metrics.json.gz'), gzipSync(boundaryRaw));
  const boundary = run();
  assert.equal(boundary.status, 0, boundary.stderr);
  assert.equal(JSON.parse(boundary.stdout).health_probes, 101);
  fs.writeFileSync(path.join(dir, 'health-metrics.json.gz'),
    gzipSync(raw + '\n' + JSON.stringify({ ...boundaryPoint, data: { ...boundaryPoint.data, value: 2 } })));
  const nonUnit = run();
  assert.notEqual(nonUnit.status, 0);
  assert.match(nonUnit.stderr, /Invalid raw probe evidence/);
  fs.writeFileSync(path.join(dir, 'health-summary.json'), JSON.stringify(health()));
  fs.writeFileSync(path.join(dir, 'health-metrics.json.gz'), gzipSync(boundaryRaw));
  const extraRaw = run();
  assert.notEqual(extraRaw.status, 0);
  assert.match(extraRaw.stderr, /probe evidence/);
  fs.writeFileSync(path.join(dir, 'health-metrics.json.gz'), gzipSync(raw));
  writeBlocked(blockedPoints().filter(row => row.metric === 'vus'
    || Date.parse(row.data.time) < phase.start_ms + 6000));
  const earlyOnly = run();
  assert.notEqual(earlyOnly.status, 0);
  assert.match(earlyOnly.stderr, /exposure/);
  writeBlocked(blockedPoints().filter(row => row.metric !== 'http_req_duration'
    || row.data.metadata.vu !== '1' || Date.parse(row.data.time) < phase.start_ms + 20000
    || Date.parse(row.data.time) > phase.start_ms + 35000));
  const midGap = run();
  assert.notEqual(midGap.status, 0);
  assert.match(midGap.stderr, /exposure/);
  writeBlocked(blockedPoints().map(row => row.metric === 'vus'
    ? { ...row, data: { ...row.data, value: 16 } } : row));
  const reducedVus = run();
  assert.notEqual(reducedVus.status, 0);
  assert.match(reducedVus.stderr, /VU/);
  writeBlocked(blockedPoints().map(row => row.metric === 'http_req_duration'
    ? { ...row, data: { ...row.data, metadata: undefined } } : row));
  const missingVuMetadata = run();
  assert.notEqual(missingVuMetadata.status, 0);
  assert.match(missingVuMetadata.stderr, /VU/);
  writeBlocked(blockedPoints());
  fs.unlinkSync(path.join(dir, 'health-metrics.json.gz'));
  const missing = run();
  assert.notEqual(missing.status, 0);
  assert.match(missing.stderr, /ENOENT|evidence/);
});

for (const env of [{ JVM_CPUS: '2' }, { JVM_MEMORY_MIB: '512' }]) {
  test(`the runner refuses an unselected resource envelope: ${JSON.stringify(env)}`, t => {
    const parent = fs.mkdtempSync(path.join(os.tmpdir(), 'shoostr-slow-client-run-'));
    t.after(() => fs.rmSync(parent, { recursive: true, force: true }));
    const target = path.join(parent, 'results');
    const result = spawnSync('bash', [fileURLToPath(new URL('./slow-client.sh', import.meta.url)),
      target, '/unused-tools'], { encoding: 'utf8', timeout: 5000,
      env: { ...process.env, JVM_CPUS: '1', JVM_MEMORY_MIB: '1024', ...env } });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /1 CPU.*1 GiB.*256 MiB/);
    assert.equal(fs.existsSync(target), false);
  });
}

test('a failed preflight retains a failed outcome and never overwrites prior artifacts', t => {
  const parent = fs.mkdtempSync(path.join(os.tmpdir(), 'shoostr-slow-client-preflight-'));
  t.after(() => fs.rmSync(parent, { recursive: true, force: true }));
  const target = path.join(parent, 'results');
  const run = () => spawnSync('bash', [fileURLToPath(new URL('./slow-client.sh', import.meta.url)),
    target, path.join(parent, 'missing-tools')], { encoding: 'utf8', timeout: 5000,
    env: { ...process.env, JVM_CPUS: '1', JVM_MEMORY_MIB: '1024' } });
  assert.notEqual(run().status, 0);
  assert.equal(fs.readFileSync(path.join(target, 'outcome.txt'), 'utf8').trim(), 'FAILED');
  fs.writeFileSync(path.join(target, 'sentinel.txt'), 'preserve');
  const repeated = run();
  assert.notEqual(repeated.status, 0);
  assert.match(repeated.stderr, /overwrite/);
  assert.equal(fs.readFileSync(path.join(target, 'sentinel.txt'), 'utf8'), 'preserve');
});
