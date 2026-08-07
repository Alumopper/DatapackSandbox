import { PlaygroundClientError } from './client'
import { PlaygroundSessionController } from './session'
import type { PlaygroundSessionControllerOptions } from './session'

interface PageSandboxEntry {
  controller: PlaygroundSessionController
  references: number
  version: string
}

export interface PageSandboxLease {
  controller: PlaygroundSessionController
  release: () => void
}

const pageSandboxes = new Map<string, PageSandboxEntry>()

export function normalizedSandboxId(value: string | undefined): string | undefined {
  const normalized = value?.trim()
  return normalized || undefined
}

export function acquirePageSandbox(
  sandboxId: string,
  options?: PlaygroundSessionControllerOptions,
): PageSandboxLease {
  const id = normalizedSandboxId(sandboxId)
  if (!id) throw new PlaygroundClientError('SANDBOX_ID_INVALID', 'sandboxId must not be blank', false)
  let entry = pageSandboxes.get(id)
  if (entry) {
    if (options && options.notebook.version !== entry.version) {
      throw new PlaygroundClientError(
        'SANDBOX_VERSION_MISMATCH',
        `Sandbox '${id}' already uses Minecraft ${entry.version}; it cannot also use ${options.notebook.version}`,
        false,
      )
    }
  } else {
    if (!options) {
      throw new PlaygroundClientError(
        'NOTEBOOK_REQUIRED',
        `Sandbox '${id}' does not exist yet; the first component must provide a notebook`,
        false,
      )
    }
    entry = {
      controller: new PlaygroundSessionController(options),
      references: 0,
      version: options.notebook.version,
    }
    pageSandboxes.set(id, entry)
  }

  entry.references += 1
  let released = false
  return {
    controller: entry.controller,
    release: () => {
      if (released) return
      released = true
      entry!.references -= 1
      if (entry!.references > 0 || pageSandboxes.get(id) !== entry) return
      pageSandboxes.delete(id)
      entry!.controller.dispose()
    },
  }
}

let nextStandaloneScope = 0

export function createComponentScopeId(): string {
  return `component-${++nextStandaloneScope}`
}
