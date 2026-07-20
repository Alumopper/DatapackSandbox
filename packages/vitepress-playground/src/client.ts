import type {
  PlaygroundCompletion,
  PlaygroundDiagnostic,
  PlaygroundEvent,
  PlaygroundNotebook,
  PlaygroundRenderOptions,
} from './types'

type EventListener = (event: PlaygroundEvent) => void
type ConnectionListener = (state: 'connecting' | 'open' | 'closed' | 'unavailable', message?: string) => void

interface PendingRequest {
  resolve: (event: PlaygroundEvent) => void
  reject: (error: PlaygroundClientError) => void
  terminal: (event: PlaygroundEvent) => boolean
  timer: number
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

export class PlaygroundClient {
  private socket?: WebSocket
  private sequence = 0
  private readonly pending = new Map<string, PendingRequest>()
  private readonly listeners = new Set<EventListener>()
  private readonly connectionListeners = new Set<ConnectionListener>()

  constructor(
    readonly apiUrl: string,
    private readonly socketFactory: (url: string) => WebSocket = (url) => new WebSocket(url),
    private readonly requestTimeoutMs = 15_000,
  ) {}

  onEvent(listener: EventListener): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  onConnection(listener: ConnectionListener): () => void {
    this.connectionListeners.add(listener)
    return () => this.connectionListeners.delete(listener)
  }

  async connect(timeoutMs = 8_000): Promise<void> {
    if (this.socket?.readyState === WebSocket.OPEN) return
    this.connectionListeners.forEach((listener) => listener('connecting'))
    const socket = this.socketFactory(toWebSocketEndpoint(this.apiUrl))
    this.socket = socket
    await new Promise<void>((resolve, reject) => {
      const timer = window.setTimeout(() => {
        socket.close()
        reject(new PlaygroundClientError('API_UNAVAILABLE', 'The playground API did not respond in time', false))
      }, timeoutMs)
      socket.onopen = () => {
        window.clearTimeout(timer)
        this.connectionListeners.forEach((listener) => listener('open'))
        resolve()
      }
      socket.onerror = () => {
        window.clearTimeout(timer)
        reject(new PlaygroundClientError('API_UNAVAILABLE', 'Unable to connect to the playground API', false))
      }
      socket.onmessage = (message) => this.receive(message.data)
      socket.onclose = () => this.handleClose('The playground connection closed')
    })
  }

  createSession(notebook: PlaygroundNotebook, render: PlaygroundRenderOptions, siteId?: string): Promise<PlaygroundEvent> {
    return this.request(
      'session.create',
      { version: notebook.version, preset: notebook.preset, siteId, render },
      (event) => event.type === 'session.ready',
    )
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

  reset(): Promise<PlaygroundEvent> {
    return this.request('session.reset', {}, (event) => event.type === 'session.ready')
  }

  interrupt(): Promise<PlaygroundEvent> {
    return this.request('session.interrupt', {}, (event) => event.type === 'cell.status')
  }

  close(): void {
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify({ id: this.nextId(), type: 'session.close' }))
    }
    this.socket?.close()
    this.socket = undefined
    this.rejectPending(new PlaygroundClientError('SESSION_LOST', 'The playground connection was closed', false))
  }

  private request(
    type: string,
    fields: Record<string, unknown>,
    terminal: (event: PlaygroundEvent) => boolean,
  ): Promise<PlaygroundEvent> {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      return Promise.reject(new PlaygroundClientError('API_UNAVAILABLE', 'The playground API is not connected', false))
    }
    const id = this.nextId()
    return new Promise((resolve, reject) => {
      const timer = window.setTimeout(() => {
        this.pending.delete(id)
        reject(new PlaygroundClientError('REQUEST_TIMEOUT', 'The playground API did not finish the request in time'))
      }, this.requestTimeoutMs)
      this.pending.set(id, { resolve, reject, terminal, timer })
      this.socket!.send(JSON.stringify({ id, type, ...withoutUndefined(fields) }))
    })
  }

  private receive(raw: unknown): void {
    let event: PlaygroundEvent
    try {
      const parsed = JSON.parse(String(raw)) as unknown
      if (!parsed || typeof parsed !== 'object' || typeof (parsed as PlaygroundEvent).type !== 'string') throw new Error('Invalid event')
      event = parsed as PlaygroundEvent
    } catch {
      this.handleClose('The playground API returned an invalid event')
      return
    }
    this.listeners.forEach((listener) => listener(event))
    const id = event.requestId == null ? undefined : String(event.requestId)
    if (!id) return
    const pending = this.pending.get(id)
    if (!pending) return
    if (event.type === 'cell.error' && event.error) {
      this.pending.delete(id)
      window.clearTimeout(pending.timer)
      pending.reject(new PlaygroundClientError(event.error.code, event.error.message, event.error.recoverable, event.error.details))
    } else if (pending.terminal(event)) {
      this.pending.delete(id)
      window.clearTimeout(pending.timer)
      pending.resolve(event)
    }
  }

  private handleClose(message: string): void {
    this.connectionListeners.forEach((listener) => listener('closed', message))
    this.rejectPending(new PlaygroundClientError('SESSION_LOST', message, false))
  }

  private rejectPending(error: PlaygroundClientError): void {
    this.pending.forEach((request) => {
      window.clearTimeout(request.timer)
      request.reject(error)
    })
    this.pending.clear()
  }

  private nextId(): string {
    this.sequence += 1
    return `web-${this.sequence}`
  }
}

export function toWebSocketEndpoint(apiUrl: string): string {
  const base = typeof window === 'undefined' ? 'http://localhost/' : window.location.href
  const url = new URL(apiUrl, base)
  if (url.protocol === 'http:') url.protocol = 'ws:'
  if (url.protocol === 'https:') url.protocol = 'wss:'
  if (url.protocol !== 'ws:' && url.protocol !== 'wss:') {
    throw new PlaygroundClientError('INVALID_API_URL', 'Playground API URL must use http, https, ws, or wss', false)
  }
  const normalized = url.pathname.replace(/\/+$/, '')
  url.pathname = normalized.endsWith('/v1/playground')
    ? normalized
    : `${normalized}/v1/playground`.replace(/^\/\//, '/')
  url.hash = ''
  return url.toString()
}

function withoutUndefined(fields: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(Object.entries(fields).filter(([, value]) => value !== undefined))
}
