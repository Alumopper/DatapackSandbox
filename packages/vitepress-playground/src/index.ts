import DpsPlayground from './DpsPlayground.vue'
import DpsCell from './DpsCell.vue'
import { defineAsyncComponent } from 'vue'
import './style.css'

export default DpsPlayground
export const DpsViewport = defineAsyncComponent(() => import('./DpsViewport.vue'))
export { DpsCell, DpsPlayground }
export { PlaygroundClientError, PlaygroundWorkerClient } from './client'
export type { PlaygroundWorkerClientOptions } from './client'
export { PlaygroundSessionController } from './session'
export type { PlaygroundSessionControllerOptions } from './session'
export type {
  PlaygroundBrowserLimits,
  PlaygroundAnimationOptions,
  PlaygroundCheckpoint,
  PlaygroundCell,
  PlaygroundCodeCell,
  PlaygroundCompletion,
  PlaygroundDiagnostic,
  PlaygroundDependencySource,
  PlaygroundErrorData,
  PlaygroundEvent,
  PlaygroundImportEntry,
  PlaygroundImportKind,
  PlaygroundImportResult,
  PlaygroundLayout,
  PlaygroundMarkdownCell,
  PlaygroundNotebook,
  PlaygroundPresetRegistry,
  PlaygroundPresetSource,
  PlaygroundRenderOptions,
  PlaygroundViewportOptions,
  PlaygroundViewportScene,
  PlaygroundSceneBatch,
  PlaygroundSceneSection,
  PlaygroundTextureAtlas,
  PlaygroundPlayerInput,
  PlaygroundCameraState,
  PlaygroundFrameStats,
  PlaygroundTheme,
} from './types'
