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
  it('shares a named sandbox while keeping editors and results independent', async () => {
    const executions: Record<string, unknown>[] = []
    let firstRequest: Record<string, unknown> | undefined
    MockWorker.responder = (worker, request) => {
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      if (request.type === 'session.create') worker.emit({ type: 'session.ready', requestId: request.id, sessionId: 'named' })
      if (request.type === 'cell.execute') {
        executions.push(request)
        if (request.source === 'say first') {
          firstRequest = request
          return
        }
        worker.emit({
          type: 'cell.output', requestId: request.id, cellId: request.cellId, kind: 'execution',
          summary: `Executed ${request.source}.`, result: { source: request.source },
        })
        worker.emit({ type: 'cell.status', requestId: request.id, cellId: request.cellId, status: 'idle' })
      }
    }
    const first = mount(DpsCell, {
      props: { modelValue: 'say first', sandboxId: 'shared-world', animation: { captureOnExecute: false } },
      global: { stubs: { CodeCell: CodeCellStub } },
    })
    const second = mount(DpsCell, {
      props: { modelValue: 'say second', sandboxId: ' shared-world ', animation: { captureOnExecute: false } },
      global: { stubs: { CodeCell: CodeCellStub } },
    })
    await vi.waitFor(() => {
      expect(first.attributes('data-state')).toBe('ready')
      expect(second.attributes('data-state')).toBe('ready')
    })

    expect(MockWorker.instances).toHaveLength(1)
    expect(first.find('.code-cell-stub').text()).toBe('say first')
    expect(second.find('.code-cell-stub').text()).toBe('say second')
    await first.get('.dps-cell-actions button:first-child').trigger('click')
    await vi.waitFor(() => expect(firstRequest).toBeDefined())
    await flushPromises()
    expect(second.get('.dps-cell-actions button:first-child').attributes()).toHaveProperty('disabled')
    await (second.vm as unknown as { run(): Promise<void> }).run()
    expect(executions).toHaveLength(1)

    MockWorker.instances[0].emit({
      type: 'cell.output', requestId: firstRequest!.id, cellId: firstRequest!.cellId, kind: 'execution',
      summary: 'Executed say first.', result: { source: 'say first' },
    })
    MockWorker.instances[0].emit({
      type: 'cell.status', requestId: firstRequest!.id, cellId: firstRequest!.cellId, status: 'idle',
    })
    await vi.waitFor(() => expect(first.text()).toContain('Executed say first.'))
    expect(second.text()).not.toContain('Executed say first.')
    await vi.waitFor(() => expect(second.get('.dps-cell-actions button:first-child').attributes()).not.toHaveProperty('disabled'))
    await second.get('.dps-cell-actions button:first-child').trigger('click')
    await vi.waitFor(() => expect(second.text()).toContain('Executed say second.'))
    expect(executions[0].cellId).not.toBe(executions[1].cellId)

    first.unmount()
    expect(MockWorker.instances[0].terminated).toBe(false)
    second.unmount()
    expect(MockWorker.instances[0].terminated).toBe(true)
  })

  it('gives playgrounds without a sandbox id separate workers', async () => {
    MockWorker.responder = (worker, request) => {
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      if (request.type === 'session.create') worker.emit({ type: 'session.ready', requestId: request.id })
    }
    const first = mount(DpsCell, {
      props: { modelValue: 'say one' },
      global: { stubs: { CodeCell: CodeCellStub } },
    })
    const second = mount(DpsCell, {
      props: { modelValue: 'say two' },
      global: { stubs: { CodeCell: CodeCellStub } },
    })
    await vi.waitFor(() => {
      expect(first.attributes('data-state')).toBe('ready')
      expect(second.attributes('data-state')).toBe('ready')
    })
    expect(MockWorker.instances).toHaveLength(2)
    expect(first.attributes('data-sandbox-id')).toBeUndefined()
    expect(second.attributes('data-sandbox-id')).toBeUndefined()
    first.unmount()
    second.unmount()
    expect(MockWorker.instances.every((worker) => worker.terminated)).toBe(true)
  })

  it('keeps a compact viewport cell editable without advanced action chrome', async () => {
    const requests: Record<string, unknown>[] = []
    MockWorker.responder = (worker, request) => {
      requests.push(request)
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      if (request.type === 'session.create') worker.emit({ type: 'session.ready', requestId: request.id, sessionId: 'compact' })
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
    const wrapper = mount(DpsCell, {
      props: { modelValue: 'say compact', compact: true, animation: { captureOnExecute: false } },
      global: { stubs: { CodeCell: CodeCellStub } },
    })
    await vi.waitFor(() => expect(wrapper.attributes('data-state')).toBe('ready'))

    expect(wrapper.find('.dps-cell-code').exists()).toBe(true)
    expect(wrapper.find('.code-cell-stub').text()).toContain('say compact')
    expect(wrapper.findAll('.dps-cell-actions button')).toHaveLength(1)
    await (wrapper.vm as unknown as { run(): Promise<void> }).run()
    expect(requests.map((request) => request.type)).toContain('cell.execute')
    wrapper.unmount()
  })

  it('shows compact command outputs as readable expandable entries', async () => {
    MockWorker.responder = (worker, request) => {
      if (request.type === 'transport.connect') worker.emit({ type: 'transport.ready', requestId: request.id })
      if (request.type === 'session.create') worker.emit({ type: 'session.ready', requestId: request.id, sessionId: 'readable-output' })
      if (request.type === 'cell.execute') {
        worker.emit({
          type: 'cell.output',
          requestId: request.id,
          cellId: request.cellId,
          kind: 'execution',
          summary: 'Executed 1 command; 1 output; 0 state changes.',
          result: {
            commands: 1,
            outputs: [{
              tick: 4,
              command: 'scoreboard players get #value demo',
              channel: 'query',
              targets: ['Steve'],
              text: '42',
            }],
          },
        })
        worker.emit({ type: 'cell.status', requestId: request.id, cellId: request.cellId, status: 'idle' })
      }
    }
    const wrapper = mount(DpsCell, {
      props: { modelValue: 'scoreboard players get #value demo', compact: true, animation: { captureOnExecute: false } },
      global: { stubs: { CodeCell: CodeCellStub } },
    })
    await vi.waitFor(() => expect(wrapper.attributes('data-state')).toBe('ready'))
    await (wrapper.vm as unknown as { run(): Promise<void> }).run()
    await vi.waitFor(() => expect(wrapper.find('.dps-command-outputs').exists()).toBe(true))
    expect(wrapper.get('.dps-command-outputs summary').text()).toBe('Command outputs (1)')
    const details = wrapper.get('.dps-command-outputs')
    ;(details.element as HTMLDetailsElement).open = true
    await details.trigger('toggle')
    await flushPromises()
    expect(wrapper.get('.dps-command-output').text()).toContain('scoreboard players get #value demo')
    expect(wrapper.get('.dps-command-output').text()).toContain('42')
    expect(wrapper.find('.dps-structured-output').exists()).toBe(false)
    wrapper.unmount()
  })

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
    const fetchMock = vi.fn(async () => new Response(new Uint8Array([80, 75, 3, 4]), { status: 200 }))
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
