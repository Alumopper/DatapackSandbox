/// <reference lib="webworker" />
/// <reference types="vite/client" />

import { Unzip, UnzipInflate, zlibSync } from 'fflate'
import { BrowserSandboxEngine } from '../.generated/kotlin/datapack-sandbox-browser-runtime.mjs'
import type {
  PlaygroundBrowserLimits,
  PlaygroundEvent,
  PlaygroundImportEntry,
  PlaygroundImportKind,
  PlaygroundOutputEvent,
  PlaygroundPlayerInput,
  PlaygroundRenderOptions,
  PlaygroundSceneBatch,
  PlaygroundViewportScene,
} from './types'

interface GeneratedProfile {
  id: string
  javaMajor: number
  dataVersion: number
  dataPackFormat: string
  commandRoots: string[]
  registries: { blocks: string[]; items: string[]; entityTypes: string[] }
}

interface WorkerRequest {
  id: string | number
  type: string
  version?: string
  cellId?: string
  source?: string
  cursor?: number
  render?: PlaygroundRenderOptions
  limits?: PlaygroundBrowserLimits
  entries?: PlaygroundImportEntry[]
  kind?: PlaygroundImportKind
  archive?: boolean
  name?: string
  delayMs?: number
  repeat?: number
  tickRate?: number
  tickFunction?: string
  input?: PlaygroundPlayerInput
  deferLoad?: boolean
  runLoad?: boolean
  profile?: GeneratedProfile
  availableProfiles?: string[]
}

interface RuntimeLimits extends Required<Omit<PlaygroundBrowserLimits, 'requestTimeoutMs' | 'cancelGraceMs'>> {}

const scope = self as unknown as DedicatedWorkerGlobalScope
const encoder = new TextEncoder()
const decoder = new TextDecoder()
let engine: BrowserSandboxEngine | undefined
let version = '26.2'
let availableProfiles: string[] = []
let sessionId: string | undefined
let busy = false
let activeCellId: string | undefined
let limits: RuntimeLimits = normalizeLimits()
const renderAssetFiles = new Map<string, Uint8Array>()
const decodedRenderAssets = new Map<string, Uint8Array>()
const parsedRenderAssets = new Map<string, { bytes: Uint8Array; value: unknown }>()
let animationWidth: number | undefined
let animationHeight: number | undefined
let animationBytes = 0
let viewportSubscribers = 0
let viewportRevision = 0
let lastBlockSignature = ''
let lastEntitySignature = ''
let lastAtlasSignature = ''
let lastSceneResourceRevision = -1
let resourceRevision = 0
let playing = false
let simulationTickRate = 20
let simulationTickFunction: string | undefined
let simulationTimer: number | undefined
let simulationClock = 0
let droppedTicks = 0
const datapackLayers: DatapackLayer[] = []

interface FunctionTagValue {
  id: string
  required: boolean
}

interface FunctionTagDefinition {
  replace: boolean
  values: FunctionTagValue[]
}

interface DatapackLayer {
  functions: Map<string, string>
  tags: Map<string, FunctionTagDefinition>
}

scope.addEventListener('message', (message: MessageEvent<WorkerRequest>) => {
  void dispatch(message.data).catch((error: unknown) => {
    sendError(message.data, error, 'INTERNAL_ERROR', false)
  })
})

async function dispatch(request: WorkerRequest): Promise<void> {
  if (!request || (typeof request.id !== 'string' && typeof request.id !== 'number') || typeof request.type !== 'string') {
    throw protocolError('INVALID_REQUEST', 'Worker request must contain a string or number id and a type')
  }
  switch (request.type) {
    case 'transport.connect':
      post({ type: 'transport.ready', requestId: request.id })
      return
    case 'session.create':
      await createSession(request)
      return
    case 'cell.execute':
      await execute(request)
      return
    case 'cell.complete':
      complete(request)
      return
    case 'cell.check':
      check(request)
      return
    case 'cell.render':
      await render(request)
      return
    case 'animation.capture':
      await captureAnimation(request)
      return
    case 'animation.export':
      exportAnimation(request)
      return
    case 'animation.clear':
      clearAnimation(request)
      return
    case 'viewport.subscribe':
      requireSession()
      viewportSubscribers += 1
      post({ type: 'viewport.subscribed', requestId: request.id, result: { subscribers: viewportSubscribers } })
      await publishViewportScene(true)
      return
    case 'viewport.unsubscribe':
      viewportSubscribers = Math.max(0, viewportSubscribers - 1)
      post({ type: 'viewport.unsubscribed', requestId: request.id, result: { subscribers: viewportSubscribers } })
      return
    case 'viewport.refresh':
      requireSession()
      await publishViewportScene(true)
      post({ type: 'viewport.refreshed', requestId: request.id })
      return
    case 'simulation.play':
      requireAvailable()
      startSimulation(request)
      postSimulationState(request.id, 'play')
      return
    case 'simulation.pause':
      pauseSimulation()
      postSimulationState(request.id, 'pause')
      return
    case 'simulation.step':
      requireAvailable()
      pauseSimulation()
      await advanceSimulation(1)
      postSimulationState(request.id, 'step')
      return
    case 'player.input':
      await dispatchPlayerInput(request)
      return
    case 'session.resources.finalize':
      requireAvailable()
      publishViewportOutputs(JSON.parse(engine!.runLoad()) as { outputs?: PlaygroundOutputEvent[] })
      post({ type: 'session.resources.ready', requestId: request.id })
      await publishViewportScene()
      return
    case 'session.import':
      await importFiles(request)
      return
    case 'session.checkpoint.save':
      saveCheckpoint(request)
      return
    case 'session.checkpoint.restore':
      await restoreCheckpoint(request)
      return
    case 'session.checkpoint.delete':
      deleteCheckpoint(request)
      return
    case 'session.checkpoint.list':
      listCheckpoints(request)
      return
    case 'session.interrupt':
      if (busy) engine?.interrupt()
      post({ type: 'cell.status', requestId: request.id, cellId: activeCellId, status: busy ? 'interrupting' : 'idle' })
      return
    case 'session.reset':
      requireSession()
      if (busy) throw protocolError('BUSY', 'A cell is already running')
      pauseSimulation()
      engine!.reset()
      post({ type: 'viewport.clear' })
      if (request.runLoad) publishViewportOutputs(JSON.parse(engine!.runLoad()) as { outputs?: PlaygroundOutputEvent[] })
      resetAnimationTracking()
      sendReady(request.id, 'reset')
      await publishViewportScene()
      return
    case 'session.close':
      pauseSimulation()
      post({ type: 'session.closed', requestId: request.id, sessionId, code: 'CLOSED' })
      engine = undefined
      sessionId = undefined
      renderAssetFiles.clear()
      decodedRenderAssets.clear()
      parsedRenderAssets.clear()
      resetAnimationTracking()
      resetViewportTracking()
      datapackLayers.length = 0
      return
    default:
      throw protocolError('INVALID_REQUEST', `Unknown request type '${request.type}'`)
  }
}

