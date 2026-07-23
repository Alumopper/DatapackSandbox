<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import CodeCell from './CodeCell.vue'
import { PlaygroundClientError } from './client'
import { PlaygroundSessionController } from './session'
import type {
  PlaygroundBrowserLimits,
  PlaygroundAnimationOptions,
  PlaygroundDependencySource,
  PlaygroundDiagnostic,
  PlaygroundEvent,
  PlaygroundRenderOptions,
  PlaygroundTheme,
  PlaygroundViewportOptions,
  PlaygroundCameraState,
  PlaygroundPlayerInput,
  PlaygroundFrameStats,
} from './types'

interface CellResult {
  status: 'idle' | 'running' | 'interrupting'
  summary?: string
  raw?: unknown
  error?: { code: string; message: string }
  diagnostics: PlaygroundDiagnostic[]
  image?: { src: string; width?: number; height?: number }
  hasRun: boolean
}

const DpsViewport = defineAsyncComponent(() => import('./DpsViewport.vue'))

const props = withDefaults(defineProps<{
  modelValue: string
  version?: string
  cellId?: string
  theme?: PlaygroundTheme
  readOnly?: boolean
  render?: PlaygroundRenderOptions
  animation?: PlaygroundAnimationOptions
  checkpointName?: string
  showDetails?: boolean
  siteId?: string
  limits?: PlaygroundBrowserLimits
  dependencies?: PlaygroundDependencySource[]
  workerUrl?: string
  session?: PlaygroundSessionController
  viewport?: boolean | PlaygroundViewportOptions
}>(), {
  version: '26.2',
  cellId: 'example',
  theme: 'auto',
  readOnly: false,
  render: () => ({ auto: false, width: 960, height: 540 }),
  animation: () => ({ delayMs: 250, repeat: 0, captureOnExecute: true }),
  checkpointName: 'example',
  showDetails: true,
  dependencies: () => [],
  viewport: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  ready: [sessionId: string]
  error: [error: { code: string; message: string }]
  executed: [result: { summary?: string; raw?: unknown }]
  gif: [result: { bytes: ArrayBuffer; frameCount: number; width?: number; height?: number }]
  checkpoint: [result: { kind: 'saved' | 'restored'; name: string; snapshot: Record<string, unknown> }]
  'play-state': [state: { playing: boolean; tickRate: number; droppedTicks: number }]
  'camera-change': [camera: PlaygroundCameraState]
  input: [input: PlaygroundPlayerInput]
  'frame-stats': [stats: PlaygroundFrameStats]
  'context-lost': [lost: boolean]
}>()

const source = ref(props.modelValue)
const initialSource = ref(props.modelValue)
const connection = ref<'connecting' | 'ready' | 'unavailable' | 'closed'>('connecting')
const result = reactive<CellResult>({ status: 'idle', diagnostics: [], hasRun: false })
const sessionAction = ref<'render' | 'reset' | 'checkpoint' | 'restore' | 'capture' | 'gif'>()
const hasCheckpoint = ref(false)
const animationFrameCount = ref(0)
const isBusy = computed(() => result.status !== 'idle' || sessionAction.value !== undefined)
const hasExampleChanges = computed(() => (
  source.value !== initialSource.value
  || result.hasRun
  || result.summary !== undefined
  || result.raw !== undefined
  || result.error !== undefined
  || result.image !== undefined
  || result.diagnostics.length > 0
  || hasCheckpoint.value
  || animationFrameCount.value > 0
))
const pendingModelValues = new Set<string>()
let sessionController: PlaygroundSessionController | undefined
let ownsSession = false
let unsubscribeEvents: (() => void) | undefined
let unsubscribeConnection: (() => void) | undefined
let disposed = false
const viewportOptions = computed<PlaygroundViewportOptions>(() => props.viewport === true ? {} : props.viewport || {})

async function connect(): Promise<void> {
  unsubscribeEvents?.()
  unsubscribeConnection?.()
  if (ownsSession) sessionController?.dispose()
  connection.value = 'connecting'
  hasCheckpoint.value = false
  animationFrameCount.value = 0
  result.error = undefined
  const next = props.session ?? new PlaygroundSessionController({
    notebook: {
      version: props.version,
      cells: [{ id: props.cellId, type: 'code', source: source.value }],
    },
    render: effectiveRender(),
    siteId: props.siteId,
    workerUrl: props.workerUrl,
    limits: props.limits,
    dependencies: props.dependencies,
  })
  sessionController = next
  ownsSession = !props.session
  unsubscribeEvents = next.onEvent(handleEvent)
  unsubscribeConnection = next.onConnection((state) => {
    if (sessionController !== next || disposed) return
    if (state === 'connecting') {
      connection.value = 'connecting'
      hasCheckpoint.value = false
      animationFrameCount.value = 0
    }
    if (state === 'closed') connection.value = 'closed'
    if (state === 'unavailable') connection.value = 'unavailable'
  })
  try {
    await next.connect()
    connection.value = 'ready'
  } catch (error) {
    if (sessionController !== next || disposed) return
    handleClientError(error, 'WORKER_UNAVAILABLE')
    connection.value = 'unavailable'
  }
}

async function run(): Promise<void> {
  if (!sessionController || connection.value !== 'ready' || isBusy.value) return
  revokeImage()
  result.summary = undefined
  result.raw = undefined
  result.error = undefined
  result.diagnostics = []
  result.status = 'running'
  try {
    await sessionController.execute(props.cellId, source.value, effectiveRender())
    result.hasRun = true
    if (effectiveAnimation().captureOnExecute) await captureAnimationFrame()
  } catch (error) {
    handleClientError(error, 'COMMAND_ERROR')
  } finally {
    if (result.status === 'running') result.status = 'idle'
  }
}

async function savePoint(): Promise<void> {
  if (!sessionController || connection.value !== 'ready' || isBusy.value) return
  sessionAction.value = 'checkpoint'
  result.error = undefined
  try {
    const checkpoint = await sessionController.saveCheckpoint(props.checkpointName)
    hasCheckpoint.value = true
    emit('checkpoint', { kind: 'saved', ...checkpoint })
  } catch (error) {
    handleClientError(error, 'CHECKPOINT_FAILED')
  } finally {
    sessionAction.value = undefined
  }
}

async function returnToPoint(): Promise<void> {
  if (!sessionController || connection.value !== 'ready' || isBusy.value || !hasCheckpoint.value) return
  sessionAction.value = 'restore'
  result.error = undefined
  try {
    const checkpoint = await sessionController.restoreCheckpoint(props.checkpointName)
    clearResult()
    emit('checkpoint', { kind: 'restored', ...checkpoint })
    await sessionController.render(props.cellId, { ...effectiveRender(), auto: false })
  } catch (error) {
    handleClientError(error, 'CHECKPOINT_FAILED')
  } finally {
    sessionAction.value = undefined
  }
}

async function captureAnimationFrame(): Promise<boolean> {
  if (!sessionController || connection.value !== 'ready') return false
  const ownsAction = sessionAction.value === undefined
  if (ownsAction) sessionAction.value = 'capture'
  try {
    const options = effectiveAnimation()
    const event = await sessionController.captureAnimationFrame(
      props.cellId,
      { auto: false, width: options.width, height: options.height },
      options.delayMs,
    )
    animationFrameCount.value = Number(event.result?.frameCount ?? animationFrameCount.value + 1)
    return true
  } catch (error) {
    handleClientError(error, 'ANIMATION_CAPTURE_FAILED')
    return false
  } finally {
    if (ownsAction) sessionAction.value = undefined
  }
}

async function exportGif(): Promise<void> {
  if (!sessionController || connection.value !== 'ready' || isBusy.value) return
  sessionAction.value = 'gif'
  result.error = undefined
  try {
    if (animationFrameCount.value === 0 && !await captureAnimationFrame()) return
    const event = await sessionController.exportAnimation(props.cellId, effectiveAnimation().repeat)
    if (!event.bytes) throw new PlaygroundClientError('ANIMATION_EXPORT_FAILED', 'Worker returned no GIF bytes')
    const frameCount = Number(event.result?.frameCount ?? animationFrameCount.value)
    const url = URL.createObjectURL(new Blob([event.bytes], { type: 'image/gif' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `${props.cellId}.gif`
    link.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 0)
    emit('gif', { bytes: event.bytes, frameCount, width: event.width, height: event.height })
  } catch (error) {
    handleClientError(error, 'ANIMATION_EXPORT_FAILED')
  } finally {
    sessionAction.value = undefined
  }
}

async function renderCell(): Promise<void> {
  if (!sessionController || connection.value !== 'ready' || isBusy.value) return
  sessionAction.value = 'render'
  result.error = undefined
  try {
    await sessionController.render(props.cellId, { ...effectiveRender(), auto: false })
  } catch (error) {
    handleClientError(error, 'RENDER_ERROR')
  } finally {
    sessionAction.value = undefined
  }
}

async function resetExample(): Promise<void> {
  if (!sessionController || connection.value !== 'ready' || isBusy.value) return
  sessionAction.value = 'reset'
  try {
    await sessionController.restoreExample()
    source.value = initialSource.value
    pendingModelValues.add(initialSource.value)
    emit('update:modelValue', initialSource.value)
    clearResult()
    hasCheckpoint.value = false
    animationFrameCount.value = 0
  } catch (error) {
    handleClientError(error, 'RESET_FAILED')
  } finally {
    sessionAction.value = undefined
  }
}

async function complete(value: string, cursor: number) {
  if (!sessionController || connection.value !== 'ready' || isBusy.value) return []
  try {
    return await sessionController.complete(props.cellId, value, cursor)
  } catch {
    return []
  }
}

async function check(value: string): Promise<PlaygroundDiagnostic[]> {
  if (!sessionController || connection.value !== 'ready' || isBusy.value) return []
  try {
    return await sessionController.check(props.cellId, value)
  } catch {
    return []
  }
}

function updateSource(value: string): void {
  source.value = value
  pendingModelValues.add(value)
  emit('update:modelValue', value)
}

function handleEvent(event: PlaygroundEvent): void {
  if (event.type === 'session.ready') {
    connection.value = 'ready'
    if (event.sessionId) emit('ready', event.sessionId)
  }
  if (event.cellId !== props.cellId) return
  if (event.type === 'cell.status' && (event.status === 'running' || event.status === 'interrupting' || event.status === 'idle')) {
    result.status = event.status
  } else if (event.type === 'cell.output' && event.kind === 'execution') {
    result.summary = event.summary
    result.raw = event.result
    result.error = undefined
    result.hasRun = true
    emit('executed', { summary: event.summary, raw: event.result })
  } else if (event.type === 'diagnostic') {
    result.diagnostics = event.diagnostics ?? []
  } else if (event.type === 'cell.render' && event.mimeType === 'image/png' && event.bytes) {
    revokeImage()
    result.image = {
      src: URL.createObjectURL(new Blob([event.bytes], { type: 'image/png' })),
      width: event.width,
      height: event.height,
    }
  } else if (event.type === 'cell.error' && event.error) {
    result.error = { code: event.error.code, message: event.error.message }
  }
}

function handleClientError(error: unknown, fallbackCode: string): void {
  const normalized = error instanceof PlaygroundClientError
    ? error
    : new PlaygroundClientError(fallbackCode, error instanceof Error ? error.message : String(error))
  result.error = { code: normalized.code, message: normalized.message }
  emit('error', { code: normalized.code, message: normalized.message })
}

function effectiveRender(): PlaygroundRenderOptions {
  return {
    auto: props.render.auto ?? false,
    width: props.render.width ?? 960,
    height: props.render.height ?? 540,
  }
}

function effectiveAnimation(): Required<PlaygroundAnimationOptions> {
  const render = effectiveRender()
  const renderWidth = render.width ?? 960
  const renderHeight = render.height ?? 540
  const width = props.animation.width ?? Math.min(renderWidth, 480)
  const height = props.animation.height ?? Math.max(16, Math.round(renderHeight * width / renderWidth))
  return {
    width,
    height,
    delayMs: props.animation.delayMs ?? 250,
    repeat: props.animation.repeat ?? 0,
    captureOnExecute: props.animation.captureOnExecute ?? true,
  }
}

function clearResult(): void {
  revokeImage()
  result.status = 'idle'
  result.summary = undefined
  result.raw = undefined
  result.error = undefined
  result.diagnostics = []
  result.hasRun = false
}

function revokeImage(): void {
  if (result.image?.src.startsWith('blob:')) URL.revokeObjectURL(result.image.src)
  result.image = undefined
}

watch(() => props.modelValue, (value) => {
  if (value !== source.value) source.value = value
  if (!pendingModelValues.delete(value)) {
    initialSource.value = value
    clearResult()
  }
})

watch(() => props.version, () => {
  clearResult()
  if (!disposed && !props.session) void connect()
})

watch(() => props.cellId, () => clearResult())

watch(() => props.dependencies, () => {
  clearResult()
  if (!disposed && !props.session) void connect()
}, { deep: true })

watch(() => props.session, () => {
  clearResult()
  if (!disposed) void connect()
})

onMounted(() => void connect())
onBeforeUnmount(() => {
  disposed = true
  revokeImage()
  unsubscribeEvents?.()
  unsubscribeConnection?.()
  if (ownsSession) sessionController?.dispose()
})

defineExpose({
  run,
  render: renderCell,
  resetExample,
  clearResult,
  savePoint,
  returnToPoint,
  captureAnimationFrame,
  exportGif,
})
</script>

<template>
  <section
    class="dps-playground dps-cell-space"
    :class="`dps-theme-${theme}`"
    :data-state="connection"
    :aria-busy="isBusy"
    aria-label="Datapack Sandbox cell"
  >
    <article class="dps-cell dps-cell-code">
      <div class="dps-cell-heading">
        <div class="dps-cell-label">MCFunction</div>
        <div class="dps-cell-actions">
          <button
            class="dps-button-primary"
            type="button"
            :disabled="connection !== 'ready' || isBusy"
            @click="run"
          >
            {{ connection === 'connecting' ? 'Starting…' : result.hasRun ? 'Rerun' : 'Run' }}
          </button>
          <button type="button" :disabled="connection !== 'ready' || isBusy" @click="renderCell">Render</button>
          <button type="button" data-action="checkpoint" :disabled="connection !== 'ready' || isBusy" @click="savePoint">
            Save point
          </button>
          <button
            type="button"
            data-action="restore-point"
            :disabled="connection !== 'ready' || isBusy || !hasCheckpoint"
            @click="returnToPoint"
          >
            Return
          </button>
          <button type="button" data-action="capture-frame" :disabled="connection !== 'ready' || isBusy" @click="captureAnimationFrame">
            Add frame<span v-if="animationFrameCount"> ({{ animationFrameCount }})</span>
          </button>
          <button type="button" data-action="export-gif" :disabled="connection !== 'ready' || isBusy" @click="exportGif">
            Export GIF
          </button>
          <button
            type="button"
            :disabled="connection !== 'ready' || isBusy || !hasExampleChanges"
            @click="resetExample"
          >
            Reset example
          </button>
        </div>
      </div>
      <CodeCell
        :model-value="source"
        :cell-id="cellId"
        :read-only="readOnly"
        :disabled="connection !== 'ready' || isBusy"
        :diagnostics="result.diagnostics"
        :complete="complete"
        :check="check"
        @update:model-value="updateSource"
        @run="run"
      />
      <div v-if="result.status !== 'idle'" class="dps-status" role="status">
        {{ result.status === 'interrupting' ? 'Interrupting…' : 'Running…' }}
      </div>
      <div v-else-if="sessionAction === 'render'" class="dps-status" role="status">Rendering…</div>
      <div v-if="result.error" class="dps-error" role="alert">
        <strong>{{ result.error.code }}</strong>
        <span>{{ result.error.message }}</span>
      </div>
      <div v-if="result.summary" class="dps-output">
        <p>{{ result.summary }}</p>
        <details v-if="showDetails && result.raw">
          <summary>Structured result</summary>
          <pre>{{ JSON.stringify(result.raw, null, 2) }}</pre>
        </details>
      </div>
      <img
        v-if="result.image"
        class="dps-render"
        :src="result.image.src"
        :width="result.image.width"
        :height="result.image.height"
        alt="Approximate Datapack Sandbox render"
      >
    </article>
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
  </section>
</template>
