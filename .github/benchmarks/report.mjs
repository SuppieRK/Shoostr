import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const support = path.dirname(fileURLToPath(import.meta.url));
const workloads = JSON.parse(fs.readFileSync(path.join(support, 'workloads.json'), 'utf8'));
const disclaimer = 'Profiled diagnostics: JFR and raw-output overhead are included. Hosted hardware/images can vary. Percentiles are per trial, never pooled.';

function text(file) {
  return fs.existsSync(file) ? fs.readFileSync(file, 'utf8').trim() : '';
}

function json(file) {
  try { return JSON.parse(text(file)); } catch { return null; }
}

function exists(file) {
  return fs.existsSync(file) && fs.statSync(file).size > 0;
}

function value(metric) {
  return metric?.values ?? metric ?? {};
}

function number(value, decimals = 2) {
  return Number.isFinite(value) ? value.toFixed(decimals) : '—';
}

function percentage(value, decimals = 2) {
  return Number.isFinite(value) ? `${value.toFixed(decimals)}%` : '—';
}

function rss(file) {
  const match = text(file).match(/Maximum resident set size \(kbytes\): (\d+)/);
  return match ? Number(match[1]) / 1024 : null;
}

function cpu(file) {
  return text(file).match(/Percent of CPU this job got: (\d+)%/)?.[1] ?? null;
}

function affinityCount(file) {
  const list = text(file).match(/affinity list: ([\d,-]+)$/m)?.[1];
  if (!list) return null;
  return list.split(',').reduce((count, range) => {
    const [first, last = first] = range.split('-').map(Number);
    return count + last - first + 1;
  }, 0);
}

function cgroupNumber(evidence, key) {
  const match = evidence.match(new RegExp(`^${key}\\n(\\d+)$`, 'm'));
  return match ? Number(match[1]) : null;
}

