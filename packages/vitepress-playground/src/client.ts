import type {
  PlaygroundBrowserLimits,
  PlaygroundCheckpoint,
  PlaygroundCompletion,
  PlaygroundDependencySource,
  PlaygroundDiagnostic,
  PlaygroundEvent,
  PlaygroundImportEntry,
  PlaygroundImportKind,
  PlaygroundImportResult,
  PlaygroundNotebook,
  PlaygroundPlayerInput,
  PlaygroundPresetRegistry,
  PlaygroundRenderOptions,
} from './types'
import defaultWorkerUrl from './worker.ts?worker&url'
import profileIndex from '../.generated/profiles/index.json'

const packagedWorkerUrl = new URL(defaultWorkerUrl, import.meta.url).href
const profileFiles = import.meta.glob('../.generated/profiles/[0-9]*.json', { import: 'default' })

interface GeneratedProfile {
  id: string
  javaMajor: number
  dataVersion: number
  dataPackFormat: string
  commandRoots: string[]
  registries: { blocks: string[]; items: string[]; entityTypes: string[] }
}

type EventListener = (event: PlaygroundEvent) => void
type ConnectionListener = (state: 'connecting' | 'open' | 'closed' | 'unavailable', message?: string) => void

interface PendingRequest {
  resolve: (event: PlaygroundEvent) => void
  reject: (error: PlaygroundClientError) => void
  terminal: (event: PlaygroundEvent) => boolean
  timer: number
  graceTimer?: number
}

export interface PlaygroundWorkerClientOptions {
  workerUrl?: string
  limits?: PlaygroundBrowserLimits
  presets?: PlaygroundPresetRegistry
  dependencies?: PlaygroundDependencySource[]
  workerFactory?: (url: string | URL, options: WorkerOptions) => Worker
}

interface SessionConfiguration {
  notebook: PlaygroundNotebook
  render: PlaygroundRenderOptions
  siteId?: string
}

export class PlaygroundClientError extends Error {
  readonly code: string
  readonly recoverable: boolean
  readonly details?: unknown

  constructor(code: string, message: string, recoverable = true, details?: unknown) {
    super(message)
    this.name = 'PlaygroundClientError'
    this.code = code
    this.recoverable = recoverable
    this.details = details
  }
}

export class PlaygroundWorkerClient {
  private worker?: Worker
  private sequence = 0
  private readonly pending = new Map<string, PendingRequest>()
  private readonly listeners = new Set<EventListener>()
  private readonly connectionListeners = new Set<ConnectionListener>()
  private readonly requestTimeoutMs: number
  private readonly cancelGraceMs: number
  private session?: SessionConfiguration
  private closed = false
  private rebuilding = false
  private deferSessionReady = false
  private deferredSessionReady?: PlaygroundEvent

  constructor(private readonly options: PlaygroundWorkerClientOptions = {}) {
    this.requestTimeoutMs = options.limits?.requestTimeoutMs ?? 15_000
    this.cancelGraceMs = options.limits?.cancelGraceMs ?? 2_000
  }

  onEvent(listener: EventListener): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  onConnection(listener: ConnectionListener): () => void {
    this.connectionListeners.add(listener)
    return () => this.connectionListeners.delete(listener)
  }

  async connect(timeoutMs = 8_000): Promise<void> {
    if (this.worker) return
    this.closed = false
    this.connectionListeners.forEach((listener) => listener('connecting'))
    this.worker = this.createWorker()
    this.attachWorker(this.worker)
    try {
      await this.request(
        'transport.connect',
        {},
        (event) => event.type === 'transport.ready',
        timeoutMs,
      )
      this.connectionListeners.forEach((listener) => listener('open'))
    } catch (error) {
      this.worker?.terminate()
      this.worker = undefined
      this.connectionListeners.forEach((listener) => listener('unavailable', error instanceof Error ? error.message : String(error)))
      throw error
    }
  }

