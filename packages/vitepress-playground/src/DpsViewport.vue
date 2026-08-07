<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { PlaygroundClientError } from './client'
import { acquirePageSandbox, normalizedSandboxId } from './page-sandbox'
import { PlaygroundSessionController } from './session'
import type {
  PlaygroundBrowserLimits,
  PlaygroundCameraState,
  PlaygroundCompletion,
  PlaygroundDependencySource,
  PlaygroundDiagnostic,
  PlaygroundFrameStats,
  PlaygroundNotebook,
  PlaygroundOutputEvent,
  PlaygroundPlayerInput,
  PlaygroundViewportOptions,
  PlaygroundViewportScene,
} from './types'
import type { WebglViewportRenderer } from './webgl/renderer'

const props = withDefaults(defineProps<{
  session?: PlaygroundSessionController
  sandboxId?: string
  notebook?: PlaygroundNotebook
  dependencies?: PlaygroundDependencySource[]
  workerUrl?: string
  limits?: PlaygroundBrowserLimits
  options?: PlaygroundViewportOptions
}>(), {
  dependencies: () => [],
  options: () => ({}),
})

const emit = defineEmits<{
  ready: [sessionId: string]
  error: [error: { code: string; message: string }]
  'play-state': [state: { playing: boolean; tickRate: number; droppedTicks: number }]
  'camera-change': [camera: PlaygroundCameraState]
  input: [input: PlaygroundPlayerInput]
  'frame-stats': [stats: PlaygroundFrameStats]
  'context-lost': [lost: boolean]
}>()

const canvas = ref<HTMLCanvasElement>()
const commandInput = ref<HTMLInputElement>()
const status = ref<'connecting' | 'ready' | 'unavailable' | 'context-lost'>('connecting')
const message = ref('Starting local sandbox…')
const playing = ref(false)
const sessionBusy = ref(false)
const latestScene = ref<PlaygroundViewportScene>()
const stats = ref<PlaygroundFrameStats>()
const touchVisible = ref(false)
const chatLines = ref<Array<{ id: number; text: string; color: string }>>([])
const titleOverlay = ref({ title: '', subtitle: '', titleColor: '#ffffff', subtitleColor: '#ffffff', visible: false })
const actionbarOverlay = ref({ text: '', color: '#ffffff', visible: false })
const commandOpen = ref(false)
const commandSource = ref('')
const commandSuggestions = ref<PlaygroundCompletion[]>([])
const commandDiagnostics = ref<PlaygroundDiagnostic[]>([])
const selectedCommandSuggestion = ref(0)
const leftKnob = ref({ x: 0, y: 0 })
const rightKnob = ref({ x: 0, y: 0 })
const mouseSensitivity = ref(props.options.mouseSensitivity ?? 0.12)
const configuredMoveSpeed = ref(props.options.moveSpeed ?? 6)
const configuredFieldOfView = ref(props.options.fieldOfView ?? 70)
let controller: PlaygroundSessionController | undefined
let ownsController = false
let releasePageSandbox: (() => void) | undefined
let renderer: WebglViewportRenderer | undefined
let unsubscribeScene: (() => void) | undefined
let unsubscribeEvents: (() => void) | undefined
let unsubscribeActivity: (() => void) | undefined
let disposed = false
let leftPointer: number | undefined
let rightPointer: number | undefined
let rightLast: { x: number; y: number } | undefined
let lastLookDispatch = 0
let pendingLook = { x: 0, y: 0 }
let lookTimer: number | undefined
let titleTimer: number | undefined
let actionbarTimer: number | undefined
let commandCompletionTimer: number | undefined
let commandCheckTimer: number | undefined
let commandAnalysisRevision = 0
let commandExecutionErrorShown = false
let overlaySequence = 0
let titleTimes = { fadeIn: 10, stay: 70, fadeOut: 20 }
const pendingVisualOutputs: PlaygroundOutputEvent[] = []
const pressed = new Set<string>()
const commandHistory: string[] = []
let commandHistoryIndex = 0
const viewportCommandCellId = `viewport-command-${Math.random().toString(36).slice(2)}`
const effectiveSandboxId = computed(() => props.session ? undefined : normalizedSandboxId(props.sandboxId))

