import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { main, selectProjects, stableVersion } from './publication.mjs';

const library = (name, version = '2026.11.5.0', dependencies = {}) =>
  ({ name, group: 'io.github.suppierk', artifact: `shoostr-${name}`, version, dependencies });
const http = library('http');
const core = library('core', http.version, { http: http.version });
const mcp = library('mcp', '2026.11.5.1', { core: core.version });

test('calendar versions reject malformed dates and counters', () => {
  assert.equal(stableVersion('2028.02.29.0'), '2028.02.29.0');
  for (const version of ['2026.02.29.0', '2026.11.05.0', '2026.11.5.01', '1.0.0', '2026.11.5.0\n']) {
    assert.throws(() => stableVersion(version));
  }
});

test('selects only stable unpublished libraries and preserves dependency order', async () => {
  assert.deepEqual(await selectProjects([http, core, mcp, library('test', '1-SNAPSHOT')],
    async project => project.name === 'http'), [core, mcp]);
  await assert.rejects(selectProjects([mcp, core, http], async () => false), /dependencies first/);
  await assert.rejects(selectProjects([http], async () => true), /No unpublished/);
});

test('an independent release requires its exact old dependency binary', async () => {
  const oldCore = '2026.11.3.0';
  const independent = library('mcp', mcp.version, { core: oldCore });
  const queried = [];
  const exists = async project => { queried.push(project); return project.name === 'core' && project.version === oldCore; };
  assert.deepEqual(await selectProjects([library('core', '1-SNAPSHOT'), independent], exists), [independent]);
  assert(queried.some(project => project.name === 'core' && project.version === oldCore));
  await assert.rejects(selectProjects([http, core, independent], async () => false), /Unpublished dependency/);
  await assert.rejects(selectProjects([library('mcp', mcp.version, { missing: oldCore })], exists), /Unknown dependency/);
});

test('Central lookup errors fail instead of selecting an artifact for upload', async t => {
  const fetch = t.mock.method(globalThis, 'fetch', async () => new Response(null, { status: 503 }));
  await assert.rejects(selectProjects([http]), /HTTP 503/);
  fetch.mock.mockImplementation(async () => new Response(null, { status: 404 }));
  assert.deepEqual(await selectProjects([http]), [http]);
});

test('stages binaries in order and delegates the selected batch to one plugin invocation', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'shoostr-publication-'));
  const previous = process.cwd();
  t.after(() => { process.chdir(previous); rmSync(directory, { recursive: true, force: true }); });
  process.chdir(directory);
  const calls = [];
  const execute = args => {
    calls.push(args);
    if (args[0] === 'publicationProjects') {
      mkdirSync('build/publication', { recursive: true });
      writeFileSync('build/publication/projects.json', JSON.stringify([http, core, mcp]));
    }
  };
  await main('stage', execute, async () => false);
  assert.deepEqual(JSON.parse(readFileSync('build/publication/selected.json')), [http, core, mcp]);
  assert.deepEqual(calls.slice(1).map(args => args.at(-1)),
    [http, core, mcp].map(project => `:${project.name}:publishMavenJavaPublicationToReleaseVerificationRepository`));
  await main('publish', execute);
  assert.deepEqual(calls.at(-1), ['-PpublicationBinaries=true', '-PpublicationModules=http,core,mcp',
    '-PcentralRelease=true', ':http:publishAndReleaseToMavenCentral', ':core:publishAndReleaseToMavenCentral', ':mcp:publishAndReleaseToMavenCentral']);
  await assert.rejects(main('unknown', execute), /Expected stage or publish/);
});
