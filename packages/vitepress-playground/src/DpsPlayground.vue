<script setup lang="ts">
import MarkdownIt from 'markdown-it'
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import ExecutionOutput from './ExecutionOutput.vue'
import FunctionSourceViewer from './FunctionSourceViewer.vue'
import PlaygroundActions from './PlaygroundActions.vue'
import { PlaygroundClientError } from './client'
import { acquirePageSandbox, createComponentScopeId, normalizedSandboxId } from './page-sandbox'
import { PlaygroundSessionController } from './session'
import type { PlaygroundActionItem } from './action-bar'
import type {
  PlaygroundActionConfig,
  PlaygroundBrowserLimits,
  PlaygroundAnimationOptions,
  PlaygroundCell,
  PlaygroundDiagnostic,
  PlaygroundEvent,
  PlaygroundImportKind,
  PlaygroundLayout,
  PlaygroundNotebook,
  PlaygroundPresetRegistry,
  PlaygroundRenderOptions,
  PlaygroundTheme,
  PlaygroundViewportOptions,
  PlaygroundCameraState,
  PlaygroundPlayerInput,
  PlaygroundFrameStats,
} from './types'

type LocalCell = PlaygroundCell & { id: string }
const DpsViewport = defineAsyncComponent(() => import('./DpsViewport.vue'))

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
  theme?: PlaygroundTheme
  layout?: PlaygroundLayout
  readOnly?: boolean
  render?: PlaygroundRenderOptions
  animation?: PlaygroundAnimationOptions
  checkpointName?: string
  siteId?: string
  sandboxId?: string
  presets?: PlaygroundPresetRegistry
  allowImport?: boolean
  limits?: PlaygroundBrowserLimits
  workerUrl?: string
  session?: PlaygroundSessionController
  viewport?: boolean | PlaygroundViewportOptions
  actions?: PlaygroundActionConfig
}>(), {
  theme: 'auto',
  layout: 'notebook',
  readOnly: false,
  render: () => ({ auto: true, width: 960, height: 540 }),
  animation: () => ({ delayMs: 250, repeat: 0, captureOnExecute: true }),
  checkpointName: 'playground',
  presets: () => ({}),
  allowImport: true,
  viewport: false,
  actions: () => ({}),
})

const emit = defineEmits<{
  ready: [sessionId: string]
  error: [error: { code: string; message: string }]
  gif: [result: { bytes: ArrayBuffer; frameCount: number; width?: number; height?: number }]
  checkpoint: [result: { kind: 'saved' | 'restored'; name: string; snapshot: Record<string, unknown> }]
  'play-state': [state: { playing: boolean; tickRate: number; droppedTicks: number }]
  'camera-change': [camera: PlaygroundCameraState]
  input: [input: PlaygroundPlayerInput]
  'frame-stats': [stats: PlaygroundFrameStats]
  'context-lost': [lost: boolean]
}>()

const markdown = new MarkdownIt({ html: false, linkify: true, typographer: false })
const cells = ref<LocalCell[]>(normalizeCells(props.notebook.cells))
const results = reactive<Record<string, CellResult>>({})
const connection = ref<'connecting' | 'ready' | 'unavailable' | 'closed'>('connecting')
const connectionMessage = ref('Starting local browser sandbox…')
const runningAll = ref(false)
const sessionBusy = ref(false)
const sessionAction = ref<'reset' | 'restore' | 'import' | 'checkpoint' | 'restore-point' | 'capture' | 'gif'>()
const hasCheckpoint = ref(false)
const animationFrameCount = ref(0)
const fileInput = ref<HTMLInputElement>()
const directoryInput = ref<HTMLInputElement>()
const pendingFiles = ref<File[]>([])
const pendingImportKind = ref<PlaygroundImportKind>('datapack')
const importMessage = ref('')
let sessionController: PlaygroundSessionController | undefined
let ownsSession = false
let releasePageSandbox: (() => void) | undefined
let unsubscribeEvents: (() => void) | undefined
let unsubscribeConnection: (() => void) | undefined
let unsubscribeActivity: (() => void) | undefined
let disposed = false
let stopRunAll = false
const componentScopeId = createComponentScopeId()
const effectiveSandboxId = computed(() => props.session ? undefined : normalizedSandboxId(props.sandboxId))

