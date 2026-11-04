import assert from 'node:assert/strict';
import { spawnSync, spawn } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync, appendFileSync, rmSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import process from 'node:process';
import { Buffer } from 'node:buffer';

const repository = 'SuppieRK/Shoostr';
const central = 'https://repo.maven.apache.org/maven2/';
const portal = 'https://central.sonatype.com/api/v1/publisher/';
const evidence = resolve('build/publication');

export function stableVersion(version) {
  const match = /^(\d{4})\.(0[1-9]|1[0-2])\.([1-9]|[12]\d|3[01])\.(0|[1-9]\d*)$/.exec(version);
  assert(match && match[0] === version, `Expected YYYY.MM.D.PATCH, received ${version}`);
  const [, year, month, day] = match;
  const date = new Date(`${year}-${month}-${day.padStart(2, '0')}T00:00:00Z`);
  assert(date.getUTCFullYear() === Number(year) && date.getUTCMonth() + 1 === Number(month)
    && date.getUTCDate() === Number(day), `Invalid release date: ${version}`);
  return version;
}

export function coordinate(project) {
  assert(/^[a-z][a-z0-9]*$/.test(project.name), 'Invalid library name');
  assert(project.group === 'io.github.suppierk' && project.artifact === `shoostr-${project.name}`,
    'Unexpected publication coordinates');
  return `${project.group}:${project.artifact}:${project.version}`;
}

export function artifactPath(project, extension = 'pom') {
  coordinate(project);
  stableVersion(project.version);
  return `${project.group.replaceAll('.', '/')}/${project.artifact}/${project.version}/${project.artifact}-${project.version}.${extension}`;
}

export async function selectProjects(projects, exists) {
  const selected = [];
  for (const project of projects) {
    coordinate(project);
    if (project.version.endsWith('-SNAPSHOT')) continue;
    stableVersion(project.version);
    if (!await exists(project)) selected.push(project);
  }
  const ordered = [];
  const visiting = new Set();
  async function visit(project) {
    if (ordered.includes(project)) return;
    assert(!visiting.has(project), 'Cyclic publication dependencies');
    visiting.add(project);
    for (const [name, version] of Object.entries(project.dependencies)) {
      stableVersion(version);
      const dependency = projects.find(item => item.name === name);
      assert(dependency, `Unknown internal dependency: ${name}`);
      const staged = selected.find(item => item.name === name && item.version === version);
      if (staged) await visit(staged);
      else assert(await exists({ ...dependency, version }),
        `${coordinate(project)} requires unpublished shoostr-${name}:${version}`);
    }
    visiting.delete(project);
    ordered.push(project);
  }
  for (const project of selected) await visit(project);
  return ordered;
}

export async function requireNoPendingDeployments(request, projects = []) {
  const selected = new Set(projects.map(project => `pkg:maven/${project.group}/${project.artifact}@${project.version}`));
  for (let page = 0; ; page++) {
    const data = await request(`deployments?namespace=io.github.suppierk&page=${page}&size=20`);
    assert(Array.isArray(data.deployments) && Number.isSafeInteger(data.pageCount)
      && data.pageCount >= 0, 'Invalid Central deployment listing');
    for (const deployment of data.deployments) {
      assert(['PUBLISHED', 'FAILED'].includes(deployment.deploymentState),
        `Reconcile Central deployment ${deployment.deploymentId ?? deployment.id ?? 'unknown'} (${deployment.deploymentState ?? 'unknown'}) before another upload`);
      assert(deployment.deploymentState !== 'PUBLISHED'
        || !(deployment.deploymentComponents ?? []).some(component => selected.has(component.purl)),
      `Selected coordinate is already in published Central deployment ${deployment.deploymentId}; wait for public availability instead of uploading again`);
    }
    if (page + 1 >= data.pageCount) return;
  }
}

