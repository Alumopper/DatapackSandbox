import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const root = fileURLToPath(new URL('../../../../', import.meta.url))
const executable = path.join(
  root,
  'playground-api',
  'build',
  'install',
  'playground-api',
  'bin',
  process.platform === 'win32' ? 'playground-api.bat' : 'playground-api',
)
const child = spawn(executable, [], {
  cwd: root,
  shell: process.platform === 'win32',
  stdio: 'inherit',
  env: {
    ...process.env,
    DPS_CLI_JAR: path.join(root, 'cli', 'build', 'libs', 'datapack-sandbox-cli.jar'),
    DPS_HOST: '127.0.0.1',
    DPS_PORT: '18080',
    DPS_ALLOWED_ORIGINS: 'http://127.0.0.1:14173',
    DPS_ALLOWED_PROFILES: '26.2',
    DPS_IDLE_TIMEOUT_MS: '30000',
  },
})

const stop = () => child.kill('SIGTERM')
process.on('SIGINT', stop)
process.on('SIGTERM', stop)
child.on('exit', (code) => process.exit(code ?? 0))
