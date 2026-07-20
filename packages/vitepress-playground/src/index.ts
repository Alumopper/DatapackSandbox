import DpsPlayground from './DpsPlayground.vue'
import './style.css'

export default DpsPlayground
export { DpsPlayground }
export { PlaygroundClient, PlaygroundClientError, toWebSocketEndpoint } from './client'
export type {
  PlaygroundCell,
  PlaygroundCodeCell,
  PlaygroundCompletion,
  PlaygroundDiagnostic,
  PlaygroundErrorData,
  PlaygroundEvent,
  PlaygroundLayout,
  PlaygroundMarkdownCell,
  PlaygroundNotebook,
  PlaygroundRenderOptions,
  PlaygroundTheme,
} from './types'
