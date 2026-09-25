import { check } from 'k6';
import { WebSocket } from 'k6/websockets';
import { Trend } from 'k6/metrics';

const base = __ENV.BASE_URL || 'ws://127.0.0.1:8080';
const workload = __ENV.WORKLOAD || 'text';
const count = Number(__ENV.MESSAGES || 16);
if (!['text', 'binary'].includes(workload) || !Number.isInteger(count) || count < 1) {
  throw new Error('Expected WORKLOAD=text|binary and positive MESSAGES');
}

const roundTrip = new Trend('message_round_trip', true);
const binaryPayload = new Uint8Array(128);
binaryPayload.fill(17);
binaryPayload[127] = 23;
const payload = workload === 'text' ? 'x'.repeat(128) : binaryPayload.buffer;

export const options = {
  scenarios: {
    sessions: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 50),
      timeUnit: '1s',
      duration: __ENV.DURATION || '180s',
      preAllocatedVUs: Number(__ENV.VUS || 64),
      gracefulStop: '5s',
    },
  },
  thresholds: {
    checks: ['rate==1'],
    dropped_iterations: ['count==0'],
    ws_sessions: ['count>0'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const socket = new WebSocket(`${base}/ws-${workload}`);
  if (workload === 'binary') socket.binaryType = 'arraybuffer';
  let received = 0;
  let sentAt = 0;
  const timer = setTimeout(() => {
    check(false, { 'session completes before timeout': value => value });
    socket.close();
  }, 5000);

  socket.addEventListener('open', () => send());
  socket.addEventListener('message', event => {
    const valid = workload === 'text'
      ? event.data === payload
      : event.data instanceof ArrayBuffer && event.data.byteLength === 128
        && new Uint8Array(event.data)[0] === 17 && new Uint8Array(event.data)[127] === 23;
    check(valid, { 'echo payload matches': value => value });
    roundTrip.add(Date.now() - sentAt);
    received++;
    if (received === count) {
      clearTimeout(timer);
      socket.close();
    } else {
      send();
    }
  });
  socket.addEventListener('error', () => {
    check(false, { 'websocket has no error': value => value });
  });
  socket.addEventListener('close', () => {
    clearTimeout(timer);
    check(received === count, { 'all messages arrive': value => value });
  });

  function send() {
    sentAt = Date.now();
    socket.send(payload);
  }
}