async function createSession(request: WorkerRequest): Promise<void> {
  if (engine) throw protocolError('SESSION_EXISTS', 'This Worker already owns a session')
  version = request.version ?? '26.2'
  const profile = request.profile
  if (!profile || profile.id !== version) throw protocolError('PROFILE_NOT_ALLOWED', `Unknown browser profile '${version}'`)
  availableProfiles = request.availableProfiles ?? [version]
  limits = normalizeLimits(request.limits)
  renderAssetFiles.clear()
  decodedRenderAssets.clear()
  parsedRenderAssets.clear()
  resetAnimationTracking()
  pauseSimulation()
  resetViewportTracking()
  datapackLayers.length = 0
  engine = new BrowserSandboxEngine(
    version,
    profile.commandRoots.join(','),
    profile.registries.blocks.join(','),
    profile.registries.items.join(','),
    profile.registries.entityTypes.join(','),
    limits.maximumCellBytes,
    limits.maximumOutputBytes,
    limits.maximumCommands,
    limits.maximumOutputEvents,
    limits.maximumRenderWidth,
    limits.maximumRenderHeight,
    limits.maximumCheckpoints,
    limits.maximumCheckpointBytes,
    limits.maximumAnimationFrames,
    limits.maximumAnimationBytes,
  )
  sessionId = typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `browser-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
  sendReady(request.id, 'created')
}

async function execute(request: WorkerRequest): Promise<void> {
  requireSession()
  if (busy) throw protocolError('BUSY', 'A cell is already running')
  const source = requiredString(request.source, 'source')
  const cellId = requiredString(request.cellId, 'cellId')
  validateSource(source)
  const renderOptions = normalizeRender(request.render)
  busy = true
  activeCellId = cellId
  post({ type: 'cell.status', requestId: request.id, cellId, status: 'running' })
  engine!.beginExecution()
  try {
    const lines = source.split(/\r?\n/)
    for (let index = 0; index < lines.length; index += 1) {
      const outcome = JSON.parse(engine!.executeLineSafe(lines[index], index + 1)) as {
        ok: boolean
        error?: { code: string; message: string; line: number; command: string }
      }
      if (!outcome.ok && outcome.error) {
        const diagnostic = {
          line: outcome.error.line,
          from: 0,
          to: outcome.error.command.length,
          severity: 'error' as const,
          code: outcome.error.code,
          message: outcome.error.message,
          command: outcome.error.command,
        }
        post({ type: 'diagnostic', requestId: request.id, cellId, diagnostics: [diagnostic] })
        post({
          type: 'cell.error',
          requestId: request.id,
          cellId,
          error: { code: outcome.error.code, message: outcome.error.message, recoverable: true },
        })
        return
      }
      await yieldCommandBoundary()
    }
    const result = JSON.parse(engine!.finishExecution()) as Record<string, unknown> & {
      commands?: number
      outputs?: unknown[]
      snapshotDiffs?: unknown[]
    }
    const commands = result.commands ?? 0
    const outputs = result.outputs?.length ?? 0
    const changes = result.snapshotDiffs?.length ?? 0
    post({
      type: 'cell.output',
      requestId: request.id,
      cellId,
      kind: 'execution',
      summary: `Executed ${commands} command${commands === 1 ? '' : 's'}; ${outputs} output${outputs === 1 ? '' : 's'}; ${changes} state change${changes === 1 ? '' : 's'}.`,
      result,
    })
    publishViewportOutputs(result as { outputs?: PlaygroundOutputEvent[] })
    if (renderOptions.auto) await sendRender(request.id, cellId, renderOptions)
  } catch (error) {
    sendError(request, error, errorMessage(error).includes('output-size') ? 'OUTPUT_LIMIT' : 'COMMAND_ERROR', true)
  } finally {
    busy = false
    activeCellId = undefined
    post({ type: 'cell.status', requestId: request.id, cellId, status: 'idle' })
    await publishViewportScene()
  }
}

function complete(request: WorkerRequest): void {
  requireAvailable()
  const source = request.source ?? ''
  validateSource(source)
  const suggestions = JSON.parse(engine!.complete(source, request.cursor ?? source.length)) as unknown[]
  post({
    type: 'cell.output',
    requestId: request.id,
    cellId: request.cellId,
    kind: 'completion',
    result: { suggestions },
  })
}

function check(request: WorkerRequest): void {
  requireAvailable()
  const source = requiredString(request.source, 'source')
  validateSource(source)
  const diagnostics = JSON.parse(engine!.check(source)) as PlaygroundEvent['diagnostics']
  post({ type: 'diagnostic', requestId: request.id, cellId: request.cellId, diagnostics })
}

async function render(request: WorkerRequest): Promise<void> {
  requireAvailable()
  await sendRender(request.id, request.cellId, normalizeRender(request.render))
}

function saveCheckpoint(request: WorkerRequest): void {
  requireAvailable()
  const name = requiredString(request.name, 'name')
  const snapshot = checkpointValue<string>(engine!.saveCheckpoint(name))
  post({
    type: 'session.checkpoint',
    requestId: request.id,
    kind: 'saved',
    name,
    result: { name, snapshot: JSON.parse(snapshot) as Record<string, unknown> },
  })
}

async function restoreCheckpoint(request: WorkerRequest): Promise<void> {
  requireAvailable()
  pauseSimulation()
  const name = requiredString(request.name, 'name')
  const snapshot = checkpointValue<string>(engine!.restoreCheckpoint(name))
  post({
    type: 'session.checkpoint',
    requestId: request.id,
    kind: 'restored',
    name,
    result: { name, snapshot: JSON.parse(snapshot) as Record<string, unknown> },
  })
  await publishViewportScene()
}

function deleteCheckpoint(request: WorkerRequest): void {
  requireAvailable()
  const name = requiredString(request.name, 'name')
  const deleted = checkpointValue<boolean>(engine!.deleteCheckpoint(name))
  post({ type: 'session.checkpoint', requestId: request.id, kind: 'deleted', name, result: { name, deleted } })
}

function listCheckpoints(request: WorkerRequest): void {
  requireAvailable()
  const names = JSON.parse(engine!.checkpointNames()) as string[]
  post({ type: 'session.checkpoint', requestId: request.id, kind: 'listed', result: { names } })
}

function checkpointValue<T>(raw: string): T {
  const outcome = JSON.parse(raw) as { ok: boolean; value?: T; error?: { code: string; message: string } }
  if (!outcome.ok || outcome.value === undefined) {
    throw protocolError(outcome.error?.code ?? 'CHECKPOINT_FAILED', outcome.error?.message ?? 'Checkpoint operation failed')
  }
  return outcome.value
}

async function captureAnimation(request: WorkerRequest): Promise<void> {
  requireAvailable()
  const options = normalizeRender(request.render)
  const delayMs = normalizeAnimationDelay(request.delayMs)
  const count = engine!.animationFrameCount()
  if (count >= limits.maximumAnimationFrames) {
    throw protocolError('ANIMATION_FRAME_LIMIT', `Animation exceeds the ${limits.maximumAnimationFrames} frame limit`)
  }
  if ((animationWidth !== undefined && animationWidth !== options.width) || (animationHeight !== undefined && animationHeight !== options.height)) {
    throw protocolError('ANIMATION_SIZE_MISMATCH', 'All animation frames must use the same dimensions')
  }
  const frameBytes = options.width * options.height * 4
  if (animationBytes + frameBytes > limits.maximumAnimationBytes) {
    throw protocolError('ANIMATION_SIZE_LIMIT', `Animation exceeds the ${limits.maximumAnimationBytes} byte limit`)
  }
  await prepareRenderTextures()
  const frameCount = engine!.captureAnimationFrame(options.width, options.height, Math.round(delayMs / 10))
  animationWidth = options.width
  animationHeight = options.height
  animationBytes += frameBytes
  post({
    type: 'animation.frame',
    requestId: request.id,
    cellId: request.cellId,
    width: options.width,
    height: options.height,
    result: { frameCount, delayMs },
  })
}

function exportAnimation(request: WorkerRequest): void {
  requireAvailable()
  const frameCount = engine!.animationFrameCount()
  if (frameCount === 0) throw protocolError('ANIMATION_EMPTY', 'Capture at least one frame before exporting a GIF')
  const repeat = normalizeAnimationRepeat(request.repeat)
  const gif = engine!.exportAnimation(repeat)
  const bytes = gif.buffer.slice(gif.byteOffset, gif.byteOffset + gif.byteLength) as ArrayBuffer
  post(
    {
      type: 'animation.gif',
      requestId: request.id,
      cellId: request.cellId,
      mimeType: 'image/gif',
      bytes,
      width: animationWidth,
      height: animationHeight,
      result: { frameCount, repeat },
    },
    [bytes],
  )
}

function clearAnimation(request: WorkerRequest): void {
  requireAvailable()
  engine!.clearAnimation()
  resetAnimationTracking()
  post({ type: 'animation.cleared', requestId: request.id, result: { frameCount: 0 } })
}

function resetAnimationTracking(): void {
  animationWidth = undefined
  animationHeight = undefined
  animationBytes = 0
}

function normalizeAnimationDelay(value: number | undefined): number {
  const delay = value ?? 250
  if (!Number.isInteger(delay) || delay < 10 || delay > 655_350) {
    throw protocolError('ANIMATION_DELAY_INVALID', 'Animation frame delay must be an integer between 10 and 655350 milliseconds')
  }
  return delay
}

function normalizeAnimationRepeat(value: number | undefined): number {
  const repeat = value ?? 0
  if (!Number.isInteger(repeat) || repeat < 0 || repeat > 65_535) {
    throw protocolError('ANIMATION_REPEAT_INVALID', 'Animation repeat must be an integer between 0 and 65535')
  }
  return repeat
}

async function sendRender(
  requestId: string | number,
  cellId: string | undefined,
  options: Required<PlaygroundRenderOptions>,
): Promise<void> {
  await prepareRenderTextures()
  const rgba = engine!.renderRgba(options.width, options.height)
  const pixels = new Uint8ClampedArray(rgba.buffer, rgba.byteOffset, rgba.byteLength)
  const bytes = await rgbaToPng(pixels, options.width, options.height)
  const metadata = JSON.parse(engine!.renderMetadata(options.width, options.height)) as Record<string, unknown>
  post(
    {
      type: 'cell.render',
      requestId,
      cellId,
      mimeType: 'image/png',
      bytes,
      width: options.width,
      height: options.height,
      metadata,
    },
    [bytes],
  )
}

async function importFiles(request: WorkerRequest): Promise<void> {
  requireAvailable()
  const kind = request.kind
  if (!kind || !['datapack', 'resource-pack', 'client-jar', 'world'].includes(kind)) {
    throw protocolError('IMPORT_TYPE_REQUIRED', 'Import kind must be selected explicitly')
  }
  const supplied = request.entries ?? []
  if (supplied.length === 0) throw protocolError('INVALID_REQUEST', 'Import contains no files')
  let entries = supplied
  if (request.archive) {
    if (supplied.length !== 1) throw protocolError('INVALID_REQUEST', 'Archive imports must contain exactly one file')
    entries = await extractZip(supplied[0].bytes, kind)
  }
  if (entries.length > limits.maximumImportFiles) throw protocolError('IMPORT_FILE_LIMIT', `Import exceeds the ${limits.maximumImportFiles} file limit`)
  const normalized = normalizeEntries(entries)
  const totalBytes = normalized.reduce((total, entry) => total + entry.bytes.byteLength, 0)
  if (totalBytes > limits.maximumImportBytes) throw protocolError('IMPORT_SIZE_LIMIT', `Import exceeds the ${limits.maximumImportBytes} byte limit`)
  let functions = 0
  if (kind === 'datapack') {
    const layer = parseDatapackLayer(normalized)
    datapackLayers.push(layer)
    functions = layer.functions.size
    rebuildEffectiveDatapacks()
  }
  const renderAssets = kind === 'resource-pack' || kind === 'client-jar' ? registerRenderAssets(normalized) : 0
  if (!request.deferLoad) publishViewportOutputs(JSON.parse(engine!.runLoad()) as { outputs?: PlaygroundOutputEvent[] })
  if (renderAssets > 0) resourceRevision += 1
  post({
    type: 'session.imported',
    requestId: request.id,
    result: { kind, files: normalized.length, bytes: totalBytes, functions, renderAssets },
  })
  await publishViewportScene()
}

function parseDatapackLayer(entries: PlaygroundImportEntry[]): DatapackLayer {
  const functions = new Map<string, string>()
  const tags = new Map<string, FunctionTagDefinition>()
  for (const entry of entries) {
    const functionMatch = entry.path.match(/(?:^|\/)data\/([a-z0-9_.-]+)\/function(?:s)?\/(.+)\.mcfunction$/)
    if (functionMatch) {
      const id = `${functionMatch[1]}:${functionMatch[2]}`
      if (functions.has(id)) throw protocolError('IMPORT_CONFLICT', `Duplicate function '${id}' in import`)
      functions.set(id, decoder.decode(entry.bytes))
      continue
    }
    const tagMatch = entry.path.match(/(?:^|\/)data\/([a-z0-9_.-]+)\/tags\/function(?:s)?\/(.+)\.json$/)
    if (!tagMatch) continue
    const id = `${tagMatch[1]}:${tagMatch[2]}`
    if (tags.has(id)) throw protocolError('IMPORT_CONFLICT', `Duplicate function tag '${id}' in import`)
    let parsed: unknown
    try {
      parsed = JSON.parse(decoder.decode(entry.bytes))
    } catch (error) {
      throw protocolError('IMPORT_RESOURCE_INVALID', `Function tag '${id}' is not valid JSON: ${errorMessage(error)}`)
    }
    if (!parsed || typeof parsed !== 'object' || !Array.isArray((parsed as { values?: unknown }).values)) {
      throw protocolError('IMPORT_RESOURCE_INVALID', `Function tag '${id}' must contain a values array`)
    }
    const root = parsed as { replace?: unknown; values: unknown[] }
    const values = root.values.map((value): FunctionTagValue => {
      if (typeof value === 'string') return { id: normalizeFunctionReference(value), required: true }
      if (value && typeof value === 'object' && typeof (value as { id?: unknown }).id === 'string') {
        return {
          id: normalizeFunctionReference((value as { id: string }).id),
          required: (value as { required?: unknown }).required !== false,
        }
      }
      throw protocolError('IMPORT_RESOURCE_INVALID', `Function tag '${id}' contains an invalid value`)
    })
    tags.set(id, { replace: root.replace === true, values })
  }
  return { functions, tags }
}

function rebuildEffectiveDatapacks(): void {
  const functions = new Map<string, string>()
  const tags = new Map<string, FunctionTagValue[]>()
  for (const layer of datapackLayers) {
    layer.functions.forEach((source, id) => functions.set(id, source))
    layer.tags.forEach((definition, id) => {
      const values = definition.replace ? [] : [...tags.get(id) ?? []]
      values.push(...definition.values)
      tags.set(id, values)
    })
  }
  engine!.clearFunctions()
  functions.forEach((source, id) => engine!.upsertFunction(id, source))
  tags.forEach((values, id) => {
    const effective: string[] = []
    for (const value of values) {
      const target = value.id.startsWith('#') ? tags.has(value.id.slice(1)) : functions.has(value.id)
      if (!target && value.required) {
        throw protocolError('MISSING_RESOURCE', `Function tag '${id}' references missing function '${value.id}'`)
      }
      if (target && !effective.includes(value.id)) effective.push(value.id)
    }
    engine!.setFunctionTag(id, effective.join(','))
  })
}

function normalizeFunctionReference(value: string): string {
  const tag = value.startsWith('#')
  const raw = tag ? value.slice(1) : value
  const normalized = raw.includes(':') ? raw : `minecraft:${raw}`
  if (!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(normalized)) {
    throw protocolError('IMPORT_RESOURCE_INVALID', `Invalid function reference '${value}'`)
  }
  return tag ? `#${normalized}` : normalized
}

