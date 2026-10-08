import request, { setup, options as baseline } from './http.js';
import exec from 'k6/execution';

export { setup };

const rate = Number(__ENV.RATE || 20000);
const rampSeconds = 10;
const smoke = __ENV.MODE === 'smoke';

export const options = smoke ? smokeOptions() : {
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
  if (!smoke) {
    exec.vu.metrics.tags.phase = Date.now() - exec.scenario.startTime >= rampSeconds * 1000 ? 'steady' : 'ramp';
  }
  request();
}

function smokeOptions() {
  const rates = (__ENV.SMOKE_RATES || '1000,2000,5000,10000,20000,50000,100000')
    .split(',').map(value => Number(value));
  const seconds = Number(__ENV.SMOKE_STEP_SECONDS || 15);
  const vus = Number(__ENV.VUS || 256);
  if (!Number.isSafeInteger(seconds) || seconds <= 0
      || !Number.isSafeInteger(vus) || vus <= 0
      || rates.some((value, index) => !Number.isSafeInteger(value) || value <= 0
        || (index > 0 && value <= rates[index - 1]))) {
    throw new Error('Smoke requires increasing positive integer SMOKE_RATES and positive integer SMOKE_STEP_SECONDS/VUS');
  }

  const scenarios = {};
  const thresholds = {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    dropped_iterations: ['count==0'],
  };
  rates.forEach((offered, index) => {
    const name = `step_${offered}`;
    scenarios[name] = {
      executor: 'constant-arrival-rate',
      rate: offered,
      timeUnit: '1s',
      duration: `${seconds}s`,
      startTime: `${index * (seconds + 5)}s`,
      preAllocatedVUs: vus,
      maxVUs: vus,
      gracefulStop: '5s',
      tags: { offered_rate: String(offered) },
    };
    // Native scenario tags also cover executor-generated drops, unlike VU-only tags.
    thresholds[`checks{scenario:${name}}`] = ['rate==1'];
    thresholds[`http_req_failed{scenario:${name}}`] = ['rate==0'];
    thresholds[`dropped_iterations{scenario:${name}}`] = ['count==0'];
    thresholds[`http_reqs{scenario:${name}}`] = [];
    thresholds[`http_req_duration{scenario:${name}}`] = [];
    thresholds[`iteration_duration{scenario:${name}}`] = [];
  });
  return { ...baseline, scenarios, thresholds };
}