const viewportOptions = computed<Required<Omit<PlaygroundViewportOptions, 'tickFunction'>> & { tickFunction?: string }>(() => ({
  targetFps: props.options.targetFps ?? 60,
  tickRate: props.options.tickRate ?? 20,
  autoplay: props.options.autoplay ?? false,
  tickFunction: props.options.tickFunction,
  inputPlayer: props.options.inputPlayer ?? 'Steve',
  keyboard: props.options.keyboard ?? true,
  touch: props.options.touch ?? true,
  pointerLock: props.options.pointerLock ?? true,
  showToolbar: props.options.showToolbar ?? true,
  fieldOfView: props.options.fieldOfView ?? 70,
  moveSpeed: props.options.moveSpeed ?? 6,
  mouseSensitivity: props.options.mouseSensitivity ?? 0.12,
  minimumPixelRatio: props.options.minimumPixelRatio ?? 0.5,
  maximumPixelRatio: props.options.maximumPixelRatio ?? 2,
}))

const leftKnobStyle = computed(() => ({ transform: `translate(${leftKnob.value.x}px, ${leftKnob.value.y}px)` }))
const rightKnobStyle = computed(() => ({ transform: `translate(${rightKnob.value.x}px, ${rightKnob.value.y}px)` }))

onMounted(async () => {
  try {
    controller = resolveSession()
    unsubscribeEvents = controller.onEvent((event) => {
      if (event.type === 'session.ready') {
        status.value = 'ready'
        message.value = ''
        if (event.sessionId) emit('ready', event.sessionId)
      } else if (event.type === 'simulation.state') {
        playing.value = event.status === 'playing'
        emit('play-state', {
          playing: playing.value,
          tickRate: Number(event.result?.tickRate ?? viewportOptions.value.tickRate),
          droppedTicks: Number(event.result?.droppedTicks ?? 0),
        })
      } else if (event.type === 'cell.error' && event.error) {
        if (event.cellId === viewportCommandCellId) {
          commandExecutionErrorShown = true
          addChatLine(event.error.message, '#ff5555')
        }
        else emit('error', { code: event.error.code, message: event.error.message })
      } else if (event.type === 'session.restore-example') {
        renderer?.resetView(latestScene.value)
      } else if (event.type === 'viewport.output' && event.output) {
        applyViewportOutput(event.output)
      } else if (event.type === 'viewport.clear') {
        clearViewportOutputs()
      } else if (event.cellId === viewportCommandCellId && event.type === 'cell.output' && event.kind === 'execution') {
        addChatLine(event.summary || 'Command executed.', '#aaaaaa')
      }
    })
    unsubscribeActivity = controller.onActivity((activity) => {
      if (!disposed) sessionBusy.value = activity.busy
    })
    unsubscribeScene = controller.subscribeScene((scene) => {
      latestScene.value = scene
      renderer?.updateScene(scene)
    })
    await controller.connect()
    if (disposed || !canvas.value) return
    const module = await import('./webgl/renderer')
    renderer = new module.WebglViewportRenderer(canvas.value, viewportOptions.value, {
      cameraChange: (camera) => emit('camera-change', camera),
      frameStats: (value) => {
        stats.value = value
        emit('frame-stats', value)
      },
      contextLost: (lost) => {
        status.value = lost ? 'context-lost' : 'ready'
        message.value = lost ? 'WebGL context lost. Waiting for the browser to restore it…' : ''
        emit('context-lost', lost)
        if (lost) void controller?.pause()
        else void controller?.refreshScene()
      },
    })
    if (latestScene.value) renderer.updateScene(latestScene.value)
    pendingVisualOutputs.splice(0).forEach((output) => renderer?.handleOutput(output))
    installInputListeners()
    touchVisible.value = viewportOptions.value.touch && matchMedia('(any-pointer: coarse)').matches
    status.value = 'ready'
    message.value = ''
    if (viewportOptions.value.autoplay) await controller.play(viewportOptions.value.tickRate, viewportOptions.value.tickFunction)
  } catch (error) {
    unavailable(error)
  }
})

