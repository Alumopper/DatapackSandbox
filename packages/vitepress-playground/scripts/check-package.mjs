import { spawnSync } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const packageRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const manifest = JSON.parse(await readFile(resolve(packageRoot, 'package.json'), 'utf8'))

const expectedMetadata = {
  license: 'MIT',
  repository: 'git+https://github.com/Alumopper/DatapackSandbox.git',
  access: 'public',
  registry: 'https://registry.npmjs.org/',
}
const actualMetadata = {
  license: manifest.license,
  repository: manifest.repository?.url,
  access: manifest.publishConfig?.access,
  registry: manifest.publishConfig?.registry,
}
for (const [field, expected] of Object.entries(expectedMetadata)) {
  if (actualMetadata[field] !== expected) {
    throw new Error(`Invalid package ${field}: expected ${expected}, received ${actualMetadata[field]}`)
  }
}

const npmCli = process.env.npm_execpath
if (!npmCli) throw new Error('npm_execpath is missing; run this check through npm')
const packed = spawnSync(
  process.execPath,
  [npmCli, 'pack', '--dry-run', '--json', '--ignore-scripts'],
  {
    cwd: packageRoot,
    encoding: 'utf8',
    env: { ...process.env, npm_config_loglevel: 'silent' },
  },
)
if (packed.status !== 0) {
  process.stderr.write(packed.stderr)
  throw new Error(`npm pack --dry-run failed with exit code ${packed.status ?? 'unknown'}`)
}

let reports
try {
  reports = JSON.parse(packed.stdout)
} catch (error) {
  throw new Error(`npm pack did not return JSON: ${packed.stdout}`, { cause: error })
}
if (reports.length !== 1) throw new Error(`Expected one packed artifact, received ${reports.length}`)

const report = reports[0]
const paths = report.files.map((file) => file.path)
const required = [
  'LICENSE',
  'README.md',
  'package.json',
  'dist/index.js',
  'dist/index.d.ts',
  'dist/cell.js',
  'dist/cell.d.ts',
  'dist/style.css',
]
const missing = required.filter((path) => !paths.includes(path))
if (missing.length > 0) throw new Error(`Package is missing required files: ${missing.join(', ')}`)

const unexpected = paths.filter(
  (path) => !['LICENSE', 'README.md', 'package.json'].includes(path) && !path.startsWith('dist/'),
)
if (unexpected.length > 0) throw new Error(`Package contains unexpected files: ${unexpected.join(', ')}`)

const workerAssets = paths.filter((path) => /^dist\/assets\/worker-.+\.js$/.test(path))
if (workerAssets.length !== 1) {
  throw new Error(`Expected one bundled Worker asset, received ${workerAssets.length}`)
}

const maximumUnpackedSize = 5 * 1024 * 1024
if (report.unpackedSize > maximumUnpackedSize) {
  throw new Error(`Package unpacked size ${report.unpackedSize} exceeds ${maximumUnpackedSize} bytes`)
}

console.log(
  `Package ${report.name}@${report.version}: ${report.entryCount} files, ${report.size} packed bytes, ${report.unpackedSize} unpacked bytes`,
)