const rootClasses = computed(() => [
  `dps-theme-${props.theme}`,
  `dps-layout-${props.layout}`,
  { 'dps-is-busy': isBusy.value },
])
const hasRunningCell = computed(() => Object.values(results).some((item) => item.status === 'running' || item.status === 'interrupting'))
const isBusy = computed(() => sessionBusy.value || hasRunningCell.value || runningAll.value || sessionAction.value !== undefined)
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
  return sourceChanged || hasResults || hasCheckpoint.value || animationFrameCount.value > 0
})
const connectionSummary = computed(() => {
  if (connection.value === 'ready') return `Minecraft ${props.notebook.version} · Local Worker`
  if (connection.value === 'connecting') return 'Starting local sandbox'
  if (connection.value === 'closed') return 'Session closed'
  return 'Local sandbox unavailable'
})
const viewportOptions = computed<PlaygroundViewportOptions>(() => props.viewport === true ? {} : props.viewport || {})
const toolbarActionDefaults: PlaygroundActionConfig = {
  'run-all': 'primary',
  interrupt: 'primary',
  'save-point': 'menu',
  'return-to-point': 'menu',
  'capture-frame': 'menu',
  'export-gif': 'menu',
  'reset-sandbox': 'menu',
  'restore-example': 'menu',
  'import-files': 'menu',
  'import-folder': 'menu',
  'restart-sandbox': 'primary',
}
const cellActionDefaults: PlaygroundActionConfig = { run: 'primary', render: 'primary' }
const toolbarActionItems = computed<PlaygroundActionItem[]>(() => [
  {
    id: 'run-all',
    label: 'Run all',
    disabled: connection.value !== 'ready' || isBusy.value,
    emphasis: true,
    run: runAll,
  },
  {
    id: 'interrupt',
    label: 'Interrupt',
    disabled: connection.value !== 'ready' || !hasRunningCell.value,
    run: interrupt,
  },
  {
    id: 'save-point',
    label: 'Save point',
    disabled: connection.value !== 'ready' || isBusy.value,
    run: savePoint,
  },
  {
    id: 'return-to-point',
    label: 'Return',
    disabled: connection.value !== 'ready' || isBusy.value || !hasCheckpoint.value,
    run: returnToPoint,
  },
  {
    id: 'capture-frame',
    label: `Add frame${animationFrameCount.value ? ` (${animationFrameCount.value})` : ''}`,
    disabled: connection.value !== 'ready' || isBusy.value,
    run: addAnimationFrame,
  },
  {
    id: 'export-gif',
    label: 'Export GIF',
    disabled: connection.value !== 'ready' || isBusy.value,
    run: exportGif,
  },
  {
    id: 'reset-sandbox',
    label: 'Reset sandbox',
    title: 'Clear sandbox state while keeping the edited cell source',
    disabled: connection.value !== 'ready' || isBusy.value,
    run: reset,
  },
  {
    id: 'restore-example',
    label: 'Restore example',
    title: 'Restore the original example source and clear sandbox state',
    disabled: isBusy.value || !hasExampleChanges.value,
    run: restoreExample,
  },
  {
    id: 'import-files',
    label: 'Import files',
    disabled: connection.value !== 'ready' || isBusy.value,
    visible: props.allowImport,
    run: chooseFiles,
  },
  {
    id: 'import-folder',
    label: 'Import folder',
    disabled: connection.value !== 'ready' || isBusy.value,
    visible: props.allowImport,
    run: chooseDirectory,
  },
  {
    id: 'restart-sandbox',
    label: 'Restart',
    visible: connection.value === 'unavailable' || connection.value === 'closed',
    run: connect,
  },
])

function cellActionItems(cell: LocalCell): PlaygroundActionItem[] {
  return [
    {
      id: 'run',
      label: cellResult(cell.id).hasRun ? 'Rerun' : 'Run',
      disabled: connection.value !== 'ready' || isBusy.value || cellResult(cell.id).status !== 'idle',
      emphasis: true,
      run: () => runCell(cell),
    },
    {
      id: 'render',
      label: 'Render',
      disabled: connection.value !== 'ready' || isBusy.value,
      run: () => renderCell(cell),
    },
  ]
}

function cellResult(id: string): CellResult {
  if (!results[id]) results[id] = { status: 'idle', diagnostics: [], hasRun: false }
  return results[id]
}

