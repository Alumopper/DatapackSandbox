import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const repositoryRoot = resolve(import.meta.dirname, '..')
const output = resolve(process.argv[2] ?? resolve(repositoryRoot, 'build', 'browser-profiles'))
const source = await readFile(resolve(repositoryRoot, 'core/src/main/kotlin/moe/afox/dpsandbox/core/VersionProfile.kt'), 'utf8')
const start = source.indexOf('val commonRoots =')
const end = source.indexOf('val minecraft1204 = CommandProfile', start)
if (start < 0 || end < 0) throw new Error('Unable to locate the JVM command catalog')
const roots = [...source.slice(start, end).matchAll(/"([a-z0-9-]+)"/g)].map((match) => match[1])
const pattern = /val\s+\w+\s*=\s*profile\(\s*"([^"]+)",\s*java\s*=\s*(\d+),\s*data\s*=\s*(\d+),\s*pack\s*=\s*"([^"]+)"/g
const profiles = {}
for (const match of source.matchAll(pattern)) {
  const [, id, javaMajor, dataVersion, dataPackFormat] = match
  profiles[id] = {
    id,
    javaMajor: Number(javaMajor),
    dataVersion: Number(dataVersion),
    dataPackFormat,
    commandRoots: id === '1.20.4' ? roots : [...roots, 'transfer'].sort(),
  }
}
if (!profiles['26.2']) throw new Error('Generated profile catalog is missing 26.2')
await mkdir(output, { recursive: true })
for (const [id, profile] of Object.entries(profiles)) {
  await writeFile(resolve(output, `${id}.json`), `${JSON.stringify(profile)}\n`)
}
await writeFile(resolve(output, 'index.json'), `${JSON.stringify({ default: '26.2', profiles: Object.fromEntries(Object.keys(profiles).map((id) => [id, `${id}.json`])) }, null, 2)}\n`)
