import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import test from 'node:test';

// These are public launcher behaviors, independent of the Gradle inventory below.
const probes = {
  'hello-example': ['/hello', 'Hello, Shoostr!'],
  'application-example': ['/echo', 'hello', { method: 'POST', body: 'hello' }],
  'routing-example': ['/notes', 'notes'],
  'request-example': ['/search/books?tag=java&tag=http', 'category=books\npath=books\nfirst=java\ntags=java|http\nsort=recent'],
  'bodies-example': ['/text', 'hello', { method: 'POST', body: 'hello' }],
  'responses-example': ['/message', 'café'],
  'json-example': ['/users', '{"name":"Ada"}', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"name":"Ada"}' }, 201],
  'errors-example': ['/input', 'Invalid input', {}, 400],
  'resources-example': ['/download', 'name\nAda\nGrace\n'],
  'lifecycle-example': ['/ready', 'ready'],
  'extensions-example': ['/health', 'ok'],
  'requests-example': ['/greetings/Ada', 'Hello, Ada!'],
  'sse-example': ['/events', /data: starting\r?\n\r?\ndata: complete/],
  'websocket-example': ['/', 'WebSocket endpoints: /echo, /rooms/{room}, /numbers'],
  'exceptions-example': ['/app-failure', 'Application failure', {}, 500],
  'json-errors-example': ['/users', '{"name":"Ada"}', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"name":"Ada"}' }, 201],
  'authentication-example': ['/public', 'public'],
  'static-streaming-example': ['/download', 'name\nAda\nGrace\n'],
  'openapi-example': ['/health', 'healthy'],
  'mcp-example': ['/health', 'healthy'],
  'micrometer-example': ['/hello/Ada', 'Hello, Ada!'],
  'opentelemetry-example': ['/hello/Ada', 'Hello, Ada!'],
};
const examples = [...readFileSync('settings.gradle', 'utf8').matchAll(
  /project\(':([^']+-example)'\)\.projectDir = file\('([^']+)'\)/g,
)].map(([, project, directory]) => ({ project, directory }));

function inventory() {
  assert.deepEqual(examples.map(({ project }) => project).sort(), Object.keys(probes).sort());
  assert.equal(new Set(examples.map(({ directory }) => directory)).size, examples.length);
  for (const { project, directory } of examples) {
    assert.ok(existsSync(`${directory}/Dockerfile`), project);
    assert.ok(existsSync(`${directory}/README.md`), project);
    assert.match(readFileSync(`${directory}/compose.yaml`, 'utf8'), /target: runtime/);
    const build = readFileSync(`${directory}/build.gradle`, 'utf8');
    assert.match(build, /example-container.gradle/);
    assert.match(build, /mainClass\s*=/);
  }
}

