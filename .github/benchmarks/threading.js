import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';

const base = __ENV.BASE_URL || 'http://127.0.0.1:18080';
const model = __ENV.THREADING_MODEL;
const workload = __ENV.WORKLOAD;
const warmup = __ENV.PHASE === 'warmup';
const duration = __ENV.DURATION || '180s';
const expected = {
  tiny: 'Hello, World!',
  io: 'pong',
  cpu: 'baa7a6d36ffa957552df230235c2d51d735f28d49c58a5f3438a3a973a25a37d',
};
if (!['A', 'B', 'C', 'D'].includes(model) || !['tiny', 'io', 'cpu', 'mixed'].includes(workload)) {
  throw new Error('Invalid threading fixture settings');
}
const arrival = (rate, exec) => ({
  executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration,
  preAllocatedVUs: 256, maxVUs: 1024, gracefulStop: '5s', exec,
});
const scenarios = workload === 'mixed' ? {
  tiny: arrival(1000, 'tiny'),
  cpu: warmup ? arrival(200, 'cpu') : {
    executor: 'ramping-arrival-rate', startRate: 200, timeUnit: '1s',
    stages: [
      { target: 200, duration: '60s' },
      { target: 500, duration: '1s' }, { target: 500, duration: '59s' },
      { target: 1000, duration: '1s' }, { target: 1000, duration: '59s' },
    ],
    preAllocatedVUs: 256, maxVUs: 1024, gracefulStop: '5s', exec: 'cpu',
  },
} : { requests: arrival(Number(__ENV.RATE), workload) };
if (workload === 'io') scenarios.downstream = arrival(2, 'downstream');
const thresholds = {
  checks: ['rate==1'], http_req_failed: ['rate==0'], dropped_iterations: ['count==0'],
};
for (const name of [...(workload === 'mixed' ? ['tiny', 'cpu'] : [workload]),
  ...(workload === 'io' ? ['downstream'] : [])]) {
  for (const metric of ['http_req_duration', 'http_req_blocked', 'http_req_connecting', 'http_reqs', 'http_req_failed']) {
    thresholds[`${metric}{name:${name}}`] = [];
    if (workload === 'mixed' && !warmup) {
      for (let step = 1; step <= 3; step++) thresholds[`${metric}{name:${name},step:${step}}`] = [];
    }
  }
}
for (const scenario of Object.keys(scenarios)) thresholds[`dropped_iterations{scenario:${scenario}}`] = [];
export const options = {
  scenarios, thresholds,
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function request(name) {
  const step = Math.min(3, 1 + Math.floor((Date.now() - exec.scenario.startTime) / 60000));
  const response = http.get(`${base}/${name}`, { timeout: '5s', redirects: 0, tags: { name, step: String(step) } });
  check(response, {
    'status matches': r => r.status === 200,
    'body matches': r => r.body === expected[name],
  });
}
export function tiny() { request('tiny'); }
export function io() { request('io'); }
export function cpu() { request('cpu'); }
export function downstream() {
  const response = http.get('http://127.0.0.1:18081/io', {
    timeout: '5s', redirects: 0, tags: { name: 'downstream' },
  });
  check(response, { 'downstream parity': r => r.status === 200 && r.body === 'pong' });
}
export function setup() {
  for (const name of workload === 'mixed' ? ['tiny', 'cpu'] : [workload]) {
    const params = { timeout: '5s', redirects: 0, tags: { name: 'fixture' } };
    const kind = http.get(`${base}/kind/${name}`, params);
    const platform = model === 'C' || (model === 'D' && (workload !== 'mixed' || name === 'cpu'));
    if (kind.status !== 200 || kind.body !== String(!platform)) fail(`Wrong ${name} thread kind`);
    const response = http.get(`${base}/${name}`, params);
    if (response.status !== 200 || response.body !== expected[name]) fail(`Wrong ${name} fixture`);
  }
}
