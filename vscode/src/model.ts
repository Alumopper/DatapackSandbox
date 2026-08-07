export interface SourceLocation { file?: string; line?: number; command?: string; functionStack?: Array<{ id: string; file?: string }>; }
export interface SnapshotDiff { path: string; kind?: string; before?: unknown; after?: unknown; }
export interface OutputTextSegment { text: string; color?: string; bold?: boolean; italic?: boolean; underlined?: boolean; strikethrough?: boolean; obfuscated?: boolean; }
export interface OutputEvent { tick: number; command: string; channel: string; text: string; rawText?: string; targets?: string[]; payload?: unknown; segments?: OutputTextSegment[]; source?: SourceLocation; }
export interface TraceEvent { tick: number; command: string; root: string; success: boolean; commandsExecuted: number; outputs: number; executor?: string; position?: { x: number; y: number; z: number }; source?: SourceLocation; outputEvents?: OutputEvent[]; snapshotDiffs?: SnapshotDiff[]; errorCode?: string; errorMessage?: string; }
export interface PlayerEventTraceEvent { tick: number; playerName: string; type: string; success: boolean; errorCode?: string; errorMessage?: string; [key: string]: unknown; }
export interface DiagnosticReport { code?: string; message?: string; version?: string; command?: string; source?: SourceLocation; }
export interface ResourceEntry { type: string; id: string; file: string; pack: string; active: boolean; behavior: string; }
export interface ResourceReport { summary?: Record<string, unknown>; resources?: ResourceEntry[]; functionIds?: string[]; lootTableIds?: string[]; predicateIds?: string[]; advancementIds?: string[]; }
export interface FunctionSource { id: string; file?: string; source: string; }
export interface RenderResult { mimeType: "image/png"; encoding: "base64"; data: string; width: number; height: number; metadata: Record<string, unknown>; }
export interface CheckpointResult { names: string[]; name?: string; action?: "saved" | "restored" | "deleted"; changed?: boolean; state?: Record<string, unknown>; }
export interface ServeHello { protocol: string; defaultVersion: string; versions: string[]; capabilities: Record<string, boolean | string>; }
export interface RunReport { version: string; passed: boolean; gameTime: number; commands: number; entities: number; assertionFailures: string[]; outputs: OutputEvent[]; traces: TraceEvent[]; diagnostics: DiagnosticReport[]; snapshot: unknown; snapshotDiffs: SnapshotDiff[]; resources?: ResourceReport; }
export interface ManifestAttemptReport extends Partial<RunReport> { version: string; packs: string[]; passed: boolean; messages: string[]; }
export interface ManifestReport { path: string; passed: boolean; messages: string[]; outputs: OutputEvent[]; traces: TraceEvent[]; diagnostics: DiagnosticReport[]; attempts: ManifestAttemptReport[]; }
export interface FunctionContext { file: string; id: string; packRoot?: string; }
