<script setup lang="ts">
import MarkdownIt from 'markdown-it'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import CodeCell from './CodeCell.vue'
import { PlaygroundClient, PlaygroundClientError } from './client'
import type {
  PlaygroundCell,
  PlaygroundDiagnostic,
  PlaygroundEvent,
  PlaygroundLayout,
  PlaygroundNotebook,
  PlaygroundRenderOptions,
  PlaygroundTheme,
} from './types'

type LocalCell = PlaygroundCell & { id: string }

interface CellResult {
  status: 'idle' | 'running' | 'interrupting'
  summary?: string
  raw?: unknown
  error?: { code: string; message: string }
  diagnostics: PlaygroundDiagnostic[]
  image?: { src: string; width?: number; height?: number }
  hasRun: boolean
}

const props = withDefaults(defineProps<{
  notebook: PlaygroundNotebook
  apiUrl: string
  theme?: PlaygroundTheme
  layout?: PlaygroundLayout
  readOnly?: boolean
  render?: PlaygroundRenderOptions
  siteId?: string
}>(), {
  theme: 'auto',
  layout: 'notebook',
  readOnly: false,
  render: () => ({ auto: true, width: 960, height: 540 }),
})

const emit = defineEmits<{
  ready: [sessionId: string]
  error: [error: { code: string; message: string }]
}>()

const markdown = new MarkdownIt({ html: false, linkify: true, typographer: false })
const cells = ref<LocalCell[]>(normalizeCells(props.notebook.cells))
const results = reactive<Record<string, CellResult>>({})
const connection = ref<'connecting' | 'ready' | 'unavailable' | 'closed'>('connecting')
const connectionMessage = ref('Connecting to the playground API…')
const runningAll = ref(false)
const sessionAction = ref<'reset' | 'restore'>()
let client: PlaygroundClient | undefined
let disposed = false
let stopRunAll = false

const rootClasses = computed(() => [
  `dps-theme-${props.theme}`,
  `dps-layout-${props.layout}`,
  { 'dps-is-busy': isBusy.value },
])
const hasRunningCell = computed(() => Object.values(results).some((item) => item.status === 'running' || item.status === 'interrupting'))
const isBusy = computed(() => hasRunningCell.value || runningAll.value || sessionAction.value !== undefined)
const hasExampleChanges = computed(() => {
  const initial = normalizeCells(props.notebook.cells)
  const sourceChanged = initial.length !== cells.value.length || initial.some((cell, index) => {
    const current = cells.value[index]
    return !current || current.id !== cell.id || current.type !== cell.type || current.source !== cell.source
  })
  const hasResults = Object.values(results).some((result) => (
    result.hasRun
    || result.summary !== undefined
    || result.raw !== undefined
    || result.error !== undefined
    || result.image !== undefined
    || result.diagnostics.length > 0
  ))
  return sourceChanged || hasResults
})
const connectionSummary = computed(() => {
  if (connection.value === 'ready') return `Minecraft ${props.notebook.version}`
  if (connection.value === 'connecting') return 'Connecting to Playground API'
  if (connection.value === 'closed') return 'Session closed'
  return 'Playground API unavailable'
})

function cellResult(id: string): CellResult {
  if (!results[id]) results[id] = { status: 'idle', diagnostics: [], hasRun: false }
  return results[id]
}

async function connect(): Promise<void> {
  client?.close()
  connection.value = 'connecting'
  connectionMessage.value = 'Connecting to the playground API…'
  const next = new PlaygroundClient(props.apiUrl)
  client = next
  next.onEvent(handleEvent)
  next.onConnection((state, message) => {
    if (client === next && state === 'closed' && !disposed) {
      connection.value = 'unavailable'
      connectionMessage.value = message ?? 'Playground unavailable.'
    }
  })
  try {
    await next.connect()
    const ready = await next.createSession(props.notebook, effectiveRender(), props.siteId)
    if (client !== next || disposed) return
    connection.value = 'ready'
    connectionMessage.value = ''
    if (ready.sessionId) emit('ready', ready.sessionId)
  } catch (error) {
    if (client !== next || disposed) return
    setUnavailable(error)
  }
}

async function runCell(cell: LocalCell): Promise<void> {
  if (cell.type !== 'code' || !client || connection.value !== 'ready') return
  const result = cellResult(cell.id)
  result.error = undefined
  result.diagnostics = []
  result.status = 'running'
  try {
    await client.execute(cell.id, cell.source, effectiveRender())
    result.hasRun = true
  } catch (error) {
    handleClientError(cell.id, error)
  } finally {
    if (result.status === 'running') result.status = 'idle'
  }
}

