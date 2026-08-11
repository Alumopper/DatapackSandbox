<script setup lang="ts">
import {
  acceptCompletion,
  autocompletion,
  closeCompletion,
  completionStatus,
  moveCompletionSelection,
  startCompletion,
  type CompletionContext,
  type CompletionResult,
} from '@codemirror/autocomplete'
import { basicSetup } from 'codemirror'
import { insertNewlineAndIndent } from '@codemirror/commands'
import { Compartment, EditorState, Prec, StateEffect, StateField, Transaction } from '@codemirror/state'
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { linter, setDiagnostics, type Diagnostic } from '@codemirror/lint'
import { Decoration, EditorView, keymap, type DecorationSet } from '@codemirror/view'
import { tags } from '@lezer/highlight'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { functionReferenceAt, functionReferences } from './function-navigation'
import { mcfunctionLanguage } from './mcfunction'
import type { PlaygroundCompletion, PlaygroundDiagnostic } from './types'

const props = defineProps<{
  modelValue: string
  cellId: string
  readOnly: boolean
  disabled: boolean
  diagnostics: PlaygroundDiagnostic[]
  complete: (source: string, cursor: number) => Promise<PlaygroundCompletion[]>
  check: (source: string) => Promise<PlaygroundDiagnostic[]>
  canNavigateBack?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  run: []
  'open-function': [id: string]
  'navigate-back': []
}>()

const host = ref<HTMLElement>()
const editable = new Compartment()
let view: EditorView | undefined
let hasUserEdited = false
let suppressMouseBack = false
let mouseBackResetTimer: number | undefined
const maximumCompletionBatch = 100

const setFunctionLinkMode = StateEffect.define<boolean>()
const functionLinkMode = StateField.define<{ enabled: boolean; decorations: DecorationSet }>({
  create: () => ({ enabled: false, decorations: Decoration.none }),
  update: (value, transaction) => {
    let enabled = value.enabled
    for (const effect of transaction.effects) {
      if (effect.is(setFunctionLinkMode)) enabled = effect.value
    }
    if (!transaction.docChanged && enabled === value.enabled) return value
    return {
      enabled,
      decorations: enabled ? functionLinkDecorations(transaction.state) : Decoration.none,
    }
  },
  provide: (field) => EditorView.decorations.from(field, (value) => value.decorations),
})

function functionLinkDecorations(state: EditorState): DecorationSet {
  return Decoration.set(functionReferences(state.doc.toString()).map((reference) => (
    Decoration.mark({ class: 'dps-function-link' }).range(reference.from, reference.to)
  )), true)
}

function setFunctionLinks(enabled: boolean): void {
  if (!view || view.state.field(functionLinkMode).enabled === enabled) return
  view.dispatch({ effects: setFunctionLinkMode.of(enabled) })
}

function onWindowKeyDown(event: KeyboardEvent): void {
  if (event.key === 'Control' || event.key === 'Meta') setFunctionLinks(true)
}

function onWindowKeyUp(event: KeyboardEvent): void {
  if (event.key === 'Control' || event.key === 'Meta') setFunctionLinks(false)
}

function onWindowBlur(): void {
  setFunctionLinks(false)
}

function editorHasFocus(): boolean {
  if (!view) return false
  const activeElement = document.activeElement
  return view.hasFocus || activeElement === view.dom || (activeElement !== null && view.dom.contains(activeElement))
}

function onWindowMouseDown(event: MouseEvent): void {
  if (event.button !== 3 || !editorHasFocus() || !props.canNavigateBack) return
  event.preventDefault()
  event.stopPropagation()
  suppressMouseBack = true
  if (mouseBackResetTimer !== undefined) window.clearTimeout(mouseBackResetTimer)
  mouseBackResetTimer = window.setTimeout(() => { suppressMouseBack = false }, 500)
  emit('navigate-back')
}

function suppressWindowMouseBack(event: MouseEvent): void {
  if (event.button !== 3 || !suppressMouseBack) return
  event.preventDefault()
  event.stopPropagation()
}

