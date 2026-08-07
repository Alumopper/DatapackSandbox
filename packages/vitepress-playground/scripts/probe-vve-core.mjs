import { readFile } from 'node:fs/promises'
import { performance } from 'node:perf_hooks'
import { resolve } from 'node:path'
import { unzipSync } from 'fflate'
import { createSession } from '../.generated/datapack-sandbox-core.js'
import { BrowserSandboxEngine } from '../.generated/kotlin/datapack-sandbox-browser-runtime.mjs'

const sourceRoot = resolve(import.meta.dirname, '../../../build/vve3-evaluation')
const packs = ['math3', 'math3-lalib', 'math3-gelib', 'vve3-runtime', 'vve-demo-pack']
const session = createSession('26.2', 2_000_000, 100_000, 64_000_000)
for (const pack of packs) {
  const archive = unzipSync(new Uint8Array(await readFile(resolve(sourceRoot, `../../../vve_doc/docs/public/vve-packs/${pack}.dpspack`))))
  for (const [path, bytes] of Object.entries(archive)) {
    if (path.startsWith('data/') || path === 'pack.mcmeta') session.upsertDatapackEntry(path, new TextDecoder().decode(bytes))
  }
}

const commands = [
  'function math:_init',
  'function math:_init_la',
  'function math:_init_ge',
  'function math:particles/_load_1214',
  'function vve:_init',
  'function vve_examples:_consts',
  'function vve_examples:dice_6/init',
  'function vve_examples:dice_simulator/init',
  'function vve_demo:start',
]
session.beginExecution()
commands.forEach((command, index) => {
  const outcome = JSON.parse(session.executeLineSafe(command, index + 1))
  if (!outcome.ok) throw new Error(`${command}: ${JSON.stringify(outcome)}`)
})
let result = JSON.parse(session.finishExecution())
if (!process.argv.includes('--quiet')) printState('tick=0', result.snapshot)
if (process.argv.includes('--sizes')) console.log(`renderSnapshotBytes=${new TextEncoder().encode(session.renderSnapshot()).length}`)
const sceneProbe = (process.argv.includes('--scene') || process.argv.includes('--geometry') || process.argv.includes('--compile-every1') || process.argv.includes('--compile-every2') || process.argv.includes('--compile-every3'))
  ? new BrowserSandboxEngine('26.2', '', '', '', '', 16_000_000, 16_000_000, 500_000, 100_000, 4096, 4096, 8, 16_000_000, 120, 64_000_000)
  : undefined
if (sceneProbe && process.argv.includes('--geometry')) {
  for (const relative of ['assets/dice/items/d6.json', 'assets/dice/models/d6.json']) {
    sceneProbe.upsertRenderAsset(relative, await readFile(resolve(sourceRoot, 'vve3-rp', relative), 'utf8'))
  }
  const opaque = new Int8Array([127, 31, -1, -1])
  sceneProbe.upsertTexture('dice:item/d6_regular/d601', 1, 1, opaque)
  sceneProbe.upsertTexture('dice:item/d6_regular/number01', 1, 1, opaque)
}
if (sceneProbe && !process.argv.includes('--quiet')) printScene('scene=0', result.snapshot)
if (process.argv.includes('--manual')) {
  for (const [label, command] of [
    ['get', 'execute as @e[tag=vve_demo_dice,limit=1] run function vve_examples:dice_6/_get'],
    ['motion', 'execute as 0-0-0-0-0 run function vve:object/_iter_motion'],
    ['contacts', 'execute as 0-0-0-0-0 run function vve:block/_iter_cpoints_c'],
    ['gravity', 'scoreboard players operation vy int -= vve_gravity int'],
    ['shift', 'execute if score shift_response int matches 1 run function vve:object/_apply_shift'],
    ['impulse', 'execute if score impulse_response int matches 1 run function vve:object/_apply_impulse_f'],
    ['friction', 'function vve:object/_apply_friction'],
    ['store', 'execute as @e[tag=vve_demo_dice,limit=1] run function vve_examples:dice_6/_store'],
  ]) {
    session.beginExecution()
    const outcome = JSON.parse(session.executeLineSafe(command, 1))
    if (!outcome.ok) throw new Error(`${command}: ${JSON.stringify(outcome)}`)
    result = JSON.parse(session.finishExecution())
    printInternals(label, result.snapshot)
  }
  process.exit(0)
}
const maximumTick = process.argv.includes('--short') ? 42 : 160
const tickBatch = process.argv.includes('--batch2') ? 2 : 1
const timings = []
for (let tick = tickBatch; tick <= maximumTick; tick += tickBatch) {
  const started = performance.now()
  result = JSON.parse(process.argv.includes('--compact')
    ? session.runRealtimeTicksCompact(tickBatch, null)
    : session.runRealtimeTicks(tickBatch, null))
  if (process.argv.includes('--render-every2') && tick % 2 === 0) session.renderSnapshot()
  if (process.argv.includes('--render-every3') && tick % 3 === 0) session.renderSnapshot()
  if (process.argv.includes('--compile-every2') && tick % 2 === 0) {
    sceneProbe.replaceSnapshot(session.renderSnapshot())
    sceneProbe.compileRealtimeScene(960, 540)
  }
  if (process.argv.includes('--compile-every1')) {
    sceneProbe.replaceSnapshot(session.renderSnapshot())
    sceneProbe.compileRealtimeScene(960, 540)
  }
  if (process.argv.includes('--compile-every3') && tick % 3 === 0) {
    sceneProbe.replaceSnapshot(session.renderSnapshot())
    sceneProbe.compileRealtimeScene(960, 540)
  }
  const elapsed = performance.now() - started
  timings.push(elapsed / tickBatch)
  if (process.argv.includes('--all')) console.log(`sample=${tick} ms=${elapsed.toFixed(3)} commands=${result.commands} outputs=${result.outputs?.length ?? 0}`)
  if (!process.argv.includes('--quiet') && [1, 5, 20, 25, 30, 40, 60, 80, 100, 120, 140, 160].includes(tick)) printState(`tick=${tick} ms=${elapsed.toFixed(3)}`, JSON.parse(session.snapshot()))
  if (process.argv.includes('--landing') && tick >= 50 && tick <= 105) printState(`landing=${tick}`, JSON.parse(session.snapshot()))
  if (process.argv.includes('--scene') && sceneProbe && [11, 30, 80].includes(tick)) printScene(`scene=${tick}`, result.renderSnapshot)
  if (process.argv.includes('--display') && [1, 30, 42].includes(tick)) {
    const die = result.renderSnapshot?.entities?.find((entity) => entity.tags?.includes('vve_demo_dice'))
    console.log(`display=${tick}`, JSON.stringify(die))
  }
}
if (process.argv.includes('--timings')) {
  const sorted = [...timings].sort((left, right) => left - right)
  const percentile = (fraction) => sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * fraction))]
  console.log('timings', JSON.stringify({
    samples: timings.length,
    averageMs: timings.reduce((sum, value) => sum + value, 0) / timings.length,
    p50Ms: percentile(0.5),
    p95Ms: percentile(0.95),
    maximumMs: sorted.at(-1),
  }))
}

