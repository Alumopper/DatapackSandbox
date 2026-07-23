import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DpsViewport from '../src/DpsViewport.vue'
import { MockWorker } from './setup'

const rendererSpies = vi.hoisted(() => ({
  updateScene: vi.fn(),
  resetView: vi.fn(),
  dispose: vi.fn(),
  handleOutput: vi.fn(),
}))

vi.mock('../src/webgl/renderer', () => ({
  WebglViewportRenderer: class {
    updateScene = rendererSpies.updateScene
    resetView = rendererSpies.resetView
    dispose = rendererSpies.dispose
    handleOutput = rendererSpies.handleOutput
    setMovement() {}
    look() {}
    adjustSpeed() {}
  },
}))

describe('DpsViewport', () => {
  beforeEach(() => {
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} })))
  })

  it('runs as a standalone lazy viewport and cleans up its owned session', async () => {
    MockWorker.responder = (worker, request) => {
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      if (request.type === 'session.create') worker.emit({ type: 'session.ready', requestId: request.id, sessionId: 'viewport' })
      if (request.type === 'viewport.subscribe') {
        worker.emit({ type: 'viewport.subscribed', requestId: request.id })
        worker.emit({
          type: 'viewport.scene',
          scene: {
            revision: 1,
            tick: 0,
            tickRate: 20,
            generatedAt: 0,
            vertexStride: 12,
            camera: { position: [6, 5, 6], yaw: -135, pitch: 25 },
            bounds: { minimum: [0, 0, 0], maximum: [1, 1, 1] },
            visibleBlocks: 1,
            visibleEntities: 0,
          },
        })
        worker.emit({
          type: 'viewport.output',
          output: {
            tick: 0,
            command: 'particle',
            channel: 'visual',
            text: 'minecraft:flame',
            targets: [],
            payload: { particle: 'minecraft:flame', x: 0, y: 1, z: 0, renderCount: 8 },
          },
        })
        worker.emit({
          type: 'viewport.output',
          output: { tick: 0, command: 'tellraw', channel: 'chat', text: 'Hello viewport', targets: ['Steve'] },
        })
        worker.emit({
          type: 'viewport.output',
          output: { tick: 0, command: 'title actionbar', channel: 'title', text: 'Ready', targets: ['Steve'] },
        })
      }
      if (request.type === 'simulation.play') {
        worker.emit({ type: 'simulation.state', requestId: request.id, status: 'playing', result: { tickRate: 20 } })
      }
      if (request.type === 'simulation.pause') {
        worker.emit({ type: 'simulation.state', requestId: request.id, status: 'paused', result: { reason: 'pause' } })
      }
      if (request.type === 'simulation.step') {
        worker.emit({ type: 'simulation.state', requestId: request.id, status: 'paused', result: { reason: 'step' } })
      }
    }
    const wrapper = mount(DpsViewport, {
      props: { notebook: { version: '26.2', cells: [] } },
    })

    await vi.waitFor(() => expect(wrapper.attributes('data-state')).toBe('ready'))
    expect(rendererSpies.updateScene).toHaveBeenCalled()
    expect(rendererSpies.handleOutput).toHaveBeenCalledWith(expect.objectContaining({ command: 'particle' }))
    expect(wrapper.text()).toContain('Hello viewport')
    expect(wrapper.text()).toContain('Ready')
    await wrapper.get('[data-action="viewport-play"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-action="viewport-play"]').text()).toBe('Pause')
    await wrapper.get('[data-action="viewport-play"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-action="viewport-step"]').trigger('click')
    await flushPromises()
    wrapper.unmount()

    expect(rendererSpies.dispose).toHaveBeenCalled()
    expect(MockWorker.instances[0].terminated).toBe(true)
  })
})
