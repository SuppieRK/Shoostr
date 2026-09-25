import { check } from 'k6';
import sse from 'k6/x/sse';
import { Trend } from 'k6/metrics';

const base = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const workload = __ENV.WORKLOAD || 'burst';
if (!['burst', 'paced'].includes(workload)) throw new Error('Expected WORKLOAD=burst|paced');
const payload = 'x'.repeat(128);
const streamDuration = new Trend('sse_stream_duration', true);

export const options = {
  scenarios: {
    streams: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 20),
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
  const start = Date.now();
  const response = sse.open(`${base}/sse-${workload}`, { method: 'GET' }, client => {
    client.on('event', event => {
      check(event.data === `${received}:${payload}`, { 'ordered event payload matches': valid => valid });
      received++;
      if (received === 16) client.close();
    });
    client.on('error', () => {
      check(false, { 'SSE stream has no error': valid => valid });
    });
  });
  streamDuration.add(Date.now() - start);
  check(response, { 'SSE response is 200': value => value && value.status === 200 });
  check(received === 16, { 'all events arrive': value => value });
}
