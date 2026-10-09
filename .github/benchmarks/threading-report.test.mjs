import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { threadingSummary } from './threading-report.mjs';

function fixture(t, workload, wrapped = false) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'shoostr-threading-report-'));
  t.after(() => fs.rmSync(directory, {recursive: true, force: true}));
  for (const window of ['timing', 'profile']) {
    for (const repeat of window === 'timing' ? [1, 2, 3] : [1]) {
      for (const model of ['A', 'B', 'C', 'D']) {
        const run = path.join(directory, window, `repeat-${repeat}`, model);
        const trial = path.join(run, 'trial-1');
        fs.mkdirSync(trial, {recursive: true});
        fs.writeFileSync(path.join(run, 'configuration.json'), JSON.stringify({model, profile: window === 'profile'}));
        for (const file of ['server-resource.txt', 'measured-client-resource.txt',
          'process-monitor.txt', 'VM.version.txt', 'VM.command_line.txt', 'VM.flags.txt', 'VM.flags-all.txt',
          'VM.info.txt', 'server-affinity.txt', 'server-limits.txt',
          ...(window === 'profile' ? ['server.jfr', 'jfr-summary.txt', 'allocation-by-site.txt',
            'hot-methods.txt', 'gc-pauses.txt', 'measured-metrics.json.gz'] : [])]) {
          fs.writeFileSync(path.join(trial, file), 'native test fixture');
        }
        fs.writeFileSync(path.join(trial, 'outcome.txt'), 'PASS');
        fs.writeFileSync(path.join(trial, 'server.log'), 'SCHEDULER timestamp=1000 parallelism=2 carriers=2 mounted=1 queued=0 transport=8\n');
        if (window === 'profile') {
          fs.writeFileSync(path.join(trial, 'cpu-time-hot-methods.txt'), 'native CPU time samples');
          fs.writeFileSync(path.join(trial, 'cpu-time-statistics.txt'), 'native CPU time statistics');
        }
        const metric = values => wrapped ? {values} : values;
        const metrics = {
          checks: metric({passes: 20, fails: 0}), dropped_iterations: metric({count: 90}),
        };
        for (const endpoint of workload === 'mixed' ? ['tiny', 'cpu'] : [workload]) {
          for (const step of workload === 'mixed' ? [1, 2, 3] : [null]) {
            const suffix = `{name:${endpoint}${step === null ? '' : `,step:${step}`}}`;
            metrics[`http_req_duration${suffix}`] = metric({med: 1, 'p(95)': 2, 'p(99)': repeat + 3, max: 500});
            metrics[`http_req_blocked${suffix}`] = metric({med: 0, 'p(95)': 0.1, 'p(99)': 0.2, max: 1});
            metrics[`http_req_connecting${suffix}`] = metric({med: 0, 'p(95)': 0.01, 'p(99)': 0.02, max: 0.1});
            metrics[`http_reqs${suffix}`] = metric({count: step === null ? 180000 : 60000, rate: 1000});
            metrics[`http_req_failed${suffix}`] = metric({value: 0});
          }
        }
        fs.writeFileSync(path.join(trial, 'measured-summary.json'), JSON.stringify({metrics}));
      }
    }
  }
  return directory;
}

test('unprofiled trials are complete without invented JFR evidence and retain all repetitions', t => {
  const result = threadingSummary(fixture(t, 'tiny'), 'tiny');
  assert.equal(result.complete, true);
  assert.match(result.markdown, /\| timing \| A \| 1 \| tiny \| all \| 1000\.000 \| 1\.000 \| 2\.000 \| 4\.000 \| 500\.000/);
  assert.match(result.markdown, /\| timing \| A \| 3 \| tiny \| all \| 1000\.000 \| 1\.000 \| 2\.000 \| 6\.000/);
  assert.match(result.markdown, /90 \/ 900000 scheduled iterations \(0\.010%\)/);
});

