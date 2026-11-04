import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import process from 'node:process';
import {
  stableVersion, coordinate, artifactPath, selectProjects, requireNoPendingDeployments,
  requireSuccessfulCi, requireReleaseEvent, deploymentId,
  stage, publish, runPublisher, verifyConsumers, verifyPublications, verifySource, publicExists,
} from './publication.mjs';

const revision = 'a'.repeat(40);
const library = (name, version = '2026.11.4.0', dependencies = {}) => ({
  name, group: 'io.github.suppierk', artifact: `shoostr-${name}`, version, dependencies,
});
const exists = published => async project => published.includes(coordinate(project));

test('accepts canonical stable calendar versions including leap days', () => {
  for (const version of ['2026.11.4.0', '2028.02.29.1', '2026.01.1.123']) {
    assert.equal(stableVersion(version), version);
  }
});

test('rejects invalid dates, noncanonical fields and release qualifiers', () => {
  for (const version of ['2026.02.29.0', '2026.04.31.0', '2026.13.1.0', '2026.1.4.0',
    '2026.11.04.0', '2026.11.4.01', '2026.11.4.-1', '2026.11.4.0-RC1', '2026.11.4.0-SNAPSHOT',
    '2026.11.4', '2026.11.4.0\n', 'not-a-version']) assert.throws(() => stableVersion(version), version);
});

test('selects only stable unpublished versions and orders mixed versions by dependency', async () => {
  const http = library('http');
  const core = library('core', '2026.11.4.1', { http: http.version });
  const mcp = library('mcp', '2026.11.4.2', { core: core.version });
  const testSupport = library('test', '0.1.0-SNAPSHOT', { core: '0.1.0-SNAPSHOT' });
  const alreadyPublic = library('micrometer', '2026.11.3.0', { core: '2026.11.3.0' });
  assert.deepEqual(await selectProjects([mcp, alreadyPublic, testSupport, core, http],
    exists([coordinate(alreadyPublic)])), [http, core, mcp]);
});

test('an MCP-only release retains an old core pin despite newer workspace core', async () => {
  const mcp = library('mcp', '2026.11.4.0', { core: '2026.11.3.0' });
  const core = library('core', '2026.11.4.0-SNAPSHOT');
  const inspected = [];
  const selected = await selectProjects([mcp, core], async project => {
    inspected.push(coordinate(project));
    return project.name === 'core' && project.version === '2026.11.3.0';
  });
  assert.deepEqual(selected, [mcp]);
  assert(inspected.includes('io.github.suppierk:shoostr-core:2026.11.3.0'));
});

test('rejects missing, snapshot and unknown internal dependencies', async () => {
  await assert.rejects(selectProjects([library('mcp', '2026.11.4.0', { core: '2026.11.3.0' }),
    library('core', '0.1.0-SNAPSHOT')], exists([])), /requires unpublished/);
  await assert.rejects(selectProjects([library('core', '2026.11.4.0', { http: '0.1.0-SNAPSHOT' }),
    library('http')], exists([])), /Expected YYYY/);
  await assert.rejects(selectProjects([library('mcp', '2026.11.4.0', { unknown: '2026.11.3.0' })],
    exists([])), /Unknown internal dependency/);
});

test('rejects cycles and propagates repository errors instead of treating them as absence', async () => {
  await assert.rejects(selectProjects([library('core', '2026.11.4.0', { http: '2026.11.4.0' }),
    library('http', '2026.11.4.0', { core: '2026.11.4.0' })], exists([])), /Cyclic/);
  await assert.rejects(selectProjects([library('http')], async () => { throw new Error('HTTP 503'); }), /503/);
});

test('rejects unexpected coordinates and names before creating tasks or artifact URLs', () => {
  assert.equal(artifactPath(library('core')), 'io/github/suppierk/shoostr-core/2026.11.4.0/shoostr-core-2026.11.4.0.pom');
  for (const project of [library('../core'), { ...library('core'), group: 'other' },
    { ...library('core'), artifact: 'other' }]) assert.throws(() => coordinate(project));
});

test('checks every deployment page and permits only terminal states', async () => {
  const requested = [];
  await requireNoPendingDeployments(async path => {
    requested.push(path);
    return { deployments: [{ deploymentState: requested.length === 1 ? 'PUBLISHED' : 'FAILED' }], pageCount: 2 };
  });
  assert.equal(requested.length, 2);
  assert.match(requested[1], /page=1/);
  await requireNoPendingDeployments(async () => ({ deployments: [], pageCount: 0 }));
});

