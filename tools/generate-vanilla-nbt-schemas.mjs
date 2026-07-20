import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { ErrorReporter, Failure, ResourceLocationNode, Source } from '@spyglassmc/core'
import * as mcdoc from '@spyglassmc/mcdoc'

const [sourceDirArg, outputFileArg, sourceRevisionArg] = process.argv.slice(2)
if (!sourceDirArg || !outputFileArg || !sourceRevisionArg) {
  console.error('Usage: node tools/generate-vanilla-nbt-schemas.mjs <vanilla-mcdoc-source-dir> <output-json> <source-revision>')
  process.exit(2)
}

const sourceDir = path.resolve(sourceDirArg)
const outputFile = path.resolve(outputFileArg)

const structs = new Map()
const aliases = new Map()
const dispatches = []
const parseErrors = []

const files = walk(sourceDir).filter((file) => file.endsWith('.mcdoc')).sort()
if (files.length < 100) {
  console.error(`Refusing to generate an incomplete vanilla NBT schema: found only ${files.length} mcdoc files`)
  process.exit(1)
}
for (const file of files) {
  const text = fs.readFileSync(file, 'utf8')
  const src = new Source(text)
  const ctx = { err: new ErrorReporter(file) }
  const ast = mcdoc.module_(src, ctx)
  const errors = ctx.err.dump()
  if (ast === Failure) {
    parseErrors.push(`${file}: parser returned Failure`)
    continue
  }
  if (errors.length > 0) {
    parseErrors.push(...errors.map((error) => `${file}: ${error.message}`))
  }
  collectTopLevel(ast)
}

if (parseErrors.length > 0) {
  console.error(`mcdoc parser reported ${parseErrors.length} error(s)`)
  parseErrors.slice(0, 20).forEach((error) => console.error(`  - ${error}`))
  process.exit(1)
}

const itemStackFields = new Set([
  ...resolveTypeName('SlottedItem'),
  ...resolveTypeName('ItemStack'),
  'Slot',
  'id',
  'count',
  'components',
])

const entitySchemas = new Map()
const blockEntitySchemas = new Map()
const blockToBlockEntity = new Map()

for (const dispatch of dispatches.filter((entry) => entry.registry === 'entity')) {
  const fields = fieldsForDispatchTarget(dispatch)
  for (const id of dispatch.ids.filter((id) => !id.startsWith('%'))) {
    mergeMapSet(entitySchemas, `minecraft:${id}`, fields)
  }
}

for (const dispatch of dispatches.filter((entry) => entry.registry === 'block_entity')) {
  const fields = fieldsForDispatchTarget(dispatch)
  for (const id of dispatch.ids.filter((id) => !id.startsWith('%'))) {
    mergeMapSet(blockEntitySchemas, `minecraft:${id}`, fields)
  }
}

for (const dispatch of dispatches.filter((entry) => entry.registry === 'block')) {
  const blockIds = dispatch.ids.filter((id) => !id.startsWith('%'))
  if (dispatch.targetRegistry === 'block_entity' && dispatch.targetId) {
    for (const id of blockIds) {
      blockToBlockEntity.set(`minecraft:${id}`, `minecraft:${dispatch.targetId}`)
    }
  } else {
    const fields = fieldsForDispatchTarget(dispatch)
    if (fields.size === 0) continue
    for (const id of blockIds) {
      const schemaId = `minecraft:${id}`
      blockToBlockEntity.set(schemaId, schemaId)
      mergeMapSet(blockEntitySchemas, schemaId, fields)
    }
  }
}

const minimumSchemaCounts = {
  entities: 100,
  blockEntities: 40,
  blockMappings: 100,
}
if (
  entitySchemas.size < minimumSchemaCounts.entities ||
  blockEntitySchemas.size < minimumSchemaCounts.blockEntities ||
  blockToBlockEntity.size < minimumSchemaCounts.blockMappings
) {
  console.error(
    'Refusing to generate an incomplete vanilla NBT schema: ' +
    `entities=${entitySchemas.size}/${minimumSchemaCounts.entities}, ` +
    `blockEntities=${blockEntitySchemas.size}/${minimumSchemaCounts.blockEntities}, ` +
    `blockMappings=${blockToBlockEntity.size}/${minimumSchemaCounts.blockMappings}`,
  )
  process.exit(1)
}

