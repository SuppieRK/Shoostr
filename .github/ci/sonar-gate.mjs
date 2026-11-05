import assert from 'node:assert/strict';
import process from 'node:process';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const project = 'SuppieRK_Shoostr';

export async function verify({ revision, taskId, pullRequest, branch }, request = sonar) {
  assert(/^[a-f0-9]{40}$/.test(revision ?? ''), 'Invalid expected revision');
  assert(taskId && (pullRequest || branch), 'Missing scanner task or analysis target');
  const target = pullRequest ? { pullRequest } : { branch };
  const { task } = await request('ce/task', { id: taskId, additionalFields: 'warnings' });
  assert(task?.id === taskId && task.componentKey === project && task.status === 'SUCCESS'
    && typeof task.analysisId === 'string' && task.analysisId, 'Scanner task did not complete for this project');
  assert(Number.isSafeInteger(task.warningCount) && task.warningCount >= 0, 'Invalid analysis warning count');
  // The issue query targets mutable PR/branch data; reject a concurrent analysis update.
  await requireRevision();
  const issues = await request('issues/search', { componentKeys: project, ...target, resolved: 'false', ps: '1' });
  assert(Number.isSafeInteger(issues.total) && issues.total === 0, 'Sonar must have zero open issues');
  await requireRevision();
  console.log(`Sonar analysis has zero open issues for ${revision}`);
  console.log(`Sonar analysis warnings: ${task.warningCount}`);

  async function requireRevision() {
    if (pullRequest) {
      const data = await request('project_pull_requests/list', { project });
      assert(data.pullRequests?.find(item => item.key === pullRequest)?.commit?.sha === revision,
        'Sonar analysis does not match the expected revision');
    } else {
      const data = await request('project_analyses/search', { project, branch, ps: '1' });
      assert(data.analyses?.[0]?.key === task.analysisId && data.analyses[0].revision === revision,
        'Sonar analysis does not match the scanner task and expected revision');
    }
  }
}

async function sonar(endpoint, query) {
  assert(process.env.SONAR_TOKEN, 'SONAR_TOKEN is required');
  const url = new URL(`https://sonarcloud.io/api/${endpoint}`);
  url.search = new URLSearchParams(query).toString();
  const response = await fetch(url, {
    headers: { Authorization: `Bearer ${process.env.SONAR_TOKEN}` },
    redirect: 'error', signal: AbortSignal.timeout(30_000),
  });
  assert(response.ok, `Sonar request failed: HTTP ${response.status}`);
  return response.json();
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  await verify({
    revision: process.env.EXPECTED_REVISION,
    taskId: readFileSync(process.argv[2], 'utf8').match(/^ceTaskId=(.+)$/m)?.[1],
    pullRequest: process.env.GITHUB_EVENT_NAME === 'pull_request' ? String(event.number) : undefined,
    branch: process.env.GITHUB_REF_NAME,
  });
}