onBeforeUnmount(() => {
  disposed = true
  releaseAllKeys()
  removeInputListeners()
  unsubscribeScene?.()
  unsubscribeEvents?.()
  unsubscribeActivity?.()
  renderer?.dispose()
  if (lookTimer !== undefined) window.clearTimeout(lookTimer)
  if (titleTimer !== undefined) window.clearTimeout(titleTimer)
  if (actionbarTimer !== undefined) window.clearTimeout(actionbarTimer)
  if (commandCompletionTimer !== undefined) window.clearTimeout(commandCompletionTimer)
  if (commandCheckTimer !== undefined) window.clearTimeout(commandCheckTimer)
  if (ownsController) controller?.dispose()
  releasePageSandbox?.()
})

function resolveSession(): PlaygroundSessionController {
  if (props.session) return props.session
  if (effectiveSandboxId.value) {
    const lease = acquirePageSandbox(
      effectiveSandboxId.value,
      props.notebook ? ownedSessionOptions(props.notebook) : undefined,
    )
    releasePageSandbox = lease.release
    return lease.controller
  }
  ownsController = true
  return createOwnedSession()
}

function createOwnedSession(): PlaygroundSessionController {
  if (!props.notebook) {
    throw new PlaygroundClientError('NOTEBOOK_REQUIRED', 'DpsViewport requires either a session or a notebook', false)
  }
  return new PlaygroundSessionController(ownedSessionOptions(props.notebook))
}

function ownedSessionOptions(notebook: PlaygroundNotebook) {
  return {
    notebook,
    dependencies: props.dependencies,
    workerUrl: props.workerUrl,
    limits: props.limits,
    render: { auto: false, width: 960, height: 540 },
  }
}

async function togglePlay(): Promise<void> {
  if (!controller || sessionBusy.value) return
  try {
    if (playing.value) await controller.pause()
    else await controller.play(viewportOptions.value.tickRate, viewportOptions.value.tickFunction)
  } catch (error) {
    unavailable(error)
  }
}

async function step(): Promise<void> {
  if (sessionBusy.value) return
  try {
    await controller?.step()
  } catch (error) {
    unavailable(error)
  }
}

function resetView(): void {
  renderer?.resetView(latestScene.value)
}

function updateViewportSettings(): void {
  renderer?.setMoveSpeed(configuredMoveSpeed.value)
  renderer?.setFieldOfView(configuredFieldOfView.value)
}

function onCanvasClick(): void {
  canvas.value?.focus()
  if (viewportOptions.value.pointerLock && canvas.value && document.pointerLockElement !== canvas.value) {
    void canvas.value.requestPointerLock()
  }
}

function installInputListeners(): void {
  window.addEventListener('keydown', onKeyDown)
  window.addEventListener('keyup', onKeyUp)
  window.addEventListener('blur', releaseAllKeys)
  document.addEventListener('mousemove', onMouseMove)
  document.addEventListener('pointerlockchange', onPointerLockChange)
}

function removeInputListeners(): void {
  window.removeEventListener('keydown', onKeyDown)
  window.removeEventListener('keyup', onKeyUp)
  window.removeEventListener('blur', releaseAllKeys)
  document.removeEventListener('mousemove', onMouseMove)
  document.removeEventListener('pointerlockchange', onPointerLockChange)
}

function inputActive(): boolean {
  return document.pointerLockElement === canvas.value || document.activeElement === canvas.value
}

function onKeyDown(event: KeyboardEvent): void {
  if (!viewportOptions.value.keyboard || !inputActive()) return
  if (event.code === 'KeyT' && !event.ctrlKey && !event.altKey && !event.metaKey) {
    event.preventDefault()
    openCommandInput()
    return
  }
  const code = KEY_BINDINGS[event.code]
  if (!code) return
  event.preventDefault()
  if (pressed.has(event.code)) return
  pressed.add(event.code)
  updateMovement()
  void dispatchInput({ device: 'keyboard', code, action: 'press' })
}

function openCommandInput(): void {
  releaseAllKeys()
  if (document.pointerLockElement === canvas.value) document.exitPointerLock()
  commandOpen.value = true
  commandSource.value = '/'
  commandSuggestions.value = []
  commandDiagnostics.value = []
  selectedCommandSuggestion.value = 0
  commandHistoryIndex = commandHistory.length
  void nextTick(() => commandInput.value?.focus())
}

