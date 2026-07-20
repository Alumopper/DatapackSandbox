import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import DpsPlayground from '../src/DpsPlayground.vue'
import { MockWebSocket } from './setup'

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
    MockWebSocket.responder = (socket, request) => {
      if (request.type === 'session.create') {
        socket.emit({ type: 'session.ready', requestId: request.id, sessionId: 'session-1' })
      }
      if (request.type === 'cell.execute') {
        socket.emit({ type: 'cell.status', requestId: request.id, cellId: request.cellId, status: 'running' })
        socket.emit({
          type: 'cell.output',
          requestId: request.id,
          cellId: request.cellId,
          kind: 'execution',
          summary: 'Executed 1 command; 0 outputs; 1 state change.',
          result: { commands: 1 },
        })
        socket.emit({
          type: 'cell.render',
          requestId: request.id,
          cellId: request.cellId,
          mimeType: 'image/png',
          data: 'iVBORw0KGgo=',
          width: 16,
          height: 16,
        })
        socket.emit({ type: 'cell.status', requestId: request.id, cellId: request.cellId, status: 'idle' })
      }
    }
    const wrapper = mount(DpsPlayground, {
      props: { notebook, apiUrl: 'https://playground.example.test' },
      global: { stubs: { CodeCell: CodeCellStub } },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('Minecraft 26.2')
    expect(wrapper.find('.dps-markdown h1').text()).toBe('Try it')
    expect(wrapper.find('.dps-markdown script').exists()).toBe(false)
    expect(wrapper.find('.code-cell-stub').text()).toContain('setblock')

    await wrapper.find('.dps-cell-actions button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Executed 1 command')
    expect(wrapper.find('img.dps-render').attributes('src')).toBe('data:image/png;base64,iVBORw0KGgo=')
    wrapper.unmount()
  })

  it('shows a clear unavailable state and retry control', async () => {
    MockWebSocket.failConnections = true
    const wrapper = mount(DpsPlayground, {
      props: { notebook, apiUrl: 'https://offline.example.test' },
      global: { stubs: { CodeCell: CodeCellStub } },
    })
    await flushPromises()
    expect(wrapper.find('.dps-unavailable').text()).toContain('Playground unavailable')
    expect(wrapper.find('.dps-unavailable button').text()).toContain('Retry')
    wrapper.unmount()
  })

  it('applies explicit theme and compact layout options', async () => {
    MockWebSocket.responder = (socket, request) => {
      if (request.type === 'session.create') socket.emit({ type: 'session.ready', requestId: request.id })
    }
    const wrapper = mount(DpsPlayground, {
      props: { notebook, apiUrl: 'https://playground.example.test', theme: 'dark', layout: 'compact', readOnly: true },
      global: { stubs: { CodeCell: CodeCellStub } },
    })
    await flushPromises()
    expect(wrapper.classes()).toContain('dps-theme-dark')
    expect(wrapper.classes()).toContain('dps-layout-compact')
    wrapper.unmount()
  })

  it('restores the original example source and resets the sandbox session', async () => {
    const requests: string[] = []
    MockWebSocket.responder = (socket, request) => {
      requests.push(String(request.type))
      if (request.type === 'session.create' || request.type === 'session.reset') {
        socket.emit({ type: 'session.ready', requestId: request.id, sessionId: 'session-1' })
      }
    }
    const wrapper = mount(DpsPlayground, {
      props: { notebook, apiUrl: 'https://playground.example.test' },
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
