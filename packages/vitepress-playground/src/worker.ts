/// <reference lib="webworker" />
/// <reference types="vite/client" />

import { zlibSync } from 'fflate'
import type { BrowserSandboxEngine } from '../.generated/kotlin/datapack-sandbox-browser-runtime.mjs'
import type { BrowserCoreSession } from '*datapack-sandbox-core.js'
import commandCatalog from '../.generated/vanilla-command-catalog-26.2.json'
import { extractZipEntries, normalizeImportEntries } from './archive'
import { decodePngTexture, inspectPngTexture } from './png'
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
}

interface GeneratedCommandCatalog {
  commandRoots: string[]
  blocks: string[]
  items: string[]
  entityTypes: string[]
  biomes: string[]
  biomeTags: string[]
  damageTypes: string[]
  enchantments: string[]
  effects: string[]
  dimensions: string[]
  attributes: string[]
  particles: string[]
  sounds: string[]
  gamerules: string[]
  scoreboardCriteria: string[]
  advancements: string[]
  recipes: string[]
  pointOfInterestTypes: string[]
  pointOfInterestTypeTags: string[]
  structures: string[]
  structureTags: string[]
  configuredFeatures: string[]
  templatePools: string[]
  testInstances: string[]
  worldClocks: string[]
  timelines: string[]
}

const completions = commandCatalog as GeneratedCommandCatalog

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
  functionId?: string
  input?: PlaygroundPlayerInput
  deferLoad?: boolean
  runLoad?: boolean
  profile?: GeneratedProfile
  availableProfiles?: string[]
}

interface RuntimeLimits extends Required<Omit<PlaygroundBrowserLimits, 'requestTimeoutMs' | 'cancelGraceMs'>> {}

interface ViewportRenderSnapshot {
  gameTime?: number
  dayTime?: number
  weather?: string
  blocks?: Array<{ id?: string }>
  entities?: Array<{
    uuid?: string
    type?: string
    x?: number
    y?: number
    z?: number
    tags?: string[]
    special?: {
      content?: {
        blockState?: { Name?: string; name?: string }
        item?: {
          id?: string
          Id?: string
          components?: Record<string, unknown>
        }
      }
    }
  }>
}

interface RealtimeTickResult {
  gameTime?: number
  outputs?: PlaygroundOutputEvent[]
  renderSnapshot?: ViewportRenderSnapshot
}

class CoreBackedBrowserSandboxEngine {
  constructor(
    private readonly core: BrowserCoreSession,
    private readonly renderer: BrowserSandboxEngine,
  ) {
    this.syncRenderSnapshot()
  }

  beginExecution(): void { this.core.beginExecution() }
  executeLineSafe(source: string, line: number): string { return this.core.executeLineSafe(source, line) }
  finishExecution(): string { return this.syncResult(this.core.finishExecution()) }
  check(source: string): string { return this.core.check(source) }
  complete(source: string, cursor: number): string { return this.renderer.complete(source, cursor) }
  interrupt(): void { this.core.interrupt() }

  reset(): void {
    this.core.reset()
    this.renderer.reset()
    this.syncRenderSnapshot()
  }

  saveCheckpoint(name: string): string { return this.core.saveCheckpoint(name) }
  restoreCheckpoint(name: string): string {
    const result = this.core.restoreCheckpoint(name)
    const parsed = JSON.parse(result) as { ok: boolean }
    if (parsed.ok) this.syncRenderSnapshot()
    return result
  }
  deleteCheckpoint(name: string): string { return this.core.deleteCheckpoint(name) }
  checkpointNames(): string { return this.core.checkpointNames() }

