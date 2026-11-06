import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { mkdtempSync, readFileSync, writeFileSync, rmSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const support = fileURLToPath(new URL('.', import.meta.url));
const binary = process.env.ZRK_BINARY;

function temporary(t) {
  const directory = mkdtempSync(join(tmpdir(), 'shoostr-zrk-'));
  t.after(() => rmSync(directory, {recursive: true, force: true}));
  return directory;
}

function check(summary, expectedClass = '2xx') {
  return spawnSync('jq', ['-e', '--argjson', 'seconds', '1', '--argjson', 'rate', '20',
    '--argjson', 'connections', '2',
    '--argjson', 'end_rate', '20', '--arg', 'status_class', expectedClass,
    '-f', join(support, 'check-zrk.jq')], {input: JSON.stringify(summary), encoding: 'utf8'});
}

function summary() {
  return {zrk_version: '3.0.0', requests: 20, duration_s: 1.01,
    config: {closed: false, duration_s: 1, target_rate: 20, target_rate_end: 20,
      connections: 2, launched: 2, streams: 1, disable_keepalive: false,
      deadline_ms: 0, record_timeouts: true},
    workload: {response: {calls: 20}}, latency_us: {p99: 100}, achieved_rate: 19.8,
    max_schedule_lag_us: 0, latency_histogram: 'native histogram',
    status_codes: {'1xx': 0, '2xx': 20, '3xx': 0, '4xx': 0, '5xx': 0},
    errors: {connect: 0, read: 0, write: 0, timeout: 0, deadline: 0, non_2xx_3xx: 0}};
}

test('native checker accepts expected HTTP errors without hiding transport failures', () => {
  const report = summary();
  assert.equal(check(report).status, 0);
  report.status_codes['2xx'] = 0;
  report.status_codes['4xx'] = report.errors.non_2xx_3xx = 20;
  assert.equal(check(report, '4xx').status, 0);
  report.errors.timeout = 1;
  assert.equal(check(report, '4xx').status, 1);
});

for (const missing of ['requests', 'latency_us', 'workload', 'latency_histogram', 'config', 'errors']) {
  test(`native checker rejects missing ${missing}`, () => {
    const report = summary();
    delete report[missing];
    assert.notEqual(check(report).status, 0);
  });
}

test('installer rejects an archive whose checksum does not match', t => {
  const directory = temporary(t);
  const bin = join(directory, 'bin');
  mkdirSync(bin);
  writeFileSync(join(bin, 'curl'), '#!/usr/bin/env bash\nprintf corrupt > "${@: -1}"\n', {mode: 0o755});
  const result = spawnSync('bash', [join(support, 'install-zrk.sh'), join(directory, 'tools')], {
    env: {...process.env, PATH: `${bin}:${process.env.PATH}`}, encoding: 'utf8'
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stdout + result.stderr, /FAILED/);
});

test('finite SSE overload is rejected before creating results or starting a JVM', t => {
  const directory = temporary(t);
  const destination = join(directory, 'results');
  const result = spawnSync('bash', [join(support, 'run.sh'), 'sse-burst', 'overload', destination, '/unused'], {
    env: {...process.env, JAVA_HOME: '/unused', JVM_CPUS: '1', JVM_MEMORY_MIB: '1024'}, encoding: 'utf8'
  });
  assert.equal(result.status, 2, result.stderr);
  assert.match(result.stderr, /does not support overload/);
  assert.throws(() => readFileSync(join(destination, 'configuration.json')), /ENOENT/);
});

async function run(t, server, workload, extra = []) {
  const directory = temporary(t);
  const output = join(directory, 'summary.json');
  const child = spawn(binary, ['-t1', '-c2', '-d1s', '-R20', '--plain', '--timeout', '1s',
    '--script', join(support, 'http.lua'), '--format', 'json', '-o', output,
    '--hdr', join(directory, 'latency.hgrm'), '--timeseries', join(directory, 'intervals.ndjson'),
    '--timeseries-histogram', ...extra, `http://127.0.0.1:${server.address().port}`, workload, '3']);
  let log = '';
  child.stdout.on('data', data => { log += data; });
  child.stderr.on('data', data => { log += data; });
  const [status] = await once(child, 'exit');
  return {status, log, directory, output};
}

test('Lua validates real responses and varies route groups', {skip: !binary}, async t => {
  const paths = new Set();
  let broken = '';
  const server = createServer((request, response) => {
    paths.add(request.url);
    const negative = request.method === 'DELETE';
    response.statusCode = broken === 'status' ? 503 : negative ? 405 : 200;
    response.setHeader('Content-Type', broken === 'type' ? 'text/html' : 'text/plain; charset=utf-8');
    if (negative) response.setHeader('Allow', broken === 'allow' ? 'GET' : 'POST, GET');
    response.end(broken === 'body' ? 'wrong' : negative ? 'Method not allowed' : 'Hello, World!');
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  const good = await run(t, server, 'route-literal');
  assert.equal(good.status, 0, good.log);
  assert.equal(check(JSON.parse(readFileSync(good.output))).status, 0);
  for (let group = 0; group < 3; group++) assert.ok(paths.has(`/api/resources/${group}/orders/latest`));
  assert.ok(readFileSync(join(good.directory, 'latency.hgrm'), 'utf8').length > 0);
  const intervals = readFileSync(join(good.directory, 'intervals.ndjson'), 'utf8').trim().split('\n').map(JSON.parse);
  assert.ok(intervals.every(row => row.latency_histogram));
  const negative = await run(t, server, 'wrong-method');
  assert.equal(negative.status, 0, negative.log);
  assert.equal(check(JSON.parse(readFileSync(negative.output)), '4xx').status, 0);
  for (broken of ['status', 'body', 'type', 'allow']) {
    const bad = await run(t, server, broken === 'allow' ? 'wrong-method' : 'plaintext');
    assert.notEqual(bad.status, 0, `${broken}: ${bad.log}`);
    assert.match(bad.log, /unexpected/);
  }
});

test('native ramp retains its rate endpoints and rejects interrupted collection', {skip: !binary}, async t => {
  const server = createServer((request, response) => {
    response.setHeader('Content-Type', 'text/plain;charset=utf-8');
    response.end('Hello, World!');
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  const ramp = await run(t, server, 'plaintext', ['-R1000:25000']);
  assert.equal(ramp.status, 0, ramp.log);
  const report = JSON.parse(readFileSync(ramp.output));
  assert.equal(report.config.target_rate, 1000);
  assert.equal(report.config.target_rate_end, 25000);
  assert.ok(report.end_window_s > 0);
  assert.ok(report.workload.response.calls > 0);

  const directory = temporary(t);
  const child = spawn(binary, ['-t1', '-c2', '-d60s', '-R20', '--plain', '--format', 'json',
    '-o', join(directory, 'partial.json'), '--script', join(support, 'http.lua'),
    `http://127.0.0.1:${server.address().port}`, 'plaintext']);
  child.stdout.resume();
  child.stderr.resume();
  await once(server, 'request');
  const done = once(child, 'exit');
  child.kill('SIGTERM');
  const [status] = await done;
  assert.equal(status, 143);
  const partial = JSON.parse(readFileSync(join(directory, 'partial.json')));
  assert.ok(partial.duration_s < 60);
  assert.notEqual(check(partial).status, 0);
});

test('finite SSE requires every ordered event and complete framing', {skip: !binary}, async t => {
  const events = Array.from({length: 16}, (_, index) => `data: ${index}:${'x'.repeat(128)}\n\n`);
  let body = events.join('');
  const server = createServer((request, response) => {
    response.setHeader('Content-Type', 'text/event-stream; charset=utf-8');
    response.write(body);
    response.end();
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  for (const workload of ['sse-burst', 'sse-paced']) {
    const result = await run(t, server, workload);
    assert.equal(result.status, 0, result.log);
  }
  for (const invalid of [events.slice(1).join(''), [...events].reverse().join(''),
    [...events, events[0]].join(''), events.join('').replace('data: 0:', 'data: changed:'),
    events.join('').slice(0, -1)]) {
    body = invalid;
    const result = await run(t, server, 'sse-burst');
    assert.notEqual(result.status, 0, result.log);
    assert.match(result.log, /unexpected body/);
  }
});
