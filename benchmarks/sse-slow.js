import { check, sleep } from 'k6';
import sse from 'k6/x/sse';

const base = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const payload = 'x'.repeat(65536);

export const options = {
  scenarios: {
    slow_streams: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 10),
      timeUnit: '1s',
      duration: __ENV.DURATION || '180s',
      preAllocatedVUs: Number(__ENV.VUS || 64),
      gracefulStop: '5s',
    },
  },
  thresholds: {
    checks: ['rate==1'],
    dropped_iterations: ['count==0'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  let received = 0;
  const response = sse.open(`${base}/sse-slow`, { method: 'GET' }, client => {
    client.on('event', event => {
      check(event.data === `${received}:${payload}`, { 'large event payload matches': valid => valid });
      received++;
      sleep(0.05);
      if (received === 16) client.close();
    });
    client.on('error', () => {
      check(false, { 'SSE stream has no error': valid => valid });
    });
  });
  check(response, { 'SSE response is 200': value => value && value.status === 200 });
  check(received === 16, { 'all large events arrive': value => value });
}