  async createSession(notebook: PlaygroundNotebook, render: PlaygroundRenderOptions, siteId?: string): Promise<PlaygroundEvent> {
    this.session = { notebook, render, siteId }
    const profile = await loadProfile(notebook.version)
    this.deferSessionReady = notebook.preset !== undefined || (this.options.dependencies?.length ?? 0) > 0
    this.deferredSessionReady = undefined
    try {
      const ready = await this.request(
        'session.create',
        {
          version: notebook.version,
          preset: notebook.preset,
          siteId,
          render,
          limits: this.workerLimits(),
          profile,
          availableProfiles: Object.keys(profileIndex.profiles),
        },
        (event) => event.type === 'session.ready',
      )
      if (notebook.preset) await this.loadPreset(notebook.preset, (this.options.dependencies?.length ?? 0) > 0)
      await this.loadDependencies()
      if (this.deferredSessionReady) this.listeners.forEach((listener) => listener(this.deferredSessionReady!))
      return ready
    } finally {
      this.deferSessionReady = false
      this.deferredSessionReady = undefined
    }
  }

  execute(cellId: string, source: string, render: PlaygroundRenderOptions): Promise<PlaygroundEvent> {
    return this.request(
      'cell.execute',
      { cellId, source, render },
      (event) => event.type === 'cell.status' && event.status === 'idle',
    )
  }

  async complete(cellId: string, source: string, cursor: number): Promise<PlaygroundCompletion[]> {
    const event = await this.request(
      'cell.complete',
      { cellId, source, cursor },
      (candidate) => candidate.type === 'cell.output' && candidate.kind === 'completion',
    )
    return (event.result?.suggestions as PlaygroundCompletion[] | undefined) ?? []
  }

  async check(cellId: string, source: string): Promise<PlaygroundDiagnostic[]> {
    const event = await this.request('cell.check', { cellId, source }, (candidate) => candidate.type === 'diagnostic')
    return event.diagnostics ?? []
  }

  render(cellId: string, render: PlaygroundRenderOptions): Promise<PlaygroundEvent> {
    return this.request('cell.render', { cellId, render }, (event) => event.type === 'cell.render')
  }

  async saveCheckpoint(name = 'default'): Promise<PlaygroundCheckpoint> {
    const event = await this.request(
      'session.checkpoint.save',
      { name },
      (candidate) => candidate.type === 'session.checkpoint' && candidate.kind === 'saved',
    )
    return event.result as unknown as PlaygroundCheckpoint
  }

  async restoreCheckpoint(name = 'default'): Promise<PlaygroundCheckpoint> {
    const event = await this.request(
      'session.checkpoint.restore',
      { name },
      (candidate) => candidate.type === 'session.checkpoint' && candidate.kind === 'restored',
    )
    return event.result as unknown as PlaygroundCheckpoint
  }

  async deleteCheckpoint(name = 'default'): Promise<boolean> {
    const event = await this.request(
      'session.checkpoint.delete',
      { name },
      (candidate) => candidate.type === 'session.checkpoint' && candidate.kind === 'deleted',
    )
    return Boolean(event.result?.deleted)
  }

  async listCheckpoints(): Promise<string[]> {
    const event = await this.request(
      'session.checkpoint.list',
      {},
      (candidate) => candidate.type === 'session.checkpoint' && candidate.kind === 'listed',
    )
    return (event.result?.names as string[] | undefined) ?? []
  }

  captureAnimationFrame(cellId: string, render: PlaygroundRenderOptions, delayMs = 250): Promise<PlaygroundEvent> {
    return this.request(
      'animation.capture',
      { cellId, render, delayMs },
      (event) => event.type === 'animation.frame',
      Math.max(this.requestTimeoutMs, 60_000),
    )
  }

  exportAnimation(cellId: string, repeat = 0): Promise<PlaygroundEvent> {
    return this.request(
      'animation.export',
      { cellId, repeat },
      (event) => event.type === 'animation.gif',
      Math.max(this.requestTimeoutMs, 60_000),
    )
  }

  clearAnimation(): Promise<PlaygroundEvent> {
    return this.request('animation.clear', {}, (event) => event.type === 'animation.cleared')
  }

