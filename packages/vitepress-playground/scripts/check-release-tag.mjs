import { readFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const packageRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const manifest = JSON.parse(await readFile(resolve(packageRoot, 'package.json'), 'utf8'))
const tag = process.argv[2] ?? process.env.GITHUB_REF_NAME
const expected = `vitepress-playground-v${manifest.version}`

if (!tag) throw new Error('Pass the release tag or set GITHUB_REF_NAME')
if (tag !== expected) {
  throw new Error(`Release tag ${tag} does not match package version; expected ${expected}`)
}

console.log(`Release tag ${tag} matches ${manifest.name}@${manifest.version}`)
