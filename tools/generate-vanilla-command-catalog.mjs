import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises'
import { dirname, join, relative, resolve } from 'node:path'

const options = parseOptions(process.argv.slice(2))
const reports = resolve(required(options, 'reports'))
const serverData = resolve(required(options, 'server-data'))
const output = resolve(required(options, 'output'))
const version = required(options, 'version')
const serverJarUrl = required(options, 'server-jar-url')
const serverJarSha1 = required(options, 'server-jar-sha1').toLowerCase()

const registries = JSON.parse(await readFile(join(reports, 'registries.json'), 'utf8'))
const commands = JSON.parse(await readFile(join(reports, 'commands.json'), 'utf8'))

const blocks = registryEntries('minecraft:block')
const items = registryEntries('minecraft:item')
const entityTypes = registryEntries('minecraft:entity_type')
const customStats = registryEntries('minecraft:custom_stat')
const gamerules = sorted(Object.keys(commands.children?.gamerule?.children ?? {}))

const catalog = {
  metadata: {
    version,
    serverJarUrl,
    serverJarSha1,
    sourceReports: ['commands.json', 'registries.json', 'generated server data'],
  },
  commandRoots: sorted(Object.keys(commands.children ?? {})),
  blocks,
  items,
  entityTypes,
  biomes: await dataRegistry('worldgen/biome'),
  biomeTags: await taggedDataRegistry('worldgen/biome'),
  damageTypes: await dataRegistry('damage_type'),
  enchantments: await dataRegistry('enchantment'),
  effects: registryEntries('minecraft:mob_effect'),
  dimensions: await dataRegistry('dimension', [
    'minecraft:overworld',
    'minecraft:the_end',
    'minecraft:the_nether',
  ]),
  attributes: registryEntries('minecraft:attribute'),
  particles: registryEntries('minecraft:particle_type'),
  sounds: registryEntries('minecraft:sound_event'),
  customStats,
  gamerules,
  scoreboardCriteria: scoreboardCriteria(blocks, items, entityTypes, customStats),
  advancements: await dataRegistry('advancement'),
  recipes: await dataRegistry('recipe'),
  pointOfInterestTypes: registryEntries('minecraft:point_of_interest_type'),
  pointOfInterestTypeTags: await taggedDataRegistry('point_of_interest_type'),
  structures: await dataRegistry('worldgen/structure'),
  structureTags: await taggedDataRegistry('worldgen/structure'),
  configuredFeatures: await dataRegistry('worldgen/configured_feature'),
  templatePools: await dataRegistry('worldgen/template_pool'),
  testInstances: await dataRegistry('test_instance'),
  worldClocks: await dataRegistry('world_clock'),
  timelines: await dataRegistry('timeline'),
  lootContextTypes: [
    'minecraft:advancement_entity',
    'minecraft:advancement_reward',
    'minecraft:block',
    'minecraft:chest',
    'minecraft:empty',
    'minecraft:entity',
    'minecraft:fishing',
  ],
  advancementTriggers: registryEntries('minecraft:trigger_type'),
  lootConditions: registryEntries('minecraft:loot_condition_type'),
  lootFunctions: registryEntries('minecraft:loot_function_type'),
}

await mkdir(dirname(output), { recursive: true })
await writeFile(output, `${JSON.stringify(catalog, null, 2)}\n`)
console.log(`Wrote ${relative(process.cwd(), output)} for Minecraft ${version}`)
for (const key of Object.keys(catalog).filter((key) => Array.isArray(catalog[key]))) {
  console.log(`${key}: ${catalog[key].length}`)
}

function registryEntries(name) {
  const entries = registries[name]?.entries
  if (!entries) throw new Error(`Registry report does not contain ${name}`)
  return sorted(Object.keys(entries))
}

async function dataRegistry(path, fallback = []) {
  const root = join(serverData, 'data', 'minecraft', ...path.split('/'))
  let files
  try {
    files = await readdir(root, { recursive: true, withFileTypes: true })
  } catch (error) {
    if (error?.code === 'ENOENT' && fallback.length > 0) return sorted(fallback)
    throw error
  }
  const ids = files
    .filter((entry) => entry.isFile() && entry.name.endsWith('.json'))
    .map((entry) => {
      const parent = entry.parentPath ?? entry.path
      const nested = relative(root, join(parent, entry.name)).replaceAll('\\', '/').replace(/\.json$/, '')
      return `minecraft:${nested}`
    })
  return sorted(ids.length > 0 ? ids : fallback)
}

async function taggedDataRegistry(path) {
  return (await dataRegistry(`tags/${path}`, [])).map((id) => `#${id}`)
}

function scoreboardCriteria(blockIds, itemIds, entities, stats) {
  const result = [
    'air',
    'armor',
    'deathCount',
    'dummy',
    'food',
    'health',
    'level',
    'playerKillCount',
    'totalKillCount',
    'trigger',
    'xp',
  ]
  const colors = [
    'black', 'dark_blue', 'dark_green', 'dark_aqua', 'dark_red', 'dark_purple', 'gold', 'gray',
    'dark_gray', 'blue', 'green', 'aqua', 'red', 'light_purple', 'yellow', 'white',
  ]
  for (const color of colors) result.push(`teamkill.${color}`, `killedByTeam.${color}`)
  for (const id of blockIds) result.push(`minecraft.mined:${id}`)
  for (const id of itemIds) {
    for (const kind of ['broken', 'crafted', 'dropped', 'picked_up', 'used']) {
      result.push(`minecraft.${kind}:${id}`)
    }
  }
  for (const id of entities) result.push(`minecraft.killed:${id}`, `minecraft.killed_by:${id}`)
  for (const id of stats) result.push(`minecraft.custom:${id}`)
  return sorted(result)
}

function sorted(values) {
  return [...new Set(values)].sort()
}

function parseOptions(args) {
  const result = {}
  for (let index = 0; index < args.length; index += 2) {
    const key = args[index]?.replace(/^--/, '')
    const value = args[index + 1]
    if (!key || value === undefined) throw new Error(`Expected --name value pairs, got '${args.slice(index).join(' ')}'`)
    result[key] = value
  }
  return result
}

function required(values, key) {
  const value = values[key]
  if (!value) throw new Error(`Missing --${key}`)
  return value
}
