import { completionStatus, selectedCompletionIndex, startCompletion } from '@codemirror/autocomplete'
import { Transaction } from '@codemirror/state'
import { EditorView } from '@codemirror/view'
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import CodeCell from '../src/CodeCell.vue'

describe('CodeCell', () => {
  it('accepts the selected completion with Tab', async () => {
    const complete = vi.fn(async () => [{
      value: 'setblock',
      description: 'Place a block',
      group: 'command',
      start: 0,
      end: 3,
      appendSpace: true,
    }])
    const wrapper = mount(CodeCell, {
      attachTo: document.body,
      props: {
        modelValue: 'set',
        cellId: 'stone',
        readOnly: false,
        disabled: false,
        diagnostics: [],
        complete,
        check: async () => [],
      },
    })
    const view = EditorView.findFromDOM(wrapper.get('.cm-editor').element as HTMLElement)
    expect(view).not.toBeNull()
    startCompletion(view!)
    await vi.waitFor(() => expect(completionStatus(view!.state)).toBe('active'))
    await new Promise((resolve) => setTimeout(resolve, 80))
    expect(complete).toHaveBeenCalledOnce()

    view!.dispatch({
      changes: { from: view!.state.doc.length, insert: 'b' },
      annotations: Transaction.userEvent.of('input.type'),
    })
    await new Promise((resolve) => setTimeout(resolve, 220))
    expect(complete).toHaveBeenCalledOnce()

    view!.contentDOM.dispatchEvent(new KeyboardEvent('keydown', {
      key: 'Tab',
      code: 'Tab',
      bubbles: true,
      cancelable: true,
    }))

    await vi.waitFor(() => expect(view!.state.doc.toString()).toBe('setblock '))
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['setblock '])
    wrapper.unmount()
  })

  it('applies structured completion ranges without appending a space', async () => {
    const source = 'tellraw @s {"co'
    const start = source.indexOf('"co')
    const complete = vi.fn(async () => [{
      value: '"color":',
      description: 'Text component field',
      group: 'text component',
      start,
      end: source.length,
      appendSpace: false,
    }])
    const wrapper = mount(CodeCell, {
      attachTo: document.body,
      props: {
        modelValue: source,
        cellId: 'structured-completion',
        readOnly: false,
        disabled: false,
        diagnostics: [],
        complete,
        check: async () => [],
      },
    })
    const view = EditorView.findFromDOM(wrapper.get('.cm-editor').element as HTMLElement)!
    view.dispatch({ selection: { anchor: source.length } })
    startCompletion(view)
    await vi.waitFor(() => expect(completionStatus(view.state)).toBe('active'))
    await new Promise((resolve) => setTimeout(resolve, 80))

    view.contentDOM.dispatchEvent(new KeyboardEvent('keydown', {
      key: 'Tab',
      code: 'Tab',
      bubbles: true,
      cancelable: true,
    }))

    await vi.waitFor(() => expect(view.state.doc.toString()).toBe('tellraw @s {"color":'))
    expect(view.state.doc.toString()).not.toMatch(/:\s$/)
    wrapper.unmount()
  })

  it('refetches a truncated completion batch as the prefix narrows', async () => {
    const complete = vi.fn(async (source: string) => {
      if (source === 's') {
        return Array.from({ length: 100 }, (_, index) => ({
          value: `stone_${index}`,
          description: 'Truncated block catalog',
          group: 'value',
          start: 0,
          end: 1,
          appendSpace: true,
        }))
      }
      return [{
        value: 'sulfur',
        description: 'Narrowed block catalog',
        group: 'value',
        start: 0,
        end: source.length,
        appendSpace: true,
      }]
    })
    const wrapper = mount(CodeCell, {
      attachTo: document.body,
      props: {
        modelValue: 's',
        cellId: 'truncated-completion',
        readOnly: false,
        disabled: false,
        diagnostics: [],
        complete,
        check: async () => [],
      },
    })
    const view = EditorView.findFromDOM(wrapper.get('.cm-editor').element as HTMLElement)!
    view.dispatch({ selection: { anchor: 1 } })
    startCompletion(view)
    await vi.waitFor(() => expect(completionStatus(view.state)).toBe('active'))

    view.dispatch({
      changes: { from: 1, insert: 'u' },
      selection: { anchor: 2 },
      annotations: Transaction.userEvent.of('input.type'),
    })

    await vi.waitFor(() => expect(complete).toHaveBeenCalledWith('su', 2))
    wrapper.unmount()
  })

  it('passes the full cell context and selects candidates with the arrow keys', async () => {
    const source = 'scoreboard objectives add qwq dummy\nset'
    const start = source.lastIndexOf('set')
    const complete = vi.fn(async () => [
      {
        value: 'setblock',
        description: 'Place a block',
        group: 'command',
        start,
        end: source.length,
        appendSpace: true,
      },
      {
        value: 'setworldspawn',
        description: 'Set the world spawn',
        group: 'command',
        start,
        end: source.length,
        appendSpace: true,
      },
    ])
    const wrapper = mount(CodeCell, {
      attachTo: document.body,
      props: {
        modelValue: source,
        cellId: 'contextual-completion',
        readOnly: false,
        disabled: false,
        diagnostics: [],
        complete,
        check: async () => [],
      },
    })
    const view = EditorView.findFromDOM(wrapper.get('.cm-editor').element as HTMLElement)!
    view.dispatch({ selection: { anchor: source.length } })
    startCompletion(view)
    await vi.waitFor(() => expect(completionStatus(view.state)).toBe('active'))
    expect(complete).toHaveBeenCalledWith(source, source.length)
    await new Promise((resolve) => setTimeout(resolve, 80))

    view.contentDOM.dispatchEvent(new KeyboardEvent('keydown', {
      key: 'ArrowDown',
      code: 'ArrowDown',
      bubbles: true,
      cancelable: true,
    }))
    await vi.waitFor(() => expect(selectedCompletionIndex(view.state)).toBe(1))
    view.contentDOM.dispatchEvent(new KeyboardEvent('keydown', {
      key: 'Tab',
      code: 'Tab',
      bubbles: true,
      cancelable: true,
    }))

    await vi.waitFor(() => expect(view.state.doc.toString()).toBe(
      'scoreboard objectives add qwq dummy\nsetworldspawn ',
    ))
    wrapper.unmount()
  })

  it('keeps Enter as a newline while completion is active', async () => {
    const source = 'scoreboard objectives add runs '
    const wrapper = mount(CodeCell, {
      attachTo: document.body,
      props: {
        modelValue: source,
        cellId: 'multiline-completion',
        readOnly: false,
        disabled: false,
        diagnostics: [],
        complete: async () => [{
          value: 'dummy',
          description: 'Objective criterion',
          group: 'value',
          start: source.length,
          end: source.length,
          appendSpace: true,
        }],
        check: async () => [],
      },
    })
    const view = EditorView.findFromDOM(wrapper.get('.cm-editor').element as HTMLElement)!
    view.dispatch({ selection: { anchor: source.length } })
    startCompletion(view)
    await vi.waitFor(() => expect(completionStatus(view.state)).toBe('active'))

    view.contentDOM.dispatchEvent(new KeyboardEvent('keydown', {
      key: 'Enter',
      code: 'Enter',
      bubbles: true,
      cancelable: true,
    }))

    await vi.waitFor(() => expect(view.state.doc.toString()).toBe(`${source}\n`))
    expect(view.state.doc.toString()).not.toContain('dummy')
    wrapper.unmount()
  })

  it('checks user edits without checking the initial or externally replaced preset', async () => {
    const check = vi.fn(async () => [])
    const complete = vi.fn(async () => [])
    const wrapper = mount(CodeCell, {
      attachTo: document.body,
      props: {
        modelValue: 'say initial',
        cellId: 'lint-gate',
        readOnly: false,
        disabled: false,
        diagnostics: [],
        complete,
        check,
      },
    })
    const view = EditorView.findFromDOM(wrapper.get('.cm-editor').element as HTMLElement)!
    await new Promise((resolve) => setTimeout(resolve, 750))
    expect(check).not.toHaveBeenCalled()

    view.dispatch({
      changes: { from: view.state.doc.length, insert: '!' },
      annotations: Transaction.userEvent.of('input.type'),
    })
    await vi.waitFor(() => expect(check).toHaveBeenCalledOnce(), { timeout: 1_500 })

    await wrapper.setProps({ modelValue: 'say preset ' })
    check.mockClear()
    await new Promise((resolve) => setTimeout(resolve, 750))
    expect(check).not.toHaveBeenCalled()
    expect(complete).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('uses Ctrl as definition-link mode without creating extra cursors and maps Mouse Back to the caller', async () => {
    const source = 'function demo:main\ndata get storage demo:state value'
    const wrapper = mount(CodeCell, {
      attachTo: document.body,
      props: {
        modelValue: source,
        cellId: 'navigation',
        readOnly: false,
        disabled: false,
        diagnostics: [],
        complete: async () => [],
        check: async () => [],
        canNavigateBack: true,
      },
    })
    const view = EditorView.findFromDOM(wrapper.get('.cm-editor').element as HTMLElement)!
    view.focus()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Control', ctrlKey: true, bubbles: true }))
    await vi.waitFor(() => expect(wrapper.findAll('.dps-function-link')).toHaveLength(1))
    expect(wrapper.get('.dps-function-link').text()).toBe('demo:main')

    const originalRanges = view.state.selection.ranges.length
    vi.spyOn(view, 'posAtCoords').mockReturnValue(source.indexOf('demo:state') + 2)
    view.contentDOM.dispatchEvent(new MouseEvent('mousedown', {
      button: 0,
      ctrlKey: true,
      bubbles: true,
      cancelable: true,
    }))
    expect(view.state.selection.ranges).toHaveLength(originalRanges)
    expect(wrapper.emitted('open-function')).toBeUndefined()

    vi.mocked(view.posAtCoords).mockReturnValue(source.indexOf('demo:main') + 2)
    view.contentDOM.dispatchEvent(new MouseEvent('mousedown', {
      button: 0,
      ctrlKey: true,
      bubbles: true,
      cancelable: true,
    }))
    expect(wrapper.emitted('open-function')?.at(-1)).toEqual(['demo:main'])

    view.contentDOM.blur()
    view.dom.focus()
    expect(document.activeElement).toBe(view.dom)
    const mouseBack = new MouseEvent('mousedown', { button: 3, bubbles: true, cancelable: true })
    window.dispatchEvent(mouseBack)
    expect(mouseBack.defaultPrevented).toBe(true)
    expect(wrapper.emitted('navigate-back')).toHaveLength(1)

    window.dispatchEvent(new KeyboardEvent('keyup', { key: 'Control', bubbles: true }))
    await vi.waitFor(() => expect(wrapper.find('.dps-function-link').exists()).toBe(false))
    wrapper.unmount()
  })
})
