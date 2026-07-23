import { readdir, readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { brotliCompressSync, constants } from 'node:zlib'

const assets = resolve(import.meta.dirname, '..', 'dist', 'assets')
const workerName = (await readdir(assets)).find((name) => name.startsWith('worker-') && name.endsWith('.js'))
if (!workerName) throw new Error('Production build did not emit a Worker asset')
const workerBytes = await readFile(resolve(assets, workerName))
const profileNames = (await readdir(resolve(assets, '..')))
  .filter((name) => /^(?:1\.|26\.).+\.js$/.test(name))
const candidates = [workerBytes, ...(await Promise.all(profileNames.map((name) => readFile(resolve(assets, '..', name)))))]
const compressedSizes = candidates.map((bytes) =>
  brotliCompressSync(bytes, {
    params: { [constants.BROTLI_PARAM_QUALITY]: 11 },
  }).length,
)
const compressed = compressedSizes[0] + Math.max(0, ...compressedSizes.slice(1))
const maximum = 4 * 1024 * 1024
if (compressed > maximum) throw new Error(`Worker/profile Brotli total ${compressed} exceeds ${maximum} bytes`)
console.log(`Worker/profile Brotli total: ${compressed} bytes (limit ${maximum})`)
