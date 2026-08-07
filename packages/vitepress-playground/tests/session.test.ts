import { describe, expect, it, vi } from 'vitest'
import { PlaygroundSessionController } from '../src/session'
import type { PlaygroundViewportScene } from '../src/types'
import { MockWorker } from './setup'

describe('PlaygroundSessionController', () => {
  it('serializes editor operations and publishes shared busy state', async () => {
    const requests: Record<string, unknown>[] = []
    const activities: Array<{ busy: boolean; pending: number; cellId?: string }> = []
    MockWorker.responder = (worker, request) => {
      requests.push(request)
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      if (request.type === 'session.create') worker.emit({ type: 'session.ready', requestId: request.id, sessionId: 'serialized' })
    }
    const controller = new PlaygroundSessionController({ notebook: { version: '26.2', cells: [] } })
    controller.onActivity((activity) => activities.push(activity))
    await controller.connect()

    const first = controller.execute('editor-a', 'say first')
    const second = controller.execute('editor-b', 'say second')
    await vi.waitFor(() => expect(requests.filter((request) => request.type === 'cell.execute')).toHaveLength(1))
    expect(activities.at(-1)).toMatchObject({ busy: true, pending: 2, cellId: 'editor-a' })

    const firstRequest = requests.find((request) => request.type === 'cell.execute')!
    MockWorker.instances[0].emit({
      type: 'cell.status', requestId: firstRequest.id, cellId: firstRequest.cellId, status: 'idle',
    })
    await vi.waitFor(() => expect(requests.filter((request) => request.type === 'cell.execute')).toHaveLength(2))
    const secondRequest = requests.filter((request) => request.type === 'cell.execute')[1]
    MockWorker.instances[0].emit({
      type: 'cell.status', requestId: secondRequest.id, cellId: secondRequest.cellId, status: 'idle',
    })
    await Promise.all([first, second])

    expect(activities.at(-1)).toEqual({ busy: false, operation: undefined, cellId: undefined, pending: 0 })
    controller.dispose()
  })

  it('shares one Worker across scene, simulation, input, and reset operations', async () => {
    const requests: Record<string, unknown>[] = []
    const scene: PlaygroundViewportScene = {
      revision: 1,
      tick: 0,
      tickRate: 20,
      generatedAt: 0,
      vertexStride: 12,
      camera: { position: [6, 5, 6], yaw: -135, pitch: 25 },
      bounds: { minimum: [0, 0, 0], maximum: [1, 1, 1] },
      visibleBlocks: 1,
      visibleEntities: 0,
    }
    MockWorker.responder = (worker, request) => {
      requests.push(request)
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      if (request.type === 'session.create') worker.emit({ type: 'session.ready', requestId: request.id, sessionId: 'shared' })
      if (request.type === 'viewport.subscribe') {
        worker.emit({ type: 'viewport.subscribed', requestId: request.id })
        worker.emit({ type: 'viewport.scene', scene })
      }
      if (request.type === 'simulation.play') {
        worker.emit({ type: 'simulation.state', requestId: request.id, status: 'playing', result: { tickRate: request.tickRate } })
      }
      if (request.type === 'simulation.pause') {
        worker.emit({ type: 'simulation.state', requestId: request.id, status: 'paused', result: { reason: 'pause' } })
      }
      if (request.type === 'simulation.step') {
        worker.emit({ type: 'simulation.state', requestId: request.id, status: 'paused', result: { reason: 'step' } })
      }
      if (request.type === 'player.input') worker.emit({ type: 'player.input', requestId: request.id, result: { event: request.input } })
      if (request.type === 'session.reset') worker.emit({ type: 'session.ready', requestId: request.id, sessionId: 'shared' })
      if (request.type === 'viewport.unsubscribe') worker.emit({ type: 'viewport.unsubscribed', requestId: request.id })
    }
    const controller = new PlaygroundSessionController({ notebook: { version: '26.2', cells: [] } })
    const listener = vi.fn()
    const unsubscribe = controller.subscribeScene(listener)

    await controller.connect()
    await vi.waitFor(() => expect(listener).toHaveBeenCalledWith(scene))
    await controller.play(20, 'demo:extra_tick')
    expect(controller.isPlaying).toBe(true)
    await controller.dispatchInput({ device: 'keyboard', code: 'key.forward', action: 'press' })
    await controller.pause()
    await controller.step()
    await controller.restoreExample()

    expect(MockWorker.instances).toHaveLength(1)
    expect(requests.map((request) => request.type)).toEqual(expect.arrayContaining([
      'viewport.subscribe',
      'simulation.play',
      'player.input',
      'simulation.pause',
      'simulation.step',
      'session.reset',
    ]))
    expect(requests.find((request) => request.type === 'session.reset')).toMatchObject({ runLoad: true })
    unsubscribe()
    controller.dispose()
    expect(MockWorker.instances[0].terminated).toBe(true)
  })
})
