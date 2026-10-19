import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const base = __ENV.BASE_URL || 'http://127.0.0.1:18080';
const role = __ENV.ROLE || 'health';
const clients = Number(__ENV.CLIENTS || 32);
if (!['health', 'blocked'].includes(role) || ![32, 128].includes(clients)) {
  throw new Error('Expected health/blocked role and 32/128 clients');
}

const completed = new Counter('health_completed');
const elapsed = new Trend('health_elapsed', true);

export const options = {
  noConnectionReuse: true,
  systemTags: ['proto', 'subproto', 'status', 'method', 'url', 'name', 'group', 'check',
    'error', 'error_code', 'tls_version', 'scenario', 'service', 'expected_response', 'vu'],
  scenarios: role === 'health' ? {
    health: { executor: 'constant-arrival-rate', exec: 'probe', rate: 1, timeUnit: '1s',
      duration: '100s', preAllocatedVUs: 4, maxVUs: 4, gracefulStop: '1s' },
  } : {
    blocked: { executor: 'constant-vus', exec: 'blocked', vus: clients,
      duration: '60s', gracefulStop: '0s' },
  },
  thresholds: role === 'health' ? {
    health_completed: ['count>=100', 'count<=101'],
    health_elapsed: ['max<=1000'],
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    dropped_iterations: ['count==0'],
  } : { checks: ['rate==1'] },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const response = http.get(`${base}/healthy`, { timeout: '1s', redirects: 0 });
  if (response.status !== 200 || response.body !== 'healthy') fail('Fixture is not healthy');
  console.log(`READY ${role}`);
}

export function probe() {
  const started = Date.now();
  const response = http.get(`${base}/healthy`, { timeout: '1s', redirects: 0 });
  const duration = Date.now() - started;
  elapsed.add(duration);
  completed.add(1);
  const valid = check(response, {
    'healthy status': r => r.status === 200,
    'complete healthy body': r => r.body === 'healthy',
    'complete call within one second': () => duration <= 1000,
  });
  if (!valid) {
    console.error(JSON.stringify({ event: 'health-failure', timestamp: Date.now(),
      elapsed_ms: duration, status: response.status, error: response.error,
      error_code: response.error_code, body: response.body?.slice(0, 128) }));
  }
}

export function blocked() {
  const response = http.get(`${base}/blocked`, { timeout: '10s', redirects: 0 });
  if (!check(response, { 'blocked request completed correctly':
      r => r.status === 200 && ['elapsed', 'released'].includes(r.body) })) {
    console.error(JSON.stringify({ event: 'blocked-failure', timestamp: Date.now(),
      status: response.status, error: response.error, error_code: response.error_code }));
  }
}