test('blocks active or unknown deployments on later pages and reports the existing ID', async () => {
  for (const state of ['PENDING', 'VALIDATING', 'VALIDATED', 'PUBLISHING', 'NEW_STATE', undefined]) {
    await assert.rejects(requireNoPendingDeployments(async path => ({
      deployments: path.includes('page=1') ? [{ deploymentState: state, deploymentId: 'existing-id' }] : [],
      pageCount: 2,
    })), /Reconcile Central deployment existing-id/);
  }
});

test('deployment preflight fails closed on malformed responses and API errors', async () => {
  for (const response of [{}, { content: [], pageCount: 1 }, { deployments: [], pageCount: '2' }]) {
    await assert.rejects(requireNoPendingDeployments(async () => response), /Invalid Central/);
  }
  await assert.rejects(requireNoPendingDeployments(async () => { throw new Error('HTTP 403'); }), /403/);
});

test('blocks a matching published deployment while public repository propagation is pending', async () => {
  const request = async () => ({ deployments: [{ deploymentId: 'published-id', deploymentState: 'PUBLISHED',
    deploymentComponents: [{ purl: 'pkg:maven/io.github.suppierk/shoostr-core@2026.11.4.0' }] }], pageCount: 1 });
  await assert.rejects(requireNoPendingDeployments(request, [library('core')]), /wait for public availability/);
  await requireNoPendingDeployments(request, [library('mcp')]);
});

const ciRun = {
  head_sha: revision, head_branch: 'main', event: 'push', status: 'completed', conclusion: 'success',
  path: '.github/workflows/ci.yml', repository: { full_name: 'SuppieRK/Shoostr' },
  head_repository: { full_name: 'SuppieRK/Shoostr' },
};

test('requires canonical main CI for the exact release revision', () => {
  requireSuccessfulCi([ciRun], revision);
  for (const change of [{ head_sha: 'b'.repeat(40) }, { head_branch: 'topic' }, { event: 'pull_request' },
    { status: 'in_progress' }, { conclusion: 'failure' }, { path: '.github/workflows/other.yml' },
    { repository: { full_name: 'other/Shoostr' } }, { head_repository: { full_name: 'other/Shoostr' } }]) {
    assert.throws(() => requireSuccessfulCi([{ ...ciRun, ...change }], revision), /canonical main CI/);
  }
  assert.throws(() => requireSuccessfulCi([], revision));
});

const env = {
  GITHUB_REPOSITORY: 'SuppieRK/Shoostr', GITHUB_EVENT_NAME: 'release',
  GITHUB_RUN_ATTEMPT: '1', GITHUB_SHA: revision,
};
const event = { action: 'published', release: { draft: false, prerelease: false } };

test('rejects reruns, non-release events, prereleases, forks and moved source tags', () => {
  requireReleaseEvent(event, env, revision, revision);
  for (const change of [{ GITHUB_REPOSITORY: 'fork/Shoostr' }, { GITHUB_EVENT_NAME: 'push' },
    { GITHUB_RUN_ATTEMPT: '2' }, { GITHUB_SHA: 'not-a-sha' }]) {
    assert.throws(() => requireReleaseEvent(event, { ...env, ...change }, revision, revision));
  }
  for (const change of [{ action: 'created' }, { release: { draft: true } }, { release: { prerelease: true } }]) {
    assert.throws(() => requireReleaseEvent({ ...event, ...change }, env, revision, revision));
  }
  assert.throws(() => requireReleaseEvent(event, env, 'b'.repeat(40), revision), /Checkout differs/);
  assert.throws(() => requireReleaseEvent(event, env, revision, 'b'.repeat(40)), /tag moved/);
});

test('requires exactly one plugin deployment ID and preserves ambiguous outcome as failure', () => {
  const line = 'Uploaded bundle to Central Portal as AUTOMATIC, deployment id: 12345678-abcd-1234-abcd-123456789abc';
  assert.equal(deploymentId(line), '12345678-abcd-1234-abcd-123456789abc');
  assert.throws(() => deploymentId('connection lost'), /reconcile/);
  assert.throws(() => deploymentId(line + '\n' + line), /Expected one/);
});