function startSimulation(request: WorkerRequest): void {
  const rate = request.tickRate ?? 20
  if (!Number.isFinite(rate) || rate < 1 || rate > 100) {
    throw protocolError('TICK_RATE_INVALID', 'Tick rate must be between 1 and 100 TPS')
  }
  simulationTickRate = rate
  simulationTickFunction = request.tickFunction?.trim() || undefined
  playing = true
  simulationClock = performance.now()
  scheduleSimulation()
}

function pauseSimulation(): void {
  playing = false
  if (simulationTimer !== undefined) clearTimeout(simulationTimer)
  simulationTimer = undefined
}

function scheduleSimulation(minimumWait = 0): void {
  if (!playing || simulationTimer !== undefined) return
  const interval = 1_000 / simulationTickRate
  const wait = Math.max(minimumWait, simulationClock + interval - performance.now())
  simulationTimer = setTimeout(() => {
    simulationTimer = undefined
    void pumpSimulation()
  }, wait) as unknown as number
}

async function pumpSimulation(): Promise<void> {
  if (!playing) return
  const interval = 1_000 / simulationTickRate
  const now = performance.now()
  const due = Math.max(1, Math.floor((now - simulationClock) / interval))
  if (busy) {
    scheduleSimulation(interval)
    return
  }
  const count = Math.min(due, 5)
  if (due > count) droppedTicks += due - count
  simulationClock += due * interval
  try {
    await advanceSimulation(count)
  } catch (error) {
    pauseSimulation()
    sendError({ id: 'simulation', cellId: undefined }, error, 'SIMULATION_ERROR', true)
  }
  postSimulationState(undefined, 'tick')
  scheduleSimulation()
}