  play(tickRate = 20, tickFunction?: string): Promise<PlaygroundEvent> {
    return this.request(
      'simulation.play',
      { tickRate, tickFunction },
      (event) => event.type === 'simulation.state' && event.status === 'playing',
    )
  }

  pause(): Promise<PlaygroundEvent> {
    return this.request(
      'simulation.pause',
      {},
      (event) => event.type === 'simulation.state' && event.status === 'paused',
    )
  }

  step(): Promise<PlaygroundEvent> {
    return this.request(
      'simulation.step',
      {},
      (event) => event.type === 'simulation.state' && event.result?.reason === 'step',
    )
  }

  dispatchInput(input: PlaygroundPlayerInput): Promise<PlaygroundEvent> {
    return this.request('player.input', { input }, (event) => event.type === 'player.input')
  }

  subscribeViewport(): Promise<PlaygroundEvent> {
    return this.request('viewport.subscribe', {}, (event) => event.type === 'viewport.subscribed')
  }

  unsubscribeViewport(): Promise<PlaygroundEvent> {
    return this.request('viewport.unsubscribe', {}, (event) => event.type === 'viewport.unsubscribed')
  }

  refreshViewport(): Promise<PlaygroundEvent> {
    return this.request('viewport.refresh', {}, (event) => event.type === 'viewport.refreshed')
  }

  finalizeDatapacks(): Promise<PlaygroundEvent> {
    return this.request('session.resources.finalize', {}, (event) => event.type === 'session.resources.ready')
  }

  reset(runLoad = false): Promise<PlaygroundEvent> {
    return this.request('session.reset', { runLoad }, (event) => event.type === 'session.ready')
  }

  interrupt(): Promise<PlaygroundEvent> {
    return this.request('session.interrupt', {}, (event) => event.type === 'cell.status')
  }

  async importEntries(kind: PlaygroundImportKind, entries: PlaygroundImportEntry[], deferLoad = false): Promise<PlaygroundImportResult> {
    const transfer = entries.map((entry) => entry.bytes)
    const event = await this.request(
      'session.import',
      { kind, entries, archive: false, deferLoad },
      (candidate) => candidate.type === 'session.imported',
      undefined,
      transfer,
    )
    return event.result as unknown as PlaygroundImportResult
  }

  async importArchive(kind: PlaygroundImportKind, name: string, bytes: ArrayBuffer, deferLoad = false): Promise<PlaygroundImportResult> {
    const event = await this.request(
      'session.import',
      { kind, entries: [{ path: name, bytes }], archive: true, deferLoad },
      (candidate) => candidate.type === 'session.imported',
      undefined,
      [bytes],
    )
    return event.result as unknown as PlaygroundImportResult
  }

  close(): void {
    this.closed = true
    if (this.worker) {
      this.worker.postMessage({ id: this.nextId(), type: 'session.close' })
      this.worker.terminate()
    }
    this.worker = undefined
    this.rejectPending(new PlaygroundClientError('SESSION_LOST', 'The playground Worker was closed', false))
    this.connectionListeners.forEach((listener) => listener('closed', 'The playground Worker was closed'))
  }

  private request(
    type: string,
    fields: Record<string, unknown>,
    terminal: (event: PlaygroundEvent) => boolean,
    timeoutMs = this.requestTimeoutMs,
    transfer: Transferable[] = [],
  ): Promise<PlaygroundEvent> {
    if (!this.worker) {
      return Promise.reject(new PlaygroundClientError('API_UNAVAILABLE', 'The playground Worker is not connected', false))
    }
    const id = this.nextId()
    return new Promise((resolve, reject) => {
      const timer = window.setTimeout(() => this.watchdog(id), timeoutMs)
      this.pending.set(id, { resolve, reject, terminal, timer })
      this.worker!.postMessage({ id, type, ...withoutUndefined(fields) }, transfer)
    })
  }