export function requireSuccessfulCi(runs, revision) {
  assert(runs.some(run => run.head_sha === revision && run.head_branch === 'main'
    && run.event === 'push' && run.status === 'completed' && run.conclusion === 'success'
    && run.path === '.github/workflows/ci.yml'
    && run.repository?.full_name === repository && run.head_repository?.full_name === repository),
  `Successful canonical main CI is required for ${revision}`);
}

export function deploymentId(log) {
  const ids = [...log.matchAll(/Uploaded bundle to Central Portal as AUTOMATIC, deployment id: ([a-f0-9-]+)/g)]
    .map(match => match[1]);
  assert(ids.length === 1, 'Expected one Central deployment ID; reconcile the upload before retrying');
  return ids[0];
}

export function requireReleaseEvent(event, env, head, tag) {
  assert(env.GITHUB_REPOSITORY === repository, 'Publication requires the canonical repository');
  assert(env.GITHUB_EVENT_NAME === 'release', 'Publication requires a GitHub Release');
  assert(env.GITHUB_RUN_ATTEMPT === '1', 'Do not rerun publication; reconcile the previous deployment first');
  assert(event.action === 'published' && event.release?.draft === false && event.release?.prerelease === false,
    'Only published stable GitHub Releases are supported');
  assert(/^[a-f0-9]{40}$/.test(env.GITHUB_SHA ?? ''), 'Invalid release revision');
  assert(head === env.GITHUB_SHA, 'Checkout differs from release event revision');
  assert(tag === env.GITHUB_SHA, 'Release tag moved away from event revision');
}

async function main(command) {
  mkdirSync(evidence, { recursive: true });
  if (command === 'source') return verifySource();
  if (command === 'stage') return stage();
  const projects = JSON.parse(readFileSync(join(evidence, 'selected.json'), 'utf8'));
  if (command === 'publish') return publish(projects);
  if (command === 'verify') return verifyPublications(projects);
  throw new Error(`Unknown publication command: ${command}`);
}

export async function verifyPublications(projects, exists = publicExists, consumers = verifyConsumers, request = github, pause = delay) {
    for (const project of projects) {
      let available = false;
      for (let attempt = 0; attempt < 60 && !available; attempt++) {
        available = await exists(project);
        if (!available) await pause(10_000);
      }
      assert(available, `Not public: ${coordinate(project)}`);
    }
    consumers(projects, false);
    const id = readFileSync(join(evidence, 'deployment-id.txt'), 'utf8').trim();
    const report = `\n\n### Maven Central publication\n\nSource: \`${process.env.GITHUB_SHA}\`\n\n`
      + projects.map(project => `- \`${coordinate(project)}\``).join('\n')
      + `\n\nDeployment: \`${id}\`. Public Maven and Gradle consumer verification passed.\n`;
    if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, report);
    const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
    const release = await request(`releases/${event.release.id}`);
    await request(`releases/${event.release.id}`, {
      method: 'PATCH', body: JSON.stringify({ body: (release.body ?? '') + report }),
    });
}

export async function verifySource(execute = run, request = github) {
  const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  const revision = process.env.GITHUB_SHA;
  requireReleaseEvent(event, process.env, execute('git', ['rev-parse', 'HEAD']).trim(),
    execute('git', ['rev-parse', '--verify', `refs/tags/${event.release.tag_name}^{commit}`]).trim());
  execute('git', ['merge-base', '--is-ancestor', revision, 'origin/main']);
  const data = await request(`actions/workflows/ci.yml/runs?head_sha=${revision}&event=push&branch=main&status=success&per_page=100`);
  requireSuccessfulCi(data.workflow_runs, revision);
}

export async function stage(executeGradle = gradle, exists = publicExists, consumers = verifyConsumers) {
  executeGradle(['publicationProjects']);
  const all = JSON.parse(readFileSync(join(evidence, 'projects.json'), 'utf8'));
  const projects = await selectProjects(all, exists);
  assert(projects.length, 'No unpublished stable library versions; prepare versions in the library builds first');
  writeFileSync(join(evidence, 'selected.json'), JSON.stringify(projects, null, 2));
  rmSync('build/release-verification-repository', { recursive: true, force: true });
  for (const project of projects) {
    executeGradle([...binaryArguments(projects), `:${project.name}:publishMavenJavaPublicationToReleaseVerificationRepository`]);
  }
  consumers(projects, true);
  console.log(`Verified selected publications:\n${projects.map(coordinate).join('\n')}`);
}

