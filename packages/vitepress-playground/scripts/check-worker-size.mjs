import { readdir, readFile } from 'node:fs/promises'
import { dirname, relative, resolve } from 'node:path'
import { brotliCompressSync, constants } from 'node:zlib'

const assets = resolve(import.meta.dirname, '..', 'dist', 'assets')
const workerName = (await readdir(assets)).find((name) => name.startsWith('worker-') && name.endsWith('.js'))
if (!workerName) throw new Error('Production build did not emit a Worker asset')

const workerModules = new Map()
await collectWorkerModules(resolve(assets, workerName))
if (workerModules.size !== 1) {
  throw new Error('The published Worker must be self-contained; downstream library consumers do not copy Worker-only dynamic chunks')
}
const workerSource = [...workerModules.values()][0].toString('utf8')
if (/^\s*(?:import|export)\b/m.test(workerSource) || /\bimport\s*(?:\(|\.meta)/.test(workerSource)) {
  throw new Error('The published Worker must remain classic-compatible for the module-Worker startup fallback')
}
const profileNames = (await readdir(resolve(assets, '..')))
  .filter((name) => /^(?:1\.|26\.).+\.js$/.test(name))
const workerCompressed = [...workerModules.values()].reduce((total, bytes) => total + compressedSize(bytes), 0)
const profileCompressed = (await Promise.all(profileNames.map((name) => readFile(resolve(assets, '..', name)))))
  .map(compressedSize)
const compressed = workerCompressed + Math.max(0, ...profileCompressed)
const maximum = 4 * 1024 * 1024
if (compressed > maximum) throw new Error(`Worker/profile Brotli total ${compressed} exceeds ${maximum} bytes`)
console.log(`Worker module graph (${workerModules.size} files) plus largest profile Brotli total: ${compressed} bytes (limit ${maximum})`)

async function collectWorkerModules(path) {
  const normalized = resolve(path)
  if (workerModules.has(normalized)) return
  const outsideAssets = relative(assets, normalized)
  if (outsideAssets.startsWith('..') || resolve(assets, outsideAssets) !== normalized) {
    throw new Error(`Worker imports JavaScript outside its asset directory: ${normalized}`)
  }
  const bytes = await readFile(normalized)
  workerModules.set(normalized, bytes)
  const dependencies = [...bytes.toString('utf8').matchAll(/["'](\.\/[^"']+\.js)["']/g)]
    .map((match) => match[1])
  await Promise.all(dependencies.map((dependency) => collectWorkerModules(resolve(dirname(normalized), dependency))))
}

function compressedSize(bytes) {
  return brotliCompressSync(bytes, {
    params: { [constants.BROTLI_PARAM_QUALITY]: 11 },
  }).length
}
