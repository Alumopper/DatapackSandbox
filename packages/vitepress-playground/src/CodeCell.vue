<script setup lang="ts">
import {
  acceptCompletion,
  autocompletion,
  startCompletion,
  type CompletionContext,
  type CompletionResult,
} from '@codemirror/autocomplete'
import { basicSetup } from 'codemirror'
import { Compartment, EditorState, Prec, Transaction } from '@codemirror/state'
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { linter, setDiagnostics, type Diagnostic } from '@codemirror/lint'
import { EditorView, keymap } from '@codemirror/view'
import { tags } from '@lezer/highlight'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
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
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  run: []
}>()

const host = ref<HTMLElement>()
const editable = new Compartment()
let view: EditorView | undefined
let hasUserEdited = false

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
  const suggestions = await props.complete(line.text, cursor)
  if (suggestions.length === 0) return null
  return {
    from: line.from + Math.min(...suggestions.map((item) => item.start), cursor),
    to: line.from + Math.max(...suggestions.map((item) => item.end), cursor),
    // Let CodeMirror filter the already-fetched token candidates while the
    // user keeps typing. Without validFor, every keystroke crosses the Worker
    // boundary and queues another Kotlin completion pass.
    validFor: /^[\w:#@~.^=+\-[\],]*$/,
    options: suggestions.map((item) => ({
      label: item.value,
      detail: item.description,
      type: item.group === 'command' ? 'keyword' : 'text',
      apply: item.appendSpace ? `${item.value} ` : item.value,
    })),
  }
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
        editable.of([EditorView.editable.of(!props.readOnly), EditorState.readOnly.of(props.readOnly)]),
        Prec.high(keymap.of([
          {
            key: 'Tab',
            run: acceptCompletion,
          },
          {
            key: 'Mod-Enter',
            run: () => {
              if (!props.disabled) emit('run')
              return true
            },
          },
        ])),
        autocompletion({ override: [completionSource], activateOnTyping: true, activateOnTypingDelay: 140 }),
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

onBeforeUnmount(() => view?.destroy())
</script>

<template>
  <div ref="host" class="dps-code-editor" :aria-label="`MCFunction cell ${cellId}`" />
</template>
