import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

try {
  await verify(process.argv[2]);
} catch (error) {
  console.error(`Sonar verification failed: ${error.message}`);
  process.exitCode = 1;
}

async function verify(path) {
  const revision = process.env.EXPECTED_REVISION;
  assert(/^[a-f0-9]{40}$/.test(revision ?? ''), 'Invalid expected revision');
  assert(process.env.SONAR_TOKEN, 'SONAR_TOKEN is required');
  const report = readReport(path);
  const server = new URL(report.get('serverUrl'));
  const dashboard = new URL(report.get('dashboardUrl'));
  assert(!server.username && !server.password, 'Server URL must not contain credentials');
  assert(
    server.protocol === 'https:'
      || (server.protocol === 'http:' && ['127.0.0.1', 'localhost', '[::1]'].includes(server.hostname)),
    'Server URL requires HTTPS outside loopback',
  );
  assert(dashboard.origin === server.origin, 'Dashboard does not match the scanner server');
  const project = report.get('projectKey');
  assert(
    dashboard.searchParams.get('id') === project && dashboard.searchParams.getAll('id').length === 1,
    'Dashboard project does not match the report',
  );
  const taskId = report.get('ceTaskId');
  const target = selectTarget(dashboard);
  const api = (endpoint, query) => request(server, endpoint, query);
  const { task } = await api('ce/task', { id: taskId, additionalFields: 'warnings' });
  assert(
    task?.id === taskId && task.componentKey === project && task.status === 'SUCCESS'
      && typeof task.analysisId === 'string' && task.analysisId.length > 0,
    'Scanner task is not a completed analysis for this project',
  );
  assert(Number.isSafeInteger(task.warningCount) && task.warningCount >= 0, 'Sonar returned an invalid analysis warning count');
  const warningCount = Number(task.warningCount);
  await verifyRevision(api, project, target, revision, task.analysisId);
  const issues = await api('issues/search', { componentKeys: project, ...target, resolved: 'false', ps: '1' });
  assert(Number.isSafeInteger(issues.total) && issues.total >= 0, 'Sonar returned an invalid issue count');
  assert(issues.total === 0, `Sonar analysis has ${issues.total} open issue(s)`);
  await verifyRevision(api, project, target, revision, task.analysisId);
  console.log(`Sonar analysis has zero open issues for ${revision}`);
  console.log(`Sonar analysis warnings: ${warningCount}`);
}

function readReport(path) {
  assert(path, 'Scanner report path is required');
  const result = new Map();
  for (const line of readFileSync(path, 'utf8').split(/\r?\n/)) {
    if (!line.trim() || line.startsWith('#')) continue;
    const separator = line.indexOf('=');
    assert(separator > 0, 'Invalid scanner report entry');
    const key = line.slice(0, separator).trim();
    assert(!result.has(key), 'Duplicate scanner report entry');
    result.set(key, line.slice(separator + 1).trim());
  }
  for (const key of ['serverUrl', 'dashboardUrl', 'projectKey', 'ceTaskId']) {
    assert(result.get(key), `Scanner report is missing ${key}`);
  }
  return result;
}

function selectTarget(dashboard) {
  const branches = dashboard.searchParams.getAll('branch');
  const requests = dashboard.searchParams.getAll('pullRequest');
  assert(
    branches.length <= 1 && requests.length <= 1 && !(branches.length && requests.length),
    'Ambiguous scanner target',
  );
  if (process.env.GITHUB_EVENT_NAME === 'pull_request') {
    const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
    assert(
      Number.isSafeInteger(event.number) && event.number > 0 && requests[0] === String(event.number),
      'Scanner report does not match the pull request',
    );
    assert(event.pull_request?.head?.sha === process.env.EXPECTED_REVISION, 'Pull request does not match the expected revision');
    return { pullRequest: requests[0] };
  }
  assert(['push', 'workflow_dispatch'].includes(process.env.GITHUB_EVENT_NAME), 'Unsupported analysis event');
  const branch = process.env.GITHUB_REF_NAME;
  assert(
    branch && !requests.length && (branches[0] === branch || (branch === 'main' && !branches.length)),
    'Scanner report does not match the branch',
  );
  return { branch };
}

async function verifyRevision(api, project, target, revision, analysisId) {
  if (target.pullRequest) {
    const data = await api('project_pull_requests/list', { project });
    const request = data.pullRequests?.find(item => item.key === target.pullRequest);
    assert(request?.commit?.sha === revision, 'Sonar pull request analysis does not match the expected revision');
    return;
  }
  const data = await api('project_analyses/search', { project, ...target, ps: '1' });
  const analysis = data.analyses?.[0];
  assert(
    analysis?.key === analysisId && analysis.revision === revision,
    'Sonar branch analysis does not match the scanner task and expected revision',
  );
}

async function request(server, endpoint, query) {
  const url = new URL(`${server.href.replace(/\/$/, '')}/api/${endpoint}`);
  url.search = new URLSearchParams(query);
  const response = await fetch(url, {
    headers: { Authorization: `Bearer ${process.env.SONAR_TOKEN}` },
    redirect: 'error',
    signal: AbortSignal.timeout(30_000),
  });
  assert(response.ok, `Sonar ${endpoint} failed with HTTP ${response.status}`);
  try {
    return await response.json();
  } catch {
    throw new Error(`Sonar ${endpoint} returned invalid JSON`);
  }
}
