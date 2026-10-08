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
const payloads = Array.from({ length: count }, (_, sequence) => {
  if (workload === 'text') return `${String(sequence).padStart(8, '0')}:${'x'.repeat(119)}`;
  const bytes = new Uint8Array(128);
  for (let offset = 0; offset < bytes.length; offset++) bytes[offset] = (offset * 31 + sequence) % 256;
  new DataView(bytes.buffer).setUint32(0, sequence);
  return bytes;
});

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
    const expected = payloads[received];
    let valid = expected !== undefined;
    if (workload === 'text') {
      valid = valid && event.data === expected;
    } else {
      const bytes = event.data instanceof ArrayBuffer ? new Uint8Array(event.data) : null;
      valid = valid && bytes !== null && bytes.length === expected.length;
      for (let offset = 0; valid && offset < bytes.length; offset++) {
        valid = bytes[offset] === expected[offset];
      }
    }
    check(valid, { 'complete ordered echo payload matches': value => value });
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
    socket.send(workload === 'text' ? payloads[received] : payloads[received].buffer);
  }
}
