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
  encoding?: string
  data?: string
  width?: number
  height?: number
  [key: string]: unknown
}
