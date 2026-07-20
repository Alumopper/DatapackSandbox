import { afterEach, vi } from 'vitest'

export class MockWebSocket {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSING = 2
  static readonly CLOSED = 3
  static instances: MockWebSocket[] = []
  static failConnections = false
  static responder: ((socket: MockWebSocket, request: Record<string, unknown>) => void) | undefined

  readonly url: string
  readyState = MockWebSocket.CONNECTING
  onopen: ((event: Event) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  onclose: ((event: CloseEvent) => void) | null = null

  constructor(url: string) {
    this.url = url
    MockWebSocket.instances.push(this)
    queueMicrotask(() => {
      if (MockWebSocket.failConnections) {
        this.readyState = MockWebSocket.CLOSED
        this.onerror?.(new Event('error'))
      } else {
        this.readyState = MockWebSocket.OPEN
        this.onopen?.(new Event('open'))
      }
    })
  }

  send(raw: string): void {
    MockWebSocket.responder?.(this, JSON.parse(raw) as Record<string, unknown>)
  }

  emit(event: Record<string, unknown>): void {
    this.onmessage?.(new MessageEvent('message', { data: JSON.stringify(event) }))
  }

  close(): void {
    if (this.readyState === MockWebSocket.CLOSED) return
    this.readyState = MockWebSocket.CLOSED
    this.onclose?.(new CloseEvent('close'))
  }
}

Object.assign(globalThis, {
  WebSocket: MockWebSocket,
  ResizeObserver: class {
    observe() {}
    unobserve() {}
    disconnect() {}
  },
})

Object.assign(Range.prototype, {
  getBoundingClientRect: () => new DOMRect(),
  getClientRects: () => [],
})

afterEach(() => {
  MockWebSocket.instances = []
  MockWebSocket.failConnections = false
  MockWebSocket.responder = undefined
  vi.restoreAllMocks()
})
