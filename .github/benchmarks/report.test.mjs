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
    'allocation-by-site.txt', 'allocation-by-class.txt', 'hot-methods.txt', 'gc-pauses.txt', 'gc-cpu-time.txt',
    'VM.version.txt', 'VM.command_line.txt', 'VM.flags.txt', 'VM.flags-all.txt', 'VM.info.txt',
    'server-limits.txt', 'server-affinity.txt', 'client-affinity.txt'];
  for (const file of files) fs.writeFileSync(path.join(directory, file), 'test evidence');
  fs.writeFileSync(path.join(directory, 'outcome.txt'), outcome);
  fs.writeFileSync(path.join(directory, 'measured-summary.json'), JSON.stringify({metrics}));
  return directory;
}

function httpMetrics() {
  return {
    checks: {passes: 4, fails: 0},
    'http_req_duration{scenario:requests}': {avg: 1, med: 0.9, 'p(95)': 2, 'p(99)': 3, max: 41},
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
  assert.equal(result.rows[0].maximum, null);
  assert.equal(result.rows[0].drop_percentage, null);
});

test('warmup drops retain their own completed count, scheduled total and percentage', t => {
  const parent = fs.mkdtempSync(path.join(os.tmpdir(), 'shoostr-warmup-drops-'));
  t.after(() => fs.rmSync(parent, {recursive: true, force: true}));
  fs.writeFileSync(path.join(parent, 'configuration.json'), JSON.stringify({warmup_seconds: 30}));
  const directory = path.join(parent, 'trial-3');
  fs.cpSync(fixture(t, httpMetrics()), directory, {recursive: true});
  fs.writeFileSync(path.join(directory, 'warmup-summary.json'), JSON.stringify({metrics: {
    checks: {passes: 119536, fails: 0}, http_req_failed: {value: 0},
    'http_reqs{scenario:requests}': {count: 29884}, dropped_iterations: {count: 117}
  }}));
  const warmup = trial(directory, 'stream', 'overload', 3).warmup;
  assert.equal(warmup.completed, 29884);
  assert.equal(warmup.drops, 117);
  assert.equal(warmup.scheduled_total, 30000);
  assert.equal(warmup.drop_percentage, 117 / 30000 * 100);
  assert.equal(warmup.check_failures, 0);
  assert.equal(warmup.http_failure_rate, 0);
});

test('missing warmup metrics remain unknown rather than silently passing', t => {
  const warmup = trial(fixture(t, httpMetrics()), 'stream', 'overload', 1).warmup;
  assert.equal(warmup.completed, null);
  assert.equal(warmup.drops, null);
  assert.equal(warmup.drop_percentage, null);
});

test('overload uses plateau duration instead of native whole-run rates', t => {
  const metrics = {checks: {passes: 4, fails: 2}};
  for (const rate of [1000, 5000, 10000, 50000]) {
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
  assert.equal(result.rows.length, 4);
  assert.deepEqual(result.rows.map(row => row.offered), [1000, 5000, 10000, 50000]);
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
      const child = path.join(directory, `trial-${index}`);
      fs.cpSync(fixture(t, httpMetrics()), child, {recursive: true});
      fs.writeFileSync(path.join(child, 'warmup-summary.json'), JSON.stringify({metrics: {
        checks: {passes: 4, fails: 0}, http_req_failed: {value: 0},
        'http_reqs{scenario:requests}': {count: 100}, dropped_iterations: {count: 9}
      }}));
    }
    const markdown = execFileSync(process.execPath, [
      fileURLToPath(new URL('./report.mjs', import.meta.url)), 'workload', directory, 'plaintext', 'steady'
    ], {
      encoding: 'utf8',
      env: {...process.env, ARTIFACT_URL: 'https://example.test/native', MEASUREMENT_OUTCOME: 'success'}
    });
    assert.ok(markdown.includes(`Warmup: ${warmupSeconds} seconds per fresh JVM.`));
    assert.ok(markdown.includes(`Warmup: completed 100; drops 9 / ${1000 * warmupSeconds}`));
    assert.ok(markdown.includes('failed checks 0; HTTP failures 0.00%.'));
    assert.ok(!markdown.includes('A 10-second warmup does not guarantee JIT stabilization.'));
  });
}

test('HTTP rows preserve the native maximum independently of p99', t => {
  const result = trial(fixture(t, httpMetrics()), 'plaintext', 'steady', 1);
  assert.equal(result.rows[0].maximum, 41);
});

for (const [workload, metric, scheduled] of [
  ['plaintext', 'http_req_duration{scenario:requests}', 180000],
  ['sse-burst', 'sse_stream_duration', 9000],
  ['ws-slow', 'ws_session_duration', 180]
]) {
  test(`${workload} drops use scheduled iterations rather than completed iterations`, t => {
    const metrics = {
      checks: {passes: 4, fails: 0}, dropped_iterations: {count: 9},
      'http_reqs{scenario:requests}': {count: 100, rate: 1}, iterations: {count: 100, rate: 1},
      [metric]: {values: {'p(95)': 2, 'p(99)': 3, max: 41}}
    };
    const row = trial(fixture(t, metrics), workload, 'steady', 1).rows[0];
    assert.equal(row.scheduled_total, scheduled);
    assert.equal(row.drop_percentage, 9 / scheduled * 100);
  });
}