const mcfunctionHighlightStyle = HighlightStyle.define([
  { tag: tags.keyword, color: 'var(--dps-syntax-keyword)', fontWeight: '650' },
  { tag: tags.typeName, color: 'var(--dps-syntax-type)' },
  { tag: tags.string, color: 'var(--dps-syntax-string)' },
  { tag: tags.number, color: 'var(--dps-syntax-number)' },
  { tag: tags.bool, color: 'var(--dps-syntax-bool)' },
  { tag: tags.special(tags.variableName), color: 'var(--dps-syntax-bool)' },
  { tag: tags.comment, color: 'var(--dps-syntax-comment)', fontStyle: 'italic' },
  { tag: tags.operator, color: 'var(--dps-syntax-operator)' },
  { tag: tags.punctuation, color: 'var(--dps-syntax-operator)' },
])

function mapDiagnostics(items: PlaygroundDiagnostic[], state: EditorState): Diagnostic[] {
  return items
    .filter((item) => item.severity !== 'ok')
    .map((item) => {
      const lineNumber = Math.min(Math.max(item.line, 1), state.doc.lines)
      const line = state.doc.line(lineNumber)
      const from = Math.min(line.from + Math.max(item.from ?? 0, 0), line.to)
      const to = Math.max(from, Math.min(line.from + (item.to ?? line.length), line.to))
      const severity: Diagnostic['severity'] = item.severity === 'ok' ? 'info' : item.severity
      return {
        from,
        to,
        severity,
        message: item.code ? `${item.code}: ${item.message}` : item.message,
      }
    })
}

