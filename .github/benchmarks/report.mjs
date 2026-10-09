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

function rss(file) {
  const match = text(file).match(/Maximum resident set size \(kbytes\): (\d+)/);
  return match ? Number(match[1]) / 1024 : null;
}

function cpu(file) {
  return text(file).match(/Percent of CPU this job got: (\d+)%/)?.[1] ?? null;
}

export function trial(directory, workload, mode, index) {
  const fixture = workloads[workload];
  const metrics = json(path.join(directory, 'measured-summary.json'))?.metrics;
  const nativeOutcome = text(path.join(directory, 'outcome.txt')) || 'INCOMPLETE';
  const required = ['server.jfr', 'jfr-summary.txt', 'measured-summary.json',
    'measured-metrics.json.gz', 'report.html', 'process-monitor.txt', 'monitor-pids.txt',
    'server-resource.txt', 'measured-client-resource.txt', 'allocation-by-site.txt',
    'allocation-by-class.txt', 'hot-methods.txt', 'gc-pauses.txt', 'gc-cpu-time.txt'];
  const missing = required.filter(file => !exists(path.join(directory, file)));
  const checks = value(metrics?.checks);
  const rows = [];
  const scenarios = mode === 'overload'
    ? [1000, 2000, 5000, 10000, 20000, 50000, 100000].map(rate => ({rate, suffix: `{scenario:step_${rate}}`}))
    : [{rate: fixture.rate, suffix: fixture.kind === 'http' ? '{scenario:requests}' : ''}];
  for (const scenario of scenarios) {
    const latency = value(metrics?.[mode === 'overload' ? `http_req_duration${scenario.suffix}` : fixture.metric]);
    const throughputKey = fixture.kind === 'http' ? `http_reqs${scenario.suffix}` : 'iterations';
    const throughput = value(metrics?.[throughputKey]);
    const failures = value(metrics?.[mode === 'overload' ? `http_req_failed${scenario.suffix}` : 'http_req_failed']);
    const drops = value(metrics?.[mode === 'overload' ? `dropped_iterations${scenario.suffix}` : 'dropped_iterations']);
    const scopedChecks = mode === 'overload' ? value(metrics?.[`checks${scenario.suffix}`]) : checks;
    rows.push({trial: index, offered: scenario.rate, count: throughput.count ?? null,
      rate: mode === 'overload' && Number.isFinite(throughput.count) ? throughput.count / 15 : throughput.rate ?? null,
      latency: fixture.latency, average: latency.avg ?? null, median: latency.med ?? null,
      p95: latency['p(95)'] ?? null, p99: latency['p(99)'] ?? null,
      check_failures: scopedChecks.fails ?? null,
      http_failure_rate: failures.value ?? failures.rate ?? null,
      drops: drops.count ?? null});
  }
  if (!metrics || !Number.isFinite(checks.passes) || !Number.isFinite(checks.fails)) missing.push('usable k6 checks');
  for (const row of rows) {
    if (!Number.isFinite(row.count) || (row.count > 0 && !Number.isFinite(row.p95))) missing.push(`usable metrics for offered rate ${row.offered}`);
  }
  return {index, outcome: missing.length ? 'INCOMPLETE' : nativeOutcome, missing, rows,
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
  const lines = ['| Trial | Outcome | Offered/s | k6 completed/s | Avg ms | p50 ms | p95 ms | p99 ms | Failed checks | HTTP failures | Drops |',
    '|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|'];
  for (const result of summary.trials) {
    for (const row of result.rows) {
      lines.push(`| ${row.trial} | ${result.outcome} | ${row.offered} | ${number(row.rate)} | ${number(row.average)} | ${number(row.median)} | ${number(row.p95)} | ${number(row.p99)} | ${row.check_failures ?? '—'} | ${Number.isFinite(row.http_failure_rate) ? number(row.http_failure_rate * 100) + '%' : '—'} | ${row.drops ?? '—'} |`);
    }
  }
  return lines.join('\n');
}

function workloadMarkdown(summary, directory) {
  const fixture = workloads[summary.workload];
  const lines = [`## ${summary.workload} (${summary.mode})`, '',
    `Evidence: **${summary.complete ? 'COMPLETE' : 'INCOMPLETE / FAILED'}**; measurement step: ${summary.execution}.`, '', disclaimer, '',
    `Latency: **${fixture.latency}**. Completed rate uses native k6 elapsed-time rates; overload rates use each 15-second plateau's completed count.`, '',
    table(summary), '', `Artifact: ${summary.artifact ? `[native outputs](${summary.artifact})` : '**UPLOAD MISSING**'}; size: ${number(summary.artifact_bytes === null ? null : summary.artifact_bytes / 1024 / 1024)} MiB; retention: ${process.env.RETENTION_DAYS || '?'} days.`, '',
    `Revision: \`${summary.configuration?.revision || 'unknown'}\`; image: \`${summary.configuration?.image || 'unknown'}\`.`, '',
    `Warmup: ${summary.configuration?.warmup_seconds ?? 'unknown'} seconds per fresh JVM.`, '',
    'Server peak RSS covers its whole lifetime, including startup/warmup. Client peak RSS covers the measured invocation.'];
  for (const result of summary.trials) {
    lines.push('', `### Trial ${result.index}: ${result.outcome}`, '',
      `Peak RSS: server ${number(result.server_peak_rss_mib)} MiB; client ${number(result.client_peak_rss_mib)} MiB.`, '',
      `Native time CPU: whole JVM lifetime ${result.server_lifetime_cpu_percent ?? '—'}%; measured client ${result.client_measured_cpu_percent ?? '—'}%.`);
    if (result.missing.length) lines.push('', `Missing evidence: ${result.missing.join(', ')}.`);
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
    '| Workload | Latency measure | Trials 1 / 2 / 3 | p95 ms (per trial) | Native outputs | Size MiB |',
    '|---|---|---|---|---|---:|'];
  let complete = true;
  for (const name of expected) {
    const summary = summaries.get(name);
    if (!summary) {
      complete = false;
      lines.push(`| ${name} | ${workloads[name].latency} | **MISSING** | — | — | — |`);
      continue;
    }
    if (!summary.complete) complete = false;
    const outcomes = summary.trials.map(result => result.outcome).join(' / ');
    const percentiles = mode === 'steady' ? summary.trials.map(result => number(result.rows[0]?.p95)).join(' / ') : 'See plateau rows in job summary';
    lines.push(`| ${name} | ${workloads[name].latency} | ${outcomes} | ${percentiles} | ${summary.artifact ? `[download](${summary.artifact})` : '**UPLOAD MISSING**'} | ${number(summary.artifact_bytes === null ? null : summary.artifact_bytes / 1024 / 1024)} |`);
  }
  lines.push('', `Campaign evidence: **${complete ? 'COMPLETE' : 'INCOMPLETE / FAILED'}**.`, '',
    'Each workload uses its own runner; repeats share that runner. See individual job summaries for throughput, errors, drops, resource measurements, and JFR excerpts. Complete artifacts expire after the selected retention period.');
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