  private watchdog(id: string): void {
    const pending = this.pending.get(id)
    if (!pending || !this.worker) return
    this.worker.postMessage({ id: `watchdog-${id}`, type: 'session.interrupt' })
    pending.graceTimer = window.setTimeout(() => {
      if (!this.pending.has(id)) return
      this.pending.delete(id)
      pending.reject(new PlaygroundClientError('SESSION_LOST', 'The playground Worker did not stop and was rebuilt', false))
      void this.rebuild()
    }, this.cancelGraceMs)
  }

  private receive(event: MessageEvent<unknown>): void {
    const value = event.data
    if (!value || typeof value !== 'object' || typeof (value as PlaygroundEvent).type !== 'string') {
      this.handleWorkerLoss('The playground Worker returned an invalid event')
      return
    }
    const playgroundEvent = value as PlaygroundEvent
    if (playgroundEvent.type === 'session.ready' && this.deferSessionReady) {
      this.deferredSessionReady = playgroundEvent
    } else {
      this.listeners.forEach((listener) => listener(playgroundEvent))
    }
    const id = playgroundEvent.requestId == null ? undefined : String(playgroundEvent.requestId)
    if (!id) return
    const pending = this.pending.get(id)
    if (!pending) return
    if (playgroundEvent.type === 'cell.error' && playgroundEvent.error) {
      this.finishPending(id, pending)
      pending.reject(new PlaygroundClientError(
        playgroundEvent.error.code,
        playgroundEvent.error.message,
        playgroundEvent.error.recoverable,
        playgroundEvent.error.details,
      ))
    } else if (pending.terminal(playgroundEvent)) {
      this.finishPending(id, pending)
      pending.resolve(playgroundEvent)
    }
  }

  private finishPending(id: string, pending: PendingRequest): void {
    this.pending.delete(id)
    window.clearTimeout(pending.timer)
    if (pending.graceTimer !== undefined) window.clearTimeout(pending.graceTimer)
  }

  private attachWorker(worker: Worker): void {
    worker.onmessage = (event) => this.receive(event)
    worker.onerror = (event) => {
      event.preventDefault()
      this.handleWorkerLoss(event.message || 'The playground Worker crashed')
    }
    worker.onmessageerror = () => this.handleWorkerLoss('The playground Worker could not clone a response')
  }

  private handleWorkerLoss(message: string): void {
    if (this.closed || this.rebuilding) return
    this.rejectPending(new PlaygroundClientError('SESSION_LOST', message, false))
    void this.rebuild()
  }

  private async rebuild(): Promise<void> {
    if (this.closed || this.rebuilding) return
    this.rebuilding = true
    this.worker?.terminate()
    this.worker = undefined
    this.rejectPending(new PlaygroundClientError('SESSION_LOST', 'The playground Worker was rebuilt', false))
    this.connectionListeners.forEach((listener) => listener('connecting', 'Rebuilding local sandbox'))
    try {
      await this.connect()
      const session = this.session
      if (session) await this.createSession(session.notebook, session.render, session.siteId)
    } catch (error) {
      this.connectionListeners.forEach((listener) => listener('unavailable', error instanceof Error ? error.message : String(error)))
    } finally {
      this.rebuilding = false
    }
  }

  private rejectPending(error: PlaygroundClientError): void {
    this.pending.forEach((request) => {
      window.clearTimeout(request.timer)
      if (request.graceTimer !== undefined) window.clearTimeout(request.graceTimer)
      request.reject(error)
    })
    this.pending.clear()
  }

  private createWorker(): Worker {
    const options: WorkerOptions = { type: 'module', name: 'datapack-sandbox' }
    if (this.options.workerFactory) {
      return this.options.workerFactory(this.options.workerUrl ?? packagedWorkerUrl, options)
    }
    if (this.options.workerUrl) return new Worker(this.options.workerUrl, options)
    return new Worker(packagedWorkerUrl, { type: 'module', name: 'datapack-sandbox' })
  }

