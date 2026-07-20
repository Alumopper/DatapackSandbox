import { completionStatus, startCompletion } from '@codemirror/autocomplete'
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
})
