declare module '*datapack-sandbox-core.js' {
  export interface BrowserCoreSession {
    beginExecution(): void
    executeLineSafe(source: string, line: number): string
    finishExecution(): string
    check(source: string): string
    interrupt(): void
    reset(): void
    saveCheckpoint(name: string): string
    restoreCheckpoint(name: string): string
    deleteCheckpoint(name: string): string
    checkpointNames(): string
    clearFunctions(): void
    clearDatapackEntries(): void
    upsertDatapackEntry(path: string, content: string): void
    upsertFunction(id: string, source: string): void
    setFunctionTag(id: string, valuesCsv: string): void
    runLoad(): string
    runTicks(count: number, tickFunction: string | null): string
    runRealtimeTicks(count: number, tickFunction: string | null): string
    runRealtimeTicksCompact(count: number, tickFunction: string | null): string
    renderSnapshot(): string
    dispatchInput(
      player: string,
      device: string,
      code: string,
      action: string,
      x: number,
      y: number,
    ): string
    snapshot(): string
  }

  export function createSession(
    version: string,
    maximumCommands: number,
    maximumOutputEvents: number,
    maximumSnapshotBytes: number,
  ): BrowserCoreSession
}