async function advanceSimulation(count: number): Promise<void> {
  requireAvailable()
  const result = JSON.parse(engine!.runTicks(count, simulationTickFunction ?? null)) as {
    snapshot?: { gameTime?: number }
    outputs?: PlaygroundOutputEvent[]
  }
  publishViewportOutputs(result)
  post({
    type: 'simulation.tick',
    result: { count, gameTime: result.snapshot?.gameTime, droppedTicks },
  })
  await publishViewportScene()
}

function publishViewportOutputs(result: { outputs?: PlaygroundOutputEvent[] }): void {
  if (viewportSubscribers <= 0) return
  for (const output of result.outputs ?? []) {
    if (output.channel === 'visual' || output.channel === 'title' || output.channel === 'chat') {
      post({ type: 'viewport.output', output })
    }
  }
}

function postSimulationState(requestId: string | number | undefined, reason: string): void {
  post({
    type: 'simulation.state',
    requestId,
    status: playing ? 'playing' : 'paused',
    result: { playing, tickRate: simulationTickRate, tickFunction: simulationTickFunction, droppedTicks, reason },
  })
}

async function dispatchPlayerInput(request: WorkerRequest): Promise<void> {
  requireAvailable()
  const input = request.input
  if (!input || !['keyboard', 'mouse', 'touch'].includes(input.device)) {
    throw protocolError('INPUT_INVALID', 'Player input requires a supported device')
  }
  const player = input.player?.trim() || 'Steve'
  const result = JSON.parse(engine!.dispatchInput(
    player,
    input.device,
    requiredString(input.code, 'input.code'),
    input.action,
    input.x ?? null,
    input.y ?? null,
  )) as Record<string, unknown>
  post({ type: 'player.input', requestId: request.id, result })
  await publishViewportScene()
}

