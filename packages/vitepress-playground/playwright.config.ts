import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e',
  testMatch: '**/*.e2e.ts',
  timeout: 30_000,
  fullyParallel: false,
  use: {
    baseURL: 'http://127.0.0.1:14173',
    trace: 'retain-on-failure',
    channel: 'chromium',
    ...devices['Desktop Chrome'],
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      command: 'node tests/e2e/start-api.mjs',
      url: 'http://127.0.0.1:18080/health',
      reuseExistingServer: true,
      timeout: 30_000,
    },
    {
      command: 'npm run e2e:fixture',
      url: 'http://127.0.0.1:14173',
      reuseExistingServer: true,
      timeout: 30_000,
    },
  ],
})
