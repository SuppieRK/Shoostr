import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { verifyHealth, verifyRecovery, verifyTiming, verifyMemory, verifyBodies,
  verifyRecoveryWindow, verifyBlocked, verifyCycle } from './slow-client-check.mjs';

function native(connections = 1, closed = false) {
  return {zrk_version: '3.0.0', requests: 100, duration_s: closed ? 60.001 : 100.001,
    config: {connections, launched: connections, streams: 1, duration_s: closed ? 60 : 100,
      closed, disable_keepalive: !closed, target_rate: 1, target_rate_end: 1,
      timeout_ms: closed ? 8000 : 1000, deadline_ms: 0, interval_s: 1,
      deadline_abort: false, record_timeouts: true},
    error_rate: 0,
    errors: {connect: 0, read: 0, write: 0, timeout: 0, deadline: 0, non_2xx_3xx: 0},
    workload: {response: {calls: 100}},
    status_codes: {'1xx': 0, '2xx': 100, '3xx': 0, '4xx': 0, '5xx': 0},
    latency_us: {max: 12000}, latency_histogram: 'native histogram'};
}

function intervals(count = 100) {
  return Array.from({length: count}, (_, index) => ({t: index + 1, requests: 1, errors: 0,
    errors_by_kind: native().errors, latency_us: {max: 12000}, latency_histogram: 'native histogram'}));
}

for (const count of [99, 100, 101]) {
  test(`accepts ${count} validated health responses within the native boundary policy`, () => {
    const summary = native();
    summary.requests = summary.workload.response.calls = summary.status_codes['2xx'] = count;
    assert.equal(verifyHealth(summary), count);
  });
}

for (const count of [98, 102, undefined, 99.5]) {
  test(`rejects health count outside the selected boundary: ${count}`, () => {
    const summary = native();
    summary.requests = summary.workload.response.calls = summary.status_codes['2xx'] = count;
    assert.throws(() => verifyHealth(summary));
  });
}

for (const field of ['config', 'errors', 'workload', 'status_codes', 'latency_us', 'latency_histogram', 'duration_s']) {
  test(`rejects missing native health ${field}`, () => {
    const summary = native(); delete summary[field];
    assert.throws(() => verifyHealth(summary));
  });
}

test('rejects excessive scheduled latency, tool failures and unvalidated health responses', () => {
  const summary = native();
  summary.latency_us.max = 1000001;
  assert.throws(() => verifyHealth(summary), /scheduled latency/);
  summary.latency_us.max = 1;
  summary.errors.timeout = 1;
  assert.throws(() => verifyHealth(summary), /timeout/);
  summary.errors.timeout = 0;
  summary.workload.response.calls = 99;
  assert.throws(() => verifyHealth(summary), /validation/);
});

const phase = {start_ms: 1001500, load_end_ms: 1061500, recovery_end_ms: 1091500,
  health_start_ms: 1000000, health_ready_ms: 1001100};

test('observed 101-response boundary still validates every response and permits only one final counter difference', () => {
  const summary = native();
  summary.requests = summary.workload.response.calls = summary.status_codes['2xx'] = 101;
  summary.duration_s = 100.003;
  assert.equal(verifyHealth(summary), 101);
  assert.equal(verifyTiming(phase, intervals(100), 101), 1);
  summary.workload.response.calls = 100;
  assert.throws(() => verifyHealth(summary), /validation/);
  assert.throws(() => verifyTiming(phase, intervals(99), 101), /boundary/);
});

test('health interval coverage uses launch/readiness bounds and rejects truncation', () => {
  assert.equal(verifyTiming(phase, intervals(), 100), 0);
  assert.equal(verifyTiming(phase, intervals(99), 100), 1);
  assert.throws(() => verifyTiming(phase, intervals(98), 100), /coverage/);
  assert.throws(() => verifyTiming({...phase, recovery_end_ms: 1101500}, intervals(), 100), /coverage/);
  assert.throws(() => verifyTiming(phase, intervals().filter(row => row.t < 20 || row.t > 25), 100), /coverage/);
  assert.throws(() => verifyTiming(phase, intervals(), 102), /boundary/);
  assert.throws(() => verifyTiming({...phase, health_ready_ms: phase.start_ms + 1}, intervals(), 100), /ready/);
});

function recovered(clients = 32) {
  return {active_bodies: 0, active_blocked: 0, active_exchanges: 0,
    peak_work: clients, admitted: clients, completed: clients, connections: 1};
}