  clearFunctions(): void {
    this.core.clearFunctions()
    this.renderer.clearFunctions()
  }
  clearDatapackEntries(): void {
    this.core.clearDatapackEntries()
    this.renderer.clearFunctions()
  }
  upsertDatapackEntry(path: string, content: string): void {
    this.core.upsertDatapackEntry(path, content)
  }
  upsertFunction(id: string, source: string): void {
    this.core.upsertFunction(id, source)
    this.renderer.upsertFunction(id, source)
  }
  setFunctionTag(id: string, valuesCsv: string): void {
    this.core.setFunctionTag(id, valuesCsv)
    this.renderer.setFunctionTag(id, valuesCsv)
  }

  runLoad(): string { return this.syncResult(this.core.runLoad()) }
  runTicks(count: number, tickFunction: string | null): string { return this.syncResult(this.core.runTicks(count, tickFunction)) }
  runRealtimeTicks(count: number, tickFunction: string | null): RealtimeTickResult {
    const result = this.core.runRealtimeTicks(count, tickFunction)
    const parsed = JSON.parse(result) as RealtimeTickResult
    if (parsed.renderSnapshot) this.syncSnapshot(JSON.stringify(parsed.renderSnapshot))
    return parsed
  }
  runRealtimeTicksCompact(count: number, tickFunction: string | null): RealtimeTickResult {
    return JSON.parse(this.core.runRealtimeTicksCompact(count, tickFunction)) as RealtimeTickResult
  }
  refreshRenderSnapshot(): ViewportRenderSnapshot {
    const snapshot = this.core.renderSnapshot()
    this.syncSnapshot(snapshot)
    return JSON.parse(snapshot) as ViewportRenderSnapshot
  }
  dispatchInput(
    player: string,
    device: string,
    code: string,
    action: string,
    x: number | null,
    y: number | null,
  ): string {
    const result = this.core.dispatchInput(player, device, code, action, x ?? Number.NaN, y ?? Number.NaN)
    this.syncRenderSnapshot()
    return result
  }

  snapshot(): string { return this.core.snapshot() }
  renderSnapshot(): string { return this.renderer.snapshot() }
  upsertTexture(id: string, width: number, height: number, rgba: Int8Array): void {
    this.renderer.upsertTexture(id, width, height, rgba)
  }
  upsertRenderAsset(path: string, text: string): void { this.renderer.upsertRenderAsset(path, text) }
  renderRgba(width: number, height: number): Int8Array { return this.renderer.renderRgba(width, height) }
  renderMetadata(width: number, height: number): string { return this.renderer.renderMetadata(width, height) }
  captureAnimationFrame(width: number, height: number, delayCentiseconds: number): number {
    return this.renderer.captureAnimationFrame(width, height, delayCentiseconds)
  }
  exportAnimation(repeat: number): Int8Array { return this.renderer.exportAnimation(repeat) }
  clearAnimation(): void { this.renderer.clearAnimation() }
  animationFrameCount(): number { return this.renderer.animationFrameCount() }
  compileRealtimeScene(width: number, height: number): string { return this.renderer.compileRealtimeScene(width, height) }
  realtimeBlockVertices(): Float32Array { return this.renderer.realtimeBlockVertices() }
  realtimeBlockIndices(): Int32Array { return this.renderer.realtimeBlockIndices() }
  realtimeEntityVertices(): Float32Array { return this.renderer.realtimeEntityVertices() }
  realtimeEntityIndices(): Int32Array { return this.renderer.realtimeEntityIndices() }
  realtimeAtlasRgba(): Int8Array { return this.renderer.realtimeAtlasRgba() }

  private syncResult(result: string): string {
    const parsed = JSON.parse(result) as { snapshot?: unknown }
    if (parsed.snapshot) this.syncSnapshot(JSON.stringify(parsed.snapshot))
    return result
  }

  private syncSnapshot(snapshot: string): void { this.renderer.replaceSnapshot(snapshot) }
  private syncRenderSnapshot(): void { this.syncSnapshot(this.core.renderSnapshot()) }
}

