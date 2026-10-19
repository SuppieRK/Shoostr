import { readFileSync } from 'node:fs';

try {
  const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  if (process.env.GITHUB_EVENT_NAME === 'pull_request'
      && (event.pull_request?.head?.repo?.full_name !== process.env.GITHUB_REPOSITORY
          || event.pull_request?.user?.login === 'dependabot[bot]'
          || process.env.GITHUB_ACTOR === 'dependabot[bot]')) {
    throw new Error('This contribution requires maintainer-reviewed branch promotion; see CONTRIBUTING.md');
  }
  if (process.env.VALIDATION_RESULT !== 'success') {
    throw new Error('CI validation did not succeed');
  }
  if (process.env.ANALYSIS_COMPLETED !== 'true') {
    throw new Error('Trusted full-project analysis has not completed');
  }
  const expected = process.env.EXPECTED_REVISION;
  const proposed = process.env.GITHUB_EVENT_NAME === 'pull_request'
    ? event.pull_request?.head?.sha : process.env.GITHUB_SHA;
  if (!/^[a-f0-9]{40}$/.test(expected ?? '') || expected !== proposed
      || process.env.ANALYZED_REVISION !== expected) {
    throw new Error('Full-project analysis does not match the current proposed revision');
  }
  console.log(`CI validation and full-project analysis succeeded for ${expected}`);
} catch (failure) {
  console.error(failure.message);
  process.exitCode = 1;
}