  private async loadPreset(id: string, deferLoad: boolean): Promise<void> {
    const preset = this.options.presets?.[id]
    if (!preset) throw new PlaygroundClientError('PRESET_NOT_ALLOWED', `Preset '${id}' is not registered`)
    let response: Response
    try {
      response = await fetch(preset.url, { credentials: 'same-origin' })
    } catch (error) {
      throw new PlaygroundClientError('PRESET_FETCH_FAILED', `Unable to fetch preset '${id}'`, true, error)
    }
    if (!response.ok) throw new PlaygroundClientError('PRESET_FETCH_FAILED', `Preset '${id}' returned HTTP ${response.status}`)
    const bytes = await response.arrayBuffer()
    if (preset.sha256) {
      if (!crypto.subtle) throw new PlaygroundClientError('PRESET_INTEGRITY_UNAVAILABLE', 'SHA-256 verification is unavailable', false)
      const actual = toHex(await crypto.subtle.digest('SHA-256', bytes))
      const expected = preset.sha256.toLowerCase().replace(/^sha256-/, '')
      if (actual !== expected) throw new PlaygroundClientError('PRESET_INTEGRITY_FAILED', `Preset '${id}' failed SHA-256 verification`, false)
    }
    await this.importArchive('datapack', `${id}.zip`, bytes, deferLoad)
  }

  private async loadDependencies(): Promise<void> {
    for (const [index, dependency] of (this.options.dependencies ?? []).entries()) {
      let response: Response
      try {
        response = await fetch(dependency.url, { credentials: 'same-origin' })
      } catch (error) {
        throw new PlaygroundClientError(
          'DEPENDENCY_FETCH_FAILED',
          `Unable to fetch ${dependency.kind} dependency '${dependency.url}'`,
          true,
          error,
        )
      }
      if (!response.ok) {
        throw new PlaygroundClientError(
          'DEPENDENCY_FETCH_FAILED',
          `Dependency '${dependency.url}' returned HTTP ${response.status}`,
        )
      }
      const bytes = await response.arrayBuffer()
      if (dependency.sha256) {
        if (!crypto.subtle) {
          throw new PlaygroundClientError('DEPENDENCY_INTEGRITY_UNAVAILABLE', 'SHA-256 verification is unavailable', false)
        }
        const actual = toHex(await crypto.subtle.digest('SHA-256', bytes))
        const expected = dependency.sha256.toLowerCase().replace(/^sha256-/, '')
        if (actual !== expected) {
          throw new PlaygroundClientError(
            'DEPENDENCY_INTEGRITY_FAILED',
            `Dependency '${dependency.url}' failed SHA-256 verification`,
            false,
          )
        }
      }
      await this.importArchive(
        dependency.kind,
        dependency.name ?? dependencyName(dependency.url, index),
        bytes,
        index < (this.options.dependencies?.length ?? 0) - 1,
      )
    }
  }

  private workerLimits(): PlaygroundBrowserLimits {
    const { requestTimeoutMs: _, cancelGraceMs: __, ...workerLimits } = this.options.limits ?? {}
    return workerLimits
  }

  private nextId(): string {
    this.sequence += 1
    return `web-${this.sequence}`
  }
}

function withoutUndefined(fields: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(Object.entries(fields).filter(([, value]) => value !== undefined))
}

function dependencyName(url: string, index: number): string {
  try {
    const name = new URL(url, globalThis.location?.href).pathname.split('/').filter(Boolean).pop()
    return name || `dependency-${index + 1}.zip`
  } catch {
    return `dependency-${index + 1}.zip`
  }
}

function toHex(bytes: ArrayBuffer): string {
  return [...new Uint8Array(bytes)].map((value) => value.toString(16).padStart(2, '0')).join('')
}

async function loadProfile(version: string): Promise<GeneratedProfile> {
  const file = (profileIndex.profiles as Record<string, string>)[version]
  if (!file) throw new PlaygroundClientError('PROFILE_NOT_ALLOWED', `Unknown browser profile '${version}'`)
  const loader = profileFiles[`../.generated/profiles/${file}`]
  if (!loader) throw new PlaygroundClientError('PROFILE_NOT_ALLOWED', `Browser profile '${version}' was not packaged`, false)
  return await loader() as GeneratedProfile
}