async function connect(): Promise<void> {
  releaseCurrentSession()
  connection.value = 'connecting'
  hasCheckpoint.value = false
  animationFrameCount.value = 0
  connectionMessage.value = 'Starting local browser sandbox…'
  let next: PlaygroundSessionController | undefined
  try {
    const options = {
      notebook: props.notebook,
      render: effectiveRender(),
      siteId: props.siteId,
      workerUrl: props.workerUrl,
      limits: props.limits,
      presets: props.presets,
    }
    if (props.session) {
      next = props.session
    } else if (effectiveSandboxId.value) {
      const lease = acquirePageSandbox(effectiveSandboxId.value, options)
      next = lease.controller
      releasePageSandbox = lease.release
    } else {
      next = new PlaygroundSessionController(options)
      ownsSession = true
    }
    sessionController = next
    unsubscribeEvents = next.onEvent(handleEvent)
    unsubscribeConnection = next.onConnection((state, message) => {
      if (sessionController !== next || disposed) return
      if (state === 'connecting') {
        connection.value = 'connecting'
        connectionMessage.value = message ?? 'Starting local browser sandbox…'
        hasCheckpoint.value = false
        animationFrameCount.value = 0
      } else if (state === 'closed') {
        connection.value = 'closed'
        connectionMessage.value = message ?? 'Local sandbox closed.'
      } else if (state === 'unavailable') {
        connection.value = 'unavailable'
        connectionMessage.value = message ?? 'Local sandbox unavailable.'
      }
    })
    unsubscribeActivity = next.onActivity((activity) => {
      if (sessionController === next && !disposed) sessionBusy.value = activity.busy
    })
    await next.connect()
    connection.value = 'ready'
  } catch (error) {
    if (disposed || (next && sessionController !== next)) return
    setUnavailable(error)
  }
}

function releaseCurrentSession(): void {
  unsubscribeEvents?.()
  unsubscribeConnection?.()
  unsubscribeActivity?.()
  unsubscribeEvents = undefined
  unsubscribeConnection = undefined
  unsubscribeActivity = undefined
  if (ownsSession) sessionController?.dispose()
  releasePageSandbox?.()
  ownsSession = false
  releasePageSandbox = undefined
  sessionController = undefined
  sessionBusy.value = false
}

function sessionCellId(cellId: string): string {
  return `${componentScopeId}:${cellId}`
}

function localCellId(cellId: string | undefined): string | undefined {
  const prefix = `${componentScopeId}:`
  return cellId?.startsWith(prefix) ? cellId.slice(prefix.length) : undefined
}

async function runCell(cell: LocalCell): Promise<void> {
  if (cell.type !== 'code' || !sessionController || connection.value !== 'ready' || sessionBusy.value) return
  const result = cellResult(cell.id)
  result.error = undefined
  result.diagnostics = []
  result.status = 'running'
  try {
    await sessionController.execute(sessionCellId(cell.id), cell.source, effectiveRender())
    result.hasRun = true
    if (effectiveAnimation().captureOnExecute) await captureFrame(cell.id)
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
    await sessionController?.interrupt()
  } catch (error) {
    handleClientError(undefined, error)
  }
}

async function reset(): Promise<void> {
  if (!sessionController || connection.value !== 'ready' || isBusy.value) return
  stopRunAll = true
  sessionAction.value = 'reset'
  try {
    await sessionController.reset()
    clearResults()
    hasCheckpoint.value = false
    animationFrameCount.value = 0
  } catch (error) {
    handleClientError(undefined, error)
  } finally {
    sessionAction.value = undefined
  }
}

async function restoreExample(): Promise<void> {
  if (isBusy.value) return
  stopRunAll = true
  sessionAction.value = 'restore'
  try {
    if (sessionController && connection.value === 'ready') await sessionController.restoreExample()
    cells.value = normalizeCells(props.notebook.cells)
    clearResults()
    hasCheckpoint.value = false
    animationFrameCount.value = 0
  } catch (error) {
    handleClientError(undefined, error)
  } finally {
    sessionAction.value = undefined
  }
}

async function renderCell(cell: LocalCell): Promise<void> {
  if (!sessionController || cell.type !== 'code' || isBusy.value) return
  try {
    await sessionController.render(sessionCellId(cell.id), { ...effectiveRender(), auto: false })
  } catch (error) {
    handleClientError(cell.id, error)
  }
}

async function savePoint(): Promise<void> {
  if (!sessionController || connection.value !== 'ready' || isBusy.value) return
  sessionAction.value = 'checkpoint'
  try {
    const checkpoint = await sessionController.saveCheckpoint(props.checkpointName)
    hasCheckpoint.value = true
    emit('checkpoint', { kind: 'saved', ...checkpoint })
  } catch (error) {
    handleClientError(undefined, error)
  } finally {
    sessionAction.value = undefined
  }
}