const supportedVersions = [
  '1.20.4',
  '1.20.5',
  '1.20.6',
  '1.21',
  '1.21.1',
  '1.21.2',
  '1.21.3',
  '1.21.4',
  '1.21.5',
  '1.21.6',
  '1.21.7',
  '1.21.8',
  '1.21.9',
  '1.21.10',
  '1.21.11',
  '26.1',
  '26.1.1',
  '26.1.2',
  '26.2',
]
const output = {
  source: 'https://github.com/SpyglassMC/vanilla-mcdoc',
  sourceRevision: sourceRevisionArg,
  parser: '@spyglassmc/mcdoc',
  format: 'mcdoc-nbt-schema-v3',
  fileCount: files.length,
  schemaSets: {
    vanilla: schemaPayload(),
  },
  versions: Object.fromEntries(supportedVersions.map((version) => [version, {
    sourceVersion: version,
    schemaSet: 'vanilla',
  }])),
}

fs.mkdirSync(path.dirname(outputFile), { recursive: true })
fs.writeFileSync(outputFile, `${JSON.stringify(output, null, 2)}\n`, 'utf8')
console.log(
  `Generated ${outputFile} from ${files.length} mcdoc files ` +
  `(entities=${entitySchemas.size}, blockEntities=${blockEntitySchemas.size}, blockMappings=${blockToBlockEntity.size})`,
)

function schemaPayload() {
  return {
    itemStackFields: sorted(itemStackFields),
    entitySchemas: sortedObject(entitySchemas),
    blockEntitySchemas: sortedObject(blockEntitySchemas),
    blockToBlockEntity: sortedStringObject(blockToBlockEntity),
  }
}

function collectTopLevel(moduleNode) {
  for (const node of moduleNode.children ?? []) {
    if (mcdoc.StructNode.is(node)) {
      const { identifier, block } = mcdoc.StructNode.destruct(node)
      if (identifier && block) {
        structs.set(identifier.value, collectStructBlock(block))
      }
    } else if (mcdoc.TypeAliasNode.is(node)) {
      const { identifier, rhs } = mcdoc.TypeAliasNode.destruct(node)
      if (!identifier || !rhs) continue
      if (mcdoc.StructNode.is(rhs)) {
        const { block } = mcdoc.StructNode.destruct(rhs)
        if (block) structs.set(identifier.value, collectStructBlock(block))
      } else if (mcdoc.UnionTypeNode.is(rhs)) {
        structs.set(identifier.value, { fields: collectTypeFields(rhs), spreads: new Set() })
      } else {
        const ref = referenceName(rhs)
        if (ref) aliases.set(identifier.value, ref)
      }
    } else if (mcdoc.DispatchStatementNode.is(node)) {
      const { location, index, target } = mcdoc.DispatchStatementNode.destruct(node)
      if (!location || !index || !target) continue
      const registry = resourceLocationName(location).split(':').at(-1)
      const ids = mcdoc.IndexBodyNode.destruct(index)
        .parallelIndices
        .map(staticIndexName)
        .filter(Boolean)
        .map((id) => id.replace(/^minecraft:/, ''))
      dispatches.push({
        registry,
        ids,
        ...dispatchTarget(target),
      })
    }
  }
}

function dispatchTarget(type) {
  if (mcdoc.DispatcherTypeNode.is(type)) {
    const { location, index } = mcdoc.DispatcherTypeNode.destruct(type)
    const registry = resourceLocationName(location).split(':').at(-1)
    const targetId = mcdoc.IndexBodyNode.destruct(index)
      .parallelIndices
      .map(staticIndexName)
      .find(Boolean)
    return { targetRegistry: registry, targetId: targetId?.replace(/^minecraft:/, '') }
  }
  if (mcdoc.StructNode.is(type)) {
    const { identifier, block } = mcdoc.StructNode.destruct(type)
    if (identifier && block) {
      structs.set(identifier.value, collectStructBlock(block))
      return { targetStruct: identifier.value }
    }
    return { fields: collectTypeFields(type) }
  }
  if (mcdoc.UnionTypeNode.is(type)) {
    return { fields: collectTypeFields(type) }
  }
  const ref = referenceName(type)
  if (ref) return { targetStruct: ref }
  return { fields: collectTypeFields(type) }
}