function closeCommandInput(): void {
  commandOpen.value = false
  commandSuggestions.value = []
  commandDiagnostics.value = []
  commandAnalysisRevision += 1
  if (commandCompletionTimer !== undefined) window.clearTimeout(commandCompletionTimer)
  if (commandCheckTimer !== undefined) window.clearTimeout(commandCheckTimer)
  commandCompletionTimer = undefined
  commandCheckTimer = undefined
  void nextTick(() => canvas.value?.focus())
}

function onCommandInput(): void {
  const revision = ++commandAnalysisRevision
  commandSuggestions.value = []
  commandDiagnostics.value = []
  selectedCommandSuggestion.value = 0
  if (commandCompletionTimer !== undefined) window.clearTimeout(commandCompletionTimer)
  if (commandCheckTimer !== undefined) window.clearTimeout(commandCheckTimer)
  const source = normalizedCommandSource()
  if (!source) return
  commandCompletionTimer = window.setTimeout(() => void refreshCommandCompletions(source, revision), 140)
  commandCheckTimer = window.setTimeout(() => void refreshCommandDiagnostics(source, revision), 550)
}

async function refreshCommandCompletions(source: string, revision: number): Promise<void> {
  commandCompletionTimer = undefined
  try {
    const suggestions = await controller?.complete(viewportCommandCellId, source, source.length) ?? []
    if (!commandOpen.value || revision !== commandAnalysisRevision || source !== normalizedCommandSource()) return
    commandSuggestions.value = suggestions.slice(0, 8)
    selectedCommandSuggestion.value = 0
  } catch {
    // Completion is advisory; command execution still reports authoritative errors.
  }
}

async function refreshCommandDiagnostics(source: string, revision: number): Promise<void> {
  commandCheckTimer = undefined
  try {
    const diagnostics = await controller?.check(viewportCommandCellId, source) ?? []
    if (!commandOpen.value || revision !== commandAnalysisRevision || source !== normalizedCommandSource()) return
    commandDiagnostics.value = diagnostics.filter((item) => item.severity !== 'ok')
  } catch {
    // Diagnostics are advisory; keep typing responsive if the Worker is busy.
  }
}

function onCommandKeyDown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    closeCommandInput()
    return
  }
  if (event.key === 'Enter') {
    event.preventDefault()
    void executeViewportCommand()
    return
  }
  if (event.key === 'Tab' && commandSuggestions.value.length > 0) {
    event.preventDefault()
    applyCommandSuggestion(commandSuggestions.value[selectedCommandSuggestion.value] ?? commandSuggestions.value[0])
    return
  }
  if (event.key === 'ArrowDown' && commandSuggestions.value.length > 0) {
    event.preventDefault()
    selectedCommandSuggestion.value = (selectedCommandSuggestion.value + 1) % commandSuggestions.value.length
    return
  }
  if (event.key === 'ArrowUp' && commandSuggestions.value.length > 0) {
    event.preventDefault()
    selectedCommandSuggestion.value = (selectedCommandSuggestion.value - 1 + commandSuggestions.value.length) % commandSuggestions.value.length
    return
  }
  if (event.key === 'ArrowUp' && commandHistory.length > 0) {
    event.preventDefault()
    commandHistoryIndex = Math.max(0, commandHistoryIndex - 1)
    commandSource.value = commandHistory[commandHistoryIndex] ?? ''
    onCommandInput()
  } else if (event.key === 'ArrowDown' && commandHistoryIndex < commandHistory.length) {
    event.preventDefault()
    commandHistoryIndex = Math.min(commandHistory.length, commandHistoryIndex + 1)
    commandSource.value = commandHistory[commandHistoryIndex] ?? ''
    onCommandInput()
  }
}

function applyCommandSuggestion(suggestion: PlaygroundCompletion): void {
  const source = normalizedCommandSource()
  const value = `${source.slice(0, suggestion.start)}${suggestion.value}${suggestion.appendSpace ? ' ' : ''}${source.slice(suggestion.end)}`
  commandSource.value = `/${value}`
  onCommandInput()
  void nextTick(() => {
    const cursor = suggestion.start + suggestion.value.length + (suggestion.appendSpace ? 1 : 0) + 1
    commandInput.value?.setSelectionRange(cursor, cursor)
  })
}

