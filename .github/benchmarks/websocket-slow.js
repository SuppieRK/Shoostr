import { check, sleep } from 'k6';
import { WebSocket } from 'k6/websockets';

const base = __ENV.BASE_URL || 'ws://127.0.0.1:8080';
const payloads = Array.from({ length: 16 }, (_, sequence) => {
  const bytes = new Uint8Array(65536);
  for (let offset = 0; offset < bytes.length; offset++) bytes[offset] = (offset * 31 + sequence) % 256;
  new DataView(bytes.buffer).setUint32(0, sequence);
  return bytes;
});

export const options = {
  scenarios: {
    slow_sessions: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 1),
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
  const socket = new WebSocket(`${base}/ws-binary`);
  socket.binaryType = 'arraybuffer';
  let received = 0;
  const timer = setTimeout(() => {
    check(false, { 'session completes before timeout': valid => valid });
    socket.close();
  }, 5000);

  socket.addEventListener('open', () => {
    for (const payload of payloads) socket.send(payload.buffer);
  });
  socket.addEventListener('message', event => {
    const bytes = event.data instanceof ArrayBuffer ? new Uint8Array(event.data) : null;
    const expected = payloads[received];
    let valid = expected !== undefined && bytes !== null && bytes.length === expected.length;
    for (let offset = 0; valid && offset < bytes.length; offset++) {
      valid = bytes[offset] === expected[offset];
    }
    check(valid, { 'complete ordered large echo payload matches': value => value });
    received++;
    sleep(0.05);
    if (received === 16) {
      clearTimeout(timer);
      socket.close();
    }
  });
  socket.addEventListener('error', () => {
    check(false, { 'websocket has no error': valid => valid });
  });
  socket.addEventListener('close', () => {
    clearTimeout(timer);
    check(received === 16, { 'all large messages arrive': valid => valid });
  });
}
