import request, { setup, options as baseline } from './http.js';
import exec from 'k6/execution';

export { setup };

const rate = Number(__ENV.RATE || 20000);
const rampSeconds = 10;

export const options = {
  ...baseline,
  scenarios: {
    requests: {
      executor: 'ramping-arrival-rate',
      startRate: 1000,
      timeUnit: '1s',
      preAllocatedVUs: Number(__ENV.VUS || 256),
      stages: [
        { target: rate, duration: `${rampSeconds}s` },
        { target: rate, duration: __ENV.DURATION || '180s' },
      ],
      gracefulStop: '5s',
    },
  },
  thresholds: {
    ...baseline.thresholds,
    'http_req_duration{phase:steady}': [],
    'http_reqs{phase:steady}': [],
    'http_req_failed{phase:steady}': ['rate==0'],
    'checks{phase:steady}': ['rate==1'],
    'iteration_duration{phase:steady}': [],
  },
};

export default function () {
  exec.vu.metrics.tags.phase = Date.now() - exec.scenario.startTime >= rampSeconds * 1000 ? 'steady' : 'ramp';
  request();
}
