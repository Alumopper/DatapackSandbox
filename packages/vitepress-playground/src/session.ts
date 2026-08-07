import { PlaygroundClientError, PlaygroundWorkerClient } from './client'
import type { PlaygroundWorkerClientOptions } from './client'
import type {
  PlaygroundCheckpoint,
  PlaygroundCompletion,
  PlaygroundDiagnostic,
  PlaygroundEvent,
  PlaygroundFunctionSource,
  PlaygroundImportEntry,
  PlaygroundImportKind,
  PlaygroundImportResult,
  PlaygroundNotebook,
  PlaygroundPlayerInput,
  PlaygroundRenderOptions,
  PlaygroundViewportScene,
} from './types'

export interface PlaygroundSessionControllerOptions extends PlaygroundWorkerClientOptions {
  notebook: PlaygroundNotebook
  render?: PlaygroundRenderOptions
  siteId?: string
}

export interface PlaygroundSessionActivity {
  busy: boolean
  operation?: string
  cellId?: string
  pending: number
}

type SessionEventListener = (event: PlaygroundEvent) => void
type SceneListener = (scene: PlaygroundViewportScene) => void
type ConnectionListener = (state: 'connecting' | 'open' | 'closed' | 'unavailable', message?: string) => void
type ActivityListener = (activity: PlaygroundSessionActivity) => void

/**
 * Shared owner for one local Worker world. Vue components can subscribe to this
 * controller without accidentally creating or terminating competing sessions.
 */
export class PlaygroundSessionController {
  readonly notebook: PlaygroundNotebook
  readonly renderOptions: PlaygroundRenderOptions
  readonly client: PlaygroundWorkerClient

  private readonly listeners = new Set<SessionEventListener>()
  private readonly sceneListeners = new Set<SceneListener>()
  private readonly activityListeners = new Set<ActivityListener>()
  private connectPromise?: Promise<void>
  private mutation: Promise<unknown> = Promise.resolve()
  private viewportSubscribed = false
  private disposed = false
  private connected = false
  private playing = false
  private pendingExclusiveOperations = 0
  private activeOperation?: { operation: string; cellId?: string }
  private readonly onVisibilityChange = () => {
    if (typeof document !== 'undefined' && document.hidden && this.playing) void this.pause()
  }

  constructor(private readonly options: PlaygroundSessionControllerOptions) {
    this.notebook = options.notebook
    this.renderOptions = options.render ?? { auto: true, width: 960, height: 540 }
    this.client = new PlaygroundWorkerClient(options)
    this.client.onEvent((event) => this.receive(event))
    this.client.onConnection((state) => {
      if (state === 'connecting') {
        this.connected = false
        this.viewportSubscribed = false
      } else if (state === 'closed' || state === 'unavailable') {
        this.connected = false
        this.playing = false
      }
    })
    if (typeof document !== 'undefined') document.addEventListener('visibilitychange', this.onVisibilityChange)
  }

  get isConnected(): boolean {
    return this.connected
  }

  get isPlaying(): boolean {
    return this.playing
  }

  onEvent(listener: SessionEventListener): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  onConnection(listener: ConnectionListener): () => void {
    return this.client.onConnection(listener)
  }

  onActivity(listener: ActivityListener): () => void {
    this.activityListeners.add(listener)
    listener(this.activity())
    return () => this.activityListeners.delete(listener)
  }

  async connect(): Promise<void> {
    this.assertActive()
    if (this.connected) return
    if (!this.connectPromise) {
      this.connectPromise = (async () => {
        await this.client.connect()
        await this.client.createSession(this.notebook, this.renderOptions, this.options.siteId)
        this.connected = true
        if (this.sceneListeners.size > 0) await this.ensureViewportSubscription()
      })().finally(() => {
        this.connectPromise = undefined
      })
    }
    await this.connectPromise
  }

  execute(cellId: string, source: string, render = this.renderOptions): Promise<PlaygroundEvent> {
    return this.serialized('execute', cellId, () => this.client.execute(cellId, source, render))
  }

  complete(cellId: string, source: string, cursor: number): Promise<PlaygroundCompletion[]> {
    return this.client.complete(cellId, source, cursor)
  }

  check(cellId: string, source: string): Promise<PlaygroundDiagnostic[]> {
    return this.client.check(cellId, source)
  }

  readFunction(functionId: string): Promise<PlaygroundFunctionSource> {
    return this.client.readFunction(functionId)
  }

  render(cellId: string, render = this.renderOptions): Promise<PlaygroundEvent> {
    return this.serialized('render', cellId, () => this.client.render(cellId, render))
  }

  interrupt(): Promise<PlaygroundEvent> {
    return this.client.interrupt()
  }

  captureAnimationFrame(cellId: string, render: PlaygroundRenderOptions, delayMs = 250): Promise<PlaygroundEvent> {
    return this.serialized('capture-animation-frame', cellId, () => this.client.captureAnimationFrame(cellId, render, delayMs))
  }

  exportAnimation(cellId: string, repeat = 0): Promise<PlaygroundEvent> {
    return this.serialized('export-animation', cellId, () => this.client.exportAnimation(cellId, repeat))
  }

  clearAnimation(): Promise<PlaygroundEvent> {
    return this.serialized('clear-animation', undefined, () => this.client.clearAnimation())
  }

  async reset(): Promise<PlaygroundEvent> {
    await this.pause()
    return await this.serialized('reset', undefined, () => this.client.reset())
  }

