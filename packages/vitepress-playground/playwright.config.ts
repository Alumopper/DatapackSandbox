import { defineConfig, devices } from '@playwright/test'

const executablePath = process.env.PLAYWRIGHT_EXECUTABLE_PATH
const e2ePort = Number(process.env.DPS_PLAYGROUND_E2E_PORT ?? 14173)
const e2eUrl = `http://127.0.0.1:${e2ePort}`
const consumerE2ePort = Number(process.env.DPS_PLAYGROUND_CONSUMER_E2E_PORT ?? 14174)
const consumerE2eUrl = `http://127.0.0.1:${consumerE2ePort}/datapack-index/`

export default defineConfig({
  testDir: './tests/e2e',
  testMatch: '**/*.e2e.ts',
  timeout: 30_000,
  fullyParallel: false,
  use: {
    baseURL: e2eUrl,
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        launchOptions: executablePath ? { executablePath } : undefined,
      },
    },
    {
      name: 'firefox',
      use: {
        ...devices['Desktop Firefox'],
        launchOptions: {
          firefoxUserPrefs: {
            'webgl.disabled': false,
            'webgl.force-enabled': true,
          },
        },
      },
    },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
  ],
  webServer: [
    {
      command: 'npm run e2e:fixture',
      url: e2eUrl,
      reuseExistingServer: true,
      timeout: 30_000,
    },
    {
      command: 'npm run e2e:consumer',
      url: consumerE2eUrl,
      reuseExistingServer: true,
      timeout: 30_000,
    },
  ],
})