async function executeViewportCommand(): Promise<void> {
  const source = normalizedCommandSource().trim()
  if (!source || !controller || sessionBusy.value) return
  commandHistory.push(`/${source}`)
  if (commandHistory.length > 50) commandHistory.shift()
  closeCommandInput()
  commandExecutionErrorShown = false
  try {
    await controller.execute(viewportCommandCellId, source, { auto: false, width: 960, height: 540 })
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    if (!commandExecutionErrorShown) addChatLine(message, '#ff5555')
  }
}

function normalizedCommandSource(): string {
  return commandSource.value.replace(/^\/+/, '')
}

function onKeyUp(event: KeyboardEvent): void {
  const code = KEY_BINDINGS[event.code]
  if (!code || !pressed.delete(event.code)) return
  event.preventDefault()
  updateMovement()
  void dispatchInput({ device: 'keyboard', code, action: 'release' })
}

function releaseAllKeys(): void {
  for (const key of pressed) {
    const code = KEY_BINDINGS[key]
    if (code) void dispatchInput({ device: 'keyboard', code, action: 'release' })
  }
  pressed.clear()
  updateMovement()
}

function updateMovement(): void {
  renderer?.setMovement({
    forward: Number(pressed.has('KeyW')) - Number(pressed.has('KeyS')),
    right: Number(pressed.has('KeyD')) - Number(pressed.has('KeyA')),
    up: Number(pressed.has('Space')) - Number(pressed.has('ShiftLeft') || pressed.has('ShiftRight')),
  })
}

function onMouseMove(event: MouseEvent): void {
  if (document.pointerLockElement !== canvas.value) return
  renderer?.look(event.movementX * mouseSensitivity.value, event.movementY * mouseSensitivity.value)
  queueLookInput(event.movementX, event.movementY, 'mouse')
}

function onPointerLockChange(): void {
  if (document.pointerLockElement !== canvas.value) releaseAllKeys()
}

function onMouseButton(event: MouseEvent, action: 'press' | 'release'): void {
  if (!inputActive()) return
  const code = MOUSE_BUTTONS[event.button] ?? `button.${event.button}`
  void dispatchInput({ device: 'mouse', code, action })
  if (action === 'release') void dispatchInput({ device: 'mouse', code, action: 'click' })
}

function onWheel(event: WheelEvent): void {
  if (!inputActive()) return
  event.preventDefault()
  renderer?.adjustSpeed(-event.deltaY * 0.001)
  void dispatchInput({ device: 'mouse', code: 'wheel', action: 'scroll', y: event.deltaY })
}

function beginLeftJoystick(event: PointerEvent): void {
  if (!viewportOptions.value.touch || leftPointer !== undefined) return
  touchVisible.value = true
  leftPointer = event.pointerId
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  moveLeftJoystick(event)
}

function moveLeftJoystick(event: PointerEvent): void {
  if (event.pointerId !== leftPointer) return
  const element = event.currentTarget as HTMLElement
  const rect = element.getBoundingClientRect()
  const point = clampedJoystick(event.clientX - (rect.left + rect.width / 2), event.clientY - (rect.top + rect.height / 2), rect.width * 0.32)
  leftKnob.value = point
  renderer?.setMovement({ forward: -point.y / (rect.width * 0.32), right: point.x / (rect.width * 0.32) })
  updateTouchKey('touch-forward', 'key.forward', point.y < -12)
  updateTouchKey('touch-back', 'key.back', point.y > 12)
  updateTouchKey('touch-left', 'key.left', point.x < -12)
  updateTouchKey('touch-right', 'key.right', point.x > 12)
}

function endLeftJoystick(event: PointerEvent): void {
  if (event.pointerId !== leftPointer) return
  leftPointer = undefined
  leftKnob.value = { x: 0, y: 0 }
  renderer?.setMovement({ forward: 0, right: 0 })
  for (const key of ['touch-forward', 'touch-back', 'touch-left', 'touch-right']) releaseTouchKey(key)
}

function beginRightJoystick(event: PointerEvent): void {
  if (!viewportOptions.value.touch || rightPointer !== undefined) return
  touchVisible.value = true
  rightPointer = event.pointerId
  rightLast = { x: event.clientX, y: event.clientY }
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}