function fieldsForDispatchTarget(dispatch) {
  const fields = new Set(dispatch.fields ?? [])
  if (dispatch.targetStruct) {
    for (const field of resolveTypeName(dispatch.targetStruct)) fields.add(field)
  }
  return fields
}

function collectTypeFields(type) {
  const fields = new Set()
  if (!type) return fields
  if (mcdoc.StructNode.is(type)) {
    const { identifier, block } = mcdoc.StructNode.destruct(type)
    if (identifier && block) {
      structs.set(identifier.value, collectStructBlock(block))
      for (const field of resolveTypeName(identifier.value)) fields.add(field)
    } else if (block) {
      const def = collectStructBlock(block)
      for (const field of def.fields) fields.add(field)
      for (const spread of def.spreads) {
        for (const field of resolveTypeName(spread)) fields.add(field)
      }
    }
  } else if (mcdoc.UnionTypeNode.is(type)) {
    for (const member of mcdoc.UnionTypeNode.destruct(type).members) {
      for (const field of collectTypeFields(member)) fields.add(field)
    }
  } else {
    const ref = referenceName(type)
    if (ref) {
      for (const field of resolveTypeName(ref)) fields.add(field)
    }
  }
  return fields
}

function collectStructBlock(block) {
  const fields = new Set()
  const spreads = new Set()
  for (const field of mcdoc.StructBlockNode.destruct(block).fields) {
    if (mcdoc.StructPairFieldNode.is(field)) {
      const { key } = mcdoc.StructPairFieldNode.destruct(field)
      const name = structKeyName(key)
      if (name) fields.add(name)
    } else if (mcdoc.StructSpreadFieldNode.is(field)) {
      const { type } = mcdoc.StructSpreadFieldNode.destruct(field)
      const ref = referenceName(type)
      if (ref) {
        spreads.add(ref)
      } else {
        for (const nested of collectTypeFields(type)) fields.add(nested)
      }
    }
  }
  return { fields, spreads }
}

function resolveTypeName(rawName, seen = new Set()) {
  const name = normalizeTypeName(rawName)
  if (!name || seen.has(name)) return new Set()
  const target = normalizeTypeName(aliases.get(name) ?? name)
  if (!target || seen.has(target)) return new Set()
  const def = structs.get(target)
  if (!def) return new Set()
  const fields = new Set(def.fields)
  const nextSeen = new Set([...seen, target])
  for (const spread of def.spreads) {
    for (const field of resolveTypeName(spread, nextSeen)) fields.add(field)
  }
  return fields
}

function referenceName(type) {
  if (mcdoc.ReferenceTypeNode.is(type)) {
    const { path: pathNode } = mcdoc.ReferenceTypeNode.destruct(type)
    return pathName(pathNode)
  }
  return undefined
}

function pathName(pathNode) {
  const { lastIdentifier, children } = mcdoc.PathNode.destruct(pathNode)
  return normalizeTypeName(lastIdentifier?.value ?? children?.at(-1)?.value)
}

function structKeyName(key) {
  if (!key) return undefined
  if (mcdoc.StructMapKeyNode.is(key)) return undefined
  return key.value
}

function staticIndexName(node) {
  if (!node) return undefined
  if (ResourceLocationNode.is(node)) return resourceLocationName(node)
  return node.value
}

function resourceLocationName(node) {
  const namespace = node.namespace ?? 'minecraft'
  const pathValue = Array.isArray(node.path) ? node.path.join('/') : node.path
  return `${namespace}:${pathValue}`
}

function normalizeTypeName(value) {
  if (!value) return undefined
  return String(value).replace(/^super::/, '').split('::').at(-1).split('<')[0].trim()
}

function mergeMapSet(map, key, values) {
  const existing = map.get(key) ?? new Set()
  for (const value of values) existing.add(value)
  map.set(key, existing)
}

function sorted(values) {
  return [...values].sort((a, b) => a.localeCompare(b))
}

function sortedObject(map) {
  return Object.fromEntries([...map.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([key, values]) => [key, sorted(values)]))
}

function sortedStringObject(map) {
  return Object.fromEntries([...map.entries()].sort(([a], [b]) => a.localeCompare(b)))
}

function walk(dir) {
  const entries = fs.readdirSync(dir, { withFileTypes: true })
  return entries.flatMap((entry) => {
    const full = path.join(dir, entry.name)
    return entry.isDirectory() ? walk(full) : [full]
  })
}

export const __filename = fileURLToPath(import.meta.url)
