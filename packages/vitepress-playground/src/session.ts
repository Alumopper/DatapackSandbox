import { PlaygroundClientError, PlaygroundWorkerClient } from './client'
import type { PlaygroundWorkerClientOptions } from './client'
import type {
  PlaygroundCheckpoint,
  PlaygroundCompletion,
  PlaygroundDiagnostic,
  PlaygroundEvent,
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

type SessionEventListener = (event: PlaygroundEvent) => void
type SceneListener = (scene: PlaygroundViewportScene) => void
type ConnectionListener = (state: 'connecting' | 'open' | 'closed' | 'unavailable', message?: string) => void

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
  private connectPromise?: Promise<void>
  private mutation: Promise<unknown> = Promise.resolve()
  private viewportSubscribed = false
  private disposed = false
  private connected = false
  private playing = false
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
    return this.serialized(() => this.client.execute(cellId, source, render))
  }

  complete(cellId: string, source: string, cursor: number): Promise<PlaygroundCompletion[]> {
    return this.client.complete(cellId, source, cursor)
  }

  check(cellId: string, source: string): Promise<PlaygroundDiagnostic[]> {
    return this.client.check(cellId, source)
  }

  render(cellId: string, render = this.renderOptions): Promise<PlaygroundEvent> {
    return this.serialized(() => this.client.render(cellId, render))
  }

  interrupt(): Promise<PlaygroundEvent> {
    return this.client.interrupt()
  }

  captureAnimationFrame(cellId: string, render: PlaygroundRenderOptions, delayMs = 250): Promise<PlaygroundEvent> {
    return this.serialized(() => this.client.captureAnimationFrame(cellId, render, delayMs))
  }

  exportAnimation(cellId: string, repeat = 0): Promise<PlaygroundEvent> {
    return this.serialized(() => this.client.exportAnimation(cellId, repeat))
  }

  clearAnimation(): Promise<PlaygroundEvent> {
    return this.serialized(() => this.client.clearAnimation())
  }

  async reset(): Promise<PlaygroundEvent> {
    await this.pause()
    return await this.serialized(() => this.client.reset())
  }

  async restoreExample(): Promise<PlaygroundEvent> {
    await this.pause()
    const ready = await this.serialized(() => this.client.reset(true))
    this.emit({ type: 'session.restore-example', sessionId: ready.sessionId })
    return ready
  }

  saveCheckpoint(name = 'default'): Promise<PlaygroundCheckpoint> {
    return this.serialized(() => this.client.saveCheckpoint(name))
  }

  async restoreCheckpoint(name = 'default'): Promise<PlaygroundCheckpoint> {
    await this.pause()
    return await this.serialized(() => this.client.restoreCheckpoint(name))
  }

  deleteCheckpoint(name = 'default'): Promise<boolean> {
    return this.serialized(() => this.client.deleteCheckpoint(name))
  }

  listCheckpoints(): Promise<string[]> {
    return this.client.listCheckpoints()
  }

  importEntries(kind: PlaygroundImportKind, entries: PlaygroundImportEntry[]): Promise<PlaygroundImportResult> {
    return this.serialized(() => this.client.importEntries(kind, entries))
  }

  importArchive(kind: PlaygroundImportKind, name: string, bytes: ArrayBuffer): Promise<PlaygroundImportResult> {
    return this.serialized(() => this.client.importArchive(kind, name, bytes))
  }

  async play(tickRate = 20, tickFunction?: string): Promise<PlaygroundEvent> {
    const event = await this.serialized(() => this.client.play(tickRate, tickFunction))
    this.playing = true
    return event
  }

  async pause(): Promise<PlaygroundEvent | undefined> {
    if (!this.connected || !this.playing) return undefined
    const event = await this.serialized(() => this.client.pause())
    this.playing = false
    return event
  }

  async step(): Promise<PlaygroundEvent> {
    if (this.playing) await this.pause()
    return await this.serialized(() => this.client.step())
  }

  dispatchInput(input: PlaygroundPlayerInput): Promise<PlaygroundEvent> {
    return this.serialized(() => this.client.dispatchInput(input))
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

  private serialized<T>(action: () => Promise<T>): Promise<T> {
    this.assertActive()
    const next = this.mutation.then(action, action)
    this.mutation = next.then(() => undefined, () => undefined)
    return next
  }

  private assertActive(): void {
    if (this.disposed) throw new PlaygroundClientError('SESSION_LOST', 'The playground session was disposed', false)
  }
}