test('recovery allows only the owned health connection and requires final quiescence', () => {
  const state = recovered();
  verifyRecovery(state, 32);
  verifyRecovery({...state, connections: 2}, 32, true);
  assert.throws(() => verifyRecovery({...state, connections: 2}, 32), /connection/);
  assert.throws(() => verifyRecovery({...state, connections: 3}, 32, true), /connection/);
  for (const changed of [{active_bodies: 1}, {active_exchanges: 1}, {completed: 31}, {peak_work: 31}]) {
    assert.throws(() => verifyRecovery({...state, ...changed}, 32, true));
  }
  const sample = {...state, connections: 2, phase: 'recovery', timestamp_ms: phase.load_end_ms + 1000};
  assert.equal(verifyRecoveryWindow([sample], phase, 32), 1000);
  assert.throws(() => verifyRecoveryWindow([{...sample, timestamp_ms: phase.load_end_ms + 30001}], phase, 32));
});

function population(clients) {
  return Array.from({length: 51}, (_, index) => ({phase: 'load', timestamp_ms: phase.start_ms + 5000 + index * 1000,
    active_blocked: clients, active_bodies: 0, active_exchanges: clients, admitted: clients, completed: 0}));
}

for (const clients of [32, 128]) {
  test(`sampled ${clients}-client exposure rejects sustained reductions, gaps and inconsistent accounting`, () => {
    const samples = population(clients);
    verifyBlocked(samples, phase, clients);
    verifyBlocked(samples.map((row, index) => index === 10 ? {...row, active_blocked: 0} : row), phase, clients);
    assert.throws(() => verifyBlocked(samples.filter((_, index) => index < 10 || index > 15), phase, clients), /exposure/);
    assert.throws(() => verifyBlocked(samples.map((row, index) => index >= 10 && index <= 15
      ? {...row, active_blocked: clients - 1} : row), phase, clients), /exposure/);
    assert.throws(() => verifyBlocked(samples.map(row => ({...row, completed: 1})), phase, clients), /accounting/);
  });
}

test('keeps native slow-body exposure and OOM evidence strict', () => {
  const csv = ['Seconds,Closed,Pending,Connected,Service Available',
    ...Array.from({length: 62}, (_, second) => `${second},0,0,32,32`)].join('\n');
  verifyBodies(csv, 32);
  assert.throws(() => verifyBodies(csv.replace('20,0,0,32,32', '20,1,0,31,32'), 32), /exposure/);
  assert.throws(() => verifyBodies(csv.replace('20,0,0,32,32\n', ''), 32), /evidence/);
  verifyMemory('oom 0\noom_kill 0\n', '');
  assert.throws(() => verifyMemory('oom 1\noom_kill 0\n', ''), /OOM/);
  assert.throws(() => verifyMemory('oom 0\noom_kill 1\n', ''), /OOM/);
  assert.throws(() => verifyMemory('oom 0\noom_kill 0\n', 'java.lang.OutOfMemoryError'), /OOM/);
  assert.throws(() => verifyMemory('', ''), /evidence/);
});

test('cycle verification rejects missing evidence, interrupted tools and incomplete native launches', t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'shoostr-zrk-cycle-'));
  t.after(() => fs.rmSync(directory, {recursive: true, force: true}));
  const json = (name, value) => fs.writeFileSync(path.join(directory, name), JSON.stringify(value));
  const lines = (name, rows) => fs.writeFileSync(path.join(directory, name), rows.map(JSON.stringify).join('\n'));
  json('phase.json', {...phase, kind: 'blocked'});
  json('health-summary.json', native());
  json('blocked-summary.json', native(32, true));
  json('recovered-state.json', recovered());
  lines('health.ndjson', intervals());
  lines('blocked.ndjson', intervals(60).map((row, index) => ({...row, requests: index < 40 ? 2 : 1})));
  for (const name of ['health.hgrm', 'blocked.hgrm']) fs.writeFileSync(path.join(directory, name), 'native histogram');
  lines('state-samples.jsonl', [...population(32), {...recovered(), phase: 'recovery', timestamp_ms: phase.load_end_ms + 1000}]);
  for (const name of ['load-exit-code.txt', 'health-exit-code.txt']) fs.writeFileSync(path.join(directory, name), '0\n');
  fs.writeFileSync(path.join(directory, 'memory.events'), 'oom 0\noom_kill 0\n');
  for (const name of ['server.log', 'java-errors.txt']) fs.writeFileSync(path.join(directory, name), '');
  assert.equal(verifyCycle(directory, 32).verified, true);
  const interrupted = native(32, true); interrupted.duration_s = 20;
  json('blocked-summary.json', interrupted);
  assert.throws(() => verifyCycle(directory, 32), /duration/);
  interrupted.duration_s = 60; interrupted.config.launched = 31;
  json('blocked-summary.json', interrupted);
  assert.throws(() => verifyCycle(directory, 32), /launch/);
  json('blocked-summary.json', native(32, true));
  fs.writeFileSync(path.join(directory, 'load-exit-code.txt'), '143\n');
  assert.throws(() => verifyCycle(directory, 32), /tool failed/);
  fs.writeFileSync(path.join(directory, 'load-exit-code.txt'), '0\n');
  fs.unlinkSync(path.join(directory, 'health.ndjson'));
  assert.throws(() => verifyCycle(directory, 32), /ENOENT/);
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
