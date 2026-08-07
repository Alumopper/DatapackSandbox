import { completionStatus, startCompletion } from '@codemirror/autocomplete'
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
