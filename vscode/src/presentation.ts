import * as vscode from "vscode";
import { ManifestReport, RunReport } from "./model";

export function showRun(output: vscode.OutputChannel, report: RunReport): void {
  output.appendLine(`\n${report.passed ? "PASS" : "FAIL"} version=${report.version} commands=${report.commands} gameTime=${report.gameTime}`);
  for (const item of report.outputs ?? []) output.appendLine(`[output:${item.channel}] ${item.text}`);
  for (const trace of report.traces ?? []) output.appendLine(`[trace:${trace.success ? "ok" : "error"}]${trace.source?.file ? ` ${trace.source.file}:${trace.source.line ?? 1}` : ""} ${trace.command}`);
  for (const diff of report.snapshotDiffs ?? []) output.appendLine(`[diff] ${diff.path}: ${JSON.stringify(diff.before)} -> ${JSON.stringify(diff.after)}`);
  for (const failure of report.assertionFailures ?? []) output.appendLine(`ASSERT: ${failure}`);
  output.show(true);
}

export function showManifest(output: vscode.OutputChannel, reports: ManifestReport[]): void {
  for (const report of reports) {
    output.appendLine(`\n${report.passed ? "PASS" : "FAIL"} ${report.path}`);
    for (const message of report.messages ?? []) output.appendLine(`  ${message}`);
    for (const trace of report.traces ?? []) output.appendLine(`[trace:${trace.success ? "ok" : "error"}] ${trace.command}`);
  }
  output.show(true);
}
