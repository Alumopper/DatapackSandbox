import { describe, expect, it, vi } from 'vitest'
import { PlaygroundClientError, PlaygroundWorkerClient } from '../src/client'
import { MockWorker } from './setup'

describe('PlaygroundWorkerClient', () => {
  it('creates an isolated Worker session and resolves typed requests', async () => {
    MockWorker.responder = (worker, request) => {
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      if (request.type === 'session.create') {
        worker.emit({ type: 'session.ready', requestId: request.id, sessionId: 'session-1' })
      }
      if (request.type === 'cell.complete') {
        worker.emit({
          type: 'cell.output',
          requestId: request.id,
          cellId: request.cellId,
          kind: 'completion',
          result: { suggestions: [{ value: 'minecraft:stone', start: 0, end: 2 }] },
        })
      }
      if (request.type === 'session.function.read') {
        worker.emit({
          type: 'session.function',
          requestId: request.id,
          kind: 'source',
          result: { id: request.functionId, path: 'data/demo/function/main.mcfunction', source: 'say source' },
        })
      }
    }
    const client = new PlaygroundWorkerClient()
    await client.connect()
    const ready = await client.createSession({ version: '26.2', cells: [] }, { auto: true })
    expect(ready.sessionId).toBe('session-1')
    await expect(client.complete('cell', 'mi', 2)).resolves.toEqual([
      { value: 'minecraft:stone', start: 0, end: 2 },
    ])
    await expect(client.readFunction('demo:main')).resolves.toEqual({
      id: 'demo:main',
      path: 'data/demo/function/main.mcfunction',
      source: 'say source',
    })
    expect(MockWorker.instances).toHaveLength(1)
    client.close()
    expect(MockWorker.instances[0].terminated).toBe(true)
  })

  it('rejects stable Worker errors', async () => {
    MockWorker.responder = (worker, request) => {
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      else worker.emit({
        type: 'cell.error',
        requestId: request.id,
        error: { code: 'PROFILE_NOT_ALLOWED', message: 'Profile denied', recoverable: true },
      })
    }
    const client = new PlaygroundWorkerClient()
    await client.connect()
    const failure = await client.createSession({ version: 'old', cells: [] }, {}).catch((error) => error)
    expect(failure).toBeInstanceOf(PlaygroundClientError)
    expect(failure.code).toBe('PROFILE_NOT_ALLOWED')
  })

  it('exposes checkpoint and animated GIF protocol requests', async () => {
    MockWorker.responder = (worker, request) => {
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      if (request.type === 'session.create') worker.emit({ type: 'session.ready', requestId: request.id, sessionId: 'history' })
      if (request.type === 'session.checkpoint.save' || request.type === 'session.checkpoint.restore') {
        worker.emit({
          type: 'session.checkpoint',
          requestId: request.id,
          kind: request.type === 'session.checkpoint.save' ? 'saved' : 'restored',
          result: { name: request.name, snapshot: { dayTime: 1000 } },
        })
      }
      if (request.type === 'animation.capture') {
        worker.emit({ type: 'animation.frame', requestId: request.id, result: { frameCount: 1 } })
      }
      if (request.type === 'animation.export') {
        worker.emit({
          type: 'animation.gif',
          requestId: request.id,
          mimeType: 'image/gif',
          bytes: new TextEncoder().encode('GIF89a').buffer,
          result: { frameCount: 1 },
        })
      }
    }
    const client = new PlaygroundWorkerClient()
    await client.connect()
    await client.createSession({ version: '26.2', cells: [] }, {})

    await expect(client.saveCheckpoint('branch')).resolves.toEqual({ name: 'branch', snapshot: { dayTime: 1000 } })
    await expect(client.restoreCheckpoint('branch')).resolves.toEqual({ name: 'branch', snapshot: { dayTime: 1000 } })
    await expect(client.captureAnimationFrame('cell', { width: 320, height: 180 }, 100)).resolves.toMatchObject({
      type: 'animation.frame',
    })
    await expect(client.exportAnimation('cell')).resolves.toMatchObject({ type: 'animation.gif', mimeType: 'image/gif' })
    client.close()
  })

  it('interrupts and rebuilds a Worker that ignores the watchdog', async () => {
    let connections = 0
    MockWorker.responder = (worker, request) => {
      if (request.type === 'transport.connect') {
        connections += 1
        worker.emit({ type: 'transport.ready', requestId: request.id })
      }
      if (request.type === 'session.create') worker.emit({ type: 'session.ready', requestId: request.id, sessionId: `session-${connections}` })
    }
    const client = new PlaygroundWorkerClient({ limits: { requestTimeoutMs: 5, cancelGraceMs: 5 } })
    await client.connect()
    await client.createSession({ version: '26.2', cells: [] }, {})
    const failure = await client.execute('cell', 'say never', {}).catch((error) => error)
    expect(failure).toBeInstanceOf(PlaygroundClientError)
    expect(failure.code).toBe('SESSION_LOST')
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(MockWorker.instances.length).toBeGreaterThanOrEqual(2)
  })

  it('preserves Worker error location and active operation when rebuilding', async () => {
    MockWorker.responder = (worker, request) => {
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      if (request.type === 'session.create') worker.emit({ type: 'session.ready', requestId: request.id, sessionId: 'diagnostics' })
      if (request.type === 'cell.execute') {
        queueMicrotask(() => worker.onerror?.(new ErrorEvent('error', {
          message: 'renderer allocation failed',
          filename: 'worker-runtime.js',
          lineno: 42,
          colno: 7,
        })))
      }
    }
    const client = new PlaygroundWorkerClient()
    await client.connect()
    await client.createSession({ version: '26.2', cells: [] }, {})
    const failure = await client.execute('cell', 'say crash', {}).catch((error) => error)
    expect(failure).toBeInstanceOf(PlaygroundClientError)
    expect(failure.code).toBe('SESSION_LOST')
    expect(failure.message).toContain('renderer allocation failed at worker-runtime.js:42:7 while handling cell.execute')
    client.close()
  })

  it('surfaces a fatal runtime report instead of replacing it with SESSION_LOST', async () => {
    MockWorker.responder = (worker, request) => {
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      if (request.type === 'session.create') worker.emit({ type: 'session.ready', requestId: request.id, sessionId: 'fatal-report' })
      if (request.type === 'cell.execute') {
        worker.emit({
          type: 'transport.fatal',
          error: {
            code: 'WORKER_RUNTIME_ERROR',
            message: 'Out of memory while compiling the scene',
            recoverable: false,
            details: { line: 91 },
          },
        })
      }
    }
    const client = new PlaygroundWorkerClient()
    await client.connect()
    await client.createSession({ version: '26.2', cells: [] }, {})
    const failure = await client.execute('cell', 'say crash', {}).catch((error) => error)
    expect(failure).toBeInstanceOf(PlaygroundClientError)
    expect(failure.code).toBe('WORKER_RUNTIME_ERROR')
    expect(failure.message).toBe('Out of memory while compiling the scene')
    expect(failure.details).toEqual({ line: 91 })
    client.close()
  })

  it('diagnoses an opaque startup failure without terminating the replacement Worker', async () => {
    let connections = 0
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: false,
      status: 404,
      statusText: 'Not Found',
      headers: new Headers({ 'content-type': 'text/html; charset=utf-8' }),
      body: null,
    } as Response)))
    MockWorker.responder = (worker, request) => {
      if (request.type === 'transport.connect') {
        connections += 1
        if (connections === 1) queueMicrotask(() => worker.onerror?.(new ErrorEvent('error')))
        else worker.emit({ type: 'transport.ready', requestId: request.id })
      }
      if (request.type === 'session.create') {
        worker.emit({ type: 'session.ready', requestId: request.id, sessionId: 'replacement' })
      }
    }
    const client = new PlaygroundWorkerClient({
      workerUrl: '/missing-worker.js',
      workerFactory: (url, options) => new MockWorker(url, options) as unknown as Worker,
    })
    const failure = await client.connect().catch((error) => error)
    expect(failure).toBeInstanceOf(PlaygroundClientError)
    expect(failure.code).toBe('SESSION_LOST')
    expect(failure.message).toContain('http://localhost:3000/missing-worker.js returned HTTP 404 Not Found')
    await vi.waitFor(() => expect(MockWorker.instances).toHaveLength(2))
    expect(MockWorker.instances[1].terminated).toBe(false)
    await expect(client.createSession({ version: '26.2', cells: [] }, {})).resolves.toMatchObject({ sessionId: 'replacement' })
    client.close()
  })

  it('retries an opaque module-Worker startup failure as a classic Worker', async () => {
    MockWorker.responder = (worker, request) => {
      if (request.type !== 'transport.connect') return
      if (worker.options?.type === 'module') {
        queueMicrotask(() => worker.onerror?.(new ErrorEvent('error')))
      } else {
        worker.emit({ type: 'transport.ready', requestId: request.id })
      }
    }
    // Exercise the package-default asset path as well as an explicit workerUrl:
    // both must drop `type: module` for the classic fallback.
    const client = new PlaygroundWorkerClient()
    await expect(client.connect()).resolves.toBeUndefined()
    expect(MockWorker.instances).toHaveLength(2)
    expect(MockWorker.instances[0].options?.type).toBe('module')
    expect(MockWorker.instances[0].terminated).toBe(true)
    expect(MockWorker.instances[1].options?.type).toBeUndefined()
    expect(MockWorker.instances[1].terminated).toBe(false)
    client.close()
  })

  it('cancels a chunked preset response when it exceeds the Worker import limit', async () => {
    let cancelled = false
    const chunks = [new Uint8Array([1, 2, 3]), new Uint8Array([4, 5, 6])]
    vi.stubGlobal('fetch', vi.fn(async () => new Response(new ReadableStream<Uint8Array>({
      pull(controller) {
        const chunk = chunks.shift()
        if (chunk) controller.enqueue(chunk)
      },
      cancel() {
        cancelled = true
      },
    }))))
    MockWorker.responder = (worker, request) => {
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      if (request.type === 'session.create') {
        worker.emit({ type: 'session.ready', requestId: request.id, sessionId: 'bounded-fetch' })
      }
    }
    const client = new PlaygroundWorkerClient({
      limits: { maximumImportBytes: 4 },
      presets: { oversized: { url: '/oversized.zip' } },
    })
    await client.connect()

    const failure = await client.createSession({ version: '26.2', preset: 'oversized', cells: [] }, {}).catch((error) => error)

    expect(failure).toBeInstanceOf(PlaygroundClientError)
    expect(failure.code).toBe('IMPORT_SIZE_LIMIT')
    expect(cancelled).toBe(true)
    client.close()
  })
})