test('scheduled totals use the recorded workload rate and duration', t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'shoostr-recorded-load-'));
  t.after(() => fs.rmSync(directory, {recursive: true, force: true}));
  const recorded = {...JSON.parse(fs.readFileSync(new URL('./workloads.json', import.meta.url), 'utf8')).plaintext,
    rate: 77};
  fs.writeFileSync(path.join(directory, 'configuration.json'), JSON.stringify({
    fixture: recorded, measurement_seconds: 60
  }));
  const child = path.join(directory, 'trial-1');
  fs.cpSync(fixture(t, httpMetrics()), child, {recursive: true});
  assert.equal(trial(child, 'plaintext', 'steady', 1).rows[0].scheduled_total, 4620);
});

test('overload drops use each plateau scheduled total', t => {
  const metrics = {checks: {passes: 4, fails: 0}};
  for (const rate of [1000, 5000, 10000, 50000]) {
    const suffix = `{scenario:step_${rate}}`;
    metrics[`http_reqs${suffix}`] = {count: 100, rate: 1};
    metrics[`http_req_duration${suffix}`] = {'p(95)': 2, 'p(99)': 3, max: 41};
    metrics[`dropped_iterations${suffix}`] = {count: 50};
  }
  const rows = trial(fixture(t, metrics, 'OVERLOAD'), 'plaintext', 'overload', 1).rows;
  assert.equal(rows[0].scheduled_total, 15000);
  assert.equal(rows[0].drop_percentage, 50 / 15000 * 100);
  assert.equal(rows.at(-1).scheduled_total, 750000);
  assert.equal(rows.at(-1).drop_percentage, 50 / 750000 * 100);
});

test('historical overload summaries retain their recorded seven plateaus', t => {
  const rates = [1000, 2000, 5000, 10000, 20000, 50000, 100000];
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'shoostr-historical-overload-'));
  t.after(() => fs.rmSync(directory, {recursive: true, force: true}));
  fs.writeFileSync(path.join(directory, 'configuration.json'), JSON.stringify({smoke_rates: rates}));
  const child = path.join(directory, 'trial-1');
  fs.cpSync(fixture(t, httpMetrics(), 'OVERLOAD'), child, {recursive: true});
  assert.deepEqual(trial(child, 'plaintext', 'overload', 1).rows.map(row => row.offered), rates);
});

function resourceFixture(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'shoostr-resource-report-'));
  t.after(() => fs.rmSync(directory, {recursive: true, force: true}));
  fs.writeFileSync(path.join(directory, 'configuration.json'), JSON.stringify({
    revision: 'test', heap: '256 MiB', jvm_cpus: 1, jvm_memory_mib: 1024
  }));
  for (const index of [1, 2, 3]) {
    const child = path.join(directory, `trial-${index}`);
    fs.cpSync(fixture(t, httpMetrics()), child, {recursive: true});
    fs.writeFileSync(path.join(child, 'server-affinity.txt'), 'pid 123 current affinity list: 2');
    const cgroup = 'memory.max\n1073741824\nmemory.swap.max\n0\nmemory.current\n0\nmemory.peak\n419430400\nmemory.events\nlow 0\nhigh 0\nmax 0\noom 0\noom_kill 0\noom_group_kill 0\n';
    for (const phase of ['before', 'after']) fs.writeFileSync(path.join(child, `server-cgroup-${phase}.txt`), cgroup);
  }
  return directory;
}

test('resource-limited summary reports requested and effective limits independently of the heap', t => {
  const directory = resourceFixture(t);
  const markdown = execFileSync(process.execPath, [
    fileURLToPath(new URL('./report.mjs', import.meta.url)), 'workload', directory, 'plaintext', 'steady'
  ], {encoding: 'utf8', env: {...process.env, ARTIFACT_URL: 'https://example.test/native', MEASUREMENT_OUTCOME: 'success'}});
  assert.ok(markdown.includes('Requested JVM limits: 1 logical CPU(s); 1024 MiB'));
  assert.ok(markdown.includes('Effective JVM limits: 1 logical CPU(s); 1024.00 MiB memory; swap 0 bytes'));
  assert.ok(markdown.includes('Cgroup peak: 400.00 MiB; OOM kills: 0'));
  assert.ok(markdown.includes('Configured Java heap: 256 MiB'));
});

