import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';

function verifyNative(summary, connections, seconds, closed) {
  const config = summary.config;
  assert.equal(summary.zrk_version, '3.0.0', 'Missing pinned native version');
  assert.ok(Number.isSafeInteger(summary.requests) && summary.requests > 0, 'Missing completed responses');
  assert.ok(Number.isFinite(summary.duration_s) && summary.duration_s >= seconds, 'Incomplete native duration');
  assert.equal(config?.duration_s, seconds, 'Unexpected configured duration');
  assert.equal(config?.connections, connections, 'Unexpected configured connections');
  assert.equal(config?.launched, connections, 'Incomplete native launch');
  assert.equal(config?.closed, closed, 'Unexpected native load model');
  assert.equal(config?.streams, 1, 'Unexpected stream count');
  assert.equal(config?.interval_s, 1, 'Unexpected native interval');
  assert.equal(config?.deadline_ms, 0, 'Unexpected deadline policy');
  assert.equal(config?.deadline_abort, false, 'Unexpected deadline abort policy');
  assert.equal(config?.record_timeouts, true, 'Timeouts must remain visible');
  for (const name of ['connect', 'read', 'write', 'timeout', 'deadline', 'non_2xx_3xx']) {
    assert.equal(summary.errors?.[name], 0, `Native ${name} failure or missing evidence`);
  }
  assert.equal(summary.workload?.response?.calls, summary.requests, 'Missing status/body validation');
  assert.equal(summary.error_rate, 0, 'Unexpected native error rate');
  assert.ok(Number.isFinite(summary.latency_us?.max) && summary.latency_us.max >= 0,
    'Missing native maximum latency');
  for (const name of ['1xx', '2xx', '3xx', '4xx', '5xx']) {
    assert.equal(summary.status_codes?.[name], name === '2xx' ? summary.requests : 0, 'Unexpected status counts');
  }
  assert.ok(typeof summary.latency_histogram === 'string' && summary.latency_histogram.length > 0,
    'Missing native histogram');
}

export function verifyHealth(summary) {
  verifyNative(summary, 1, 100, false);
  assert.ok([99, 100, 101].includes(summary.requests), 'Expected 99, 100 or 101 complete health probes');
  assert.equal(summary.config.disable_keepalive, true, 'Health probes must reconnect');
  assert.equal(summary.config.target_rate, 1, 'Expected one scheduled probe per second');
  assert.equal(summary.config.target_rate_end, 1, 'Expected fixed health rate');
  assert.equal(summary.config.timeout_ms, 1000, 'Unexpected health timeout');
  const maximum = summary.latency_us?.max;
  assert.ok(Number.isFinite(maximum) && maximum >= 0 && maximum <= 1000000,
    'Reported maximum scheduled latency must be within one second');
  return summary.requests;
}

export function verifyRecovery(state, clients, healthRunning = false) {
  if (![32, 128].includes(clients)) throw new Error('Expected 32 or 128 clients');
  for (const name of ['active_bodies', 'active_blocked', 'active_exchanges']) {
    if (state[name] !== 0) throw new Error(`Recovery has outstanding ${name}`);
  }
  if (state.connections !== 1 && !(healthRunning && state.connections === 2)) {
    throw new Error('Only the control connection and an owned running health connection may remain');
  }
  if (!Number.isSafeInteger(state.admitted) || state.admitted < clients
      || state.completed !== state.admitted) {
    throw new Error('Workload completion accounting is incomplete');
  }
  if (state.peak_work !== clients) {
    throw new Error(`Did not observe the selected ${clients} clients`);
  }
}

function verifyIntervals(rows, maximumLatency = Infinity) {
  assert.ok(Array.isArray(rows) && rows.length > 0, 'Missing native interval coverage');
  let previous = 0;
  for (const row of rows) {
    assert.ok(Number.isFinite(row.t) && row.t > previous && row.t - previous <= 3.5,
      'Native interval coverage gap');
    assert.ok(Number.isSafeInteger(row.requests) && row.requests >= 0 && row.errors === 0,
      'Invalid native interval counts');
    assert.ok(Number.isFinite(row.latency_us?.max) && row.latency_us.max >= 0
      && row.latency_us.max <= maximumLatency, 'Invalid interval scheduled latency');
    for (const name of ['connect', 'read', 'write', 'timeout', 'deadline', 'non_2xx_3xx']) {
      assert.equal(row.errors_by_kind?.[name], 0, 'Native interval failure or missing evidence');
    }
    assert.ok(typeof row.latency_histogram === 'string' && row.latency_histogram.length > 0,
      'Missing interval histogram');
    previous = row.t;
  }
  return previous;
}

