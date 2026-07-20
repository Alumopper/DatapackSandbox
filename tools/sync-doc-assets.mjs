import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const source = path.join(repositoryRoot, 'schema', 'manifest', 'dps-manifest.schema.json')
const destination = path.join(repositoryRoot, 'docs', 'public', 'dps-manifest.schema.json')

if (!fs.existsSync(source)) {
  throw new Error(`Canonical manifest schema is missing: ${source}`)
}

fs.mkdirSync(path.dirname(destination), { recursive: true })
fs.copyFileSync(source, destination)
console.log(`Synced documentation schema: ${destination}`)