async function runAll(): Promise<void> {
  if (runningAll.value) return
  runningAll.value = true
  stopRunAll = false
  try {
    for (const cell of cells.value) {
      if (cell.type === 'code') await runCell(cell)
      if (stopRunAll || connection.value !== 'ready') break
    }
  } finally {
    runningAll.value = false
  }
}

async function interrupt(): Promise<void> {
  stopRunAll = true
  try {
    await client?.interrupt()
  } catch (error) {
    handleClientError(undefined, error)
  }
}

async function reset(): Promise<void> {
  if (!client || connection.value !== 'ready' || sessionAction.value) return
  stopRunAll = true
  sessionAction.value = 'reset'
  try {
    await client.reset()
    clearResults()
  } catch (error) {
    handleClientError(undefined, error)
  } finally {
    sessionAction.value = undefined
  }
}

async function restoreExample(): Promise<void> {
  if (sessionAction.value) return
  stopRunAll = true
  sessionAction.value = 'restore'
  try {
    if (client && connection.value === 'ready') await client.reset()
    cells.value = normalizeCells(props.notebook.cells)
    clearResults()
  } catch (error) {
    handleClientError(undefined, error)
  } finally {
    sessionAction.value = undefined
  }
}

async function renderCell(cell: LocalCell): Promise<void> {
  if (!client || cell.type !== 'code') return
  try {
    await client.render(cell.id, { ...effectiveRender(), auto: false })
  } catch (error) {
    handleClientError(cell.id, error)
  }
}

async function complete(cellId: string, source: string, cursor: number) {
  if (!client || connection.value !== 'ready' || isBusy.value) return []
  try {
    return await client.complete(cellId, source, cursor)
  } catch {
    return []
  }
}

async function check(cellId: string, source: string): Promise<PlaygroundDiagnostic[]> {
  if (!client || connection.value !== 'ready' || isBusy.value) return []
  try {
    return await client.check(cellId, source)
  } catch {
    return []
  }
}

function handleEvent(event: PlaygroundEvent): void {
  if (event.cellId) {
    const result = cellResult(event.cellId)
    if (event.type === 'cell.status' && (event.status === 'running' || event.status === 'interrupting' || event.status === 'idle')) {
      result.status = event.status
    } else if (event.type === 'cell.output' && event.kind === 'execution') {
      result.summary = event.summary
      result.raw = event.result
      result.error = undefined
      result.hasRun = true
    } else if (event.type === 'diagnostic') {
      result.diagnostics = event.diagnostics ?? []
    } else if (event.type === 'cell.render' && event.mimeType === 'image/png' && event.data) {
      result.image = { src: `data:image/png;base64,${event.data}`, width: event.width, height: event.height }
    } else if (event.type === 'cell.error' && event.error) {
      result.error = { code: event.error.code, message: event.error.message }
      if (event.error.code === 'SESSION_LOST') setUnavailable(new PlaygroundClientError(event.error.code, event.error.message, false))
    }
  }
}

function handleClientError(cellId: string | undefined, error: unknown): void {
  const normalized = error instanceof PlaygroundClientError
    ? error
    : new PlaygroundClientError('PLAYGROUND_ERROR', error instanceof Error ? error.message : 'Playground request failed')
  if (cellId) cellResult(cellId).error = { code: normalized.code, message: normalized.message }
  emit('error', { code: normalized.code, message: normalized.message })
  if (!normalized.recoverable || normalized.code === 'API_UNAVAILABLE' || normalized.code === 'SESSION_LOST') setUnavailable(normalized)
}

function setUnavailable(error: unknown): void {
  const message = error instanceof Error ? error.message : 'The playground API is unavailable.'
  connection.value = 'unavailable'
  connectionMessage.value = `Playground unavailable: ${message}`
}

function effectiveRender(): PlaygroundRenderOptions {
  return { auto: props.render.auto ?? true, width: props.render.width ?? 960, height: props.render.height ?? 540 }
}

function updateSource(cell: LocalCell, source: string): void {
  cell.source = source
}

function clearResults(): void {
  Object.keys(results).forEach((id) => delete results[id])
}

watch(() => props.notebook, (notebook) => {
  cells.value = normalizeCells(notebook.cells)
  Object.keys(results).forEach((id) => delete results[id])
  if (!disposed) void connect()
}, { deep: true })

onMounted(() => void connect())
onBeforeUnmount(() => {
  disposed = true
  client?.close()
})

function normalizeCells(input: PlaygroundCell[]): LocalCell[] {
  const used = new Set<string>()
  return input.map((cell, index) => {
    let id = cell.id?.trim() || `cell-${index + 1}`
    while (used.has(id)) id = `${id}-${index + 1}`
    used.add(id)
    return { ...cell, id }
  })
}
</script>

