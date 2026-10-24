import { readFileSync } from 'node:fs';

try {
  const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  if (process.env.VALIDATION_RESULT !== 'success') {
    throw new Error('CI validation did not succeed');
  }
  const expected = process.env.EXPECTED_REVISION;
  const proposed = process.env.GITHUB_EVENT_NAME === 'pull_request'
    ? event.pull_request?.head?.sha : process.env.GITHUB_SHA;
  if (!/^[a-f0-9]{40}$/.test(expected ?? '') || expected !== proposed) {
    throw new Error('CI validation does not match the current proposed revision');
  }
  console.log(`CI validation succeeded for ${expected}`);
} catch (error_) {
  console.error(error_.message);
  process.exitCode = 1;
}
