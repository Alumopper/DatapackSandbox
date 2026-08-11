import { execFile } from 'node:child_process'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve, sep } from 'node:path'
import { promisify } from 'node:util'
import { fileURLToPath } from 'node:url'

const run = promisify(execFile)
const consumerRoot = dirname(fileURLToPath(import.meta.url))
const packageRoot = resolve(consumerRoot, '../..')
const fixtureNodeModules = resolve(consumerRoot, 'fixture/node_modules')
const npmCli = process.env.npm_execpath

if (!npmCli) {
  throw new Error('npm_execpath is required; run this script from an npm script')
}

const stagingRoot = await mkdtemp(join(tmpdir(), 'dps-playground-consumer-'))

try {
  await rm(fixtureNodeModules, { recursive: true, force: true })
  const { stdout } = await run(
    process.execPath,
    [npmCli, 'pack', '--json', '--pack-destination', stagingRoot],
    { cwd: packageRoot, maxBuffer: 10 * 1024 * 1024 },
  )
  const packed = JSON.parse(stdout)
  if (packed.length !== 1 || typeof packed[0]?.filename !== 'string') {
    throw new Error(`Expected npm pack to produce one tarball, received: ${stdout}`)
  }

  const tarball = resolve(stagingRoot, packed[0].filename)
  if (!tarball.startsWith(`${stagingRoot}${sep}`)) {
    throw new Error(`Refusing to install a tarball outside the staging directory: ${tarball}`)
  }

  await run(
    process.execPath,
    [
      npmCli,
      'install',
      '--workspaces=false',
      '--package-lock=false',
      '--no-save',
      '--prefix',
      consumerRoot,
      tarball,
    ],
    { cwd: consumerRoot, maxBuffer: 10 * 1024 * 1024 },
  )
  await rm(resolve(consumerRoot, 'node_modules/.vite'), { recursive: true, force: true })
} finally {
  await rm(stagingRoot, { recursive: true, force: true })
}