export function trial(directory, workload, mode, index) {
  const configuration = json(path.join(path.dirname(directory), 'configuration.json'));
  const fixture = configuration?.fixture ?? workloads[workload];
  const seconds = mode === 'overload' ? configuration?.smoke_step_seconds ?? 15
    : configuration?.measurement_seconds ?? 180;
  const metrics = json(path.join(directory, 'measured-summary.json'))?.metrics;
  const warmupMetrics = json(path.join(directory, 'warmup-summary.json'))?.metrics;
  const warmupDrops = value(warmupMetrics?.dropped_iterations).count ?? null;
  const warmupTotal = Number.isFinite(configuration?.warmup_seconds)
    ? fixture.rate * configuration.warmup_seconds : null;
  const warmup = {
    completed: value(warmupMetrics?.[fixture.kind === 'http' ? 'http_reqs{scenario:requests}' : 'iterations']).count ?? null,
    drops: warmupDrops, scheduled_total: warmupTotal,
    drop_percentage: Number.isFinite(warmupDrops) && warmupTotal > 0 ? warmupDrops / warmupTotal * 100 : null,
    check_failures: value(warmupMetrics?.checks).fails ?? null,
    http_failure_rate: value(warmupMetrics?.http_req_failed).value ?? value(warmupMetrics?.http_req_failed).rate ?? null
  };
  const nativeOutcome = text(path.join(directory, 'outcome.txt')) || 'INCOMPLETE';
  const required = ['server.jfr', 'jfr-summary.txt', 'measured-summary.json',
    'measured-metrics.json.gz', 'report.html', 'process-monitor.txt', 'monitor-pids.txt',
    'server-resource.txt', 'measured-client-resource.txt', 'allocation-by-site.txt',
    'allocation-by-class.txt', 'hot-methods.txt', 'gc-pauses.txt', 'gc-cpu-time.txt',
    'VM.version.txt', 'VM.command_line.txt', 'VM.flags.txt', 'VM.flags-all.txt', 'VM.info.txt',
    'server-limits.txt', 'server-affinity.txt', 'client-affinity.txt'];
  const missing = required.filter(file => !exists(path.join(directory, file)));
  const cgroupBefore = text(path.join(directory, 'server-cgroup-before.txt'));
  const cgroupAfter = text(path.join(directory, 'server-cgroup-after.txt'));
  const effectiveCpus = affinityCount(path.join(directory, 'server-affinity.txt'));
  const memoryMax = cgroupNumber(cgroupAfter, 'memory.max');
  const swapMax = cgroupNumber(cgroupAfter, 'memory.swap.max');
  const oomKills = Number(cgroupAfter.match(/^oom_kill (\d+)$/m)?.[1] ?? NaN);
  if (configuration?.jvm_memory_mib !== undefined) {
    for (const file of ['server-cgroup-before.txt', 'server-cgroup-after.txt']) {
      if (!exists(path.join(directory, file))) missing.push(file);
    }
    if (memoryMax !== configuration.jvm_memory_mib * 1024 * 1024
        || cgroupNumber(cgroupBefore, 'memory.max') !== memoryMax || swapMax !== 0
        || cgroupNumber(cgroupBefore, 'memory.swap.max') !== 0) missing.push('verified JVM memory/swap limits');
    if (oomKills !== 0) missing.push('OOM-kill-free JVM memory cgroup');
    if (cgroupNumber(cgroupAfter, 'memory.peak') === null) missing.push('usable JVM cgroup peak memory');
  }
  if (configuration?.jvm_cpus !== undefined && effectiveCpus !== configuration.jvm_cpus) {
    missing.push('verified JVM CPU affinity');
  }
  const checks = value(metrics?.checks);
  const rows = [];
  const scenarios = mode === 'overload'
    ? (configuration?.smoke_rates ?? [1000, 5000, 10000, 50000])
      .map(rate => ({rate, suffix: `{scenario:step_${rate}}`}))
    : [{rate: fixture.rate, suffix: fixture.kind === 'http' ? '{scenario:requests}' : ''}];
  for (const scenario of scenarios) {
    const latency = value(metrics?.[mode === 'overload' ? `http_req_duration${scenario.suffix}` : fixture.metric]);
    const throughputKey = fixture.kind === 'http' ? `http_reqs${scenario.suffix}` : 'iterations';
    const throughput = value(metrics?.[throughputKey]);
    const failures = value(metrics?.[mode === 'overload' ? `http_req_failed${scenario.suffix}` : 'http_req_failed']);
    const drops = value(metrics?.[mode === 'overload' ? `dropped_iterations${scenario.suffix}` : 'dropped_iterations']);
    const scopedChecks = mode === 'overload' ? value(metrics?.[`checks${scenario.suffix}`]) : checks;
    const scheduled = scenario.rate * seconds;
    const total = Number.isFinite(scheduled) && scheduled > 0 ? scheduled : null;
    rows.push({trial: index, offered: scenario.rate, count: throughput.count ?? null,
      rate: mode === 'overload' && Number.isFinite(throughput.count) ? throughput.count / seconds : throughput.rate ?? null,
      latency: fixture.latency, average: latency.avg ?? null, median: latency.med ?? null,
      p95: latency['p(95)'] ?? null, p99: latency['p(99)'] ?? null, maximum: latency.max ?? null,
      check_failures: scopedChecks.fails ?? null,
      http_failure_rate: failures.value ?? failures.rate ?? null,
      drops: drops.count ?? null, scheduled_total: total,
      drop_percentage: Number.isFinite(drops.count) && total !== null ? drops.count / total * 100 : null});
  }
  if (!metrics || !Number.isFinite(checks.passes) || !Number.isFinite(checks.fails)) missing.push('usable k6 checks');
  for (const row of rows) {
    if (!Number.isFinite(row.count) || (row.count > 0 && !Number.isFinite(row.p95))) missing.push(`usable metrics for offered rate ${row.offered}`);
  }
  return {index, outcome: missing.length ? 'INCOMPLETE' : nativeOutcome, missing, rows, warmup,
    server_effective_cpus: effectiveCpus,
    server_memory_max_mib: memoryMax === null ? null : memoryMax / 1024 / 1024,
    server_swap_max_bytes: swapMax,
    server_cgroup_peak_mib: cgroupNumber(cgroupAfter, 'memory.peak') === null ? null
      : cgroupNumber(cgroupAfter, 'memory.peak') / 1024 / 1024,
    server_oom_kills: Number.isFinite(oomKills) ? oomKills : null,
    server_lifetime_cpu_percent: cpu(path.join(directory, 'server-resource.txt')),
    client_measured_cpu_percent: cpu(path.join(directory, 'measured-client-resource.txt')),
    server_peak_rss_mib: rss(path.join(directory, 'server-resource.txt')),
    client_peak_rss_mib: rss(path.join(directory, 'measured-client-resource.txt'))};
}

export function workloadSummary(directory, workload, mode) {
  if (!workloads[workload]) throw new Error(`Unknown workload: ${workload}`);
  const configuration = json(path.join(directory, 'configuration.json'));
  const trials = [1, 2, 3].map(index => trial(path.join(directory, `trial-${index}`), workload, mode, index));
  const artifact = process.env.ARTIFACT_URL || '';
  const execution = process.env.MEASUREMENT_OUTCOME || 'unknown';
  const complete = Boolean(configuration && artifact && execution === 'success'
    && trials.every(result => ['PASS', 'OVERLOAD'].includes(result.outcome)));
  return {workload, mode, configuration, artifact, artifact_bytes: Number(process.env.ARTIFACT_BYTES) || null,
    job_url: `${process.env.GITHUB_SERVER_URL || 'https://github.com'}/${process.env.GITHUB_REPOSITORY || 'SuppieRK/Shoostr'}/actions/runs/${process.env.GITHUB_RUN_ID || ''}`,
    complete, execution, trials};
}