if (process.argv.includes('--matrix')) {
  inventory();
  console.log(JSON.stringify({ include: examples }));
} else {
  test('every registered example has a standalone container and public behavior probe', inventory);
  if (!Object.hasOwn(process.env, 'EXAMPLE')) {
    test('matrix output is JSON and empty or unknown live selections fail', () => {
      const matrix = spawnSync(process.execPath, [process.argv[1], '--matrix'], { encoding: 'utf8' });
      assert.equal(matrix.status, 0, matrix.stderr);
      assert.equal(JSON.parse(matrix.stdout).include.length, examples.length);
      for (const selection of ['', 'not-an-example']) {
        const environment = { ...process.env, EXAMPLE: selection };
        delete environment.NODE_TEST_CONTEXT;
        const rejected = spawnSync(process.execPath, ['--test', process.argv[1]], {
          encoding: 'utf8', env: environment, timeout: 5000,
        });
        assert.equal(rejected.status, 1, rejected.stderr);
        assert.match(rejected.stdout + rejected.stderr, /Unknown EXAMPLE/);
      }
    });
  }
  if (Object.hasOwn(process.env, 'EXAMPLE')) {
    const project = process.env.EXAMPLE;
    const base = process.env.BASE_URL ?? 'http://127.0.0.1:8080';
    test(`${project} serves its documented behavior from the selected image`, { timeout: 100_000 }, async () => {
      assert.ok(Object.hasOwn(probes, project), `Unknown EXAMPLE: ${project}`);
      const [path, fixture, options = {}, status = 200] = probes[project];
      const expected = (project === 'resources-example' ? process.env.REPORT_CONTENT : undefined) ?? fixture;
      await ready(base);
      const response = await fetch(base + path, { ...options, signal: AbortSignal.timeout(10_000) });
      assert.equal(response.status, status);
      const body = await response.text();
      if (expected instanceof RegExp) assert.match(body, expected);
      else assert.equal(body, expected);
      if (project === 'resources-example') {
        assert.match(response.headers.get('content-disposition'), /attachment/);
        assert.match(response.headers.get('content-disposition'), /r%C3%A9sum%C3%A9.csv/);
      }
      if (project === 'application-example' || project === 'bodies-example') {
        const limit = project === 'application-example' ? 1024 : 64;
        const oversized = await fetch(base + path, {
          method: 'POST', body: 'x'.repeat(limit + 1), signal: AbortSignal.timeout(5000),
        });
        assert.equal(oversized.status, 413);
        await oversized.arrayBuffer();
      }
      if (project === 'responses-example') {
        const head = await fetch(base + '/message', { method: 'HEAD', signal: AbortSignal.timeout(5000) });
        assert.equal(head.status, 200);
        assert.equal(head.headers.get('content-length'), '5');
        assert.equal((await head.arrayBuffer()).byteLength, 0);
      }
      if (project === 'extensions-example') {
        const catalog = await fetch(base + '/catalog', { signal: AbortSignal.timeout(5000) });
        assert.equal(catalog.status, 200);
        assert.match(await catalog.text(), /GET \/api\/items\/\{id\}/);
      }
      if (project === 'static-streaming-example') {
        const asset = await fetch(base + '/assets/greeting.txt', { signal: AbortSignal.timeout(5000) });
        assert.equal(asset.status, 200);
        assert.equal(await asset.text(), 'Hello from a classpath asset!\n');
      }
      if (project === 'authentication-example') {
        const denied = await fetch(base + '/private/me', { signal: AbortSignal.timeout(5000) });
        assert.equal(denied.status, 401);
        assert.match(denied.headers.get('www-authenticate'), /Basic/);
        await denied.arrayBuffer();
        const accepted = await fetch(base + '/private/me', {
          headers: { Authorization: 'Basic ' + Buffer.from('demo:local-only').toString('base64') },
          signal: AbortSignal.timeout(5000),
        });
        assert.equal(accepted.status, 200);
        assert.equal(await accepted.text(), 'demo');
      }
      if (project === 'openapi-example') {
        const spec = await fetch(base + '/openapi.json', {
          headers: { Authorization: 'Basic ' + Buffer.from('demo:local-only').toString('base64') },
          signal: AbortSignal.timeout(5000),
        });
        assert.equal(spec.status, 200);
        assert.match((await spec.json()).openapi, /^3\.1\./);
      }
      if (project === 'mcp-example') await ready('http://localhost:6274');
      if (project === 'websocket-example') {
        await new Promise((resolve, reject) => {
          const socket = new WebSocket(base.replace(/^http/, 'ws') + '/echo');
          const timeout = setTimeout(() => { socket.close(); reject(new Error('WebSocket echo timed out')); }, 5000);
          socket.onopen = () => socket.send('container echo');
          socket.onerror = () => { clearTimeout(timeout); socket.close(); reject(new Error('WebSocket failed')); };
          socket.onmessage = ({ data }) => {
            clearTimeout(timeout);
            socket.close(1000, 'Finished');
            try { assert.equal(data, 'container echo'); resolve(); } catch (error) { reject(error); }
          };
        });
      }
    });
  }
}

async function ready(base) {
  let last;
  for (let attempt = 0; attempt < 40; attempt++) {
    try {
      const response = await fetch(base + '/', { signal: AbortSignal.timeout(1000) });
      await response.arrayBuffer();
      return;
    } catch (error) { last = error; }
    await delay(500);
  }
  throw new Error('Container listener did not start', { cause: last });
}
