import * as path from "node:path";
import * as vscode from "vscode";
import { DiagnosticReport, ManifestReport, RunReport, TraceEvent } from "./model";

export class DiagnosticPublisher {
  readonly collection = vscode.languages.createDiagnosticCollection("datapack-sandbox");
  clear(): void { this.collection.clear(); }
  publishRun(report: RunReport, fallback: vscode.Uri): void { this.publish(report.diagnostics ?? fromTraces(report.traces ?? []), fallback); }
  publishManifest(reports: ManifestReport[], fallback: vscode.Uri): void { this.publish(reports.flatMap((report) => report.diagnostics ?? fromTraces(report.traces ?? [])), fallback); }

  private publish(items: DiagnosticReport[], fallback: vscode.Uri): void {
    const grouped = new Map<string, vscode.Diagnostic[]>();
    for (const item of items) {
      const uri = item.source?.file && !item.source.file.startsWith("<") ? vscode.Uri.file(path.resolve(item.source.file)) : fallback;
      const line = Math.max(0, (item.source?.line ?? 1) - 1);
      const diagnostic = new vscode.Diagnostic(new vscode.Range(line, 0, line, Number.MAX_SAFE_INTEGER), item.message ?? item.code ?? "Datapack Sandbox diagnostic", vscode.DiagnosticSeverity.Error);
      diagnostic.code = item.code; diagnostic.source = "Datapack Sandbox";
      const values = grouped.get(uri.toString()) ?? []; values.push(diagnostic); grouped.set(uri.toString(), values);
    }
    this.collection.clear();
    for (const [uri, values] of grouped) this.collection.set(vscode.Uri.parse(uri), values);
  }
}

function fromTraces(traces: TraceEvent[]): DiagnosticReport[] { return traces.filter((trace) => trace.errorCode).map((trace) => ({ code: trace.errorCode, message: trace.errorMessage, command: trace.command, source: trace.source })); }
