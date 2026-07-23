import { afterEach, vi } from 'vitest'

export class MockWorker {
  static instances: MockWorker[] = []
  static failRequests = false
  static responder: ((worker: MockWorker, request: Record<string, unknown>) => void) | undefined

  readonly url: string
  onmessage: ((event: MessageEvent) => void) | null = null
  onerror: ((event: ErrorEvent) => void) | null = null
  onmessageerror: ((event: MessageEvent) => void) | null = null
  terminated = false

  constructor(url: string | URL) {
    this.url = String(url)
    MockWorker.instances.push(this)
  }

  postMessage(request: Record<string, unknown>): void {
    if (this.terminated) return
    if (MockWorker.failRequests) {
      queueMicrotask(() => this.onerror?.(new ErrorEvent('error', { message: 'Worker failed to start' })))
      return
    }
    MockWorker.responder?.(this, request)
  }

  emit(event: Record<string, unknown>): void {
    queueMicrotask(() => this.onmessage?.(new MessageEvent('message', { data: event })))
  }

  terminate(): void {
    this.terminated = true
  }
}

let blobSequence = 0
Object.assign(globalThis, {
  Worker: MockWorker,
  ResizeObserver: class {
    observe() {}
    unobserve() {}
    disconnect() {}
  },
})
Object.assign(URL, {
  createObjectURL: vi.fn(() => `blob:test-${++blobSequence}`),
  revokeObjectURL: vi.fn(),
})

Object.assign(Range.prototype, {
  getBoundingClientRect: () => new DOMRect(),
  getClientRects: () => [],
})

afterEach(() => {
  MockWorker.instances = []
  MockWorker.failRequests = false
  MockWorker.responder = undefined
  blobSequence = 0
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