function moveRightJoystick(event: PointerEvent): void {
  if (event.pointerId !== rightPointer || !rightLast) return
  const dx = event.clientX - rightLast.x
  const dy = event.clientY - rightLast.y
  rightLast = { x: event.clientX, y: event.clientY }
  rightKnob.value = clampedJoystick(dx * 2, dy * 2, 24)
  renderer?.look(dx * mouseSensitivity.value * 1.8, dy * mouseSensitivity.value * 1.8)
  queueLookInput(dx, dy, 'touch')
}

function endRightJoystick(event: PointerEvent): void {
  if (event.pointerId !== rightPointer) return
  rightPointer = undefined
  rightLast = undefined
  rightKnob.value = { x: 0, y: 0 }
}

function updateTouchKey(local: string, code: string, active: boolean): void {
  if (active && !pressed.has(local)) {
    pressed.add(local)
    void dispatchInput({ device: 'touch', code, action: 'press' })
  } else if (!active) releaseTouchKey(local)
}

function releaseTouchKey(local: string): void {
  if (!pressed.delete(local)) return
  const code = TOUCH_BINDINGS[local]
  if (code) void dispatchInput({ device: 'touch', code, action: 'release' })
}

function queueLookInput(x: number, y: number, device: 'mouse' | 'touch'): void {
  pendingLook.x += x
  pendingLook.y += y
  const remaining = 50 - (performance.now() - lastLookDispatch)
  if (remaining <= 0) flushLookInput(device)
  else if (lookTimer === undefined) lookTimer = window.setTimeout(() => flushLookInput(device), remaining)
}

function flushLookInput(device: 'mouse' | 'touch'): void {
  if (lookTimer !== undefined) window.clearTimeout(lookTimer)
  lookTimer = undefined
  if (pendingLook.x === 0 && pendingLook.y === 0) return
  const { x, y } = pendingLook
  pendingLook = { x: 0, y: 0 }
  lastLookDispatch = performance.now()
  void dispatchInput({ device, code: 'look', action: 'move', x, y })
}

async function dispatchInput(input: PlaygroundPlayerInput): Promise<void> {
  const value = { ...input, player: viewportOptions.value.inputPlayer }
  emit('input', value)
  try {
    await controller?.dispatchInput(value)
  } catch (error) {
    if (!(error instanceof PlaygroundClientError && error.code === 'BUSY')) unavailable(error)
  }
}

function unavailable(error: unknown): void {
  const normalized = error instanceof PlaygroundClientError
    ? error
    : new PlaygroundClientError('VIEWPORT_UNAVAILABLE', error instanceof Error ? error.message : String(error), false)
  status.value = 'unavailable'
  message.value = normalized.message
  emit('error', { code: normalized.code, message: normalized.message })
}

function applyViewportOutput(output: PlaygroundOutputEvent): void {
  if (!visibleToInputPlayer(output)) return
  if (output.channel === 'visual') {
    if (renderer) renderer.handleOutput(output)
    else pendingVisualOutputs.push(output)
    return
  }
  if (output.channel === 'chat') {
    addChatLine(output.text || output.rawText || '', outputColor(output))
    return
  }
  if (output.channel !== 'title') return
  const action = output.command.replace(/^title\s+/, '')
  if (action === 'clear' || action === 'reset') {
    titleOverlay.value = { title: '', subtitle: '', titleColor: '#ffffff', subtitleColor: '#ffffff', visible: false }
    actionbarOverlay.value = { text: '', color: '#ffffff', visible: false }
    if (action === 'reset') titleTimes = { fadeIn: 10, stay: 70, fadeOut: 20 }
    return
  }
  if (action === 'times' && output.payload && typeof output.payload === 'object' && !Array.isArray(output.payload)) {
    const payload = output.payload as Record<string, unknown>
    titleTimes = {
      fadeIn: Math.max(0, Number(payload.fadeIn ?? titleTimes.fadeIn)),
      stay: Math.max(0, Number(payload.stay ?? titleTimes.stay)),
      fadeOut: Math.max(0, Number(payload.fadeOut ?? titleTimes.fadeOut)),
    }
    return
  }
  if (action === 'actionbar') {
    actionbarOverlay.value = { text: output.text, color: outputColor(output), visible: true }
    if (actionbarTimer !== undefined) window.clearTimeout(actionbarTimer)
    actionbarTimer = window.setTimeout(() => { actionbarOverlay.value.visible = false }, 3_000)
    return
  }
  if (action === 'title') {
    titleOverlay.value.title = output.text
    titleOverlay.value.titleColor = outputColor(output)
  } else if (action === 'subtitle') {
    titleOverlay.value.subtitle = output.text
    titleOverlay.value.subtitleColor = outputColor(output)
  }
  titleOverlay.value.visible = true
  if (titleTimer !== undefined) window.clearTimeout(titleTimer)
  titleTimer = window.setTimeout(() => { titleOverlay.value.visible = false }, (titleTimes.fadeIn + titleTimes.stay + titleTimes.fadeOut) * 50)
}