for (const [name, file, replacement, expected] of [
  ['missing cgroup snapshot', 'server-cgroup-before.txt', null, 'server-cgroup-before.txt'],
  ['unapplied memory cap', 'server-cgroup-after.txt', 'memory.max\n2147483648', 'verified JVM memory/swap limits'],
  ['swap remains enabled', 'server-cgroup-after.txt', 'memory.swap.max\nmax', 'verified JVM memory/swap limits'],
  ['wrong CPU affinity', 'server-affinity.txt', 'pid 123 current affinity list: 2-3', 'verified JVM CPU affinity'],
  ['OOM kill', 'server-cgroup-after.txt', 'oom_kill 1', 'OOM-kill-free JVM memory cgroup']
]) {
  test(`${name} prevents resource-limited evidence from being marked complete`, t => {
    const directory = resourceFixture(t);
    const child = path.join(directory, 'trial-1');
    const target = path.join(child, file);
    if (replacement === null) fs.unlinkSync(target);
    else fs.writeFileSync(target, replacement);
    const result = trial(child, 'plaintext', 'steady', 1);
    assert.equal(result.outcome, 'INCOMPLETE');
    assert.ok(result.missing.includes(expected));
  });
}

test('missing JVM limit diagnostics make trial evidence incomplete', t => {
  const directory = fixture(t, httpMetrics());
  fs.unlinkSync(path.join(directory, 'VM.info.txt'));
  const result = trial(directory, 'plaintext', 'steady', 1);
  assert.equal(result.outcome, 'INCOMPLETE');
  assert.ok(result.missing.includes('VM.info.txt'));
});

for (const [name, verify] of [
  ['workload Markdown places native maximum next to p99', markdown => {
    assert.ok(markdown.includes('| p99 ms | Max ms |'));
  }],
  ['workload Markdown places scheduled total and percentage next to drops', markdown => {
    assert.ok(markdown.includes('| Drops | Scheduled total | Dropped % |'));
  }],
  ['workload Markdown prints actual JVM characteristics and CPU/RAM limits', markdown => {
    for (const expected of ['JDK 25.0.4', 'ServerMain 18080 1000', '-XX:+UseG1GC',
      'initial active 2', 'memory_limit: 1 GiB', 'JVM affinity: 0,1', 'k6 affinity: 2,3']) {
      assert.ok(markdown.includes(expected), expected);
    }
    assert.ok(markdown.includes('256 MiB'));
    assert.ok(markdown.includes('not a cap on native memory or process RSS'));
  }]
]) {
  test(name, t => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'shoostr-jvm-report-'));
    t.after(() => fs.rmSync(directory, {recursive: true, force: true}));
    fs.writeFileSync(path.join(directory, 'configuration.json'), JSON.stringify({
      revision: 'test', warmup_seconds: 30, heap: '256 MiB', collector: 'G1'
    }));
    for (const index of [1, 2, 3]) {
      const child = path.join(directory, `trial-${index}`);
      fs.cpSync(fixture(t, httpMetrics()), child, {recursive: true});
      fs.writeFileSync(path.join(child, 'VM.version.txt'), 'JDK 25.0.4');
      fs.writeFileSync(path.join(child, 'VM.command_line.txt'), 'java_command: ServerMain 18080 1000');
      fs.writeFileSync(path.join(child, 'VM.flags.txt'), '-XX:+UseG1GC -XX:MaxHeapSize=268435456');
      fs.writeFileSync(path.join(child, 'VM.info.txt'), 'CPU: total 4 (initial active 2)\ncontainer memory_limit: 1 GiB');
      fs.writeFileSync(path.join(child, 'server-limits.txt'), 'Max resident set unlimited');
      fs.writeFileSync(path.join(child, 'server-affinity.txt'), 'JVM affinity: 0,1');
      fs.writeFileSync(path.join(child, 'client-affinity.txt'), 'k6 affinity: 2,3');
    }
    const markdown = execFileSync(process.execPath, [
      fileURLToPath(new URL('./report.mjs', import.meta.url)), 'workload', directory, 'plaintext', 'steady'
    ], {encoding: 'utf8', env: {...process.env, ARTIFACT_URL: 'https://example.test/native', MEASUREMENT_OUTCOME: 'success'}});
    verify(markdown);
  });
}

test('campaign summary preserves each trial maximum instead of pooling them', t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'shoostr-campaign-report-'));
  t.after(() => fs.rmSync(directory, {recursive: true, force: true}));
  const workloads = JSON.parse(fs.readFileSync(new URL('./workloads.json', import.meta.url), 'utf8'));
  for (const workload of Object.keys(workloads)) {
    const child = path.join(directory, workload);
    fs.mkdirSync(child);
    fs.writeFileSync(path.join(child, 'workload-summary.json'), JSON.stringify({
      workload, complete: true, artifact: 'https://example.test/native', artifact_bytes: 1024,
      trials: [1, 2, 3].map(index => ({outcome: 'PASS', rows: [{
        p95: index, p99: index + 10, maximum: index + 90, drops: 0,
        scheduled_total: workloads[workload].rate * 180, drop_percentage: 0
      }]}))
    }));
  }
  const markdown = execFileSync(process.execPath, [
    fileURLToPath(new URL('./report.mjs', import.meta.url)), 'campaign', directory, 'steady'
  ], {encoding: 'utf8'});
  assert.ok(markdown.includes('| p99 ms (per trial) | Max ms (per trial) |'));
  assert.ok(markdown.includes('| 11.00 / 12.00 / 13.00 | 91.00 / 92.00 / 93.00 |'));
});
