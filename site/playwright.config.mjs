import { defineConfig } from '@playwright/test';
import process from 'node:process';

const baseURL = `http://127.0.0.1:4322${process.env.BASE_PATH || '/'}`;

export default defineConfig({
  testDir: './tests',
  workers: 1,
  use: {
    browserName: 'firefox',
    baseURL,
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'npm run preview -- --port 4322',
    url: baseURL,
    timeout: 120_000,
    reuseExistingServer: false,
  },
});