<template>
  <section class="dps-playground" :class="rootClasses" :aria-busy="isBusy" aria-label="Datapack Sandbox playground">
    <header class="dps-toolbar">
      <div class="dps-brand">
        <span class="dps-brand-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24">
            <path d="m12 2 8.5 4.7v9.6L12 21l-8.5-4.7V6.7L12 2Z" />
            <path d="m3.8 6.9 8.2 4.6 8.2-4.6M12 11.5v9.1" />
          </svg>
        </span>
        <div class="dps-brand-copy">
          <strong>Datapack Sandbox</strong>
          <div class="dps-connection" :data-state="connection">
            <span class="dps-connection-dot" />
            <span>{{ connectionSummary }}</span>
          </div>
        </div>
      </div>
      <div class="dps-toolbar-actions">
        <button class="dps-button-primary" type="button" :disabled="connection !== 'ready' || isBusy" @click="runAll">Run all</button>
        <button type="button" :disabled="connection !== 'ready' || !hasRunningCell" @click="interrupt">Interrupt</button>
        <button
          type="button"
          data-action="reset"
          title="Clear sandbox state while keeping the edited cell source"
          :disabled="connection !== 'ready' || isBusy"
          @click="reset"
        >
          Reset sandbox
        </button>
        <button
          type="button"
          data-action="restore"
          title="Restore the original example source and clear sandbox state"
          :disabled="isBusy || !hasExampleChanges"
          @click="restoreExample"
        >
          Restore example
        </button>
        <button v-if="connection === 'unavailable' || connection === 'closed'" type="button" @click="connect">Reconnect</button>
      </div>
    </header>

    <div v-if="connection === 'unavailable'" class="dps-unavailable" role="status">
      <strong>Playground unavailable</strong>
      <span>{{ connectionMessage }}</span>
      <button type="button" @click="connect">Retry connection</button>
    </div>

    <div class="dps-cells">
      <article v-for="(cell, index) in cells" :key="cell.id" class="dps-cell" :class="`dps-cell-${cell.type}`">
        <div v-if="cell.type === 'markdown'" class="dps-markdown" v-html="markdown.render(cell.source)" />
        <template v-else>
          <div class="dps-cell-heading">
            <div class="dps-cell-label">
              <span class="dps-cell-number">{{ String(index + 1).padStart(2, '0') }}</span>
              <span>MCFunction</span>
            </div>
            <div class="dps-cell-actions">
              <button class="dps-button-primary" type="button" :disabled="connection !== 'ready' || cellResult(cell.id).status !== 'idle'" @click="runCell(cell)">
                {{ cellResult(cell.id).hasRun ? 'Rerun' : 'Run' }}
              </button>
              <button type="button" :disabled="connection !== 'ready' || isBusy" @click="renderCell(cell)">Render</button>
            </div>
          </div>
          <CodeCell
            :model-value="cell.source"
            :cell-id="cell.id"
            :read-only="readOnly"
            :disabled="connection !== 'ready' || isBusy"
            :diagnostics="cellResult(cell.id).diagnostics"
            :complete="(source, cursor) => complete(cell.id, source, cursor)"
            :check="(source) => check(cell.id, source)"
            @update:model-value="(source) => updateSource(cell, source)"
            @run="runCell(cell)"
          />
          <div v-if="!readOnly" class="dps-editor-hint">
            <span><kbd>Tab</kbd> accept suggestion</span>
            <span><kbd>Ctrl/⌘</kbd> + <kbd>Enter</kbd> run cell</span>
          </div>
          <div v-if="cellResult(cell.id).status !== 'idle'" class="dps-status" role="status">
            {{ cellResult(cell.id).status === 'interrupting' ? 'Interrupting…' : 'Running…' }}
          </div>
          <div v-if="cellResult(cell.id).error" class="dps-error" role="alert">
            <strong>{{ cellResult(cell.id).error?.code }}</strong>
            <span>{{ cellResult(cell.id).error?.message }}</span>
          </div>
          <div v-if="cellResult(cell.id).summary" class="dps-output">
            <p>{{ cellResult(cell.id).summary }}</p>
            <details v-if="cellResult(cell.id).raw">
              <summary>Structured result</summary>
              <pre>{{ JSON.stringify(cellResult(cell.id).raw, null, 2) }}</pre>
            </details>
          </div>
          <img
            v-if="cellResult(cell.id).image"
            class="dps-render"
            :src="cellResult(cell.id).image?.src"
            :width="cellResult(cell.id).image?.width"
            :height="cellResult(cell.id).image?.height"
            alt="Approximate Datapack Sandbox render"
          >
        </template>
      </article>
    </div>
  </section>
</template>