function printScene(label, snapshot) {
  const renderSnapshot = snapshot.blocks && snapshot.entities && 'scores' in snapshot
    ? JSON.parse(session.renderSnapshot())
    : snapshot
  sceneProbe.replaceSnapshot(JSON.stringify(renderSnapshot))
  const metadata = JSON.parse(sceneProbe.compileRealtimeScene(960, 540))
  const vertices = sceneProbe.realtimeEntityVertices()
  const diePositions = []
  for (let index = 0; index < vertices.length; index += metadata.vertexStride) {
    if (vertices[index + 1] > 90) diePositions.push([vertices[index], vertices[index + 1], vertices[index + 2]])
  }
  const vertexBounds = diePositions.length ? {
    minimum: [0, 1, 2].map((axis) => Math.min(...diePositions.map((position) => position[axis]))),
    maximum: [0, 1, 2].map((axis) => Math.max(...diePositions.map((position) => position[axis]))),
    count: diePositions.length,
  } : undefined
  console.log(label, JSON.stringify({ camera: metadata.camera, bounds: metadata.bounds, vertexBounds, entities: renderSnapshot.entities.map((entity) => ({ type: entity.type, tags: entity.tags, position: entity.position ?? [entity.x, entity.y, entity.z] })) }))
}

function printInternals(label, snapshot) {
  const names = [
    'x', 'y', 'z', 'vx', 'vy', 'vz', 'angular_x', 'angular_y', 'angular_z',
    'shift_response', 'impulse_response', 'couple_response', 'grab_layer_response',
    'grab_layer_receiver_v_norm', 'grab_layer_regular_v',
  ]
  console.log(label, JSON.stringify(Object.fromEntries(names.map((name) => [name, score(snapshot, name, 'int')]))))
}

function printState(label, snapshot) {
  const die = snapshot.entities.find((entity) => entity.tags?.includes('vve_demo_dice'))
  if (!die) throw new Error('VVE die is missing')
  if (process.argv.includes('--entities')) {
    console.log('entities', JSON.stringify(snapshot.entities.map((entity) => ({ uuid: entity.uuid, type: entity.type, position: entity.position ?? [entity.x, entity.y, entity.z], tags: entity.tags }))))
  }
  const scores = Object.fromEntries(['x', 'y', 'z', 'vx', 'vy', 'vz', 'angular_x', 'angular_y', 'angular_z'].map((objective) => [objective, score(snapshot, die.uuid, objective)]))
  const responses = Object.fromEntries(['shift_response', 'impulse_response', 'grab_layer_response'].map((objective) => [objective, score(snapshot, 'int', objective)]))
  console.log(label, JSON.stringify({ position: die.position, scores, responses, tags: die.tags }))
}

function score(snapshot, target, objective) {
  const scores = snapshot.scores?.[objective]
  if (Array.isArray(scores)) return scores.find((entry) => entry.target === target)?.value ?? 0
  if (scores && typeof scores === 'object') return scores[target] ?? 0
  return 0
}