async function publishViewportScene(force = false): Promise<void> {
  if (!engine || viewportSubscribers <= 0) return
  await prepareRenderTextures()
  const snapshot = JSON.parse(engine.snapshot()) as {
    gameTime?: number
    blocks?: unknown[]
    entities?: unknown[]
  }
  const blockSignature = JSON.stringify(snapshot.blocks ?? [])
  const entitySignature = JSON.stringify(snapshot.entities ?? [])
  if (
    !force
    && blockSignature === lastBlockSignature
    && entitySignature === lastEntitySignature
    && resourceRevision === lastSceneResourceRevision
  ) return
  const metadata = JSON.parse(engine.compileRealtimeScene(960, 540)) as {
    vertexStride: number
    camera: PlaygroundViewportScene['camera']
    bounds: PlaygroundViewportScene['bounds']
    atlas: { width: number; height: number }
    blocks: { batches: PlaygroundSceneBatch[] }
    entities: { batches: PlaygroundSceneBatch[] }
    visibleBlocks: number
    visibleEntities: number
  }
  const atlasView = engine.realtimeAtlasRgba()
  const atlasBytes = Uint8Array.from(atlasView, (value) => value & 0xff)
  const atlasSignature = `${resourceRevision}:${metadata.atlas.width}x${metadata.atlas.height}:${crc32(atlasBytes)}`
  const includeAtlas = force || atlasSignature !== lastAtlasSignature
  const includeBlocks = force || includeAtlas || blockSignature !== lastBlockSignature
  const includeEntities = force || includeAtlas || entitySignature !== lastEntitySignature
  const transfers: Transferable[] = []
  let blocks: PlaygroundViewportScene['blocks']
  let entities: PlaygroundViewportScene['entities']
  let atlas: PlaygroundViewportScene['atlas']
  if (includeBlocks) {
    const vertices = Float32Array.from(engine.realtimeBlockVertices()).buffer
    const indices = Int32Array.from(engine.realtimeBlockIndices()).buffer
    blocks = { vertices, indices, batches: metadata.blocks.batches }
    transfers.push(vertices, indices)
  }
  if (includeEntities) {
    const vertices = Float32Array.from(engine.realtimeEntityVertices()).buffer
    const indices = Int32Array.from(engine.realtimeEntityIndices()).buffer
    entities = { vertices, indices, batches: metadata.entities.batches }
    transfers.push(vertices, indices)
  }
  if (includeAtlas) {
    const rgba = atlasBytes.buffer
    atlas = { ...metadata.atlas, rgba }
    transfers.push(rgba)
  }
  viewportRevision += 1
  const scene: PlaygroundViewportScene = {
    revision: viewportRevision,
    tick: snapshot.gameTime ?? 0,
    tickRate: simulationTickRate,
    generatedAt: performance.now(),
    vertexStride: metadata.vertexStride,
    camera: metadata.camera,
    bounds: metadata.bounds,
    blocks,
    entities,
    atlas,
    visibleBlocks: metadata.visibleBlocks,
    visibleEntities: metadata.visibleEntities,
  }
  lastBlockSignature = blockSignature
  lastEntitySignature = entitySignature
  lastAtlasSignature = atlasSignature
  lastSceneResourceRevision = resourceRevision
  post({ type: 'viewport.scene', scene }, transfers)
}

function resetViewportTracking(): void {
  viewportSubscribers = 0
  viewportRevision = 0
  lastBlockSignature = ''
  lastEntitySignature = ''
  lastAtlasSignature = ''
  lastSceneResourceRevision = -1
  resourceRevision = 0
  droppedTicks = 0
}

function registerRenderAssets(entries: PlaygroundImportEntry[]): number {
  let registered = 0
  for (const entry of entries) {
    const match = entry.path.match(/(?:^|\/)(assets\/[a-z0-9_.-]+\/(?:textures|models|blockstates|items)\/.+)$/)
    if (!match) continue
    const key = match[1]
    const bytes = new Uint8Array(entry.bytes)
    renderAssetFiles.set(key, bytes)
    parsedRenderAssets.delete(key)
    if (key.endsWith('.png')) {
      const textureId = textureIdFromAssetPath(key)
      if (textureId) decodedRenderAssets.delete(textureId)
    }
    registered += 1
  }
  return registered
}