function table(summary) {
  const lines = ['| Trial | Outcome | Offered/s | k6 completed/s | Avg ms | p50 ms | p95 ms | p99 ms | Max ms | Failed checks | HTTP failures | Drops | Scheduled total | Dropped % |',
    '|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|'];
  for (const result of summary.trials) {
    for (const row of result.rows) {
      lines.push(`| ${row.trial} | ${result.outcome} | ${row.offered} | ${number(row.rate)} | ${number(row.average)} | ${number(row.median)} | ${number(row.p95)} | ${number(row.p99)} | ${number(row.maximum)} | ${row.check_failures ?? '—'} | ${percentage(row.http_failure_rate === null ? null : row.http_failure_rate * 100)} | ${row.drops ?? '—'} | ${row.scheduled_total ?? '—'} | ${percentage(row.drop_percentage, 4)} |`);
    }
  }
  return lines.join('\n');
}

function workloadMarkdown(summary, directory) {
  const fixture = summary.configuration?.fixture ?? workloads[summary.workload];
  const lines = [`## ${summary.workload} (${summary.mode})`, '',
    `Evidence: **${summary.complete ? 'COMPLETE' : 'INCOMPLETE / FAILED'}**; measurement step: ${summary.execution}.`, '', disclaimer, '',
    `Latency: **${fixture.latency}**. Completed rate uses native k6 elapsed-time rates; overload rates use each ${summary.configuration?.smoke_step_seconds ?? 15}-second plateau's completed count.`, '',
    'Drops are k6 iterations that never started, not failed HTTP requests. Scheduled total = offered rate × load duration (excluding warmup and drains); dropped percentage uses that total. One iteration is one HTTP request or one SSE/WebSocket session, not an event or message. Partial runs still show the configured full-run total.', '',
    table(summary), '', `Artifact: ${summary.artifact ? `[native outputs](${summary.artifact})` : '**UPLOAD MISSING**'}; size: ${number(summary.artifact_bytes === null ? null : summary.artifact_bytes / 1024 / 1024)} MiB; retention: ${process.env.RETENTION_DAYS || '?'} days.`, '',
    `Revision: \`${summary.configuration?.revision || 'unknown'}\`; image: \`${summary.configuration?.image || 'unknown'}\`.`, '',
    `Warmup: ${summary.configuration?.warmup_seconds ?? 'unknown'} seconds per fresh JVM.`, '',
    `Configured Java heap: ${summary.configuration?.heap || 'unknown'}; collector: ${summary.configuration?.collector || 'unknown'}. This is not a cap on native memory or process RSS. CPU affinity restricts eligible logical CPUs, not exclusive CPU ownership. Native VM.info and OS limits below describe JVM-visible CPU/RAM and any detected container/process limits; unavailable limits are not inferred.`, '',
    `Requested JVM limits: ${summary.configuration?.jvm_cpus ?? 'not recorded'} logical CPU(s); ${summary.configuration?.jvm_memory_mib ?? 'not recorded'} MiB total cgroup memory. Heap remains separate. Configured memory includes native memory and cgroup-accounted cache/kernel memory; swap is disabled for new resource-limited runs.`, '',
    'Server peak RSS covers its whole lifetime, including startup/warmup. Client peak RSS covers the measured invocation.'];
  for (const result of summary.trials) {
    lines.push('', `### Trial ${result.index}: ${result.outcome}`, '',
      `Peak RSS: server ${number(result.server_peak_rss_mib)} MiB; client ${number(result.client_peak_rss_mib)} MiB.`, '',
      `Native time CPU: whole JVM lifetime ${result.server_lifetime_cpu_percent ?? '—'}%; measured client ${result.client_measured_cpu_percent ?? '—'}%.`);
    lines.push('', `Effective JVM limits: ${result.server_effective_cpus ?? '—'} logical CPU(s); ${number(result.server_memory_max_mib)} MiB memory; swap ${result.server_swap_max_bytes ?? '—'} bytes. Cgroup peak: ${number(result.server_cgroup_peak_mib)} MiB; OOM kills: ${result.server_oom_kills ?? '—'}.`);
    lines.push('', `Warmup: completed ${result.warmup.completed ?? '—'}; drops ${result.warmup.drops ?? '—'} / ${result.warmup.scheduled_total ?? '—'} (${percentage(result.warmup.drop_percentage, 4)}); failed checks ${result.warmup.check_failures ?? '—'}; HTTP failures ${percentage(result.warmup.http_failure_rate === null ? null : result.warmup.http_failure_rate * 100)}.`);
    if (result.missing.length) lines.push('', `Missing evidence: ${result.missing.join(', ')}.`);
    const trialDirectory = path.join(directory, `trial-${result.index}`);
    lines.push('', `CPU affinity: JVM \`${text(path.join(trialDirectory, 'server-affinity.txt')) || 'Unavailable'}\`; k6 \`${text(path.join(trialDirectory, 'client-affinity.txt')) || 'Unavailable'}\`.`, '',
      '<details><summary>JVM version, flags, CPU/RAM and process limits</summary>', '');
    for (const file of ['VM.version', 'VM.command_line', 'VM.flags', 'VM.info', 'server-limits', 'server-cgroup-before', 'server-cgroup-after']) {
      lines.push(`**${file}**`, '', '```text', text(path.join(trialDirectory, `${file}.txt`)) || 'Unavailable', '```', '');
    }
    lines.push('The complete effective flag list is retained as `VM.flags-all.txt` in the native artifact.', '', '</details>');
    lines.push('', '<details><summary>Native JFR excerpts</summary>', '');
    for (const view of ['jfr-summary', 'allocation-by-site', 'hot-methods', 'gc-pauses', 'gc-cpu-time']) {
      const excerpt = text(path.join(directory, `trial-${result.index}`, `${view}.txt`)).split('\n').slice(0, 18).join('\n');
      lines.push(`**${view}**`, '', '```text', excerpt || 'Unavailable', '```', '');
    }
    lines.push('</details>');
  }
  return lines.join('\n') + '\n';
}

