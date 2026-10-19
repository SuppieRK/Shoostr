import { readFileSync } from 'node:fs';

try {
  const task = JSON.parse(readFileSync(process.env.SONAR_TASK_FILE, 'utf8')).task;
  const analyses = JSON.parse(readFileSync(process.env.SONAR_ANALYSES_FILE, 'utf8')).analyses;
  const revision = process.env.EXPECTED_REVISION;
  if (process.env.SONAR_AUDIT_BRANCH !== `branch-${revision}-audit`) {
    throw new Error('Sonar requires an immutable audit branch for the proposed revision');
  }
  if (task?.status !== 'SUCCESS' || !task.analysisId
      || !/^[a-f0-9]{40}$/.test(revision ?? '')
      || !Array.isArray(analyses)
      || analyses[0]?.key !== task.analysisId || analyses[0]?.revision !== revision) {
    throw new Error('Sonar task did not complete analysis of the proposed revision');
  }
  const issues = JSON.parse(readFileSync(process.env.SONAR_ISSUES_FILE, 'utf8'));
  if (issues.total !== 0) {
    throw new Error('Sonar full-project audit requires zero open issues');
  }
  console.log(`Sonar full-project audit passed for ${revision}`);
} catch (error_) {
  console.error(error_.message);
  process.exitCode = 1;
}
