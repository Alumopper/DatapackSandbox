import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import DpsCell from '../src/DpsCell.vue'
import { MockWorker } from './setup'

const CodeCellStub = {
  name: 'CodeCell',
  props: ['modelValue', 'cellId'],
  template: '<pre class="code-cell-stub">{{ modelValue }}</pre>',
}

describe('DpsCell', () => {
  it('embeds only one executable cell and its result', async () => {
    const requests: Record<string, unknown>[] = []
    MockWorker.responder = (worker, request) => {
      requests.push(request)
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      if (request.type === 'session.create') {
        worker.emit({ type: 'session.ready', requestId: request.id, sessionId: 'cell-session' })
      }
      if (request.type === 'cell.execute') {
        worker.emit({
          type: 'cell.output',
          requestId: request.id,
          cellId: request.cellId,
          kind: 'execution',
          summary: 'Executed 1 command; 0 outputs; 1 state change.',
          result: { commands: 1 },
        })
        worker.emit({ type: 'cell.status', requestId: request.id, cellId: request.cellId, status: 'idle' })
      }
      if (request.type === 'animation.capture') {
        worker.emit({ type: 'animation.frame', requestId: request.id, cellId: request.cellId, result: { frameCount: 1 } })
      }
    }
    const wrapper = mount(DpsCell, {
      props: { modelValue: 'setblock 0 0 0 minecraft:stone' },
      global: { stubs: { CodeCell: CodeCellStub } },
    })
    await flushPromises()
    await vi.waitFor(() => expect(wrapper.attributes('data-state')).toBe('ready'))

    expect(wrapper.find('.dps-toolbar').exists()).toBe(false)
    expect(wrapper.findAll('.dps-cell-actions button')).toHaveLength(7)
    expect(wrapper.text()).not.toContain('Import')
    expect(wrapper.find('.code-cell-stub').text()).toContain('setblock')

    const runButton = wrapper.get('.dps-cell-actions button:first-child')
    expect(runButton.attributes()).not.toHaveProperty('disabled')
    await runButton.trigger('click')
    await flushPromises()
    expect(requests.map((request) => request.type)).toContain('cell.execute')
    await vi.waitFor(() => expect(wrapper.text()).toContain('Executed 1 command'))
    expect(wrapper.emitted('executed')?.[0]?.[0]).toEqual({
      summary: 'Executed 1 command; 0 outputs; 1 state change.',
      raw: { commands: 1 },
    })
    const execute = requests.find((request) => request.type === 'cell.execute')
    expect(execute?.render).toEqual({ auto: false, width: 960, height: 540 })
    wrapper.unmount()
  })

  it('saves and restores a point and exports captured GIF frames', async () => {
    const requests: Record<string, unknown>[] = []
    MockWorker.responder = (worker, request) => {
      requests.push(request)
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      if (request.type === 'session.create') worker.emit({ type: 'session.ready', requestId: request.id, sessionId: 'history-session' })
      if (request.type === 'session.checkpoint.save') {
        worker.emit({
          type: 'session.checkpoint',
          requestId: request.id,
          kind: 'saved',
          result: { name: request.name, snapshot: { blocks: [{ id: 'minecraft:stone' }] } },
        })
      }
      if (request.type === 'session.checkpoint.restore') {
        worker.emit({
          type: 'session.checkpoint',
          requestId: request.id,
          kind: 'restored',
          result: { name: request.name, snapshot: { blocks: [{ id: 'minecraft:stone' }] } },
        })
      }
      if (request.type === 'cell.render') {
        worker.emit({
          type: 'cell.render',
          requestId: request.id,
          cellId: request.cellId,
          mimeType: 'image/png',
          bytes: new Uint8Array([137, 80, 78, 71]).buffer,
          width: 16,
          height: 16,
        })
      }
      if (request.type === 'animation.capture') {
        worker.emit({ type: 'animation.frame', requestId: request.id, cellId: request.cellId, result: { frameCount: 1 } })
      }
      if (request.type === 'animation.export') {
        worker.emit({
          type: 'animation.gif',
          requestId: request.id,
          cellId: request.cellId,
          mimeType: 'image/gif',
          bytes: new TextEncoder().encode('GIF89a').buffer,
          width: 480,
          height: 270,
          result: { frameCount: 1, repeat: 0 },
        })
      }
    }
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const wrapper = mount(DpsCell, {
      props: { modelValue: 'setblock 0 0 0 minecraft:stone' },
      global: { stubs: { CodeCell: CodeCellStub } },
    })
    await vi.waitFor(() => expect(wrapper.attributes('data-state')).toBe('ready'))

    await wrapper.get('[data-action="checkpoint"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-action="restore-point"]').attributes()).not.toHaveProperty('disabled')
    await wrapper.get('[data-action="restore-point"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-action="capture-frame"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-action="capture-frame"]').text()).toContain('(1)')
    await wrapper.get('[data-action="export-gif"]').trigger('click')
    await flushPromises()

    expect(requests.map((request) => request.type)).toEqual(expect.arrayContaining([
      'session.checkpoint.save',
      'session.checkpoint.restore',
      'animation.capture',
      'animation.export',
    ]))
    expect(wrapper.emitted('checkpoint')).toHaveLength(2)
    expect(wrapper.emitted('gif')?.[0]?.[0]).toMatchObject({ frameCount: 1, width: 480, height: 270 })
    expect(click).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('restores the initial source and sandbox state', async () => {
    const requests: string[] = []
    MockWorker.responder = (worker, request) => {
      requests.push(String(request.type))
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      if (request.type === 'session.create' || request.type === 'session.reset') {
        worker.emit({ type: 'session.ready', requestId: request.id })
      }
    }
    const wrapper = mount(DpsCell, {
      props: { modelValue: 'say before' },
      global: { stubs: { CodeCell: CodeCellStub } },
    })
    await flushPromises()
    wrapper.findComponent({ name: 'CodeCell' }).vm.$emit('update:modelValue', 'say after')
    await flushPromises()
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['say after'])
    await wrapper.get('.dps-cell-actions button:last-child').trigger('click')
    await flushPromises()
    expect(wrapper.find('.code-cell-stub').text()).toBe('say before')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['say before'])
    expect(requests).toContain('session.reset')
    wrapper.unmount()
  })

  it('loads ordered datapack and resource-pack dependencies before ready', async () => {
    const requests: Record<string, unknown>[] = []
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      arrayBuffer: async () => new Uint8Array([80, 75, 3, 4]).buffer,
    } as Response))
    vi.stubGlobal('fetch', fetchMock)
    MockWorker.responder = (worker, request) => {
      requests.push(request)
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      if (request.type === 'session.create') {
        worker.emit({ type: 'session.ready', requestId: request.id, sessionId: 'dependency-session' })
      }
      if (request.type === 'session.import') {
        worker.emit({
          type: 'session.imported',
          requestId: request.id,
          result: { kind: request.kind, files: 1, bytes: 4, functions: 0 },
        })
      }
    }
    const wrapper = mount(DpsCell, {
      props: {
        modelValue: 'function demo:main',
        dependencies: [
          { kind: 'datapack', url: '/packs/base.zip' },
          { kind: 'resource-pack', url: '/packs/assets.zip' },
        ],
      },
      global: { stubs: { CodeCell: CodeCellStub } },
    })

    await vi.waitFor(() => expect(wrapper.attributes('data-state')).toBe('ready'))
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(['/packs/base.zip', '/packs/assets.zip'])
    expect(requests.filter((request) => request.type === 'session.import').map((request) => request.kind)).toEqual([
      'datapack',
      'resource-pack',
    ])
    expect(wrapper.emitted('ready')?.[0]).toEqual(['dependency-session'])
    wrapper.unmount()
  })
})