export async function publish(projects, request = jsonRequest, exists = publicExists, executePublisher = runPublisher) {
  assert(process.env.GITHUB_RUN_ATTEMPT === '1', 'Upload retries require manual reconciliation');
  for (const key of ['ORG_GRADLE_PROJECT_mavenCentralUsername', 'ORG_GRADLE_PROJECT_mavenCentralPassword',
    'ORG_GRADLE_PROJECT_signingInMemoryKey']) assert(process.env[key], `Missing secret: ${key}`);
  await requireNoPendingDeployments(async path => {
    const token = Buffer.from(`${process.env.ORG_GRADLE_PROJECT_mavenCentralUsername}:${process.env.ORG_GRADLE_PROJECT_mavenCentralPassword}`).toString('base64');
    return request(new URL(path, portal), { headers: { Authorization: `Bearer ${token}` } });
  }, projects);
  for (const project of projects) assert(!await exists(project), `Already published: ${coordinate(project)}`);
  const args = [...binaryArguments(projects), '-PcentralRelease=true', '-PSONATYPE_CLOSE_TIMEOUT_SECONDS=3600',
    ...projects.map(project => `:${project.name}:publishAndReleaseToMavenCentral`), '--console=plain'];
  await executePublisher(args);
}

export async function runPublisher(args, executable = './gradlew') {
  mkdirSync(evidence, { recursive: true });
  const logPath = join(evidence, 'publisher.log');
  writeFileSync(logPath, '');
  const child = spawn(executable, args, { stdio: ['ignore', 'pipe', 'pipe'] });
  let log = '';
  for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => {
    const value = chunk.toString();
    log += value;
    appendFileSync(logPath, value);
    process.stdout.write(value);
    const found = /Uploaded bundle to Central Portal as AUTOMATIC, deployment id: ([a-f0-9-]+)/.exec(log);
    if (found) writeFileSync(join(evidence, 'deployment-id.txt'), found[1] + '\n');
  });
  const status = await new Promise((accept, reject) => {
    child.on('error', reject);
    child.on('close', accept);
  });
  assert(status === 0, 'Publisher failed or timed out; inspect publisher.log and reconcile its deployment before retrying');
  writeFileSync(join(evidence, 'deployment-id.txt'), deploymentId(log) + '\n');
}

function binaryArguments(projects) {
  return ['-PpublicationBinaries=true', `-PpublicationModules=${projects.map(project => project.name).join(',')}`];
}

export function verifyConsumers(projects, staged, execute = run, executeGradle = gradle) {
  // Each artifact gets its own consumers: aggregating would let a newer core mask an older pin.
  for (const project of projects) {
    for (const metadata of ['module', 'pom', 'maven']) {
      const directory = join(evidence, `${staged ? 'staged' : 'public'}-${project.name}-${metadata}`);
      mkdirSync(directory, { recursive: true });
      const sourceDirectory = join(directory, 'src/main/java/io/github/suppierk/consumer');
      mkdirSync(sourceDirectory, { recursive: true });
      writeFileSync(join(sourceDirectory, 'Main.java'), consumerSource(project));
      const local = staged ? pathToFileURL(resolve('build/release-verification-repository')).href : null;
      if (metadata === 'maven') verifyMavenConsumer(directory, project, local, execute);
      else verifyGradleConsumer(directory, project, metadata, local, executeGradle);
    }
  }
}