async function completionSource(context: CompletionContext): Promise<CompletionResult | null> {
  const line = context.state.doc.lineAt(context.pos)
  const cursor = context.pos - line.from
  const prefix = line.text.slice(0, cursor).match(/[\w:#@~.^=+\-[\],]*$/)?.[0] ?? ''
  if (!context.explicit && prefix.length === 0) return null
  const suggestions = await props.complete(context.state.doc.toString(), context.pos)
  if (suggestions.length === 0) return null
  const result: CompletionResult = {
    from: Math.min(...suggestions.map((item) => item.start), context.pos),
    to: Math.max(...suggestions.map((item) => item.end), context.pos),
    options: suggestions.map((item) => ({
      label: item.value,
      detail: item.description,
      type: item.group === 'command' ? 'keyword' : 'text',
      apply: item.appendSpace ? `${item.value} ` : item.value,
    })),
  }
  // Let CodeMirror filter complete result sets locally. A full 100-item batch
  // may have been truncated by the engine, so continuing to type must query
  // again or late-sorting values (for example minecraft:sulfur) stay hidden.
  if (suggestions.length < maximumCompletionBatch) result.validFor = /^[\w:#@~.^=+\-[\],]*$/
  return result
}

async function lintSource(editor: EditorView): Promise<Diagnostic[]> {
  // CodeMirror schedules an initial lint pass and another pass for every
  // programmatic document replacement. Tutorial presets change the editor
  // this way while a 20 TPS simulation is active, so checking those unchanged
  // presets can monopolize the shared Worker. Only lint after a real editor
  // interaction; explicit Run still validates and reports command errors.
  if (!hasUserEdited) return []
  return mapDiagnostics(await props.check(editor.state.doc.toString()), editor.state)
}

onMounted(() => {
  view = new EditorView({
    parent: host.value,
    state: EditorState.create({
      doc: props.modelValue,
      extensions: [
        basicSetup,
        mcfunctionLanguage,
        syntaxHighlighting(mcfunctionHighlightStyle),
        functionLinkMode,
        editable.of([EditorView.editable.of(!props.readOnly), EditorState.readOnly.of(props.readOnly)]),
        Prec.high(keymap.of([
          {
            key: 'Enter',
            run: (editor) => {
              if (completionStatus(editor.state) === null) return false
              closeCompletion(editor)
              return insertNewlineAndIndent(editor)
            },
          },
          {
            key: 'Tab',
            run: acceptCompletion,
          },
          {
            key: 'ArrowDown',
            run: moveCompletionSelection(true),
          },
          {
            key: 'ArrowUp',
            run: moveCompletionSelection(false),
          },
          {
            key: 'Mod-Enter',
            run: () => {
              if (!props.disabled) emit('run')
              return true
            },
          },
          {
            key: 'Alt-ArrowLeft',
            run: () => {
              if (!props.canNavigateBack) return false
              emit('navigate-back')
              return true
            },
          },
        ])),
        autocompletion({
          override: [completionSource],
          activateOnTyping: true,
          activateOnTypingDelay: 140,
          // The editor advertises Tab as the acceptance key. Disable the
          // higher-priority Enter binding so multiline commands keep newlines.
          defaultKeymap: false,
        }),
        linter(lintSource, { delay: 650 }),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) {
            const userEdited = update.transactions.some(
              (transaction) => transaction.annotation(Transaction.userEvent) !== undefined,
            )
            if (userEdited) hasUserEdited = true
            emit('update:modelValue', update.state.doc.toString())
            const last = update.state.doc.sliceString(Math.max(0, update.state.selection.main.head - 1), update.state.selection.main.head)
            if (userEdited && /[\s:@\[\],=]/.test(last)) startCompletion(update.view)
          }
        }),
        EditorView.domEventHandlers({
          mousedown: (event, editor) => {
            if (event.button !== 0 || (!event.ctrlKey && !event.metaKey)) return false
            event.preventDefault()
            const position = editor.posAtCoords({ x: event.clientX, y: event.clientY })
            if (position !== null) {
              const reference = functionReferenceAt(editor.state.doc.toString(), position)
              if (reference) emit('open-function', reference.id)
            }
            // Ctrl/Meta is reserved for definition navigation in this editor.
            // Consume every modified click so CodeMirror cannot add a cursor.
            return true
          },
        }),
        EditorView.theme({
          '&': { minHeight: '72px', backgroundColor: 'transparent' },
          '.cm-scroller': {
            fontFamily: 'var(--vp-font-family-mono, ui-monospace, SFMono-Regular, Consolas, monospace)',
            lineHeight: '1.7',
          },
          '.cm-gutters': { backgroundColor: 'transparent', border: 'none' },
          '.cm-content': { caretColor: 'var(--dps-accent)' },
          '&.cm-focused': { outline: 'none' },
        }),
      ],
    }),
  })
  // Read-only function sources cannot focus CodeMirror's content DOM in every
  // browser. Keep the editor shell keyboard-focusable so Mouse Back can still
  // be scoped to the active source viewer instead of browser history.
  view.dom.tabIndex = 0
  window.addEventListener('keydown', onWindowKeyDown)
  window.addEventListener('keyup', onWindowKeyUp)
  window.addEventListener('blur', onWindowBlur)
  window.addEventListener('mousedown', onWindowMouseDown, true)
  window.addEventListener('mouseup', suppressWindowMouseBack, true)
  window.addEventListener('auxclick', suppressWindowMouseBack, true)
})

watch(() => props.modelValue, (value) => {
  if (!view || value === view.state.doc.toString()) return
  hasUserEdited = false
  view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value } })
})

watch(() => props.readOnly, (value) => {
  view?.dispatch({ effects: editable.reconfigure([EditorView.editable.of(!value), EditorState.readOnly.of(value)]) })
})

watch(() => props.diagnostics, (value) => {
  if (view) view.dispatch(setDiagnostics(view.state, mapDiagnostics(value, view.state)))
}, { deep: true })

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onWindowKeyDown)
  window.removeEventListener('keyup', onWindowKeyUp)
  window.removeEventListener('blur', onWindowBlur)
  window.removeEventListener('mousedown', onWindowMouseDown, true)
  window.removeEventListener('mouseup', suppressWindowMouseBack, true)
  window.removeEventListener('auxclick', suppressWindowMouseBack, true)
  if (mouseBackResetTimer !== undefined) window.clearTimeout(mouseBackResetTimer)
  view?.destroy()
})
</script>

<template>
  <div ref="host" class="dps-code-editor" :aria-label="`MCFunction cell ${cellId}`" />
</template>