async function returnToPoint(): Promise<void> {
  if (!sessionController || connection.value !== 'ready' || isBusy.value || !hasCheckpoint.value) return
  sessionAction.value = 'restore-point'
  try {
    const checkpoint = await sessionController.restoreCheckpoint(props.checkpointName)
    clearResults()
    emit('checkpoint', { kind: 'restored', ...checkpoint })
    const firstCodeCell = cells.value.find((cell) => cell.type === 'code')
    if (firstCodeCell) await sessionController.render(sessionCellId(firstCodeCell.id), { ...effectiveRender(), auto: false })
  } catch (error) {
    handleClientError(undefined, error)
  } finally {
    sessionAction.value = undefined
  }
}

async function captureFrame(cellId = 'notebook'): Promise<boolean> {
  if (!sessionController || connection.value !== 'ready') return false
  try {
    const options = effectiveAnimation()
    const event = await sessionController.captureAnimationFrame(
      sessionCellId(cellId),
      { auto: false, width: options.width, height: options.height },
      options.delayMs,
    )
    animationFrameCount.value = Number(event.result?.frameCount ?? animationFrameCount.value + 1)
    return true
  } catch (error) {
    handleClientError(undefined, error)
    return false
  }
}

async function addAnimationFrame(): Promise<void> {
  if (isBusy.value) return
  sessionAction.value = 'capture'
  try {
    await captureFrame()
  } finally {
    sessionAction.value = undefined
  }
}

async function exportGif(): Promise<void> {
  if (!sessionController || connection.value !== 'ready' || isBusy.value) return
  sessionAction.value = 'gif'
  try {
    if (animationFrameCount.value === 0 && !await captureFrame()) return
    const event = await sessionController.exportAnimation(sessionCellId('notebook'), effectiveAnimation().repeat)
    if (!event.bytes) throw new PlaygroundClientError('ANIMATION_EXPORT_FAILED', 'Worker returned no GIF bytes')
    const frameCount = Number(event.result?.frameCount ?? animationFrameCount.value)
    const url = URL.createObjectURL(new Blob([event.bytes], { type: 'image/gif' }))
    const link = document.createElement('a')
    link.href = url
    link.download = 'datapack-sandbox.gif'
    link.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 0)
    emit('gif', { bytes: event.bytes, frameCount, width: event.width, height: event.height })
  } catch (error) {
    handleClientError(undefined, error)
  } finally {
    sessionAction.value = undefined
  }
}

async function complete(cellId: string, source: string, cursor: number) {
  if (!sessionController || connection.value !== 'ready' || isBusy.value) return []
  try {
    return await sessionController.complete(sessionCellId(cellId), source, cursor)
  } catch {
    return []
  }
}

async function check(cellId: string, source: string): Promise<PlaygroundDiagnostic[]> {
  if (!sessionController || connection.value !== 'ready' || isBusy.value) return []
  try {
    return await sessionController.check(sessionCellId(cellId), source)
  } catch {
    return []
  }
}

async function resolveFunction(id: string) {
  if (!sessionController || connection.value !== 'ready') {
    throw new PlaygroundClientError('SESSION_LOST', 'The sandbox is not ready to open function sources')
  }
  return await sessionController.readFunction(id)
}

function handleEvent(event: PlaygroundEvent): void {
  if (event.type === 'session.ready') {
    connection.value = 'ready'
    connectionMessage.value = ''
    if (event.sessionId) emit('ready', event.sessionId)
  }
  if (event.cellId) {
    const cellId = localCellId(event.cellId)
    if (!cellId) return
    const result = cellResult(cellId)
    if (event.type === 'cell.status' && (event.status === 'running' || event.status === 'interrupting' || event.status === 'idle')) {
      result.status = event.status
    } else if (event.type === 'cell.output' && event.kind === 'execution') {
      result.summary = event.summary
      result.raw = event.result
      result.error = undefined
      result.hasRun = true
    } else if (event.type === 'diagnostic') {
      result.diagnostics = event.diagnostics ?? []
    } else if (event.type === 'cell.render' && event.mimeType === 'image/png' && event.bytes) {
      revokeImage(result)
      result.image = {
        src: URL.createObjectURL(new Blob([event.bytes], { type: 'image/png' })),
        width: event.width,
        height: event.height,
      }
    } else if (event.type === 'cell.error' && event.error) {
      result.error = { code: event.error.code, message: event.error.message }
    }
  }
}

