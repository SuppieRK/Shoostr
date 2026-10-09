import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const models = ['A', 'B', 'C', 'D'];
const value = metric => metric?.values ?? metric ?? {};
const read = file => fs.existsSync(file) ? fs.readFileSync(file, 'utf8').trim() : '';
const exists = file => fs.existsSync(file) && fs.statSync(file).size > 0;
const number = n => Number.isFinite(n) ? n.toFixed(3) : '—';
function excerpt(file) {
  const content = read(file);
  const bounded = Buffer.from(content.split('\n').slice(0, 40).join('\n')).subarray(0, 4000).toString('utf8');
  return bounded + (bounded.length < content.length ? '\nFull native output retained in artifact.' : '');
}

export function threadingSummary(directory, workload) {
  if (!['tiny', 'io', 'cpu', 'mixed'].includes(workload)) throw new Error('Unknown workload');
  let complete = true;
  const lines = [`# Threading comparison: ${workload}`, '',
    'A = current virtual transport; B = platform transport + virtual consumption; C = platform-only control; D = B + selected matched lifecycle on shared platform workers.', '',
    'Timing trials have no JFR/raw JSON/dashboard. Profiling trials are separate and include that overhead. Hosted CPUs are affinity-restricted, not exclusive. Warmup is 30 seconds, not a guarantee of JIT stabilization.', '',
    'Each row preserves native trial percentiles. Mixed steps are 60 seconds, including two 1-second rate transitions; step throughput is tagged request count / 60, while whole-trial throughput is native k6 rate. No percentile pooling.', '',
    'HTTP duration covers sending, waiting and complete response receipt; connection acquisition/establishment are excluded and reported separately. Phase percentiles cannot be added to invent a whole-call percentile.', '',
    '| Window | Model | Repeat | Endpoint | Step | Completed/s | p50 ms | p95 ms | p99 ms | Max ms | Blocked p99 ms | Connect p99 ms | HTTP failed % |',
    '|---|---|---:|---|---|---:|---:|---:|---:|---:|---:|---:|---:|'];
  const details = [];
  const rates = new Map();
  for (const window of ['timing', 'profile']) {
    const repeats = window === 'timing' ? [1, 2, 3] : [1];
    for (const repeat of repeats) {
      for (const model of models) {
        const run = path.join(directory, window, `repeat-${repeat}`, model);
        const trial = path.join(run, 'trial-1');
        let metrics;
        try { metrics = JSON.parse(read(path.join(trial, 'measured-summary.json'))).metrics; } catch { metrics = null; }
        const required = ['outcome.txt', 'measured-summary.json', 'server-resource.txt',
          'measured-client-resource.txt', 'process-monitor.txt', 'VM.version.txt', 'VM.command_line.txt', 'VM.flags.txt',
          'VM.flags-all.txt', 'VM.info.txt', 'server-affinity.txt', 'server-limits.txt'];
        if (window === 'profile') required.push('server.jfr', 'jfr-summary.txt',
          'allocation-by-site.txt', 'hot-methods.txt', 'cpu-time-hot-methods.txt',
          'cpu-time-statistics.txt', 'gc-pauses.txt', 'measured-metrics.json.gz', 'server.log');
        if (workload === 'io') required.push('downstream-resource.txt', 'downstream-VM.info.txt');
        const missing = required.filter(file => !exists(path.join(trial, file)));
        const outcome = read(path.join(trial, 'outcome.txt'));
        let configuration;
        try { configuration = JSON.parse(read(path.join(run, 'configuration.json'))); } catch { configuration = null; }
        if (configuration?.model !== model || configuration?.profile !== (window === 'profile')) {
          missing.push('matching configuration.json');
        }
        if (!Number.isFinite(value(metrics?.checks).passes) || !Number.isFinite(value(metrics?.checks).fails)) {
          missing.push('usable checks');
        }
        if (!Number.isFinite(value(metrics?.dropped_iterations).count)) missing.push('usable dropped_iterations');
        if (window === 'profile' && !/^SCHEDULER timestamp=\d+ /m.test(read(path.join(trial, 'server.log')))) {
          missing.push('scheduler samples');
        }
        if (!metrics || missing.length || !['PASS', 'OVERLOAD'].includes(outcome)) complete = false;
        const endpoints = workload === 'mixed' ? ['tiny', 'cpu'] : workload === 'io' ? ['io', 'downstream'] : [workload];
        for (const endpoint of endpoints) {
          for (const step of workload === 'mixed' ? [1, 2, 3] : [null]) {
            const suffix = `{name:${endpoint}${step === null ? '' : `,step:${step}`}}`;
            const latency = value(metrics?.[`http_req_duration${suffix}`]);
            const count = value(metrics?.[`http_reqs${suffix}`]);
            const failure = value(metrics?.[`http_req_failed${suffix}`]);
            const blocked = value(metrics?.[`http_req_blocked${suffix}`]);
            const connecting = value(metrics?.[`http_req_connecting${suffix}`]);
            if (!Number.isFinite(count.count) || !Number.isFinite(count.rate)
                || !Number.isFinite(failure.value ?? failure.rate)
                || (count.count > 0 && ![latency.med, latency['p(95)'], latency['p(99)'], latency.max,
                  blocked['p(99)'], connecting['p(99)']].every(Number.isFinite))) complete = false;
            const rate = step === null ? count.rate : count.count / 60;
            lines.push(`| ${window} | ${model} | ${repeat} | ${endpoint} | ${step ?? 'all'} | ${number(rate)} | ${number(latency.med)} | ${number(latency['p(95)'])} | ${number(latency['p(99)'])} | ${number(latency.max)} | ${number(blocked['p(99)'])} | ${number(connecting['p(99)'])} | ${number((failure.value ?? failure.rate) * 100)} |`);
            if (window === 'timing') {
              const key = `${model}/${endpoint}/${step ?? 'all'}`;
              if (!rates.has(key)) rates.set(key, []);
              rates.get(key).push(rate);
            }
          }
        }
        const drops = value(metrics?.dropped_iterations).count;
        const scheduled = workload === 'mixed' ? 281600 : (workload === 'tiny' ? 5000 : workload === 'io' ? 82 : 200) * 180;
        details.push('', `## ${window} ${model}, repeat ${repeat}: ${outcome || 'MISSING'}`, '',
          `Drops: ${drops ?? 'unknown'} / ${scheduled} scheduled iterations (${number(drops / scheduled * 100)}%). I/O total includes the 2/s independent downstream probe. Checks failed: ${value(metrics?.checks).fails ?? 'unknown'}.`, '',
          missing.length ? `Missing evidence: ${missing.join(', ')}.` : 'Required native outputs retained.', '',
          '<details><summary>JVM, CPU/RAM, resource limits and native counters</summary>', '');
        for (const file of ['VM.version', 'VM.command_line', 'VM.flags', 'VM.info',
          'server-affinity', 'server-limits', 'server-resource', 'measured-client-resource',
          ...(workload === 'io' ? ['downstream-resource', 'downstream-affinity', 'downstream-VM.info'] : [])]) {
          details.push(`### ${file}`, '', '```text', excerpt(path.join(trial, `${file}.txt`)) || 'Unavailable', '```', '');
        }
        if (window === 'profile') {
          for (const file of ['hot-methods', 'cpu-time-hot-methods', 'cpu-time-statistics', 'allocation-by-site', 'gc-pauses']) {
            details.push(`### ${file}`, '', '```text', excerpt(path.join(trial, `${file}.txt`)), '```', '');
          }
          details.push('Scheduler/worker/adaptive counters are in server.log. An empty native strategy list is unavailable evidence, not zero adaptive activity.', '');
        }
        details.push('</details>');
      }
    }
  }
  lines.push('', 'Supplemental arithmetic mean throughput across the three timing repetitions (not pooled latency):', '');
  for (const [key, samples] of rates) {
    lines.push(`- ${key}: ${samples.length === 3 && samples.every(Number.isFinite) ? number(samples.reduce((a, b) => a + b, 0) / 3) : 'incomplete'} completed/s.`);
  }
  lines.push('', `Evidence: ${complete ? 'COMPLETE' : 'INCOMPLETE'}. Overload threshold failures are diagnostic outcomes, not proof of correctness or equal capacity.`, ...details);
  return {complete, markdown: lines.join('\n') + '\n'};
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const result = threadingSummary(process.argv[2], process.argv[3]);
  process.stdout.write(result.markdown);
  if (!result.complete) process.exitCode = 1;
}