test('repository lookup distinguishes absence from forbidden, throttled and unavailable responses', async () => {
  for (const status of [200, 404]) {
    const found = await publicExists(library('core'), async (url, options) => {
      assert.equal(url.origin, 'https://repo.maven.apache.org');
      assert.equal(options.method, 'HEAD');
      return { status, ok: status === 200 };
    });
    assert.equal(found, status === 200);
  }
  for (const status of [401, 403, 429, 503]) {
    await assert.rejects(publicExists(library('core'), async () => ({ status, ok: false })), /lookup failed/);
  }
});

test('stages dependencies first and verifies consumers before any publication command', async () => {
  const http = library('http');
  const core = library('core', '2026.11.4.1', { http: http.version });
  const calls = [];
  mkdirSync('build/publication', { recursive: true });
  await stage(args => {
    calls.push(args);
    if (args.includes('publicationProjects')) writeFileSync('build/publication/projects.json', JSON.stringify([core, http]));
  }, exists([]), (projects, staged) => {
    assert.deepEqual(projects, [http, core]);
    assert.equal(staged, true);
    assert.equal(calls.length, 3);
  });
  assert(calls[1].includes(':http:publishMavenJavaPublicationToReleaseVerificationRepository'));
  assert(calls[2].includes(':core:publishMavenJavaPublicationToReleaseVerificationRepository'));
  assert(calls.flat().every(arg => !arg.includes('MavenCentral')));
  await assert.rejects(stage(() => {}, exists([coordinate(core), coordinate(http)]), () => {}), /No unpublished/);
});

test('publishes a selected mixed-version batch in one invocation after authenticated preflight', async () => {
  await withEnvironment({ GITHUB_RUN_ATTEMPT: '1', ORG_GRADLE_PROJECT_mavenCentralUsername: 'fake-user',
    ORG_GRADLE_PROJECT_mavenCentralPassword: 'fake-token', ORG_GRADLE_PROJECT_signingInMemoryKey: 'fake-key' }, async () => {
    const projects = [library('core'), library('mcp', '2026.11.4.2')];
    const calls = [];
    await publish(projects, async (url, options) => {
      assert.equal(url.hostname, 'central.sonatype.com');
      assert.match(options.headers.Authorization, /^Bearer /);
      return { deployments: [], pageCount: 0 };
    }, exists([]), async args => { calls.push(args); });
    assert.equal(calls.length, 1);
    assert(calls[0].includes(':core:publishAndReleaseToMavenCentral'));
    assert(calls[0].includes(':mcp:publishAndReleaseToMavenCentral'));
    assert(calls[0].includes('-PSONATYPE_CLOSE_TIMEOUT_SECONDS=3600'));
    assert(!calls[0].some(arg => arg.startsWith(':http:')));
    await assert.rejects(publish(projects, async () => ({ deployments: [], pageCount: 0 }), exists([coordinate(projects[0])]),
      async () => assert.fail('must not upload an existing GAV')), /Already published/);
  });
  await withEnvironment({ GITHUB_RUN_ATTEMPT: '2' }, async () => {
    await assert.rejects(publish([]), /reconciliation/);
  });
  await withEnvironment({ GITHUB_RUN_ATTEMPT: '1', ORG_GRADLE_PROJECT_mavenCentralUsername: '' }, async () => {
    await assert.rejects(publish([]), /Missing secret/);
  });
});

test('publisher records the deployment ID even when its process later fails', async () => {
  const line = 'Uploaded bundle to Central Portal as AUTOMATIC, deployment id: 12345678-abcd-1234-abcd-123456789abc';
  await runPublisher(['-e', `console.log(${JSON.stringify(line)})`], process.execPath);
  assert.match(readFileSync('build/publication/deployment-id.txt', 'utf8'), /12345678/);
  await assert.rejects(runPublisher(['-e', `console.log(${JSON.stringify(line)}); process.exitCode=1`], process.execPath), /reconcile/);
  assert.match(readFileSync('build/publication/publisher.log', 'utf8'), /deployment id/);
  await assert.rejects(runPublisher(['-e', 'console.log("response lost")'], process.execPath), /Expected one/);
  await assert.rejects(runPublisher([], '/nonexistent-publisher'), /ENOENT/);
});

