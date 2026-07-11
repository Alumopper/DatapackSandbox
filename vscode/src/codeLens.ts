import * as vscode from "vscode";
import { isManifest } from "./functionContext";

export class DatapackCodeLensProvider implements vscode.CodeLensProvider {
  provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] {
    const range = new vscode.Range(0, 0, 0, 0);
    if (document.uri.fsPath.endsWith(".mcfunction")) return [lens(range, "Run", "datapackSandbox.runCurrentMcfunction", document.uri), lens(range, "Run in Active Sandbox", "datapackSandbox.runCurrentInActiveSandbox", document.uri), lens(range, "Debug", "datapackSandbox.debugCurrentMcfunction", document.uri), lens(range, "Debug in Active Sandbox", "datapackSandbox.debugCurrentInActiveSandbox", document.uri), lens(range, "Generate Manifest", "datapackSandbox.generateManifest", document.uri)];
    if (isManifest(document.uri.fsPath)) return [lens(range, "Run Manifest", "datapackSandbox.runManifest", document.uri), lens(range, "Run in Active Sandbox", "datapackSandbox.runManifestInActiveSandbox", document.uri), lens(range, "Run Strict", "datapackSandbox.runManifestStrict", document.uri), lens(range, "Debug Manifest Trace", "datapackSandbox.debugManifestTrace", document.uri)];
    return [];
  }
}
function lens(range: vscode.Range, title: string, command: string, uri: vscode.Uri): vscode.CodeLens { return new vscode.CodeLens(range, { title, command, arguments: [uri] }); }
