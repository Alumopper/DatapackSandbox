import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import DpsPlayground from '../src/DpsPlayground.vue'
import { MockWorker } from './setup'

const notebook = {
  version: '26.2',
  cells: [
    { type: 'markdown' as const, source: '# Try it\n<script>unsafe()</script>' },
    { id: 'stone', type: 'code' as const, source: 'setblock 0 0 2 minecraft:stone' },
  ],
}

const CodeCellStub = {
  name: 'CodeCell',
  props: ['modelValue', 'cellId'],
  template: '<pre class="code-cell-stub">{{ modelValue }}</pre>',
}

describe('DpsPlayground', () => {
  it('renders notebook cells, execution output, and an inline PNG', async () => {
    MockWorker.responder = (worker, request) => {
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      if (request.type === 'session.create') {
        worker.emit({ type: 'session.ready', requestId: request.id, sessionId: 'session-1' })
      }
      if (request.type === 'cell.execute') {
        worker.emit({ type: 'cell.status', requestId: request.id, cellId: request.cellId, status: 'running' })
        worker.emit({
          type: 'cell.output',
          requestId: request.id,
          cellId: request.cellId,
          kind: 'execution',
          summary: 'Executed 1 command; 0 outputs; 1 state change.',
          result: { commands: 1 },
        })
        worker.emit({
          type: 'cell.render',
          requestId: request.id,
          cellId: request.cellId,
          mimeType: 'image/png',
          bytes: new Uint8Array([137, 80, 78, 71]).buffer,
          width: 16,
          height: 16,
        })
        worker.emit({ type: 'cell.status', requestId: request.id, cellId: request.cellId, status: 'idle' })
      }
      if (request.type === 'animation.capture') {
        worker.emit({ type: 'animation.frame', requestId: request.id, cellId: request.cellId, result: { frameCount: 1 } })
      }
    }
    const wrapper = mount(DpsPlayground, {
      props: { notebook },
      global: { stubs: { CodeCell: CodeCellStub } },
    })
    await flushPromises()
    await vi.waitFor(() => expect(wrapper.text()).toContain('Minecraft 26.2'))
    expect(wrapper.find('.dps-markdown h1').text()).toBe('Try it')
    expect(wrapper.find('.dps-markdown script').exists()).toBe(false)
    expect(wrapper.find('.code-cell-stub').text()).toContain('setblock')

    await wrapper.find('.dps-cell-actions button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Executed 1 command')
    expect(wrapper.find('img.dps-render').attributes('src')).toMatch(/^blob:test-/)
    wrapper.unmount()
  })

  it('shows a clear unavailable state and retry control', async () => {
    MockWorker.failRequests = true
    const wrapper = mount(DpsPlayground, {
      props: { notebook },
      global: { stubs: { CodeCell: CodeCellStub } },
    })
    await flushPromises()
    expect(wrapper.find('.dps-unavailable').text()).toContain('Local sandbox unavailable')
    expect(wrapper.find('.dps-unavailable button').text()).toContain('Restart')
    wrapper.unmount()
  })

  it('applies explicit theme and compact layout options', async () => {
    MockWorker.responder = (worker, request) => {
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      if (request.type === 'session.create') worker.emit({ type: 'session.ready', requestId: request.id })
    }
    const wrapper = mount(DpsPlayground, {
      props: { notebook, theme: 'dark', layout: 'compact', readOnly: true },
      global: { stubs: { CodeCell: CodeCellStub } },
    })
    await flushPromises()
    expect(wrapper.classes()).toContain('dps-theme-dark')
    expect(wrapper.classes()).toContain('dps-layout-compact')
    wrapper.unmount()
  })

  it('restores the original example source and resets the sandbox session', async () => {
    const requests: string[] = []
    MockWorker.responder = (worker, request) => {
      requests.push(String(request.type))
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      if (request.type === 'session.create' || request.type === 'session.reset') {
        worker.emit({ type: 'session.ready', requestId: request.id, sessionId: 'session-1' })
      }
    }
    const wrapper = mount(DpsPlayground, {
      props: { notebook },
      global: { stubs: { CodeCell: CodeCellStub } },
    })
    await flushPromises()

    const restore = wrapper.get('[data-action="restore"]')
    expect(restore.attributes()).toHaveProperty('disabled')
    wrapper.findComponent({ name: 'CodeCell' }).vm.$emit('update:modelValue', 'say changed')
    await flushPromises()
    expect(wrapper.find('.code-cell-stub').text()).toBe('say changed')
    expect(restore.attributes()).not.toHaveProperty('disabled')

    await restore.trigger('click')
    await flushPromises()
    expect(wrapper.find('.code-cell-stub').text()).toContain('setblock 0 0 2 minecraft:stone')
    expect(requests).toContain('session.reset')
    expect(restore.attributes()).toHaveProperty('disabled')
    wrapper.unmount()
  })
})