test('creates separate Maven and both Gradle metadata consumers for every artifact', async () => {
  const commands = [];
  const projects = [library('mcp', '2026.11.4.2', { core: '2026.11.3.0' })];
  await withEnvironment({ JAVA_HOME: '/jdk' }, async () => {
    verifyConsumers(projects, true, (command, args) => {
      commands.push([command, args]);
      if (command === 'mvn') {
        const directory = dirname(args[args.indexOf('-f') + 1]);
        writeFileSync(join(directory, 'classpath.txt'), '/repository/shoostr-mcp-2026.11.4.2.jar:/repository/shoostr-core-2026.11.3.0.jar');
      }
    }, args => { commands.push(['gradle', args]); });
  });
  assert.equal(commands.filter(([command]) => command === 'gradle').length, 2);
  assert.equal(commands.filter(([command]) => command === 'mvn').length, 1);
  assert.equal(commands.filter(([command]) => command.endsWith('/javac')).length, 1);
  assert.equal(commands.filter(([command]) => command.endsWith('/java')).length, 1);
  assert.match(readFileSync('build/publication/staged-mcp-pom/build.gradle', 'utf8'), /ignoreGradleMetadataRedirection/);
  assert.match(readFileSync('build/publication/staged-mcp-module/build.gradle', 'utf8'), /gradleMetadata\(\)/);
  assert(readFileSync('build/publication/staged-mcp-module/build.gradle', 'utf8').includes("resolved['shoostr-core'] == '2026.11.3.0'"));
  assert.throws(() => verifyConsumers(projects, true, (command, args) => {
    if (command === 'mvn') writeFileSync(join(dirname(args[args.indexOf('-f') + 1]), 'classpath.txt'), '/repository/shoostr-mcp-2026.11.4.2.jar:/repository/shoostr-core-2026.11.4.0.jar');
  }, () => {}), /unexpected shoostr-core/);
});

test('public verification waits for availability and reports success only after consumer checks', async () => {
  const path = resolve('build/publication/test-event.json');
  writeFileSync(path, JSON.stringify({ release: { id: 123 } }));
  const requests = [];
  let attempts = 0;
  await withEnvironment({ GITHUB_EVENT_PATH: path, GITHUB_SHA: revision, GITHUB_STEP_SUMMARY: resolve('build/publication/summary.md') }, async () => {
    await verifyPublications([library('core')], async () => ++attempts > 1, (projects, staged) => {
      assert.equal(staged, false);
      assert.equal(projects[0].name, 'core');
    }, async (url, options) => {
      requests.push([url, options]);
      return { body: 'Original release notes' };
    }, async () => {});
    assert.equal(requests.length, 2);
    assert.match(JSON.parse(requests[1][1].body).body, /Original release notes[\s\S]*Public Maven and Gradle consumer verification passed/);
    await assert.rejects(verifyPublications([library('core')], async () => false,
      () => assert.fail('consumer verification must wait'), async () => assert.fail('must not report success'), async () => {}), /Not public/);
    await assert.rejects(verifyPublications([library('core')], async () => true,
      () => { throw new Error('broken binary'); }, async () => assert.fail('must not report success')), /broken binary/);
  });
});

test('source verification binds tag and ancestry before checking canonical CI', async () => {
  const path = resolve('build/publication/test-source-event.json');
  writeFileSync(path, JSON.stringify({ ...event, release: { ...event.release, tag_name: 'release-2026.11.4' } }));
  await withEnvironment({ ...env, GITHUB_EVENT_PATH: path }, async () => {
    const commands = [];
    await verifySource((command, args) => {
      commands.push([command, args]);
      return revision + '\n';
    }, async url => {
      assert.match(url, new RegExp(`head_sha=${revision}`));
      return { workflow_runs: [ciRun] };
    });
    assert(commands.some(([, args]) => args.includes('--is-ancestor')));
    await assert.rejects(verifySource(() => { throw new Error('not on main'); }, async () => assert.fail('no API before git verification')), /not on main/);
  });
});

async function withEnvironment(values, callback) {
  const saved = new Map(Object.keys(values).map(key => [key, process.env[key]]));
  Object.assign(process.env, values);
  try { await callback(); } finally {
    for (const [key, value] of saved) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  }
}
