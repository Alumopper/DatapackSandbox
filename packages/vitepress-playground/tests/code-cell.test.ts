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
})