async function prepareRenderTextures(): Promise<void> {
  if (renderAssetFiles.size === 0) return
  const snapshot = JSON.parse(engine!.snapshot()) as {
    blocks?: Array<{ id?: string }>
    entities?: Array<{
      type?: string
      special?: {
        content?: {
          blockState?: { Name?: string; name?: string }
          item?: { id?: string; Id?: string }
        }
      }
    }>
  }
  const candidates = new Set<string>()
  const modelTextures = new Set<string>()
  for (const block of snapshot.blocks ?? []) {
    if (!block.id) continue
    const [namespace, path] = splitResourceId(block.id)
    candidates.add(`assets/${namespace}/textures/block/${path}.png`)
    if (path === 'grass_block') {
      candidates.add(`assets/${namespace}/textures/block/grass_block_top.png`)
      candidates.add(`assets/${namespace}/textures/block/grass_block_side.png`)
      candidates.add(`assets/${namespace}/textures/block/dirt.png`)
    }
    if (path.endsWith('_log') || path.endsWith('_stem') || path.endsWith('_hyphae')) {
      candidates.add(`assets/${namespace}/textures/block/${path}_top.png`)
    }
    prepareBlockModelAssets(block.id, modelTextures)
  }
  for (const entity of snapshot.entities ?? []) {
    const type = entity.type?.substring((entity.type?.indexOf(':') ?? -1) + 1)
    if (type === 'zombie') candidates.add('assets/minecraft/textures/entity/zombie/zombie.png')
    if (type === 'skeleton') candidates.add('assets/minecraft/textures/entity/skeleton/skeleton.png')
    if (type === 'block_display') {
      const blockId = entity.special?.content?.blockState?.Name ?? entity.special?.content?.blockState?.name
      if (blockId) prepareBlockModelAssets(blockId, modelTextures)
    }
    if (type === 'item_display') {
      const itemId = entity.special?.content?.item?.id ?? entity.special?.content?.item?.Id
      if (itemId) {
        const [namespace, path] = splitResourceId(itemId)
        candidates.add(`assets/${namespace}/textures/item/${path}.png`)
        prepareItemModelAssets(itemId, modelTextures)
      }
    }
    if (type === 'text_display') candidates.add('assets/minecraft/textures/font/ascii.png')
  }
  for (const textureId of modelTextures) {
    const [namespace, path] = splitResourceId(textureId)
    candidates.add(`assets/${namespace}/textures/${path}.png`)
  }
  for (const assetPath of candidates) {
    const bytes = renderAssetFiles.get(assetPath)
    const textureId = textureIdFromAssetPath(assetPath)
    if (!bytes || !textureId || decodedRenderAssets.get(textureId) === bytes) continue
    const decoded = await decodeTexture(bytes)
    if (!decoded) continue
    engine!.upsertTexture(textureId, decoded.width, decoded.height, new Int8Array(decoded.rgba.buffer))
    decodedRenderAssets.set(textureId, bytes)
  }
}

function prepareBlockModelAssets(blockId: string, textureIds: Set<string>): void {
  const [namespace, path] = splitResourceId(blockId)
  const state = readRenderJson(`assets/${namespace}/blockstates/${path}.json`)
  if (!state) return
  const modelIds = new Set<string>()
  collectModelIds(state, modelIds)
  const visited = new Set<string>()
  for (const modelId of modelIds) prepareModelAsset(modelId, textureIds, visited)
}

function prepareItemModelAssets(itemId: string, textureIds: Set<string>): void {
  const [namespace, path] = splitResourceId(itemId)
  const definition = readRenderJson(`assets/${namespace}/items/${path}.json`)
  const modelIds = new Set<string>()
  if (definition) collectModelIds(definition, modelIds)
  if (modelIds.size === 0) modelIds.add(`${namespace}:item/${path}`)
  const visited = new Set<string>()
  for (const modelId of modelIds) prepareModelAsset(modelId, textureIds, visited)
}

function prepareModelAsset(rawId: string, textureIds: Set<string>, visited: Set<string>): void {
  const modelId = normalizeResourceId(rawId)
  if (!visited.add(modelId)) return
  const [namespace, path] = splitResourceId(modelId)
  const model = readRenderJson(`assets/${namespace}/models/${path}.json`) as Record<string, unknown> | undefined
  if (!model) return
  if (typeof model.parent === 'string') prepareModelAsset(model.parent, textureIds, visited)
  if (model.textures && typeof model.textures === 'object') {
    for (const value of Object.values(model.textures as Record<string, unknown>)) {
      if (typeof value === 'string' && !value.startsWith('#')) textureIds.add(normalizeResourceId(value))
    }
  }
}

function readRenderJson(path: string): unknown | undefined {
  const bytes = renderAssetFiles.get(path)
  if (!bytes) return undefined
  const cached = parsedRenderAssets.get(path)
  if (cached?.bytes === bytes) return cached.value
  try {
    const text = decoder.decode(bytes)
    const value = JSON.parse(text) as unknown
    engine!.upsertRenderAsset(path, text)
    parsedRenderAssets.set(path, { bytes, value })
    return value
  } catch {
    return undefined
  }
}

function collectModelIds(value: unknown, result: Set<string>): void {
  if (Array.isArray(value)) {
    for (const item of value) collectModelIds(item, result)
    return
  }
  if (!value || typeof value !== 'object') return
  for (const [key, child] of Object.entries(value as Record<string, unknown>)) {
    if (key === 'model' && typeof child === 'string') result.add(child)
    else collectModelIds(child, result)
  }
}

async function decodeTexture(bytes: Uint8Array): Promise<{ width: number; height: number; rgba: Uint8ClampedArray } | undefined> {
  if (typeof createImageBitmap !== 'function' || typeof OffscreenCanvas === 'undefined') return undefined
  try {
    const copy = new Uint8Array(bytes.byteLength)
    copy.set(bytes)
    const bitmap = await createImageBitmap(new Blob([copy], { type: 'image/png' }))
    const width = bitmap.width
    const height = bitmap.height > width && bitmap.height % width === 0 ? width : bitmap.height
    const canvas = new OffscreenCanvas(width, height)
    const context = canvas.getContext('2d', { willReadFrequently: true })
    if (!context) {
      bitmap.close()
      return undefined
    }
    context.imageSmoothingEnabled = false
    context.drawImage(bitmap, 0, 0)
    const rgba = context.getImageData(0, 0, width, height).data
    bitmap.close()
    return { width, height, rgba }
  } catch {
    return undefined
  }
}

function textureIdFromAssetPath(path: string): string | undefined {
  const match = path.match(/^assets\/([a-z0-9_.-]+)\/textures\/(.+)\.png$/)
  return match ? `${match[1]}:${match[2]}` : undefined
}

function splitResourceId(id: string): [string, string] {
  const separator = id.indexOf(':')
  return separator >= 0 ? [id.slice(0, separator), id.slice(separator + 1)] : ['minecraft', id]
}

function normalizeResourceId(id: string): string {
  return id.includes(':') ? id : `minecraft:${id}`
}

function normalizeEntries(entries: PlaygroundImportEntry[]): PlaygroundImportEntry[] {
  const seen = new Set<string>()
  return entries.map((entry) => {
    const path = normalizePath(entry.path)
    if (seen.has(path)) throw protocolError('IMPORT_CONFLICT', `Duplicate imported path '${path}'`)
    seen.add(path)
    if (!(entry.bytes instanceof ArrayBuffer)) throw protocolError('INVALID_REQUEST', `Imported entry '${path}' has no ArrayBuffer`)
    return { path, bytes: entry.bytes }
  })
}