const scope = self as unknown as DedicatedWorkerGlobalScope
const encoder = new TextEncoder()
const decoder = new TextDecoder()
const MAX_JAVA_INT = 2_147_483_647
let engine: CoreBackedBrowserSandboxEngine | undefined
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
let preparedRenderTextureSignature = ''
let playing = false
let simulationTickRate = 20
let simulationTickFunction: string | undefined
let simulationTimer: number | undefined
let simulationClock = 0
let droppedTicks = 0
const datapackLayers: DatapackLayer[] = []
let runtimeModules: Promise<{
  BrowserSandboxEngine: typeof import('../.generated/kotlin/datapack-sandbox-browser-runtime.mjs').BrowserSandboxEngine
  createCoreSession: typeof import('../.generated/datapack-sandbox-core.js').createSession
}> | undefined

interface FunctionTagValue {
  id: string
  required: boolean
}

interface FunctionTagDefinition {
  path: string
  replace: boolean
  values: FunctionTagValue[]
}

interface DatapackLayer {
  functions: Map<string, string>
  resources: Map<string, { path: string; content: string }>
  tags: Map<string, FunctionTagDefinition>
}

scope.addEventListener('error', (event: ErrorEvent) => {
  event.preventDefault()
  post({
    type: 'transport.fatal',
    error: {
      code: 'WORKER_RUNTIME_ERROR',
      message: event.message?.trim()
        || (event.error == null ? 'Unhandled playground Worker error' : errorMessage(event.error)),
      recoverable: false,
      details: {
        filename: event.filename || undefined,
        line: event.lineno || undefined,
        column: event.colno || undefined,
        stack: event.error instanceof Error ? event.error.stack : undefined,
      },
    },
  })
})

scope.addEventListener('unhandledrejection', (event: PromiseRejectionEvent) => {
  event.preventDefault()
  post({
    type: 'transport.fatal',
    error: {
      code: 'WORKER_RUNTIME_ERROR',
      message: event.reason == null ? 'Unhandled playground Worker promise rejection' : errorMessage(event.reason),
      recoverable: false,
      details: { stack: event.reason instanceof Error ? event.reason.stack : undefined },
    },
  })
})

let requestQueue: Promise<void> = Promise.resolve()
const latestAdvisoryRequests = new Map<string, string | number>()

scope.addEventListener('message', (message: MessageEvent<unknown>) => {
  if (!message.data || typeof message.data !== 'object') {
    sendError({}, protocolError('INVALID_REQUEST', 'Worker request must be an object'), 'INVALID_REQUEST', true)
    return
  }
  const request = message.data as WorkerRequest
  if (request?.type === 'session.interrupt') {
    // Watchdog interrupts must remain able to reach a long-running command.
    void dispatchRequest(request)
    return
  }
  const advisoryKey = advisoryRequestKey(request)
  if (advisoryKey !== undefined) latestAdvisoryRequests.set(advisoryKey, request.id)
  // Worker message events do not await async listeners. Keep one explicit
  // queue so scene compilation, cell execution, stepping, and play-state
  // transitions cannot enter the shared Kotlin/renderer state concurrently.
  requestQueue = requestQueue.then(
    () => dispatchRequest(request),
    () => dispatchRequest(request),
  )
})

async function dispatchRequest(request: WorkerRequest): Promise<void> {
  const advisoryKey = advisoryRequestKey(request)
  try {
    if (advisoryKey !== undefined && latestAdvisoryRequests.get(advisoryKey) !== request.id) {
      finishSupersededAdvisory(request)
      return
    }
    await dispatch(request)
  } catch (error) {
    sendError(request, error, 'INTERNAL_ERROR', false)
  } finally {
    // Cell ids are page-owned and unbounded. Retain only advisory requests that
    // still have a newer queued successor, otherwise the tracker would leak ids.
    if (advisoryKey !== undefined && latestAdvisoryRequests.get(advisoryKey) === request.id) {
      latestAdvisoryRequests.delete(advisoryKey)
    }
  }
}

