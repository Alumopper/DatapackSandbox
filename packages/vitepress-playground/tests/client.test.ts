import { describe, expect, it } from 'vitest'
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
    }
    const client = new PlaygroundWorkerClient()
    await client.connect()
    const ready = await client.createSession({ version: '26.2', cells: [] }, { auto: true })
    expect(ready.sessionId).toBe('session-1')
    await expect(client.complete('cell', 'mi', 2)).resolves.toEqual([
      { value: 'minecraft:stone', start: 0, end: 2 },
    ])
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
})