function handleClientError(cellId: string | undefined, error: unknown): void {
  const normalized = error instanceof PlaygroundClientError
    ? error
    : new PlaygroundClientError('PLAYGROUND_ERROR', error instanceof Error ? error.message : 'Playground request failed')
  if (cellId) cellResult(cellId).error = { code: normalized.code, message: normalized.message }
  emit('error', { code: normalized.code, message: normalized.message })
  if (normalized.code === 'SESSION_LOST') {
    connection.value = 'connecting'
    connectionMessage.value = 'Rebuilding local sandbox…'
  } else if (!normalized.recoverable || normalized.code === 'API_UNAVAILABLE') {
    setUnavailable(normalized)
  }
}

function setUnavailable(error: unknown): void {
  const message = error instanceof Error ? error.message : 'The local sandbox is unavailable.'
  connection.value = 'unavailable'
  connectionMessage.value = `Local sandbox unavailable: ${message}`
}

function effectiveRender(): PlaygroundRenderOptions {
  return { auto: props.render.auto ?? true, width: props.render.width ?? 960, height: props.render.height ?? 540 }
}

function effectiveAnimation(): Required<PlaygroundAnimationOptions> {
  const render = effectiveRender()
  const renderWidth = render.width ?? 960
  const renderHeight = render.height ?? 540
  const width = props.animation.width ?? Math.min(renderWidth, 480)
  return {
    width,
    height: props.animation.height ?? Math.max(16, Math.round(renderHeight * width / renderWidth)),
    delayMs: props.animation.delayMs ?? 250,
    repeat: props.animation.repeat ?? 0,
    captureOnExecute: props.animation.captureOnExecute ?? true,
  }
}

function updateSource(cell: LocalCell, source: string): void {
  cell.source = source
}

function clearResults(): void {
  Object.values(results).forEach(revokeImage)
  Object.keys(results).forEach((id) => delete results[id])
}

function revokeImage(result: CellResult): void {
  if (result.image?.src.startsWith('blob:')) URL.revokeObjectURL(result.image.src)
}

function chooseFiles(): void {
  fileInput.value?.click()
}

function chooseDirectory(): void {
  directoryInput.value?.click()
}

async function onFileSelection(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  await considerImport([...input.files ?? []])
  input.value = ''
}

async function onDrop(event: DragEvent): Promise<void> {
  if (!props.allowImport || !event.dataTransfer?.files.length) return
  await considerImport([...event.dataTransfer.files])
}

async function considerImport(files: File[]): Promise<void> {
  if (!sessionController || files.length === 0) return
  const inferred = inferImportKind(files)
  if (!inferred) {
    pendingFiles.value = files
    pendingImportKind.value = 'datapack'
    importMessage.value = 'Select what this input contains before importing.'
    return
  }
  await importFiles(inferred, files)
}

async function confirmImport(): Promise<void> {
  const files = pendingFiles.value
  pendingFiles.value = []
  await importFiles(pendingImportKind.value, files)
}

async function importFiles(kind: PlaygroundImportKind, files: File[]): Promise<void> {
  if (!sessionController || files.length === 0 || sessionAction.value) return
  sessionAction.value = 'import'
  importMessage.value = 'Reading files into the local Worker…'
  try {
    await sessionController.connect()
    const isArchive = files.length === 1 && /\.(zip|jar)$/i.test(files[0].name)
    const imported = isArchive
      ? await sessionController.importArchive(kind, files[0].name, await files[0].arrayBuffer())
      : await sessionController.importEntries(kind, await Promise.all(files.map(async (file) => ({
          path: file.webkitRelativePath || file.name,
          bytes: await file.arrayBuffer(),
        }))))
    importMessage.value = `Imported ${imported.files} files (${imported.functions} functions) into this session.`
  } catch (error) {
    const normalized = error instanceof PlaygroundClientError
      ? error
      : new PlaygroundClientError('IMPORT_FAILED', error instanceof Error ? error.message : String(error))
    importMessage.value = `${normalized.code}: ${normalized.message}`
    emit('error', { code: normalized.code, message: normalized.message })
  } finally {
    sessionAction.value = undefined
  }
}

