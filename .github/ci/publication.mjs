import assert from 'node:assert/strict';
import process from 'node:process';
import { execFileSync } from 'node:child_process';
import { readFileSync, rmSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export function stableVersion(version) {
  const match = /^(\d{4})\.(0[1-9]|1[0-2])\.([1-9]|[12]\d|3[01])\.(0|[1-9]\d*)$/.exec(version);
  assert(match && match[0] === version, `Expected YYYY.MM.D.PATCH, received ${version}`);
  const [, year, month, day] = match;
  const date = new Date(`${year}-${month}-${day.padStart(2, '0')}T00:00:00Z`);
  assert(date.getUTCFullYear() === Number(year) && date.getUTCMonth() + 1 === Number(month)
    && date.getUTCDate() === Number(day), `Invalid release date: ${version}`);
  return version;
}

async function published(project) {
  const { group, artifact, version } = project;
  const response = await fetch(`https://repo.maven.apache.org/maven2/${group.replaceAll('.', '/')}/${artifact}/${version}/${artifact}-${version}.pom`, {
    method: 'HEAD', redirect: 'error', signal: AbortSignal.timeout(30_000),
  });
  assert(response.ok || response.status === 404, `Central lookup failed: HTTP ${response.status}`);
  return response.ok;
}

export async function selectProjects(projects, exists = published) {
  const stable = projects.filter(project => !project.version.endsWith('-SNAPSHOT'));
  stable.forEach(project => { stableVersion(project.version); });
  const available = await Promise.all(stable.map(exists));
  const selected = stable.filter((project, index) => !available[index]);
  const dependencies = [];
  for (const [index, project] of selected.entries()) {
    for (const [name, version] of Object.entries(project.dependencies)) {
      stableVersion(version);
      const dependency = projects.find(item => item.name === name);
      assert(dependency, `Unknown dependency: ${name}`);
      const staged = selected.findIndex(item => item.name === name && item.version === version);
      if (staged >= 0) assert(staged < index, 'Publication order must place dependencies first');
      else dependencies.push({ ...dependency, version });
    }
  }
  await Promise.all(dependencies.map(async dependency => {
    assert(await exists(dependency), `Unpublished dependency: ${dependency.name}:${dependency.version}`);
  }));
  assert(selected.length, 'No unpublished stable library versions');
  return selected;
}

function gradle(args) {
  execFileSync('./gradlew', [...args, '--console=plain'], { stdio: 'inherit' });
}

export async function main(command, execute = gradle, exists = published) {
  if (command === 'stage') {
    execute(['publicationProjects']);
    const projects = await selectProjects(JSON.parse(readFileSync('build/publication/projects.json', 'utf8')), exists);
    writeFileSync('build/publication/selected.json', JSON.stringify(projects, null, 2));
    rmSync('build/release-verification-repository', { recursive: true, force: true });
    for (const project of projects) {
      execute([...binaryArguments(projects), `:${project.name}:publishMavenJavaPublicationToReleaseVerificationRepository`]);
    }
  } else {
    assert(command === 'publish', 'Expected stage or publish');
    const projects = JSON.parse(readFileSync('build/publication/selected.json', 'utf8'));
    execute([...binaryArguments(projects), '-PcentralRelease=true',
      ...projects.map(project => `:${project.name}:publishAndReleaseToMavenCentral`)]);
  }
}

function binaryArguments(projects) {
  return ['-PpublicationBinaries=true', `-PpublicationModules=${projects.map(project => project.name).join(',')}`];
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  await main(process.argv[2]);
}