function addChatLine(text: string, color: string): void {
  const id = ++overlaySequence
  chatLines.value = [...chatLines.value.slice(-4), { id, text, color }]
  window.setTimeout(() => {
    chatLines.value = chatLines.value.filter((line) => line.id !== id)
  }, 10_000)
}

function visibleToInputPlayer(output: PlaygroundOutputEvent): boolean {
  if (output.targets.length > 0) return output.targets.includes(viewportOptions.value.inputPlayer)
  return !(output.payload && typeof output.payload === 'object' && !Array.isArray(output.payload) && 'viewerSelector' in output.payload && output.payload.viewerSelector)
}

function outputColor(output: PlaygroundOutputEvent): string {
  const color = output.segments?.find((segment) => segment.color)?.color
  if (!color) return '#ffffff'
  if (/^#[0-9a-f]{6}$/i.test(color)) return color
  return TEXT_COLORS[color] ?? '#ffffff'
}

function clearViewportOutputs(): void {
  chatLines.value = []
  titleOverlay.value = { title: '', subtitle: '', titleColor: '#ffffff', subtitleColor: '#ffffff', visible: false }
  actionbarOverlay.value = { text: '', color: '#ffffff', visible: false }
  pendingVisualOutputs.length = 0
}

function clampedJoystick(x: number, y: number, radius: number): { x: number; y: number } {
  const length = Math.hypot(x, y)
  const scale = length > radius ? radius / length : 1
  return { x: x * scale, y: y * scale }
}

const KEY_BINDINGS: Record<string, string> = {
  KeyW: 'key.forward',
  KeyS: 'key.back',
  KeyA: 'key.left',
  KeyD: 'key.right',
  Space: 'key.jump',
  ShiftLeft: 'key.sneak',
  ShiftRight: 'key.sneak',
}

const TOUCH_BINDINGS: Record<string, string> = {
  'touch-forward': 'key.forward',
  'touch-back': 'key.back',
  'touch-left': 'key.left',
  'touch-right': 'key.right',
}

const MOUSE_BUTTONS: Record<number, string> = { 0: 'left', 1: 'middle', 2: 'right' }
const TEXT_COLORS: Record<string, string> = {
  black: '#000000', dark_blue: '#0000aa', dark_green: '#00aa00', dark_aqua: '#00aaaa', dark_red: '#aa0000',
  dark_purple: '#aa00aa', gold: '#ffaa00', gray: '#aaaaaa', dark_gray: '#555555', blue: '#5555ff',
  green: '#55ff55', aqua: '#55ffff', red: '#ff5555', light_purple: '#ff55ff', yellow: '#ffff55', white: '#ffffff',
}
</script>

<template>
  <section class="dps-viewport" :data-state="status" :data-sandbox-id="effectiveSandboxId" aria-label="Datapack Sandbox realtime viewport">
    <div v-if="viewportOptions.showToolbar" class="dps-viewport-toolbar">
      <div class="dps-viewport-playback">
        <button type="button" :disabled="status !== 'ready' || sessionBusy" data-action="viewport-play" @click="togglePlay">
          {{ playing ? 'Pause' : 'Play' }}
        </button>
        <button type="button" :disabled="status !== 'ready' || playing || sessionBusy" data-action="viewport-step" @click="step">Step</button>
        <button type="button" :disabled="!latestScene" data-action="viewport-reset-view" @click="resetView">Reset view</button>
        <details class="dps-viewport-settings">
          <summary>Settings</summary>
          <div>
            <label>
              <span>Mouse {{ mouseSensitivity.toFixed(2) }}</span>
              <input v-model.number="mouseSensitivity" type="range" min="0.02" max="0.5" step="0.01">
            </label>
            <label>
              <span>Speed {{ configuredMoveSpeed.toFixed(1) }}</span>
              <input v-model.number="configuredMoveSpeed" type="range" min="0.5" max="30" step="0.5" @input="updateViewportSettings">
            </label>
            <label>
              <span>FOV {{ configuredFieldOfView }}°</span>
              <input v-model.number="configuredFieldOfView" type="range" min="30" max="110" step="1" @input="updateViewportSettings">
            </label>
          </div>
        </details>
      </div>
      <span class="dps-viewport-stats">
        {{ stats ? `${Math.round(stats.fps)} FPS · ${stats.triangles} tris · ${stats.pixelRatio.toFixed(2)}×` : `${viewportOptions.tickRate} TPS` }}
      </span>
    </div>
    <div class="dps-viewport-stage">
      <canvas
        ref="canvas"
        class="dps-viewport-canvas"
        tabindex="0"
        aria-label="Realtime WebGL sandbox view"
        @click="onCanvasClick"
        @mousedown="onMouseButton($event, 'press')"
        @mouseup="onMouseButton($event, 'release')"
        @wheel="onWheel"
        @contextmenu.prevent
        @pointerdown="touchVisible = touchVisible || $event.pointerType === 'touch'"
      />
      <div v-if="message" class="dps-viewport-message" role="status">{{ message }}</div>
      <div v-if="titleOverlay.visible" class="dps-viewport-title" aria-live="polite">
        <strong v-if="titleOverlay.title" :style="{ color: titleOverlay.titleColor }">{{ titleOverlay.title }}</strong>
        <span v-if="titleOverlay.subtitle" :style="{ color: titleOverlay.subtitleColor }">{{ titleOverlay.subtitle }}</span>
      </div>
      <div v-if="actionbarOverlay.visible" class="dps-viewport-actionbar" :style="{ color: actionbarOverlay.color }">
        {{ actionbarOverlay.text }}
      </div>
      <div v-if="chatLines.length" class="dps-viewport-chat" aria-live="polite">
        <span v-for="line in chatLines" :key="line.id" :style="{ color: line.color }">{{ line.text }}</span>
      </div>
      <div v-if="commandOpen" class="dps-viewport-command" @mousedown.stop>
        <div v-if="commandSuggestions.length" class="dps-command-suggestions" role="listbox">
          <button
            v-for="(suggestion, index) in commandSuggestions"
            :key="`${suggestion.start}:${suggestion.end}:${suggestion.value}`"
            type="button"
            role="option"
            :aria-selected="index === selectedCommandSuggestion"
            @mousedown.prevent="applyCommandSuggestion(suggestion)"
          >
            <b>{{ suggestion.value }}</b>
            <small v-if="suggestion.description">{{ suggestion.description }}</small>
          </button>
        </div>
        <div v-if="commandDiagnostics[0]" class="dps-command-diagnostic" role="status">
          {{ commandDiagnostics[0].message }}
        </div>
        <input
          ref="commandInput"
          v-model="commandSource"
          type="text"
          spellcheck="false"
          autocomplete="off"
          aria-label="Sandbox command"
          placeholder="/command"
          @input="onCommandInput"
          @keydown.stop="onCommandKeyDown"
        >
      </div>
      <div v-if="touchVisible && viewportOptions.touch" class="dps-touch-controls" aria-label="Touch camera controls">
        <div
          class="dps-joystick dps-joystick-left"
          @pointerdown.prevent="beginLeftJoystick"
          @pointermove.prevent="moveLeftJoystick"
          @pointerup.prevent="endLeftJoystick"
          @pointercancel.prevent="endLeftJoystick"
        >
          <span class="dps-joystick-knob" :style="leftKnobStyle" />
        </div>
        <div
          class="dps-joystick dps-joystick-right"
          @pointerdown.prevent="beginRightJoystick"
          @pointermove.prevent="moveRightJoystick"
          @pointerup.prevent="endRightJoystick"
          @pointercancel.prevent="endRightJoystick"
        >
          <span class="dps-joystick-knob" :style="rightKnobStyle" />
        </div>
      </div>
    </div>
  </section>
</template>
