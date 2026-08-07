import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DpsViewport from '../src/DpsViewport.vue'
import { MockWorker } from './setup'

const rendererSpies = vi.hoisted(() => ({
  updateScene: vi.fn(),
  resetView: vi.fn(),
  dispose: vi.fn(),
  handleOutput: vi.fn(),
  look: vi.fn(),
}))

vi.mock('../src/webgl/renderer', () => ({
  WebglViewportRenderer: class {
    updateScene = rendererSpies.updateScene
    resetView = rendererSpies.resetView
    dispose = rendererSpies.dispose
    handleOutput = rendererSpies.handleOutput
    setMovement() {}
    look = rendererSpies.look
    adjustSpeed() {}
  },
}))

describe('DpsViewport', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} })))
  })

  it('turns the camera right when the locked mouse moves right', async () => {
    MockWorker.responder = (worker, request) => {
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      if (request.type === 'session.create') worker.emit({ type: 'session.ready', requestId: request.id, sessionId: 'mouse-direction' })
      if (request.type === 'viewport.subscribe') worker.emit({ type: 'viewport.subscribed', requestId: request.id })
    }
    const wrapper = mount(DpsViewport, {
      attachTo: document.body,
      props: { notebook: { version: '26.2', cells: [] }, options: { mouseSensitivity: 0.12 } },
    })
    await vi.waitFor(() => expect(wrapper.attributes('data-state')).toBe('ready'))
    const canvas = wrapper.get('canvas').element as HTMLCanvasElement
    Object.defineProperty(document, 'pointerLockElement', { configurable: true, value: canvas })
    const movement = new MouseEvent('mousemove', { bubbles: true })
    Object.defineProperties(movement, {
      movementX: { value: 10 },
      movementY: { value: -2 },
    })
    document.dispatchEvent(movement)
    expect(rendererSpies.look).toHaveBeenCalledWith(1.2, -0.24)
    Object.defineProperty(document, 'pointerLockElement', { configurable: true, value: null })
    wrapper.unmount()
  })

  it('joins a page-local named sandbox and reference-counts its Worker', async () => {
    MockWorker.responder = (worker, request) => {
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      if (request.type === 'session.create') worker.emit({ type: 'session.ready', requestId: request.id, sessionId: 'shared-viewports' })
      if (request.type === 'viewport.subscribe') worker.emit({ type: 'viewport.subscribed', requestId: request.id })
      if (request.type === 'viewport.unsubscribe') worker.emit({ type: 'viewport.unsubscribed', requestId: request.id })
    }
    const props = { notebook: { version: '26.2', cells: [] }, sandboxId: 'viewport-world' }
    const first = mount(DpsViewport, { props })
    await vi.waitFor(() => expect(first.attributes('data-state')).toBe('ready'))
    const second = mount(DpsViewport, { props })
    await vi.waitFor(() => expect(second.attributes('data-state')).toBe('ready'))
    expect(MockWorker.instances).toHaveLength(1)
    expect(first.attributes('data-sandbox-id')).toBe('viewport-world')
    expect(second.attributes('data-sandbox-id')).toBe('viewport-world')
    expect(second.find('.dps-viewport-message').exists()).toBe(false)
    first.unmount()
    expect(MockWorker.instances[0].terminated).toBe(false)
    second.unmount()
    expect(MockWorker.instances[0].terminated).toBe(true)
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

  it('opens a debounced command console with T and executes the selected completion', async () => {
    const requests: Record<string, unknown>[] = []
    MockWorker.responder = (worker, request) => {
      requests.push(request)
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      if (request.type === 'session.create') worker.emit({ type: 'session.ready', requestId: request.id, sessionId: 'commands' })
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
            camera: { position: [0, 2, 4], yaw: 180, pitch: 0 },
            bounds: { minimum: [0, 0, 0], maximum: [1, 1, 1] },
            visibleBlocks: 0,
            visibleEntities: 0,
          },
        })
      }
      if (request.type === 'cell.complete') {
        worker.emit({
          type: 'cell.output',
          requestId: request.id,
          cellId: request.cellId,
          kind: 'completion',
          result: { suggestions: [{ value: 'function', start: 0, end: 3, appendSpace: true }] },
        })
      }
      if (request.type === 'cell.check') {
        worker.emit({ type: 'diagnostic', requestId: request.id, cellId: request.cellId, diagnostics: [] })
      }
      if (request.type === 'cell.execute') {
        worker.emit({
          type: 'cell.output',
          requestId: request.id,
          cellId: request.cellId,
          kind: 'execution',
          summary: 'Executed 1 command.',
          result: { commands: 1 },
        })
        worker.emit({ type: 'cell.status', requestId: request.id, cellId: request.cellId, status: 'idle' })
      }
    }
    const wrapper = mount(DpsViewport, {
      attachTo: document.body,
      props: { notebook: { version: '26.2', cells: [] }, options: { keyboard: true } },
    })
    await vi.waitFor(() => expect(wrapper.attributes('data-state')).toBe('ready'))

    const canvas = wrapper.get('canvas').element as HTMLCanvasElement
    canvas.focus()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 't', code: 'KeyT', bubbles: true, cancelable: true }))
    await flushPromises()
    const input = wrapper.get<HTMLInputElement>('[aria-label="Sandbox command"]')
    await input.setValue('/fun')
    await vi.waitFor(() => expect(wrapper.text()).toContain('function'))
    expect(requests.filter((request) => request.type === 'cell.complete')).toHaveLength(1)

    await input.trigger('keydown', { key: 'Tab' })
    expect(input.element.value).toBe('/function ')
    await input.trigger('keydown', { key: 'Enter' })
    await vi.waitFor(() => expect(requests.some((request) => request.type === 'cell.execute')).toBe(true))
    expect(wrapper.text()).toContain('Executed 1 command.')
    wrapper.unmount()
  })
})