export function verifyTiming(phase, rows, completed) {
  assert.ok([phase.start_ms, phase.load_end_ms, phase.recovery_end_ms,
    phase.health_start_ms, phase.health_ready_ms].every(Number.isFinite), 'Missing monotonic phase timing');
  assert.ok(phase.load_end_ms - phase.start_ms >= 60000, 'Load did not last 60 seconds');
  assert.ok(phase.recovery_end_ms - phase.load_end_ms >= 30000, 'Recovery was not observed for 30 seconds');
  assert.ok(phase.health_start_ms <= phase.health_ready_ms && phase.health_ready_ms <= phase.start_ms,
    'Health was not ready before exposure');
  assert.ok(Array.isArray(rows) && rows.length >= 99, 'Missing native interval coverage');
  const previous = verifyIntervals(rows, 1000000);
  assert.ok(previous >= 99, 'Truncated native interval coverage');
  const positive = rows.filter(row => row.requests > 0);
  assert.ok(positive.length > 0 && positive[0].t <= 2, 'Missing initial positive health interval');
  // The native start lies between process launch and readiness minus the first
  // positive interval time. These are interval boundaries, not response timestamps.
  const latestStart = phase.health_ready_ms - positive[0].t * 1000;
  assert.ok(latestStart >= phase.health_start_ms, 'Inconsistent health launch/readiness bounds');
  assert.ok(phase.health_start_ms + (positive.at(-1).t - 1) * 1000 >= phase.recovery_end_ms - 1000,
    'Health interval coverage does not span recovery');
  const total = rows.reduce((count, row) => count + row.requests, 0);
  assert.ok(total <= completed && completed - total <= 1, 'Unbounded native interval/summary boundary difference');
  return completed - total;
}

export function verifyRecoveryWindow(samples, phase, clients) {
  for (const sample of samples) {
    const elapsed = sample.timestamp_ms - phase.load_end_ms;
    if (sample.phase !== 'recovery' || !Number.isFinite(elapsed) || elapsed < 0 || elapsed > 30000) {
      continue;
    }
    try {
      verifyRecovery(sample, clients, true);
      return elapsed;
    } catch {
      // Continue observing native/control-connection cleanup until the fixed deadline.
    }
  }
  throw new Error('Work and connections did not recover within 30 seconds');
}

export function verifyMemory(events, errors) {
  const entries = events.trim().split('\n').filter(Boolean).map(line => {
    const match = line.match(/^(\w+)\s+(\d+)$/);
    if (!match) throw new Error('Missing valid cgroup memory evidence');
    return [match[1], Number(match[2])];
  });
  const names = new Set(entries.map(([name]) => name));
  if (!names.has('oom') || !names.has('oom_kill') || names.size !== entries.length) {
    throw new Error('Missing complete cgroup OOM evidence');
  }
  if (entries.some(([name, value]) => name.startsWith('oom') && value !== 0)
      || /\bOutOfMemoryError\b/.test(errors)) {
    throw new Error('Java or cgroup OOM detected');
  }
}

export function verifyBodies(csv, clients) {
  if (![32, 128].includes(clients)) throw new Error('Expected 32 or 128 clients');
  const [header, ...lines] = csv.trim().split('\n').map(line => line.trim());
  if (header !== 'Seconds,Closed,Pending,Connected,Service Available') {
    throw new Error('Unsupported slowhttptest CSV evidence');
  }
  const rows = lines.map(line => line.split(',').map(Number));
  if (rows.some(row => row.length !== 5 || row.some(value => !Number.isSafeInteger(value) || value < 0))
      || !rows.some(row => row[0] >= 60)) throw new Error('Incomplete slow-body evidence');
  for (let second = 5; second < 60; second++) {
    const samples = rows.filter(row => row[0] === second);
    if (!samples.length) throw new Error('Missing slow-body connection evidence');
    // slowhttptest 1.9.0 scales its availability flag by the configured connection count.
    if (samples.some(row => row[1] !== 0 || row[2] !== 0 || row[3] !== clients || row[4] !== clients)) {
      throw new Error('Reduced slow-body exposure or native probe failure');
    }
  }
}

