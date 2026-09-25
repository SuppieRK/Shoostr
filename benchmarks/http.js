import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';

const base = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const workload = __ENV.WORKLOAD || 'plaintext';
const routeGroups = Number(__ENV.ROUTE_GROUPS || 0);
if (!Number.isInteger(routeGroups) || routeGroups < 0) throw new Error('Invalid ROUTE_GROUPS');
const chunk = 'x'.repeat(1023) + '\n';
const fixtures = {
  plaintext: ['Hello, World!', 'text/plain;charset=utf-8'],
  'json-bytes': ['{"message":"Hello, World!"}', 'application/json'],
  echo: [chunk, 'application/octet-stream'],
  stream: [chunk.repeat(16), 'application/octet-stream'],
};
if (routeGroups > 0) {
  Object.assign(fixtures, {
    'route-literal': ['Hello, World!', 'text/plain;charset=utf-8', 200],
    'route-parameter': ['order-42', 'text/plain; charset=utf-8', 200],
    'not-found': ['Not found', 'text/plain; charset=utf-8', 404],
    'wrong-method': ['Method not allowed', 'text/plain; charset=utf-8', 405],
  });
}
if (!fixtures[workload]) throw new Error(`Unknown workload: ${workload}`);
const expectedStatuses = Object.fromEntries(Object.entries(fixtures)
  .map(([name, fixture]) => [name, http.expectedStatuses(fixture[2] || 200)]));

export const options = {
  scenarios: {
    requests: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 1000),
      timeUnit: '1s',
      duration: __ENV.DURATION || '180s',
      preAllocatedVUs: Number(__ENV.VUS || 64),
      gracefulStop: '5s',
    },
  },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    dropped_iterations: ['count==0'],
    'http_req_duration{scenario:requests}': [],
    'http_reqs{scenario:requests}': [],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const response = send(workload, exec.scenario.iterationInTest);
  check(response, {
    'status matches': r => r.status === (fixtures[workload][2] || 200),
    'body matches': r => r.body === fixtures[workload][0],
    'content type matches': r => contentType(r) === fixtures[workload][1].replace(/;\s*/g, ';'),
    'Allow matches': r => workload !== 'wrong-method' || allowedMethods(r) === 'GET,POST',
  });
}

export function setup() {
  if (http.get(`${base}/virtual`).body !== 'true') fail('Expected virtual-thread handler execution');
  for (const name of Object.keys(fixtures)) {
    const response = send(name);
    if (response.status !== (fixtures[name][2] || 200) || response.body !== fixtures[name][0]
        || contentType(response) !== fixtures[name][1].replace(/;\s*/g, ';')) {
      fail(`Fixture parity failed: ${name}`);
    }
    const headers = Object.keys(response.headers).sort().join(',');
    // k6's Go transport removes Transfer-Encoding after decoding chunked bodies.
    const expectedHeaders = name === 'stream'
      ? ['Content-Type', 'Date'] : ['Content-Type', 'Date', 'Content-Length'];
    if (name === 'wrong-method') expectedHeaders.push('Allow');
    if (headers !== expectedHeaders.sort().join(',')
        || !response.headers.Date || Number.isNaN(Date.parse(response.headers.Date))
        || (name !== 'stream' && Number(response.headers['Content-Length']) !== fixtures[name][0].length)) {
      fail(`Response header parity failed: ${name}: ${headers}`);
    }
    if (name === 'wrong-method' && allowedMethods(response) !== 'GET,POST') {
      fail('Expected complete allowed-method union');
    }
  }
}

function contentType(response) {
  return (response.headers['Content-Type'] || '').replace(/;\s*/g, ';');
}

function allowedMethods(response) {
  return (response.headers.Allow || '').split(',').map(method => method.trim()).sort().join(',');
}

function send(name, iteration = 0) {
  const params = {
    timeout: '5s', redirects: 0,
    responseCallback: expectedStatuses[name],
    tags: { name },
  };
  if (name === 'route-literal' || name === 'route-parameter'
      || name === 'not-found' || name === 'wrong-method') {
    const group = (iteration * 977) % routeGroups;
    const prefix = `${base}/api/resources/${group}/orders`;
    if (name === 'wrong-method') return http.del(`${prefix}/latest`, null, params);
    const suffix = name === 'not-found' ? '/missing/extra'
      : name === 'route-parameter' ? '/order-42' : '/latest';
    return http.get(prefix + suffix, params);
  }
  return name === 'echo'
    ? http.post(`${base}/echo`, chunk, params)
    : http.get(`${base}/${name}`, params);
}
