import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const resources = fileURLToPath(new URL('./resources.sh', import.meta.url));

function shell(script, env = {}) {
  return execFileSync('bash', ['-euo', 'pipefail', '-c', `source "$1"; ${script}`, 'bash', resources], {
    encoding: 'utf8', env: {...process.env, ...env}
  });
}

test('CPU selection honors permitted ranges and gives all remaining CPUs to k6', () => {
  assert.equal(shell('select_cpus 2-3,7,9-10 2; printf "%s;%s" "$server_cpus" "$client_cpus"'), '2,3;7,9,10');
});

test('one CPU is assigned to the JVM by default with a fixed 1 GiB total memory cap', () => {
  assert.equal(shell('configure_resources; printf "%s;%s;%s" "$jvm_cpus" "$jvm_memory_mib" "$jvm_memory_bytes"', {
    JVM_CPUS: '', JVM_MEMORY_MIB: ''
  }), '1;1024;1073741824');
});

test('CPU selection refuses to consume every permitted CPU', () => {
  assert.throws(() => shell('select_cpus 2-3 2'), /leave at least one permitted CPU/);
});

for (const cpus of ['0', '-1', '1.5', '01', '10000', 'two']) {
  test(`invalid CPU count ${cpus} is rejected`, () => {
    assert.throws(() => shell('select_cpus 0-3 "$JVM_CPUS"', {JVM_CPUS: cpus}), /positive integer/);
  });
}

for (const memory of ['0', '-1', '256', '256.5', '01', '10000000', 'one']) {
  test(`invalid memory budget ${memory} is rejected`, () => {
    assert.throws(() => shell('configure_resources', {JVM_MEMORY_MIB: memory}), /integer greater than/);
  });
}

test('memory budgets larger than host RAM are rejected instead of silently clamped', () => {
  const mib = Math.floor(Number(fs.readFileSync('/proc/meminfo', 'utf8').match(/^MemTotal:\s+(\d+)/m)[1]) / 1024);
  assert.throws(() => shell('configure_resources', {JVM_MEMORY_MIB: String(mib + 1)}), /exceeds host RAM/);
});

test('overload options schedule exactly the four approved plateaus even when thresholds breach', () => {
  const source = fs.readFileSync(new URL('./overload.js', import.meta.url), 'utf8');
  const code = source.slice(source.indexOf('function smokeOptions()'));
  const options = new Function('__ENV', 'baseline', `${code}; return smokeOptions();`)({}, {});
  assert.deepEqual(Object.values(options.scenarios).map(scenario => scenario.rate), [1000, 5000, 10000, 50000]);
  assert.deepEqual(Object.values(options.scenarios).map(scenario => scenario.startTime), ['0s', '20s', '40s', '60s']);
  assert.ok(Object.values(options.scenarios).every(scenario => scenario.duration === '15s' && scenario.gracefulStop === '5s'));
  assert.ok(Object.values(options.thresholds).flat().every(threshold => typeof threshold === 'string'));
});

for (const mode of ['normal', 'oom']) {
  test(`native cgroup ${mode} run enforces limits outside the time wrapper and cleans up`, {
    skip: process.env.BENCHMARK_TEST_CGROUP !== '1'
  }, t => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'shoostr-cgroup-test-'));
    t.after(() => fs.rmSync(directory, {recursive: true, force: true}));
    const probe = path.join(directory, 'ResourceProbe.java');
    fs.writeFileSync(probe, `package io.github.suppierk.shoostr.bench;
      class ResourceProbe {
        public static void main(String[] args) throws InterruptedException {
          System.out.println("READY");
          Thread.sleep(30000);
        }
      }
    `);
    const command = mode === 'normal'
      ? [path.join(process.env.JAVA_HOME, 'bin/java'), '-Xms256m', '-Xmx256m', '-XX:+UseG1GC', probe]
      : [process.execPath, '-e', `
          console.log("READY");
          setTimeout(() => { globalThis.bytes = Buffer.alloc(128 * 1024 * 1024, 1); }, 1500);
          setInterval(() => {}, 1000);
        `];
    const result = spawnSync('bash', ['-euo', 'pipefail', '-c', `
      source "$1"
      configure_resources
      server_cgroup="/sys/fs/cgroup/shoostr-resource-test-$$"
      server_pid=''
      wrapper=''
      trap 'if [[ -n "$server_pid" ]]; then kill "$server_pid" 2>/dev/null || true; fi;
        if [[ -n "$wrapper" ]]; then wait "$wrapper" 2>/dev/null || true; fi;
        if [[ -d "$server_cgroup" ]]; then sudo -n rmdir "$server_cgroup"; fi' EXIT
      process_name=java
      if [[ "$BENCHMARK_PROBE_MODE" == oom ]]; then
        jvm_memory_bytes=$((64 * 1024 * 1024))
        process_name=node
      fi
      create_server_cgroup
      /usr/bin/time -v -o "$2/resource.txt" bash "$1" "$server_cgroup" \
        taskset -c "$server_cpus" "\${@:3}" > "$2/child.log" 2>&1 &
      wrapper=$!
      for ((attempt=0; attempt<500; attempt++)); do
        server_pid=$(pgrep -P "$wrapper" -x "$process_name" || true)
        if [[ -n "$server_pid" ]] && grep -q READY "$2/child.log"; then break; fi
        sleep 0.01
      done
      verify_server_cgroup "$server_pid"
      taskset -pc "$server_pid"
      capture_server_cgroup > "$2/before.txt"
      if [[ "$BENCHMARK_PROBE_MODE" == normal ]]; then
        "$JAVA_HOME/bin/jcmd" "$server_pid" VM.flags
        kill "$server_pid"
      fi
      status=0
      wait "$wrapper" || status=$?
      wrapper=''
      server_pid=''
      capture_server_cgroup > "$2/after.txt"
      cat "$2/after.txt"
      printf 'EXIT=%s\\n' "$status"
    `, 'bash', resources, directory, ...command], {
      encoding: 'utf8', timeout: 20000,
      env: {...process.env, JVM_CPUS: '1', JVM_MEMORY_MIB: '1024', BENCHMARK_PROBE_MODE: mode}
    });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.match(result.stdout, new RegExp(`memory.max\\n${mode === 'normal' ? 1073741824 : 67108864}\\nmemory.swap.max\\n0`));
    assert.match(result.stdout, /affinity list: \d+$/m);
    assert.match(result.stdout, new RegExp(`oom_kill ${mode === 'oom' ? 1 : 0}`));
    if (mode === 'normal') {
      assert.match(result.stdout, /-XX:MaxHeapSize=268435456/);
      assert.match(result.stdout, /-XX:\+UseG1GC/);
    }
    if (mode === 'oom') assert.match(result.stdout, /EXIT=137/);
    assert.match(fs.readFileSync(path.join(directory, 'resource.txt'), 'utf8'), /Maximum resident set size/);
  });
}
