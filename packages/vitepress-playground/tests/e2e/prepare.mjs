import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../../../../', import.meta.url))
const executable = process.platform === 'win32' ? 'gradlew.bat' : './gradlew'
const result = spawnSync(executable, [':cli:fatJar', ':playground-api:installDist'], {
  cwd: root,
  shell: process.platform === 'win32',
  stdio: 'inherit',
})

if (result.status !== 0) process.exit(result.status ?? 1)
