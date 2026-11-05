import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const support = fileURLToPath(new URL('.', import.meta.url));

function fixture(t) {
  const directory = mkdtempSync(join(tmpdir(), 'shoostr-benchmark-evidence-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return directory;
}

for (const [mode, kind] of [['steady', 'http'], ['overload', 'http'], ['steady', 'sse'], ['steady', 'ws']]) {
  for (const missing of [null, 'count', 'p(95)']) {
    test(`${mode}/${kind} metrics ${missing ? 'reject missing ' + missing : 'accept complete evidence'}`, t => {
      const directory = fixture(t);
      const configuration = { mode, smoke_rates: [1000, 5000, 10000, 50000],
        fixture: { kind, metric: kind === 'http' ? 'http_req_duration{scenario:requests}' : 'stream_duration' } };
      const scenarios = mode === 'overload' ? configuration.smoke_rates.map(rate => `step_${rate}`) : ['requests'];
      const metrics = { checks: { passes: 1, fails: 0 } };
      for (const scenario of scenarios) {
        metrics[`http_reqs{scenario:${scenario}}`] = { count: 1 };
        metrics[`http_req_duration{scenario:${scenario}}`] = { 'p(95)': 2 };
      }
      if (kind !== 'http') {
        metrics.iterations = { values: { count: 1 } };
        metrics.stream_duration = { 'p(95)': 2 };
      }
      if (missing) {
        const key = kind === 'http'
          ? `${missing === 'count' ? 'http_reqs' : 'http_req_duration'}{scenario:${scenarios.at(-1)}}`
          : missing === 'count' ? 'iterations' : 'stream_duration';
        if (key === 'iterations') metrics[key] = metrics[key].values;
        delete metrics[key][missing];
      }
      writeFileSync(join(directory, 'configuration.json'), JSON.stringify(configuration));
      writeFileSync(join(directory, 'summary.json'), JSON.stringify({ metrics }));
      const result = spawnSync('jq', ['-e', '--slurpfile', 'configuration', 'configuration.json',
        '-f', join(support, 'check-results.jq'), 'summary.json'], { cwd: directory, encoding: 'utf8' });
      assert.equal(result.status, missing ? 1 : 0, result.stderr + result.stdout);
    });
  }
}

for (const invalid of [null, 'limit', 'peak']) {
  test(`cleanup retains evidence and attempts removal with ${invalid ?? 'valid'} resource controls`, t => {
    const directory = fixture(t);
    const group = join(directory, 'cgroup');
    mkdirSync(group);
    for (const [file, value] of Object.entries({
      'memory.max': invalid === 'limit' ? '512' : '1073741824', 'memory.swap.max': '0',
      'memory.current': '0', 'memory.peak': invalid === 'peak' ? 'unavailable' : '1024', 'memory.events': 'oom_kill 0',
    })) writeFileSync(join(group, file), value);
    const source = readFileSync(join(support, 'run.sh'), 'utf8');
    const cleanup = source.slice(source.indexOf('cleanup() {'), source.indexOf('\ntrap cleanup EXIT'));
    const result = spawnSync('bash', ['-euo', 'pipefail', '-c', `
      source "$1/resources.sh"
      run_dir=$2; server_cgroup="$2/cgroup"; jvm_memory_bytes=1073741824
      recording=0; monitor_pid=''; client_group=''; client_pid=''; server_group=''; server_pid=''; failed=0
      stop_group() { :; }
      sudo() { [[ "$1" == -n && "$2" == rmdir && "$3" == "$run_dir/cgroup" ]]; touch "$run_dir/removed"; }
      ${cleanup}
      cleanup
      [[ "$failed" == "$3" ]]
      test -s "$run_dir/server-cgroup-after.txt"
      test -f "$run_dir/removed"
    `, 'bash', support, directory, invalid ? '1' : '0'], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr + result.stdout);
  });
}
