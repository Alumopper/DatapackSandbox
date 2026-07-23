export interface PlaygroundMarkdownCell {
  id?: string
  type: 'markdown'
  source: string
}

export interface PlaygroundCodeCell {
  id?: string
  type: 'code'
  source: string
}

export type PlaygroundCell = PlaygroundMarkdownCell | PlaygroundCodeCell

export interface PlaygroundNotebook {
  version: string
  cells: PlaygroundCell[]
  preset?: string
}

export interface PlaygroundRenderOptions {
  auto?: boolean
  width?: number
  height?: number
}

export interface PlaygroundViewportOptions {
  targetFps?: number
  tickRate?: number
  autoplay?: boolean
  tickFunction?: string
  inputPlayer?: string
  keyboard?: boolean
  touch?: boolean
  pointerLock?: boolean
  showToolbar?: boolean
  fieldOfView?: number
  moveSpeed?: number
  minimumPixelRatio?: number
  maximumPixelRatio?: number
}

export interface PlaygroundPlayerInput {
  player?: string
  device: 'keyboard' | 'mouse' | 'touch'
  code: string
  action: 'press' | 'release' | 'click' | 'move' | 'scroll'
  x?: number
  y?: number
}

export interface PlaygroundCameraState {
  position: [number, number, number]
  yaw: number
  pitch: number
  speed: number
  automatic: boolean
}

export interface PlaygroundFrameStats {
  fps: number
  frameTimeMs: number
  pixelRatio: number
  triangles: number
  revision: number
}

export interface PlaygroundSceneBatch {
  pass: 'opaque' | 'cutout' | 'translucent'
  indexOffset: number
  indexCount: number
  seeThrough: boolean
}

export interface PlaygroundSceneSection {
  vertices: ArrayBuffer
  indices: ArrayBuffer
  batches: PlaygroundSceneBatch[]
}

export interface PlaygroundTextureAtlas {
  width: number
  height: number
  rgba: ArrayBuffer
}

export interface PlaygroundViewportScene {
  revision: number
  tick: number
  tickRate: number
  generatedAt: number
  vertexStride: number
  camera: { position: [number, number, number]; yaw: number; pitch: number }
  bounds: { minimum: [number, number, number]; maximum: [number, number, number] }
  blocks?: PlaygroundSceneSection
  entities?: PlaygroundSceneSection
  atlas?: PlaygroundTextureAtlas
  visibleBlocks: number
  visibleEntities: number
}

export interface PlaygroundAnimationOptions {
  width?: number
  height?: number
  delayMs?: number
  repeat?: number
  captureOnExecute?: boolean
}

export interface PlaygroundBrowserLimits {
  requestTimeoutMs?: number
  cancelGraceMs?: number
  maximumCellBytes?: number
  maximumOutputBytes?: number
  maximumCommands?: number
  maximumOutputEvents?: number
  maximumRenderWidth?: number
  maximumRenderHeight?: number
  maximumCheckpoints?: number
  maximumCheckpointBytes?: number
  maximumAnimationFrames?: number
  maximumAnimationBytes?: number
  maximumImportBytes?: number
  maximumImportFiles?: number
}

export interface PlaygroundCheckpoint {
  name: string
  snapshot: Record<string, unknown>
}

export interface PlaygroundPresetSource {
  url: string
  sha256?: string
}

export type PlaygroundPresetRegistry = Record<string, PlaygroundPresetSource>

export interface PlaygroundDependencySource {
  kind: 'datapack' | 'resource-pack'
  url: string
  sha256?: string
  name?: string
}

export type PlaygroundImportKind = 'datapack' | 'resource-pack' | 'client-jar' | 'world'

export interface PlaygroundImportEntry {
  path: string
  bytes: ArrayBuffer
}

export interface PlaygroundImportResult {
  kind: PlaygroundImportKind
  files: number
  bytes: number
  functions: number
}

export type PlaygroundTheme = 'auto' | 'light' | 'dark'
export type PlaygroundLayout = 'notebook' | 'compact'

export interface PlaygroundDiagnostic {
  line: number
  from?: number
  to?: number
  severity: 'error' | 'warning' | 'info' | 'hint' | 'ok'
  code?: string
  message: string
  command?: string
}

export interface PlaygroundCompletion {
  value: string
  description?: string
  group?: string
  appendSpace?: boolean
  start: number
  end: number
  behavior?: string
}

export interface PlaygroundOutputEvent {
  tick: number
  command: string
  channel: string
  targets: string[]
  text: string
  rawText?: string
  payload?: Record<string, unknown> | unknown[] | string | number | boolean | null
  segments?: Array<{ text: string; color?: string; bold?: boolean; italic?: boolean }>
}

export interface PlaygroundErrorData {
  code: string
  message: string
  recoverable: boolean
  details?: unknown
}

export interface PlaygroundEvent {
  type: string
  requestId?: string | number
  sessionId?: string
  cellId?: string
  status?: string
  kind?: string
  summary?: string
  result?: Record<string, unknown>
  diagnostics?: PlaygroundDiagnostic[]
  error?: PlaygroundErrorData
  mimeType?: string
  bytes?: ArrayBuffer
  width?: number
  height?: number
  scene?: PlaygroundViewportScene
  output?: PlaygroundOutputEvent
  [key: string]: unknown
}