function inferImportKind(files: File[]): PlaygroundImportKind | undefined {
  if (files.length === 1 && files[0].name.toLowerCase().endsWith('.jar')) return 'client-jar'
  const paths = files.map((file) => (file.webkitRelativePath || file.name).replaceAll('\\', '/').toLowerCase())
  if (paths.some((path) => path.endsWith('/level.dat') || path === 'level.dat')) return 'world'
  if (paths.some((path) => /(^|\/)data\/[^/]+\/function(?:s)?\//.test(path))) return 'datapack'
  if (paths.some((path) => /(^|\/)assets\//.test(path))) return 'resource-pack'
  return undefined
}

watch(() => props.notebook, (notebook) => {
  cells.value = normalizeCells(notebook.cells)
  clearResults()
  if (!disposed && !props.session) void connect()
}, { deep: true })

watch(() => props.session, () => {
  if (!disposed) void connect()
})

watch(() => props.sandboxId, () => {
  clearResults()
  if (!disposed && !props.session) void connect()
})

onMounted(() => void connect())
onBeforeUnmount(() => {
  disposed = true
  clearResults()
  releaseCurrentSession()
})

defineExpose({ savePoint, returnToPoint, addAnimationFrame, exportGif })

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
  <section
    class="dps-playground"
    :class="rootClasses"
    :aria-busy="isBusy"
    :data-sandbox-id="effectiveSandboxId"
    aria-label="Datapack Sandbox playground"
    @dragover.prevent
    @drop.prevent="onDrop"
  >
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
        <PlaygroundActions :items="toolbarActionItems" :defaults="toolbarActionDefaults" :config="actions" />
      </div>
      <input ref="fileInput" class="dps-file-input" type="file" multiple accept=".zip,.jar,.mcmeta,.dat,.mcfunction,.json,.nbt,.mca" @change="onFileSelection">
      <input ref="directoryInput" class="dps-file-input" type="file" multiple webkitdirectory @change="onFileSelection">
    </header>

    <div v-if="connection === 'unavailable'" class="dps-unavailable" role="status">
      <strong>Local sandbox unavailable</strong>
      <span>{{ connectionMessage }}</span>
      <button type="button" @click="connect">Restart sandbox</button>
    </div>

    <div v-if="pendingFiles.length" class="dps-import-choice" role="group" aria-label="Import type">
      <span>{{ importMessage }}</span>
      <select v-model="pendingImportKind">
        <option value="datapack">Datapack</option>
        <option value="resource-pack">Resource pack</option>
        <option value="client-jar">Minecraft client JAR</option>
        <option value="world">Minecraft world</option>
      </select>
      <button type="button" @click="confirmImport">Import locally</button>
      <button type="button" @click="pendingFiles = []">Cancel</button>
    </div>
    <div v-else-if="importMessage" class="dps-import-message" role="status">{{ importMessage }}</div>

    <DpsViewport
      v-if="viewport && sessionController"
      :session="sessionController"
      :options="viewportOptions"
      @error="emit('error', $event)"
      @play-state="emit('play-state', $event)"
      @camera-change="emit('camera-change', $event)"
      @input="emit('input', $event)"
      @frame-stats="emit('frame-stats', $event)"
      @context-lost="emit('context-lost', $event)"
    />

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
              <PlaygroundActions :items="cellActionItems(cell)" :defaults="cellActionDefaults" :config="actions" />
            </div>
          </div>
          <FunctionSourceViewer
            :model-value="cell.source"
            :cell-id="cell.id"
            :read-only="readOnly"
            :disabled="connection !== 'ready' || isBusy"
            :diagnostics="cellResult(cell.id).diagnostics"
            :complete="(source, cursor) => complete(cell.id, source, cursor)"
            :check="(source) => check(cell.id, source)"
            :resolve-function="resolveFunction"
            @update:model-value="(source) => updateSource(cell, source)"
            @run="runCell(cell)"
          />
          <div v-if="cellResult(cell.id).status !== 'idle'" class="dps-status" role="status">
            {{ cellResult(cell.id).status === 'interrupting' ? 'Interrupting…' : 'Running…' }}
          </div>
          <div v-if="cellResult(cell.id).error" class="dps-error" role="alert">
            <strong>{{ cellResult(cell.id).error?.code }}</strong>
            <span>{{ cellResult(cell.id).error?.message }}</span>
          </div>
          <ExecutionOutput
            v-if="cellResult(cell.id).summary"
            :summary="cellResult(cell.id).summary!"
            :raw="cellResult(cell.id).raw"
          />
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
