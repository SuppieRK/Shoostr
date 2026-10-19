import fs from 'node:fs';
import path from 'node:path';
import { gunzipSync } from 'node:zlib';
import { pathToFileURL } from 'node:url';

export function verifyHealth(summary) {
  const completed = summary.metrics?.health_completed?.count;
  if (![100, 101].includes(completed)) {
    throw new Error('Expected 100 or 101 complete health probes');
  }
  const elapsed = summary.metrics?.health_elapsed?.max;
  if (!Number.isFinite(elapsed) || elapsed < 0 || elapsed > 1000) {
    throw new Error('Every complete healthy response must finish within one second');
  }
  if (summary.metrics?.checks?.value !== 1 || summary.metrics?.checks?.passes !== 3 * completed
      || summary.metrics?.checks?.fails !== 0) {
    throw new Error('Healthy response status/body validation failed');
  }
  if (summary.metrics?.http_req_failed?.value !== 0) {
    throw new Error('Healthy HTTP failure detected');
  }
  if (summary.metrics?.dropped_iterations?.count !== 0) {
    throw new Error('Healthy probes were dropped or their evidence is missing');
  }
  return completed;
}

export function verifyRecovery(state, clients) {
  if (![32, 128].includes(clients)) throw new Error('Expected 32 or 128 clients');
  for (const name of ['active_bodies', 'active_blocked', 'active_exchanges']) {
    if (state[name] !== 0) throw new Error(`Recovery has outstanding ${name}`);
  }
  if (state.connections !== 1) {
    throw new Error('Only the current state-control connection may remain');
  }
  if (!Number.isSafeInteger(state.admitted) || state.admitted < clients
      || state.completed !== state.admitted) {
    throw new Error('Workload completion accounting is incomplete');
  }
  if (state.peak_work !== clients) {
    throw new Error(`Did not observe the selected ${clients} clients`);
  }
}

export function verifyTiming(phase, points, completed = 100) {
  if (![phase.start_ms, phase.load_end_ms, phase.recovery_end_ms].every(Number.isFinite)) {
    throw new Error('Missing phase timing evidence');
  }
  if (phase.load_end_ms - phase.start_ms < 60000) throw new Error('Load did not last 60 seconds');
  if (phase.recovery_end_ms - phase.load_end_ms < 30000) {
    throw new Error('Recovery was not observed for 30 seconds');
  }
  if (![100, 101].includes(completed) || !Array.isArray(points) || points.length !== completed
      || !points.every(Number.isFinite)) {
    throw new Error('Missing complete raw probe evidence');
  }
  if (Math.min(...points) > phase.start_ms + 1000
      || Math.max(...points) < phase.recovery_end_ms - 1000) {
    throw new Error('Health probe coverage does not span load and recovery');
  }
}

export function verifyRecoveryWindow(samples, phase, clients) {
  for (const sample of samples) {
    const elapsed = sample.timestamp_ms - phase.load_end_ms;
    if (sample.phase !== 'recovery' || !Number.isFinite(elapsed) || elapsed < 0 || elapsed > 30000) {
      continue;
    }
    try {
      verifyRecovery(sample, clients);
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

function verifyBlocked(rows, phase, clients) {
  // Native HTTP durations end when the response body completes. Allow five seconds
  // for startup and the final interrupted handler batch; bound between-call gaps to
  // one observation interval, not an offered/completed throughput target.
  const start = phase.start_ms + 5000;
  const end = phase.start_ms + 55000;
  const vus = rows.filter(row => row.type === 'Point' && row.metric === 'vus')
    .map(row => ({ time: Date.parse(row.data.time), count: row.data.value }))
    .filter(row => row.time >= start && row.time < phase.start_ms + 60000)
    .sort((a, b) => a.time - b.time);
  if (!vus.length || vus[0].time > start + 1500
      || vus.at(-1).time < phase.start_ms + 58000
      || vus.some((row, index) => row.count !== clients
        || (index > 0 && row.time - vus[index - 1].time > 1500))) {
    throw new Error('Missing sustained selected VU exposure');
  }
  const requests = rows.filter(row => row.type === 'Point' && row.metric === 'http_req_duration'
    && row.data.tags?.scenario === 'blocked' && row.data.tags?.status === '200');
  for (let vu = 1; vu <= clients; vu++) {
    const intervals = requests.filter(row => row.data.metadata?.vu === String(vu)).map(row => {
      const finished = Date.parse(row.data.time);
      if (!Number.isFinite(finished) || !Number.isFinite(row.data.value) || row.data.value <= 0) {
        throw new Error('Invalid native blocked exposure evidence');
      }
      return { start: finished - row.data.value, end: finished };
    }).sort((a, b) => a.start - b.start);
    let covered = start;
    for (const interval of intervals) {
      if (interval.end < start) continue;
      if (interval.start > covered + 1000) break;
      covered = Math.max(covered, interval.end);
      if (covered >= end) break;
    }
    if (covered < end - 1000) throw new Error(`Blocked exposure gap for VU ${vu}`);
  }
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
  const completed = verifyHealth(summary);
  const state = json('recovered-state.json');
  verifyRecovery(state, clients);
  const raw = gunzipSync(fs.readFileSync(path.join(directory, 'health-metrics.json.gz')))
    .toString().trim().split('\n').map(line => JSON.parse(line))
    .filter(row => row.type === 'Point' && row.metric === 'health_completed');
  if (raw.some(row => row.data.value !== 1)) throw new Error('Invalid raw probe evidence');
  verifyTiming(phase, raw.map(row => Date.parse(row.data.time)), completed);
  const samples = read('state-samples.jsonl').trim().split('\n').map(line => JSON.parse(line));
  const recoveryMs = verifyRecoveryWindow(samples, phase, clients);
  verifyMemory(read('memory.events'), read('server.log') + '\n' + read('java-errors.txt'));
  if (phase.kind === 'blocked') {
    const blocked = gunzipSync(fs.readFileSync(path.join(directory, 'blocked-metrics.json.gz')))
      .toString().trim().split('\n').map(line => JSON.parse(line));
    verifyBlocked(blocked, phase, clients);
    const checks = json('blocked-summary.json').metrics?.checks;
    if (checks?.value !== 1 || !(checks.passes > 0) || checks.fails !== 0) {
      throw new Error('Blocked workload validation failed');
    }
  } else {
    verifyBodies(read('body.csv'), clients);
  }
  return { verified: true, kind: phase.kind, clients, peak_work: state.peak_work,
    admitted: state.admitted, completed: state.completed,
    health_probes: completed, health_max_ms: summary.metrics.health_elapsed.max, recovery_ms: recoveryMs };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const [directory, count, ...extra] = process.argv.slice(2);
    if (!directory || !['32', '128'].includes(count) || extra.length) {
      throw new Error('Supply a cycle artifact directory and 32/128 clients');
    }
    console.log(JSON.stringify(verifyCycle(directory, Number(count))));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