test('mixed endpoints and steps retain independent percentiles with wrapped native values', t => {
  const result = threadingSummary(fixture(t, 'mixed', true), 'mixed');
  assert.equal(result.complete, true);
  assert.match(result.markdown, /\| timing \| D \| 2 \| tiny \| 1 \| 1000\.000/);
  assert.match(result.markdown, /\| timing \| D \| 2 \| cpu \| 3 \| 1000\.000/);
  assert.match(result.markdown, /90 \/ 281600 scheduled iterations/);
});

test('missing timing trial and missing profile recording cannot be called complete', t => {
  const directory = fixture(t, 'tiny');
  fs.unlinkSync(path.join(directory, 'timing/repeat-3/D/trial-1/measured-summary.json'));
  fs.unlinkSync(path.join(directory, 'profile/repeat-1/A/trial-1/server.jfr'));
  assert.equal(threadingSummary(directory, 'tiny').complete, false);
});

for (const field of ['med', 'p(95)', 'max']) {
  test(`missing ${field} prevents complete latency evidence`, t => {
    const directory = fixture(t, 'tiny');
    const file = path.join(directory, 'timing/repeat-1/A/trial-1/measured-summary.json');
    const native = JSON.parse(fs.readFileSync(file, 'utf8'));
    delete native.metrics['http_req_duration{name:tiny}'][field];
    fs.writeFileSync(file, JSON.stringify(native));
    assert.equal(threadingSummary(directory, 'tiny').complete, false);
  });
}

test('missing failures and drop counts cannot be silently reported complete or zero', t => {
  const directory = fixture(t, 'tiny');
  const file = path.join(directory, 'timing/repeat-1/A/trial-1/measured-summary.json');
  const native = JSON.parse(fs.readFileSync(file, 'utf8'));
  delete native.metrics['http_req_failed{name:tiny}'];
  delete native.metrics.dropped_iterations;
  fs.writeFileSync(file, JSON.stringify(native));
  const result = threadingSummary(directory, 'tiny');
  assert.equal(result.complete, false);
  assert.match(result.markdown, /Drops: unknown/);
});

test('missing scheduler samples and CPU-time views make profile evidence incomplete', t => {
  const directory = fixture(t, 'tiny');
  const profile = path.join(directory, 'profile/repeat-1/A/trial-1');
  fs.writeFileSync(path.join(profile, 'server.log'), 'No sampler data');
  assert.equal(threadingSummary(directory, 'tiny').complete, false);
  fs.writeFileSync(path.join(profile, 'server.log'), 'SCHEDULER timestamp=1000 parallelism=2');
  assert.equal(threadingSummary(directory, 'tiny').complete, true);
  fs.unlinkSync(path.join(profile, 'cpu-time-hot-methods.txt'));
  assert.equal(threadingSummary(directory, 'tiny').complete, false);
});

test('mixed blocked and connecting phase p99 remain separately visible', t => {
  const result = threadingSummary(fixture(t, 'mixed'), 'mixed');
  assert.match(result.markdown, /Blocked p99 ms \| Connect p99 ms/);
  assert.match(result.markdown, /\| timing \| D \| 2 \| tiny \| 1 \| 1000\.000 \| 1\.000 \| 2\.000 \| 5\.000 \| 500\.000 \| 0\.200 \| 0\.020 \|/);
});

test('large diagnostics are bounded below the Actions one-MiB summary limit', t => {
  const directory = fixture(t, 'tiny');
  for (const window of ['timing', 'profile']) {
    for (const repeat of window === 'timing' ? [1, 2, 3] : [1]) {
      for (const model of ['A', 'B', 'C', 'D']) {
        fs.writeFileSync(path.join(directory, window, `repeat-${repeat}`, model, 'trial-1/VM.info.txt'), 'x'.repeat(100000));
      }
    }
  }
  const markdown = threadingSummary(directory, 'tiny').markdown;
  assert.ok(Buffer.byteLength(markdown) < 1024 * 1024);
  assert.match(markdown, /Full native output retained/);
});