function normalizePath(raw: string): string {
  const portable = raw.replaceAll('\\', '/')
  if (!portable || portable.startsWith('/') || /^[A-Za-z]:($|\/)/.test(portable) || portable.includes('\0')) {
    throw protocolError('IMPORT_PATH_INVALID', `Absolute or invalid imported path '${raw}'`)
  }
  const parts = portable.split('/').filter(Boolean)
  if (parts.length === 0 || parts.some((part) => part === '.' || part === '..' || /[\u0000-\u001f]/.test(part))) {
    throw protocolError('IMPORT_PATH_INVALID', `Imported path traversal is not allowed: '${raw}'`)
  }
  return parts.join('/')
}

async function extractZip(bytes: ArrayBuffer, kind: PlaygroundImportKind): Promise<PlaygroundImportEntry[]> {
  if (bytes.byteLength > limits.maximumImportBytes) throw protocolError('IMPORT_SIZE_LIMIT', 'Compressed archive exceeds the import-size limit')
  preflightZip(new Uint8Array(bytes), kind)
  return await new Promise((resolve, reject) => {
    const result: PlaygroundImportEntry[] = []
    let inflated = 0
    let files = 0
    let pending = 0
    let inputFinished = false
    let settled = false
    const finish = () => {
      if (!settled && inputFinished && pending === 0) {
        settled = true
        resolve(result)
      }
    }
    const fail = (error: unknown) => {
      if (!settled) {
        settled = true
        reject(error)
      }
    }
    const unzip = new Unzip((file) => {
      if (settled || file.name.endsWith('/') || (kind === 'client-jar' && !file.name.startsWith('assets/'))) return
      files += 1
      if (files > limits.maximumImportFiles) {
        fail(protocolError('IMPORT_FILE_LIMIT', `Archive exceeds the ${limits.maximumImportFiles} file limit`))
        return
      }
      pending += 1
      const chunks: Uint8Array[] = []
      let length = 0
      file.ondata = (error, chunk, final) => {
        if (error) {
          fail(error)
          return
        }
        inflated += chunk.length
        length += chunk.length
        if (inflated > limits.maximumImportBytes) {
          fail(protocolError('IMPORT_SIZE_LIMIT', `Expanded archive exceeds the ${limits.maximumImportBytes} byte limit`))
          return
        }
        chunks.push(chunk)
        if (final) {
          const joined = new Uint8Array(length)
          let offset = 0
          for (const value of chunks) {
            joined.set(value, offset)
            offset += value.length
          }
          result.push({ path: file.name, bytes: joined.buffer })
          pending -= 1
          finish()
        }
      }
      if (kind === 'client-jar') queueMicrotask(() => {
        if (!settled) file.start()
      })
      else file.start()
    })
    unzip.register(UnzipInflate)
    const input = new Uint8Array(bytes)
    let inputOffset = 0
    const pushNextChunk = () => {
      if (settled) return
      try {
        const end = Math.min(input.length, inputOffset + 256 * 1024)
        unzip.push(input.subarray(inputOffset, end), end === input.length)
        inputOffset = end
        if (end === input.length) {
          inputFinished = true
          finish()
        } else {
          setTimeout(pushNextChunk, 0)
        }
      } catch (error) {
        fail(protocolError('IMPORT_ARCHIVE_INVALID', errorMessage(error)))
      }
    }
    pushNextChunk()
  })
}

function preflightZip(bytes: Uint8Array, kind: PlaygroundImportKind): void {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const minimum = Math.max(0, bytes.length - 65_557)
  let end = -1
  for (let offset = bytes.length - 22; offset >= minimum; offset -= 1) {
    if (view.getUint32(offset, true) === 0x06054b50) {
      end = offset
      break
    }
  }
  if (end < 0) throw protocolError('IMPORT_ARCHIVE_INVALID', 'ZIP end-of-directory record is missing')
  const entries = view.getUint16(end + 10, true)
  const centralSize = view.getUint32(end + 12, true)
  const centralOffset = view.getUint32(end + 16, true)
  if (entries === 0xffff || centralSize === 0xffffffff || centralOffset === 0xffffffff) {
    throw protocolError('IMPORT_ARCHIVE_INVALID', 'ZIP64 imports are not supported in the browser playground')
  }
  if (kind !== 'client-jar' && entries > limits.maximumImportFiles) {
    throw protocolError('IMPORT_FILE_LIMIT', `Archive exceeds the ${limits.maximumImportFiles} file limit`)
  }
  if (centralOffset + centralSize > end || centralOffset + 46 > bytes.length) {
    throw protocolError('IMPORT_ARCHIVE_INVALID', 'ZIP central directory is outside the archive')
  }
  let offset = centralOffset
  let expanded = 0
  let includedFiles = 0
  for (let index = 0; index < entries; index += 1) {
    if (offset + 46 > bytes.length || view.getUint32(offset, true) !== 0x02014b50) {
      throw protocolError('IMPORT_ARCHIVE_INVALID', 'ZIP central directory entry is invalid')
    }
    const nameLength = view.getUint16(offset + 28, true)
    const extraLength = view.getUint16(offset + 30, true)
    const commentLength = view.getUint16(offset + 32, true)
    const name = decoder.decode(bytes.subarray(offset + 46, offset + 46 + nameLength))
    if (kind !== 'client-jar' || name.startsWith('assets/')) {
      includedFiles += 1
      expanded += view.getUint32(offset + 24, true)
      if (includedFiles > limits.maximumImportFiles) {
        throw protocolError('IMPORT_FILE_LIMIT', `Archive exceeds the ${limits.maximumImportFiles} file limit`)
      }
      if (expanded > limits.maximumImportBytes) {
        throw protocolError('IMPORT_SIZE_LIMIT', `Expanded archive exceeds the ${limits.maximumImportBytes} byte limit`)
      }
    }
    offset += 46 + nameLength + extraLength + commentLength
  }
}

