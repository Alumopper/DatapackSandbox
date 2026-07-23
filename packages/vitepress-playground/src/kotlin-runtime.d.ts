declare module '*.mjs' {
  export class BrowserSandboxEngine {
    constructor(
      version: string,
      rootsCsv: string,
      blocksCsv: string,
      itemsCsv: string,
      entitiesCsv: string,
      maximumCellBytes: number,
      maximumOutputBytes: number,
      maximumCommands: number,
      maximumOutputEvents: number,
      maximumRenderWidth: number,
      maximumRenderHeight: number,
      maximumCheckpoints: number,
      maximumCheckpointBytes: number,
      maximumAnimationFrames: number,
      maximumAnimationBytes: number,
    )
    beginExecution(): void
    executeLine(source: string, line: number): void
    executeLineSafe(source: string, line: number): string
    finishExecution(): string
    check(source: string): string
    complete(source: string, cursor: number): string
    interrupt(): void
    reset(): void
    saveCheckpoint(name: string): string
    restoreCheckpoint(name: string): string
    deleteCheckpoint(name: string): string
    checkpointNames(): string
    upsertFunction(id: string, source: string): void
    clearFunctions(): void
    setFunctionTag(id: string, valuesCsv: string): void
    runLoad(): string
    runTicks(count: number, tickFunction: string | null): string
    dispatchInput(
      player: string,
      device: string,
      code: string,
      action: string,
      x: number | null,
      y: number | null,
    ): string
    upsertTexture(id: string, width: number, height: number, rgba: Int8Array): void
    upsertRenderAsset(path: string, text: string): void
    snapshot(): string
    renderRgba(width: number, height: number): Int8Array
    renderMetadata(width: number, height: number): string
    captureAnimationFrame(width: number, height: number, delayCentiseconds: number): number
    exportAnimation(repeat: number): Int8Array
    clearAnimation(): void
    animationFrameCount(): number
    compileRealtimeScene(width: number, height: number): string
    realtimeBlockVertices(): Float32Array
    realtimeBlockIndices(): Int32Array
    realtimeEntityVertices(): Float32Array
    realtimeEntityIndices(): Int32Array
    realtimeAtlasRgba(): Int8Array
  }
}
