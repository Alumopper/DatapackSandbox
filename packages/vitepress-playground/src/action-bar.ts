import type { PlaygroundAction } from './types'

export interface PlaygroundActionItem {
  id: PlaygroundAction
  label: string
  disabled?: boolean
  title?: string
  emphasis?: boolean
  visible?: boolean
  run: () => unknown | Promise<unknown>
}