function advisoryRequestKey(request: WorkerRequest): string | undefined {
  if (request?.type !== 'cell.complete' && request?.type !== 'cell.check') return undefined
  return `${request.type}:${request.cellId ?? ''}`
}

function finishSupersededAdvisory(request: WorkerRequest): void {
  if (request.type === 'cell.complete') {
    post({
      type: 'cell.output',
      requestId: request.id,
      cellId: request.cellId,
      kind: 'completion',
      result: { suggestions: [] },
    })
  } else {
    post({ type: 'diagnostic', requestId: request.id, cellId: request.cellId, diagnostics: [] })
  }
}

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
    case 'session.function.read':
      readFunction(request)
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
  const runtime = await loadRuntimeModules()
  const core = runtime.createCoreSession(
    version,
    limits.maximumCommands,
    limits.maximumOutputEvents,
    Math.max(limits.maximumOutputBytes, limits.maximumCheckpointBytes),
  )
  const renderer = new runtime.BrowserSandboxEngine(
    version,
    (version === '26.2' ? completions.commandRoots : profile.commandRoots).join(','),
    completions.blocks.join(','),
    completions.items.join(','),
    completions.entityTypes.join(','),
    completions.biomes.join(','),
    completions.biomeTags.join(','),
    completions.damageTypes.join(','),
    completions.enchantments.join(','),
    completions.effects.join(','),
    completions.dimensions.join(','),
    completions.attributes.join(','),
    completions.particles.join(','),
    completions.sounds.join(','),
    completions.gamerules.join(','),
    completions.scoreboardCriteria.join(','),
    completions.advancements.join(','),
    completions.recipes.join(','),
    completions.pointOfInterestTypes.join(','),
    completions.pointOfInterestTypeTags.join(','),
    completions.structures.join(','),
    completions.structureTags.join(','),
    completions.configuredFeatures.join(','),
    completions.templatePools.join(','),
    completions.testInstances.join(','),
    completions.worldClocks.join(','),
    completions.timelines.join(','),
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
  engine = new CoreBackedBrowserSandboxEngine(core, renderer)
  sessionId = typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `browser-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
  sendReady(request.id, 'created')
}

function loadRuntimeModules(): Promise<{
  BrowserSandboxEngine: typeof import('../.generated/kotlin/datapack-sandbox-browser-runtime.mjs').BrowserSandboxEngine
  createCoreSession: typeof import('../.generated/datapack-sandbox-core.js').createSession
}> {
  runtimeModules ??= Promise.all([
    import('../.generated/kotlin/datapack-sandbox-browser-runtime.mjs'),
    import('../.generated/datapack-sandbox-core.js'),
  ]).then(([renderer, core]) => ({
    BrowserSandboxEngine: renderer.BrowserSandboxEngine,
    createCoreSession: core.createSession,
  }))
  return runtimeModules
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
  let executionError: unknown
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
        throw protocolError(outcome.error.code, outcome.error.message)
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
    executionError = error
  } finally {
    try {
      // Keep the operation busy until its viewport revision has been compiled
      // and transferred. `cell.status=idle` is the session serialization
      // boundary; emitting it before this await allows a following play or
      // execute request to enter the same renderer concurrently.
      await publishViewportScene()
    } catch (error) {
      executionError ??= error
    } finally {
      busy = false
      activeCellId = undefined
      if (executionError !== undefined) {
        sendError(
          request,
          executionError,
          errorMessage(executionError).includes('output-size') ? 'OUTPUT_LIMIT' : 'COMMAND_ERROR',
          true,
        )
      }
      post({ type: 'cell.status', requestId: request.id, cellId, status: 'idle' })
    }
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
    entries = await extractZipEntries(supplied[0].bytes, kind, limits)
  }
  if (entries.length > limits.maximumImportFiles) throw protocolError('IMPORT_FILE_LIMIT', `Import exceeds the ${limits.maximumImportFiles} file limit`)
  const normalized = normalizeImportEntries(entries)
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
  const resources = new Map<string, { path: string; content: string }>()
  const tags = new Map<string, FunctionTagDefinition>()
  const packRoot = datapackRoot(entries)
  for (const entry of entries) {
    const datapackPath = datapackEntryPath(entry.path, packRoot)
    if (!datapackPath) continue
    const functionMatch = datapackPath.match(/^data\/([a-z0-9_.-]+)\/function(?:s)?\/(.+)\.mcfunction$/)
    if (functionMatch) {
      const id = `${functionMatch[1]}:${functionMatch[2]}`
      const content = decoder.decode(entry.bytes)
      functions.set(id, content)
      resources.set(`function:${id}`, { path: entry.path, content })
      continue
    }
    const tagMatch = datapackPath.match(/^data\/([a-z0-9_.-]+)\/tags\/([a-z0-9_./-]+?)\/(.+)\.json$/)
    if (!tagMatch) {
      if (/^data\/[a-z0-9_.-]+\/.+\.json$/.test(datapackPath)) {
        resources.set(datapackPath.toLowerCase(), { path: entry.path, content: decoder.decode(entry.bytes) })
      }
      continue
    }
    const registry = /^functions?$/.test(tagMatch[2]) ? 'function' : tagMatch[2]
    const id = `${tagMatch[1]}:${registry}/${tagMatch[3]}`
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
    const previous = tags.get(id)
    tags.set(id, {
      path: entry.path,
      replace: previous?.replace === true || root.replace === true,
      values: root.replace === true ? values : [...previous?.values ?? [], ...values],
    })
  }
  return { functions, resources, tags }
}

function readFunction(request: WorkerRequest): void {
  requireSession()
  const reference = normalizeFunctionReference(requiredString(request.functionId, 'functionId'))
  if (reference.startsWith('#')) {
    throw protocolError('FUNCTION_TAG_NOT_BROWSABLE', `Function tag '${reference}' does not have one source file`)
  }
  for (let index = datapackLayers.length - 1; index >= 0; index -= 1) {
    const resource = datapackLayers[index].resources.get(`function:${reference}`)
    if (!resource) continue
    post({
      type: 'session.function',
      requestId: request.id,
      kind: 'source',
      result: { id: reference, path: resource.path, source: resource.content },
    })
    return
  }
  throw protocolError('FUNCTION_NOT_FOUND', `Function '${reference}' is not loaded in this sandbox`)
}

function datapackRoot(entries: PlaygroundImportEntry[]): string | undefined {
  const roots = entries
    .map((entry) => entry.path.replaceAll('\\', '/'))
    .filter((path) => path.toLowerCase().endsWith('pack.mcmeta'))
    .map((path) => path.slice(0, -'pack.mcmeta'.length))
    .sort((left, right) => left.length - right.length || left.localeCompare(right))
  return roots[0]
}

function datapackEntryPath(path: string, root: string | undefined): string | undefined {
  const normalized = path.replaceAll('\\', '/')
  if (root === undefined) {
    const data = normalized.indexOf('data/')
    return data < 0 ? undefined : normalized.slice(data)
  }
  return normalized.startsWith(root) ? normalized.slice(root.length) : undefined
}

function rebuildEffectiveDatapacks(): void {
  const functions = new Map<string, string>()
  const resources = new Map<string, { path: string; content: string }>()
  const tags = new Map<string, { path: string; values: FunctionTagValue[] }>()
  for (const layer of datapackLayers) {
    layer.functions.forEach((source, id) => functions.set(id, source))
    layer.resources.forEach((resource, id) => resources.set(id, resource))
    layer.tags.forEach((definition, id) => {
      const values = definition.replace ? [] : [...tags.get(id)?.values ?? []]
      values.push(...definition.values)
      tags.set(id, { path: definition.path, values })
    })
  }
  engine!.clearDatapackEntries()
  resources.forEach(({ path, content }) => engine!.upsertDatapackEntry(path, content))
  tags.forEach(({ path, values }, id) => {
    const effective: string[] = []
    for (const value of values) {
      const registry = id.slice(id.indexOf(':') + 1).split('/', 1)[0]
      const target = registry !== 'function' || (value.id.startsWith('#') ? tags.has(`${value.id.slice(1).replace(':', `:${registry}/`)}`) : functions.has(value.id))
      if (!target && value.required && registry === 'function') {
        throw protocolError('MISSING_RESOURCE', `Function tag '${id}' references missing function '${value.id}'`)
      }
      if (target && !effective.includes(value.id)) effective.push(value.id)
    }
    engine!.upsertDatapackEntry(path, JSON.stringify({
      replace: true,
      values: effective.map((value) => {
        const original = values.find((candidate) => candidate.id === value)
        return original?.required === false ? { id: value, required: false } : value
      }),
    }))
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
  if (!playing) droppedTicks = 0
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
  const result = engine!.runRealtimeTicksCompact(count, simulationTickFunction ?? null)
  const renderSnapshot = viewportSubscribers > 0 ? engine!.refreshRenderSnapshot() : undefined
  publishViewportOutputs(result)
  post({
    type: 'simulation.tick',
    result: {
      count,
      gameTime: result.gameTime,
      droppedTicks,
      entities: renderSnapshot?.entities?.map((entity) => ({
        uuid: entity.uuid,
        position: [entity.x ?? 0, entity.y ?? 0, entity.z ?? 0],
        tags: entity.tags ?? [],
      })),
    },
  })
  await publishViewportScene(false, renderSnapshot)
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
  if ((input.x !== undefined && !Number.isFinite(input.x)) || (input.y !== undefined && !Number.isFinite(input.y))) {
    throw protocolError('INPUT_INVALID', 'Player input coordinates must be finite numbers')
  }
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

async function publishViewportScene(
  force = false,
  suppliedSnapshot?: ViewportRenderSnapshot,
): Promise<void> {
  if (!engine || viewportSubscribers <= 0) return
  const snapshot = suppliedSnapshot ?? JSON.parse(engine.renderSnapshot()) as ViewportRenderSnapshot
  await prepareRenderTextures(snapshot)
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
    atlas: { width: number; height: number; signature: number }
    blocks: { batches: PlaygroundSceneBatch[] }
    entities: { batches: PlaygroundSceneBatch[] }
    visibleBlocks: number
    visibleEntities: number
  }
  const atlasSignature = `${resourceRevision}:${metadata.atlas.signature}`
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
    const atlasView = engine.realtimeAtlasRgba()
    const atlasBytes = Uint8Array.from(atlasView, (value) => value & 0xff)
    const rgba = atlasBytes.buffer
    atlas = { width: metadata.atlas.width, height: metadata.atlas.height, rgba }
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
    environment: {
      dayTime: snapshot.dayTime ?? 0,
      weather: snapshot.weather ?? 'clear',
      dimension: 'minecraft:overworld',
    },
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
  preparedRenderTextureSignature = ''
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

async function prepareRenderTextures(snapshot?: ViewportRenderSnapshot): Promise<void> {
  if (renderAssetFiles.size === 0) return
  const renderWorld = snapshot ?? JSON.parse(engine!.renderSnapshot()) as ViewportRenderSnapshot
  const textureSignature = renderTextureSignature(renderWorld)
  if (textureSignature === preparedRenderTextureSignature) return
  const candidates = new Set<string>()
  const modelTextures = new Set<string>()
  for (const block of renderWorld.blocks ?? []) {
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
  for (const entity of renderWorld.entities ?? []) {
    const type = entity.type?.substring((entity.type?.indexOf(':') ?? -1) + 1)
    if (type === 'zombie') candidates.add('assets/minecraft/textures/entity/zombie/zombie.png')
    if (type === 'skeleton') candidates.add('assets/minecraft/textures/entity/skeleton/skeleton.png')
    if (type === 'block_display') {
      const blockId = entity.special?.content?.blockState?.Name ?? entity.special?.content?.blockState?.name
      if (blockId) prepareBlockModelAssets(blockId, modelTextures)
    }
    if (type === 'item_display') {
      const item = entity.special?.content?.item
      const itemModel = item?.components?.['minecraft:item_model']
      const itemId = typeof itemModel === 'string' ? itemModel : item?.id ?? item?.Id
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
  preparedRenderTextureSignature = textureSignature
}

function renderTextureSignature(snapshot: ViewportRenderSnapshot): string {
  const blocks = new Set((snapshot.blocks ?? []).map((block) => block.id ?? ''))
  const entities = new Set<string>()
  for (const entity of snapshot.entities ?? []) {
    const type = entity.type ?? ''
    const content = entity.special?.content
    if (type.endsWith(':block_display')) entities.add(`${type}:${content?.blockState?.Name ?? content?.blockState?.name ?? ''}`)
    else if (type.endsWith(':item_display')) {
      const item = content?.item
      const model = item?.components?.['minecraft:item_model']
      entities.add(`${type}:${typeof model === 'string' ? model : item?.id ?? item?.Id ?? ''}`)
    } else entities.add(type)
  }
  return `${resourceRevision}|${[...blocks].sort().join(',')}|${[...entities].sort().join(',')}`
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
  const metadata = inspectPngTexture(bytes, limits.maximumImportBytes)
  if (!metadata) return undefined
  if (typeof createImageBitmap === 'function' && typeof OffscreenCanvas !== 'undefined') {
    let bitmap: ImageBitmap | undefined
    try {
      const copy = new Uint8Array(bytes.byteLength)
      copy.set(bytes)
      bitmap = await createImageBitmap(new Blob([copy], { type: 'image/png' }))
      if (bitmap.width !== metadata.width || bitmap.height !== metadata.sourceHeight) {
        return undefined
      }
      const canvas = new OffscreenCanvas(metadata.width, metadata.displayHeight)
      const context = canvas.getContext('2d', { willReadFrequently: true })
      if (context) {
        context.imageSmoothingEnabled = false
        context.drawImage(bitmap, 0, 0)
        const rgba = context.getImageData(0, 0, metadata.width, metadata.displayHeight).data
        return { width: metadata.width, height: metadata.displayHeight, rgba }
      }
    } catch {
      // Native decoder failures use the same bounded fallback as Workers without canvas APIs.
    } finally {
      bitmap?.close()
    }
  }
  return decodePngTexture(bytes, limits.maximumImportBytes)
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
  if (!Number.isSafeInteger(width) || !Number.isSafeInteger(height)
    || width < 16 || height < 16 || width > limits.maximumRenderWidth || height > limits.maximumRenderHeight) {
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
  return Number.isSafeInteger(value) && (value ?? 0) > 0 && value! <= MAX_JAVA_INT ? value! : fallback
}

function bounded(value: number | undefined, fallback: number, minimum: number, maximum: number): number {
  return Number.isSafeInteger(value) && value! >= minimum && value! <= maximum ? value! : fallback
}

function yieldCommandBoundary(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

function protocolError(code: string, message: string): Error & { code: string; recoverable: boolean } {
  return Object.assign(new Error(message), { code, recoverable: true })
}

function sendError(
  request: { id?: unknown; cellId?: unknown },
  error: unknown,
  fallbackCode: string,
  fallbackRecoverable: boolean,
): void {
  const candidate = error as { code?: unknown; recoverable?: unknown }
  post({
    type: 'cell.error',
    requestId: typeof request.id === 'string' || typeof request.id === 'number' ? request.id : undefined,
    cellId: typeof request.cellId === 'string' ? request.cellId : undefined,
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