export function verifyBlocked(samples, phase, clients) {
  const start = phase.start_ms + 5000;
  const end = phase.start_ms + 55000;
  const observations = samples.filter(row => row.phase === 'load'
    && row.timestamp_ms >= start && row.timestamp_ms <= end);
  let previous = start;
  let full = start;
  for (const row of observations) {
    assert.ok(Number.isFinite(row.timestamp_ms) && row.timestamp_ms >= previous
      && row.timestamp_ms - previous <= 3500, 'Missing sampled exposure evidence');
    for (const name of ['active_blocked', 'active_bodies', 'active_exchanges', 'admitted', 'completed']) {
      assert.ok(Number.isSafeInteger(row[name]) && row[name] >= 0, 'Invalid sampled workload counters');
    }
    assert.ok(row.active_blocked <= clients && row.active_bodies === 0
      && row.active_exchanges >= row.active_blocked
      && row.admitted - row.completed === row.active_exchanges, 'Invalid sampled workload accounting');
    if (row.active_blocked === clients) {
      assert.ok(row.timestamp_ms - full <= 3500, 'Reduced sampled blocked exposure');
      full = row.timestamp_ms;
    }
    previous = row.timestamp_ms;
  }
  assert.ok(end - previous <= 3500 && end - full <= 3500, 'Incomplete sampled blocked exposure');
}

export function verifyCycle(directory, clients) {
  if (![32, 128].includes(clients)) throw new Error('Expected 32 or 128 clients');
  const read = name => fs.readFileSync(path.join(directory, name), 'utf8');
  const json = name => JSON.parse(read(name));
  const phase = json('phase.json');
  if (!['body', 'blocked'].includes(phase.kind)) throw new Error('Unknown diagnostic kind');
  for (const name of ['load-exit-code.txt', 'health-exit-code.txt']) {
    if (read(name).trim() !== '0') throw new Error(`Load tool failed: ${name}`);
  }
  const summary = json('health-summary.json');
  assert.ok(read('health.hgrm').trim().length > 0, 'Missing health histogram');
  const completed = verifyHealth(summary);
  const state = json('recovered-state.json');
  verifyRecovery(state, clients);
  const intervals = read('health.ndjson').trim().split('\n').map(line => JSON.parse(line));
  const boundaryDifference = verifyTiming(phase, intervals, completed);
  const samples = read('state-samples.jsonl').trim().split('\n').map(line => JSON.parse(line));
  const recoveryMs = verifyRecoveryWindow(samples, phase, clients);
  verifyMemory(read('memory.events'), read('server.log') + '\n' + read('java-errors.txt'));
  if (phase.kind === 'blocked') {
    const blocked = json('blocked-summary.json');
    assert.ok(read('blocked.hgrm').trim().length > 0, 'Missing blocked histogram');
    verifyNative(blocked, clients, 60, true);
    assert.equal(blocked.config.disable_keepalive, false, 'Blocked clients must reuse connections');
    assert.equal(blocked.config.timeout_ms, 8000, 'Unexpected blocked timeout');
    const rows = read('blocked.ndjson').trim().split('\n').map(line => JSON.parse(line));
    assert.ok(rows.length >= 59 && verifyIntervals(rows) >= 59, 'Truncated blocked interval evidence');
    const total = rows.reduce((count, row) => count + row.requests, 0);
    assert.ok(total <= blocked.requests && blocked.requests - total <= clients,
      'Unbounded blocked interval/summary boundary difference');
    verifyBlocked(samples, phase, clients);
  } else {
    verifyBodies(read('body.csv'), clients);
  }
  return { verified: true, kind: phase.kind, clients, peak_work: state.peak_work,
    admitted: state.admitted, completed: state.completed,
    health_probes: completed, health_max_scheduled_us: summary.latency_us.max, interval_summary_boundary_difference: boundaryDifference,
    exposure: "sampled population", recovery_ms: recoveryMs };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const [directory, count, ...extra] = process.argv.slice(2);
    assert(directory && ['32', '128'].includes(count) && extra.length === 0,
      'Supply a cycle artifact directory and 32/128 clients');
    console.log(JSON.stringify(verifyCycle(directory, Number(count))));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
