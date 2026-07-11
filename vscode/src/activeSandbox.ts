import * as path from "node:path";
import * as vscode from "vscode";
import { inferFunctionContext, isManifest } from "./functionContext";
import { DiagnosticReport, ManifestReport, OutputEvent, RunReport, SnapshotDiff, TraceEvent } from "./model";
import { SandboxClient } from "./sandboxClient";

interface TrackedResult {
  commands: number;
  outputs: OutputEvent[];
  traces: TraceEvent[];
  snapshotDiffs: SnapshotDiff[];
  state: Record<string, unknown>;
}

export class ActiveSandboxService {
  constructor(private readonly client: SandboxClient) {}

  get active(): boolean { return this.client.hasActiveSandbox; }

  async start(uri?: vscode.Uri): Promise<Record<string, unknown>> {
    const config = vscode.workspace.getConfiguration("datapackSandbox");
    const root = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? process.cwd();
    const packs = config.get<string[]>("packPaths", []).map((pack) => path.resolve(root, pack));
    if (uri?.fsPath.endsWith(".mcfunction")) {
      const inferred = inferFunctionContext(uri.fsPath).packRoot;
      if (inferred) packs.unshift(inferred);
    }
    return this.client.create(config.get("defaultVersion", "26.2"), [...new Set(packs)]);
  }

  stop(): void { this.client.close(); }

  requireActive(): void {
    if (!this.active) throw new Error("No active Datapack Sandbox. Run 'Datapack Sandbox: Start Sandbox' first.");
  }

  async runFunction(file: string): Promise<RunReport> {
    this.requireActive();
    const context = inferFunctionContext(file);
    await this.client.request("upsertFunctionSource", { id: context.id, path: context.file, sourceName: context.file });
    const result = await this.client.request<TrackedResult>("runFunction", { id: context.id });
    const snapshot = await this.client.request<unknown>("snapshot");
    const resources = await this.client.request<RunReport["resources"]>("resources");
    return {
      version: String(result.state.version), passed: !result.traces.some((trace) => !trace.success), gameTime: Number(result.state.gameTime ?? 0),
      commands: result.commands, entities: Number(result.state.entities ?? 0), assertionFailures: [], outputs: result.outputs, traces: result.traces,
      diagnostics: diagnosticsFromTraces(result.traces), snapshot, snapshotDiffs: result.snapshotDiffs, resources,
    };
  }

  async runManifest(file: string, strict: boolean): Promise<ManifestReport[]> {
    this.requireActive();
    if (!isManifest(file)) throw new Error("Active sandbox manifest runs require a .dps.json file");
    const result = await this.client.request<ManifestReport & { state: Record<string, unknown> }>("runManifest", { path: file, strict });
    return [result];
  }
}

function diagnosticsFromTraces(traces: TraceEvent[]): DiagnosticReport[] {
  return traces.filter((trace) => trace.errorCode).map((trace) => ({ code: trace.errorCode, message: trace.errorMessage, command: trace.command, source: trace.source }));
}
