import { check, sleep } from 'k6';
import { WebSocket } from 'k6/websockets';

const base = __ENV.BASE_URL || 'ws://127.0.0.1:8080';
const payload = new Uint8Array(65536);
payload.fill(17);
payload[65535] = 23;

export const options = {
  scenarios: {
    slow_sessions: {
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
    for (let index = 0; index < 16; index++) socket.send(payload.buffer);
  });
  socket.addEventListener('message', event => {
    const bytes = event.data instanceof ArrayBuffer ? new Uint8Array(event.data) : null;
    check(bytes !== null && bytes.length === 65536 && bytes[0] === 17 && bytes[65535] === 23,
      { 'large echo payload matches': valid => valid });
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