function verifyGradleConsumer(directory, project, metadata, local, executeGradle) {
  writeFileSync(join(directory, 'settings.gradle'), "rootProject.name = 'publication-consumer'\n");
  const dependencies = { [project.name]: project.version, ...project.dependencies };
  writeFileSync(join(directory, 'build.gradle'), `
plugins { id 'application' }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
repositories {
  ${local ? `maven { url = uri('${local}'); content { includeGroup 'io.github.suppierk' }; metadataSources { ${metadata === 'pom' ? 'mavenPom(); ignoreGradleMetadataRedirection()' : 'gradleMetadata()'} } }` : ''}
  maven { url = uri('${central}'); content { includeGroup 'io.github.suppierk' }; metadataSources { ${metadata === 'pom' ? 'mavenPom(); ignoreGradleMetadataRedirection()' : 'gradleMetadata()'} } }
  mavenCentral { content { excludeGroup 'io.github.suppierk' } }
}
dependencies { implementation '${coordinate(project)}' }
application { mainClass = 'io.github.suppierk.consumer.Main' }
tasks.register('verifyCoordinates') {
  doLast {
    def components = configurations.runtimeClasspath.incoming.resolutionResult.allComponents
    def resolved = components.findAll { it.id instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier }.collectEntries { [(it.id.module): it.id.version] }
    ${Object.entries(dependencies).map(([name, version]) => `assert resolved['shoostr-${name}'] == '${version}'`).join('\n    ')}
    assert components.every { it.id == configurations.runtimeClasspath.incoming.resolutionResult.rootComponent.get().id || it.id instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier }
  }
}
tasks.named('run') { dependsOn 'verifyCoordinates' }
`);
  executeGradle(['--project-dir', directory, '--no-build-cache', '--refresh-dependencies', 'run']);
}

function verifyMavenConsumer(directory, project, local, execute) {
  writeFileSync(join(directory, 'pom.xml'), `<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>io.github.suppierk.consumer</groupId><artifactId>consumer</artifactId><version>1</version>
${local ? `<repositories><repository><id>staged</id><url>${local}</url></repository></repositories>` : ''}
<dependencies><dependency><groupId>${project.group}</groupId><artifactId>${project.artifact}</artifactId><version>${project.version}</version></dependency></dependencies></project>`);
  execute('mvn', ['-B', '-ntp', '-f', join(directory, 'pom.xml'),
    `-Dmaven.repo.local=${join(directory, 'repository')}`,
    'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath',
    '-Dmdep.outputFile=classpath.txt']);
  const classpath = readFileSync(join(directory, 'classpath.txt'), 'utf8').trim();
  for (const [name, version] of Object.entries({ [project.name]: project.version, ...project.dependencies })) {
    assert(classpath.split(':').some(path => path.endsWith(`/shoostr-${name}-${version}.jar`)),
      `Maven resolved unexpected shoostr-${name} dependency`);
  }
  const source = join(directory, 'src/main/java/io/github/suppierk/consumer/Main.java');
  execute(join(process.env.JAVA_HOME, 'bin/javac'), ['--release', '21', '-proc:none', '-cp', classpath, '-d', join(directory, 'classes'), source]);
  execute(join(process.env.JAVA_HOME, 'bin/java'), ['-cp', `${join(directory, 'classes')}:${classpath}`, 'io.github.suppierk.consumer.Main']);
}