async function rgbaToPng(rgba: Uint8ClampedArray, width: number, height: number): Promise<ArrayBuffer> {
  if (typeof OffscreenCanvas !== 'undefined') {
    try {
      const canvas = new OffscreenCanvas(width, height)
      const context = canvas.getContext('2d')
      if (context) {
        const imagePixels = new Uint8ClampedArray(rgba.length)
        imagePixels.set(rgba)
        context.putImageData(new ImageData(imagePixels, width, height), 0, 0)
        return await (await canvas.convertToBlob({ type: 'image/png' })).arrayBuffer()
      }
    } catch {
      // The deterministic encoder below covers engines without worker canvas encoding.
    }
  }
  const encoded = encodePng(rgba, width, height)
  return encoded.buffer.slice(encoded.byteOffset, encoded.byteOffset + encoded.byteLength) as ArrayBuffer
}

function encodePng(rgba: Uint8ClampedArray, width: number, height: number): Uint8Array {
  const scanlines = new Uint8Array((width * 4 + 1) * height)
  for (let y = 0; y < height; y += 1) {
    const target = y * (width * 4 + 1)
    scanlines[target] = 0
    scanlines.set(rgba.subarray(y * width * 4, (y + 1) * width * 4), target + 1)
  }
  const header = new Uint8Array(13)
  const view = new DataView(header.buffer)
  view.setUint32(0, width)
  view.setUint32(4, height)
  header.set([8, 6, 0, 0, 0], 8)
  const signature = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10])
  const chunks = [pngChunk('IHDR', header), pngChunk('IDAT', zlibSync(scanlines)), pngChunk('IEND', new Uint8Array())]
  const length = signature.length + chunks.reduce((total, chunk) => total + chunk.length, 0)
  const png = new Uint8Array(length)
  png.set(signature)
  let offset = signature.length
  for (const chunk of chunks) {
    png.set(chunk, offset)
    offset += chunk.length
  }
  return png
}

function pngChunk(type: string, data: Uint8Array): Uint8Array {
  const result = new Uint8Array(data.length + 12)
  const view = new DataView(result.buffer)
  view.setUint32(0, data.length)
  result.set(encoder.encode(type), 4)
  result.set(data, 8)
  view.setUint32(data.length + 8, crc32(result.subarray(4, data.length + 8)))
  return result
}

function crc32(bytes: Uint8Array): number {
  let value = 0xffffffff
  for (const byte of bytes) {
    value ^= byte
    for (let bit = 0; bit < 8; bit += 1) value = (value >>> 1) ^ (0xedb88320 & -(value & 1))
  }
  return (value ^ 0xffffffff) >>> 0
}

function sendReady(requestId: string | number, reason: string): void {
  post({
    type: 'session.ready',
    requestId,
    sessionId,
    version,
    reason,
    capabilities: {
      transport: 'worker',
      profiles: availableProfiles,
      imports: true,
      rendering: true,
      checkpoints: true,
      animatedGif: true,
      realtimeViewport: true,
      playerInput: true,
      simulation: true,
      visualParity: false,
    },
  })
}

function requireSession(): void {
  if (!engine || !sessionId) throw protocolError('SESSION_REQUIRED', 'Create a session before using the playground')
}

function requireAvailable(): void {
  requireSession()
  if (busy) throw protocolError('BUSY', 'A cell is already running')
}

function validateSource(source: string): void {
  const bytes = encoder.encode(source).length
  if (bytes > limits.maximumCellBytes) throw protocolError('CELL_TOO_LARGE', `Cell is ${bytes} bytes; maximum is ${limits.maximumCellBytes}`)
}

function requiredString(value: unknown, name: string): string {
  if (typeof value !== 'string') throw protocolError('INVALID_REQUEST', `Request is missing ${name}`)
  return value
}

function normalizeRender(value: PlaygroundRenderOptions = {}): Required<PlaygroundRenderOptions> {
  const width = value.width ?? 960
  const height = value.height ?? 540
  if (width < 16 || height < 16 || width > limits.maximumRenderWidth || height > limits.maximumRenderHeight) {
    throw protocolError('RENDER_SIZE_LIMIT', `Render size ${width}x${height} exceeds the configured limit`)
  }
  return { auto: value.auto ?? false, width, height }
}

function normalizeLimits(value: PlaygroundBrowserLimits = {}): RuntimeLimits {
  return {
    maximumCellBytes: positive(value.maximumCellBytes, 64 * 1024),
    maximumOutputBytes: positive(value.maximumOutputBytes, 1024 * 1024),
    maximumCommands: positive(value.maximumCommands, 10_000),
    maximumOutputEvents: positive(value.maximumOutputEvents, 2_000),
    maximumRenderWidth: bounded(value.maximumRenderWidth, 1_920, 16, 4_096),
    maximumRenderHeight: bounded(value.maximumRenderHeight, 1_080, 16, 4_096),
    maximumCheckpoints: bounded(value.maximumCheckpoints, 32, 1, 256),
    maximumCheckpointBytes: positive(value.maximumCheckpointBytes, 8 * 1024 * 1024),
    maximumAnimationFrames: bounded(value.maximumAnimationFrames, 120, 1, 1_000),
    maximumAnimationBytes: positive(value.maximumAnimationBytes, 64 * 1024 * 1024),
    maximumImportBytes: positive(value.maximumImportBytes, 64 * 1024 * 1024),
    maximumImportFiles: positive(value.maximumImportFiles, 16_384),
  }
}

function positive(value: number | undefined, fallback: number): number {
  return Number.isInteger(value) && (value ?? 0) > 0 ? value! : fallback
}

function bounded(value: number | undefined, fallback: number, minimum: number, maximum: number): number {
  return Number.isInteger(value) && value! >= minimum && value! <= maximum ? value! : fallback
}

function yieldCommandBoundary(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

function protocolError(code: string, message: string): Error & { code: string; recoverable: boolean } {
  return Object.assign(new Error(message), { code, recoverable: true })
}

function sendError(
  request: Pick<WorkerRequest, 'id' | 'cellId'>,
  error: unknown,
  fallbackCode: string,
  fallbackRecoverable: boolean,
): void {
  const candidate = error as { code?: unknown; recoverable?: unknown }
  post({
    type: 'cell.error',
    requestId: request.id,
    cellId: request.cellId,
    error: {
      code: typeof candidate?.code === 'string' ? candidate.code : fallbackCode,
      message: errorMessage(error),
      recoverable: typeof candidate?.recoverable === 'boolean' ? candidate.recoverable : fallbackRecoverable,
    },
  })
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

function post(event: PlaygroundEvent, transfer: Transferable[] = []): void {
  scope.postMessage(event, transfer)
}
