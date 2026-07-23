import { cp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const here = dirname(fileURLToPath(import.meta.url))
const packageRoot = resolve(here, '..')
const repoRoot = resolve(packageRoot, '..', '..')
const wrapper = resolve(repoRoot, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew')
const result = spawnSync(wrapper, [':browser-runtime:jsBrowserProductionLibraryDistribution'], {
  cwd: repoRoot,
  stdio: 'inherit',
  shell: process.platform === 'win32',
})
if (result.status !== 0) process.exit(result.status ?? 1)

const generated = resolve(packageRoot, '.generated')
const kotlinOutput = resolve(repoRoot, 'browser-runtime', 'build', 'dist', 'js', 'productionLibrary')
await rm(generated, { recursive: true, force: true })
await mkdir(generated, { recursive: true })
await cp(kotlinOutput, resolve(generated, 'kotlin'), { recursive: true })

const versionSource = await readFile(resolve(repoRoot, 'core', 'src', 'main', 'kotlin', 'moe', 'afox', 'dpsandbox', 'core', 'VersionProfile.kt'), 'utf8')
const registrySource = await readFile(resolve(repoRoot, 'core', 'src', 'main', 'kotlin', 'moe', 'afox', 'dpsandbox', 'core', 'RegistryView.kt'), 'utf8')
const commonRootStart = versionSource.indexOf('val commonRoots =')
const commonRootEnd = versionSource.indexOf('val minecraft1204 = CommandProfile', commonRootStart)
if (commonRootStart < 0 || commonRootEnd < 0) throw new Error('Unable to locate the JVM command catalog')
const commonRoots = [...versionSource.slice(commonRootStart, commonRootEnd).matchAll(/"([a-z0-9-]+)"/g)].map((match) => match[1])

function registry(name) {
  const match = registrySource.match(new RegExp(`${name}\\s*=\\s*ids\\(([^)]*)\\)`, 's'))
  if (!match) throw new Error(`Unable to locate JVM registry ${name}`)
  return [...match[1].matchAll(/"([^"]+)"/g)]
    .map((item) => item[1])
    .map((id) => id.includes(':') ? id : `minecraft:${id}`)
    .sort()
}

const registries = {
  blocks: registry('blocks'),
  items: registry('items'),
  entityTypes: registry('entityTypes'),
}
const profilePattern = /val\s+\w+\s*=\s*profile\("([^"]+)",\s*java\s*=\s*(\d+),\s*data\s*=\s*(\d+),\s*pack\s*=\s*"([^"]+)"/g
const profiles = {}
for (const match of versionSource.matchAll(profilePattern)) {
  const [, id, javaMajor, dataVersion, dataPackFormat] = match
  profiles[id] = {
    id,
    javaMajor: Number(javaMajor),
    dataVersion: Number(dataVersion),
    dataPackFormat,
    commandRoots: id === '1.20.4' ? commonRoots : [...commonRoots, 'transfer'].sort(),
    registries,
  }
}
if (!profiles['26.2']) throw new Error('Generated profile catalog is missing 26.2')
const profileDirectory = resolve(generated, 'profiles')
await mkdir(profileDirectory, { recursive: true })
for (const [id, profile] of Object.entries(profiles)) {
  await writeFile(resolve(profileDirectory, `${id}.json`), `${JSON.stringify(profile)}\n`)
}
await writeFile(
  resolve(profileDirectory, 'index.json'),
  `${JSON.stringify({ default: '26.2', profiles: Object.fromEntries(Object.keys(profiles).map((id) => [id, `${id}.json`])) }, null, 2)}\n`,
)