function consumerSource(project) {
  const probes = {
    http: `if (!io.github.suppierk.shoostr.http.MediaType.of("text/plain").equals(
        io.github.suppierk.shoostr.http.MediaType.TEXT_PLAIN)) throw new AssertionError();`,
    core: `try (var app = new io.github.suppierk.shoostr.Shoostr().modifyServer(server -> {
        var connector = (org.eclipse.jetty.server.ServerConnector) server.getConnectors()[0];
        connector.setHost("127.0.0.1");
        connector.setPort(0);
      }); var client = java.net.http.HttpClient.newHttpClient()) {
        app.routes().get("/publication", (request, response) -> response.text("ok"));
        app.start();
        var request = java.net.http.HttpRequest.newBuilder(
            java.net.URI.create("http://127.0.0.1:" + app.port() + "/publication"))
            .timeout(java.time.Duration.ofSeconds(5)).build();
        var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || !"ok".equals(response.body())) throw new AssertionError();
      }`,
    test: `try (var server = io.github.suppierk.shoostr.testing.TestServer.start(
        new io.github.suppierk.shoostr.Shoostr())) {
        if (server.baseUri() == null) throw new AssertionError();
      }`,
    micrometer: `var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
      try (var app = new io.github.suppierk.shoostr.Shoostr()) {
        app.extensions(new io.github.suppierk.shoostr.micrometer.MicrometerMetrics(registry));
      } finally { registry.close(); }`,
    opentelemetry: `try (var app = new io.github.suppierk.shoostr.Shoostr()) {
        app.extensions(new io.github.suppierk.shoostr.opentelemetry.OpenTelemetryTracing(
            io.opentelemetry.api.OpenTelemetry.noop()));
      }`,
    openapi: `try (var app = new io.github.suppierk.shoostr.Shoostr()) {
        app.extensions(new io.github.suppierk.shoostr.openapi.OpenApi("publication", "1"));
      }`,
    pac4j: `try (var app = new io.github.suppierk.shoostr.Shoostr()) {
        app.authentication(new io.github.suppierk.shoostr.pac4j.Pac4j(
            new org.pac4j.core.client.direct.AnonymousClient(), "Basic realm=publication"));
      }`,
    mcp: `var security = new io.github.suppierk.shoostr.mcp.McpHttpSecurity(
          java.util.Set.of("https://publication.invalid"),
          ignored -> new io.github.suppierk.shoostr.mcp.McpIdentity("publication",
              io.modelcontextprotocol.common.McpTransportContext.EMPTY, null),
          (ignored, identity) -> {});
      try (var app = new io.github.suppierk.shoostr.Shoostr()) {
        var mcp = io.github.suppierk.shoostr.mcp.Mcp.sync(security,
            transport -> io.modelcontextprotocol.server.McpServer.sync(transport)
                .serverInfo("publication", "1").build());
        app.extensions(mcp).routes(routes -> routes.path("/mcp", mcp::routes));
      }`,
  };
  assert(probes[project.name], 'Missing artifact runtime probe');
  return `package io.github.suppierk.consumer;
public final class Main {
  public static void main(String[] args) throws Exception {
    ${probes[project.name]}
    System.out.println("PUBLICATION_OK");
  }
}
`;
}

export async function publicExists(project, request = fetch) {
  const response = await request(new URL(artifactPath(project), central), {
    method: 'HEAD', redirect: 'error', signal: AbortSignal.timeout(30_000),
  });
  assert(response.ok || response.status === 404, `Central lookup failed: HTTP ${response.status}`);
  return response.ok;
}

async function github(path, options = {}) {
  assert(process.env.GITHUB_TOKEN, 'GITHUB_TOKEN is required');
  return jsonRequest(`https://api.github.com/repos/${repository}/${path}`, {
    ...options, headers: { Authorization: `Bearer ${process.env.GITHUB_TOKEN}`, Accept: 'application/vnd.github+json', 'Content-Type': 'application/json' },
  });
}

async function jsonRequest(url, options) {
  const response = await fetch(url, { ...options, redirect: 'error', signal: AbortSignal.timeout(30_000) });
  assert(response.ok, `Publication API failed: HTTP ${response.status}`);
  return response.json();
}

function gradle(args) { return run('./gradlew', [...args, '--console=plain']); }

function run(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, timeout: 900_000 });
  if (result.stdout) process.stdout.write(result.stdout);
  if (result.stderr) process.stderr.write(result.stderr);
  assert(!result.error && result.status === 0, `${command} failed: ${result.error?.message ?? result.status}`);
  return result.stdout;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main(process.argv[2]).catch(error => { console.error(error.message); process.exitCode = 1; });
}