  async restoreExample(): Promise<PlaygroundEvent> {
    await this.pause()
    const ready = await this.serialized('restore-example', undefined, () => this.client.reset(true))
    this.emit({ type: 'session.restore-example', sessionId: ready.sessionId })
    return ready
  }

  saveCheckpoint(name = 'default'): Promise<PlaygroundCheckpoint> {
    return this.serialized('save-checkpoint', undefined, () => this.client.saveCheckpoint(name))
  }

  async restoreCheckpoint(name = 'default'): Promise<PlaygroundCheckpoint> {
    await this.pause()
    return await this.serialized('restore-checkpoint', undefined, () => this.client.restoreCheckpoint(name))
  }

  deleteCheckpoint(name = 'default'): Promise<boolean> {
    return this.serialized('delete-checkpoint', undefined, () => this.client.deleteCheckpoint(name))
  }

  listCheckpoints(): Promise<string[]> {
    return this.serialized('list-checkpoints', undefined, () => this.client.listCheckpoints())
  }

  importEntries(kind: PlaygroundImportKind, entries: PlaygroundImportEntry[]): Promise<PlaygroundImportResult> {
    return this.serialized('import', undefined, () => this.client.importEntries(kind, entries))
  }

  importArchive(kind: PlaygroundImportKind, name: string, bytes: ArrayBuffer): Promise<PlaygroundImportResult> {
    return this.serialized('import', undefined, () => this.client.importArchive(kind, name, bytes))
  }

  async play(tickRate = 20, tickFunction?: string): Promise<PlaygroundEvent> {
    const event = await this.serialized('play', undefined, () => this.client.play(tickRate, tickFunction))
    this.playing = true
    return event
  }

  async pause(): Promise<PlaygroundEvent | undefined> {
    if (!this.connected || !this.playing) return undefined
    const event = await this.serialized('pause', undefined, () => this.client.pause())
    this.playing = false
    return event
  }

  async step(): Promise<PlaygroundEvent> {
    if (this.playing) await this.pause()
    return await this.serialized('step', undefined, () => this.client.step())
  }

  dispatchInput(input: PlaygroundPlayerInput): Promise<PlaygroundEvent> {
    return this.serialized('player-input', undefined, () => this.client.dispatchInput(input), false)
  }

  subscribeScene(listener: SceneListener): () => void {
    this.assertActive()
    this.sceneListeners.add(listener)
    if (this.connected) void this.ensureViewportSubscription()
    return () => {
      this.sceneListeners.delete(listener)
      if (this.sceneListeners.size === 0 && this.viewportSubscribed) {
        this.viewportSubscribed = false
        void this.client.unsubscribeViewport().catch(() => undefined)
      }
    }
  }

  async refreshScene(): Promise<void> {
    if (!this.connected || this.sceneListeners.size === 0) return
    await this.client.refreshViewport()
  }

  dispose(): void {
    if (this.disposed) return
    this.disposed = true
    this.playing = false
    this.connected = false
    this.listeners.clear()
    this.sceneListeners.clear()
    this.pendingExclusiveOperations = 0
    this.activeOperation = undefined
    this.emitActivity()
    this.activityListeners.clear()
    if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', this.onVisibilityChange)
    this.client.close()
  }

  private async ensureViewportSubscription(): Promise<void> {
    if (this.viewportSubscribed || this.sceneListeners.size === 0) return
    this.viewportSubscribed = true
    try {
      await this.client.subscribeViewport()
    } catch (error) {
      this.viewportSubscribed = false
      throw error
    }
  }

  private receive(event: PlaygroundEvent): void {
    if (event.type === 'session.ready') {
      this.connected = true
      if (this.sceneListeners.size > 0) void this.ensureViewportSubscription()
    }
    if (event.type === 'simulation.state') this.playing = event.status === 'playing'
    if (event.type === 'viewport.scene' && event.scene) {
      this.sceneListeners.forEach((listener) => listener(event.scene!))
    }
    this.emit(event)
  }

  private emit(event: PlaygroundEvent): void {
    this.listeners.forEach((listener) => listener(event))
  }

  private serialized<T>(
    operation: string,
    cellId: string | undefined,
    action: () => Promise<T>,
    exclusive = true,
  ): Promise<T> {
    this.assertActive()
    if (exclusive) {
      this.pendingExclusiveOperations += 1
      this.emitActivity()
    }
    const invoke = async () => {
      if (exclusive) {
        this.activeOperation = { operation, cellId }
        this.emitActivity()
      }
      try {
        return await action()
      } finally {
        if (exclusive) {
          this.pendingExclusiveOperations -= 1
          this.activeOperation = undefined
          this.emitActivity()
        }
      }
    }
    const next = this.mutation.then(invoke, invoke)
    this.mutation = next.then(() => undefined, () => undefined)
    return next
  }

  private activity(): PlaygroundSessionActivity {
    return {
      busy: this.pendingExclusiveOperations > 0,
      operation: this.activeOperation?.operation,
      cellId: this.activeOperation?.cellId,
      pending: this.pendingExclusiveOperations,
    }
  }

  private emitActivity(): void {
    const activity = this.activity()
    this.activityListeners.forEach((listener) => listener(activity))
  }

  private assertActive(): void {
    if (this.disposed) throw new PlaygroundClientError('SESSION_LOST', 'The playground session was disposed', false)
  }
}
