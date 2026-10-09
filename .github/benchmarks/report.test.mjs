import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { trial, workloadSummary } from './report.mjs';

function fixture(t, metrics, outcome = 'PASS') {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'shoostr-report-'));
  t.after(() => fs.rmSync(directory, {recursive: true, force: true}));
  const files = ['server.jfr', 'jfr-summary.txt', 'measured-metrics.json.gz', 'report.html',
    'process-monitor.txt', 'monitor-pids.txt', 'server-resource.txt', 'measured-client-resource.txt',
    'allocation-by-site.txt', 'allocation-by-class.txt', 'hot-methods.txt', 'gc-pauses.txt', 'gc-cpu-time.txt'];
  for (const file of files) fs.writeFileSync(path.join(directory, file), 'test evidence');
  fs.writeFileSync(path.join(directory, 'outcome.txt'), outcome);
  fs.writeFileSync(path.join(directory, 'measured-summary.json'), JSON.stringify({metrics}));
  return directory;
}

function httpMetrics() {
  return {
    checks: {passes: 4, fails: 0},
    'http_req_duration{scenario:requests}': {avg: 1, med: 0.9, 'p(95)': 2, 'p(99)': 3},
    'http_reqs{scenario:requests}': {count: 180000, rate: 999},
    http_reqs: {count: 180006, rate: 999.1},
    http_req_failed: {value: 0}, dropped_iterations: {count: 0}
  };
}

test('HTTP rows use scenario counts and preserve each native percentile', t => {
  const directory = fixture(t, httpMetrics());
  const result = trial(directory, 'plaintext', 'steady', 2);
  assert.equal(result.outcome, 'PASS');
  assert.equal(result.rows[0].count, 180000);
  assert.equal(result.rows[0].rate, 999);
  assert.equal(result.rows[0].p95, 2);
  assert.equal(result.rows[0].p99, 3);
  assert.equal(result.rows[0].drops, 0);
});

test('missing recording cannot be reported as a complete passing trial', t => {
  const directory = fixture(t, httpMetrics());
  fs.unlinkSync(path.join(directory, 'server.jfr'));
  const result = trial(directory, 'plaintext', 'steady', 1);
  assert.equal(result.outcome, 'INCOMPLETE');
  assert.ok(result.missing.includes('server.jfr'));
});

test('missing measurement metrics remain unknown rather than zero', t => {
  const directory = fixture(t, {});
  const result = trial(directory, 'plaintext', 'steady', 1);
  assert.equal(result.outcome, 'INCOMPLETE');
  assert.equal(result.rows[0].count, null);
  assert.equal(result.rows[0].p95, null);
  assert.equal(result.rows[0].drops, null);
});

test('overload uses plateau duration instead of native whole-run rates', t => {
  const metrics = {checks: {passes: 4, fails: 2}};
  for (const rate of [1000, 2000, 5000, 10000, 20000, 50000, 100000]) {
    const suffix = `{scenario:step_${rate}}`;
    metrics[`http_reqs${suffix}`] = {count: 150, rate: 1};
    metrics[`http_req_duration${suffix}`] = {'p(95)': 5};
    metrics[`checks${suffix}`] = {passes: 4, fails: 2};
    metrics[`http_req_failed${suffix}`] = {value: 0.1};
    metrics[`dropped_iterations${suffix}`] = {count: 50};
  }
  const directory = fixture(t, metrics, 'OVERLOAD');
  const result = trial(directory, 'plaintext', 'overload', 1);
  assert.equal(result.outcome, 'OVERLOAD');
  assert.equal(result.rows.length, 7);
  assert.equal(result.rows[0].rate, 10);
  assert.equal(result.rows[0].check_failures, 2);
  assert.equal(result.rows[0].drops, 50);
});

test('SSE and WebSocket summaries keep their own latency measures', t => {
  const metrics = {
    checks: {values: {passes: 16, fails: 0}}, iterations: {values: {count: 5, rate: 1}},
    sse_stream_duration: {values: {'p(95)': 300}}, message_round_trip: {values: {'p(95)': 2}},
    ws_session_duration: {values: {'p(95)': 900}}
  };
  const directory = fixture(t, metrics);
  assert.equal(trial(directory, 'sse-burst', 'steady', 1).rows[0].p95, 300);
  assert.equal(trial(directory, 'ws-text', 'steady', 1).rows[0].p95, 2);
  assert.equal(trial(directory, 'ws-slow', 'steady', 1).rows[0].p95, 900);
});

test('missing upload or failed harness blocks complete campaign evidence', t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'shoostr-workload-report-'));
  t.after(() => fs.rmSync(directory, {recursive: true, force: true}));
  fs.writeFileSync(path.join(directory, 'configuration.json'), JSON.stringify({revision: 'test'}));
  for (const index of [1, 2, 3]) {
    const source = fixture(t, httpMetrics());
    fs.cpSync(source, path.join(directory, `trial-${index}`), {recursive: true});
  }
  const previousArtifact = process.env.ARTIFACT_URL;
  const previousOutcome = process.env.MEASUREMENT_OUTCOME;
  t.after(() => {
    if (previousArtifact === undefined) delete process.env.ARTIFACT_URL;
    else process.env.ARTIFACT_URL = previousArtifact;
    if (previousOutcome === undefined) delete process.env.MEASUREMENT_OUTCOME;
    else process.env.MEASUREMENT_OUTCOME = previousOutcome;
  });
  process.env.MEASUREMENT_OUTCOME = 'success';
  delete process.env.ARTIFACT_URL;
  assert.equal(workloadSummary(directory, 'plaintext', 'steady').complete, false);
  process.env.ARTIFACT_URL = 'https://example.test/artifact';
  assert.equal(workloadSummary(directory, 'plaintext', 'steady').complete, true);
  process.env.MEASUREMENT_OUTCOME = 'failure';
  assert.equal(workloadSummary(directory, 'plaintext', 'steady').complete, false);
});

for (const warmupSeconds of [10, 30]) {
  test(`workload summary reports the recorded ${warmupSeconds}-second warmup`, t => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'shoostr-warmup-report-'));
    t.after(() => fs.rmSync(directory, {recursive: true, force: true}));
    fs.writeFileSync(path.join(directory, 'configuration.json'), JSON.stringify({
      revision: 'test', warmup_seconds: warmupSeconds
    }));
    for (const index of [1, 2, 3]) {
      fs.cpSync(fixture(t, httpMetrics()), path.join(directory, `trial-${index}`), {recursive: true});
    }
    const markdown = execFileSync(process.execPath, [
      fileURLToPath(new URL('./report.mjs', import.meta.url)), 'workload', directory, 'plaintext', 'steady'
    ], {
      encoding: 'utf8',
      env: {...process.env, ARTIFACT_URL: 'https://example.test/native', MEASUREMENT_OUTCOME: 'success'}
    });
    assert.ok(markdown.includes(`Warmup: ${warmupSeconds} seconds per fresh JVM.`));
    assert.ok(!markdown.includes('A 10-second warmup does not guarantee JIT stabilization.'));
  });
}