function campaign(directory, mode) {
  const summaries = new Map();
  if (fs.existsSync(directory)) {
    for (const entry of fs.readdirSync(directory, {withFileTypes: true})) {
      if (!entry.isDirectory()) continue;
      const summary = json(path.join(directory, entry.name, 'workload-summary.json'));
      if (summary) summaries.set(summary.workload, summary);
    }
  }
  const expected = Object.keys(workloads).filter(name => mode === 'steady' || workloads[name].kind === 'http');
  const lines = ['# Shoostr benchmark campaign', '', disclaimer, '',
    '| Workload | Latency measure | Trials 1 / 2 / 3 | p95 ms (per trial) | p99 ms (per trial) | Max ms (per trial) | Drops / scheduled total (%) (per trial) | Native outputs | Size MiB |',
    '|---|---|---|---|---|---|---|---|---:|'];
  let complete = true;
  for (const name of expected) {
    const summary = summaries.get(name);
    if (!summary) {
      complete = false;
      lines.push(`| ${name} | ${workloads[name].latency} | **MISSING** | — | — | — | — | — | — |`);
      continue;
    }
    if (!summary.complete) complete = false;
    const outcomes = summary.trials.map(result => result.outcome).join(' / ');
    const latencies = ['p95', 'p99', 'maximum'].map(key => mode === 'steady'
      ? summary.trials.map(result => number(result.rows[0]?.[key])).join(' / ') : 'See plateau rows');
    const drops = mode === 'steady' ? summary.trials.map(result => {
      const row = result.rows[0];
      return `${row?.drops ?? '—'} / ${row?.scheduled_total ?? '—'} (${percentage(row?.drop_percentage, 4)})`;
    }).join('<br>') : 'See plateau rows';
    lines.push(`| ${name} | ${workloads[name].latency} | ${outcomes} | ${latencies.join(' | ')} | ${drops} | ${summary.artifact ? `[download](${summary.artifact})` : '**UPLOAD MISSING**'} | ${number(summary.artifact_bytes === null ? null : summary.artifact_bytes / 1024 / 1024)} |`);
  }
  lines.push('', `Campaign evidence: **${complete ? 'COMPLETE' : 'INCOMPLETE / FAILED'}**.`, '',
    'Each workload uses its own runner; repeats share that runner. Drops are never-started iterations divided by configured scheduled iterations: HTTP requests or SSE/WebSocket sessions, not messages. See individual job summaries for per-plateau metrics, actual JVM version/flags, CPU/RAM limits, resource measurements and JFR excerpts. Complete artifacts expire after the selected retention period.');
  return {markdown: lines.join('\n') + '\n', complete};
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [operation, directory, workload, mode] = process.argv.slice(2);
  if (operation === 'workload') {
    fs.mkdirSync(directory, {recursive: true});
    const summary = workloadSummary(directory, workload, mode);
    const markdown = workloadMarkdown(summary, directory);
    fs.writeFileSync(path.join(directory, 'workload-summary.json'), JSON.stringify(summary, null, 2));
    fs.writeFileSync(path.join(directory, 'summary.md'), markdown);
    process.stdout.write(markdown);
    if (!summary.complete) process.exitCode = 1;
  } else if (operation === 'campaign') {
    const result = campaign(directory, workload);
    process.stdout.write(result.markdown);
    if (!result.complete) process.exitCode = 1;
  } else {
    throw new Error('Expected workload or campaign report operation');
  }
}
