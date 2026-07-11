import * as fs from "node:fs/promises";
import * as path from "node:path";
import * as vscode from "vscode";
import { CliRunner } from "./cli";
import { DatapackCodeLensProvider } from "./codeLens";
import { SandboxCompletionProvider } from "./completions";
import { TraceDebugAdapterFactory, TraceDebugConfigurationProvider } from "./debugAdapter";
import { DiagnosticPublisher } from "./diagnostics";
import { inferFunctionContext, isManifest } from "./functionContext";
import { showManifest, showRun } from "./presentation";
import { ResourceTreeProvider } from "./resources";
import { SandboxClient } from "./sandboxClient";
import { SandboxPanel } from "./sandboxPanel";
import { ManifestTestController } from "./testController";
import { ActiveSandboxService } from "./activeSandbox";

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  const output = vscode.window.createOutputChannel("Datapack Sandbox");
  const cli = new CliRunner(output);
  const diagnostics = new DiagnosticPublisher();
  const client = new SandboxClient(cli, output);
  const activeSandbox = new ActiveSandboxService(client);
  const panel = new SandboxPanel(client);
  const resources = new ResourceTreeProvider(client);
  const tests = new ManifestTestController(cli, diagnostics, activeSandbox);
  const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 50);
  status.command = "datapackSandbox.openSandboxPanel";
  updateStatusBar(status, undefined);
  status.show();
  client.onDidChangeState((state) => updateStatusBar(status, state));

  const runCurrent = async (uri?: vscode.Uri, forcePack = false, targetMode?: "temporary" | "active") => {
    const target = resolveUri(uri, ".mcfunction");
    if (!target) return;
    await vscode.window.withProgress({ location: vscode.ProgressLocation.Notification, title: "Running Datapack Sandbox function" }, async () => {
      const mode = targetMode ?? vscode.workspace.getConfiguration("datapackSandbox").get<"temporary" | "active">("defaultExecutionTarget", "temporary");
      const report = mode === "active" ? await activeSandbox.runFunction(target.fsPath) : (await cli.runCurrent(target.fsPath, forcePack)).report;
      diagnostics.publishRun(report, target); showRun(output, report);
      if (!report.passed) void vscode.window.showErrorMessage("Datapack Sandbox function failed. See Output and Problems.");
    });
  };
  const runManifest = async (uri?: vscode.Uri, strict = false, targetMode?: "temporary" | "active") => {
    const target = resolveUri(uri, ".dps.json");
    if (!target) return;
    await vscode.window.withProgress({ location: vscode.ProgressLocation.Notification, title: `Running Datapack Sandbox manifest${strict ? " (strict)" : ""}` }, async () => {
      const mode = targetMode ?? vscode.workspace.getConfiguration("datapackSandbox").get<"temporary" | "active">("defaultExecutionTarget", "temporary");
      const reports = mode === "active" ? await activeSandbox.runManifest(target.fsPath, strict) : (await cli.checkManifest(target.fsPath, strict)).report;
      diagnostics.publishManifest(reports, target); showManifest(output, reports);
      if (reports.some((report) => !report.passed)) void vscode.window.showErrorMessage("Datapack Sandbox manifest failed. See Test Results, Output, and Problems.");
    });
  };
  const debug = async (uri?: vscode.Uri, targetMode?: "temporary" | "active") => {
    const target = resolveUri(uri);
    if (!target) return;
    const sandbox = targetMode ?? vscode.workspace.getConfiguration("datapackSandbox").get<"temporary" | "active">("defaultExecutionTarget", "temporary");
    await vscode.debug.startDebugging(vscode.workspace.getWorkspaceFolder(target), { name: `Datapack Sandbox Trace (${sandbox})`, type: "datapack-sandbox", request: "launch", program: target.fsPath, strict: vscode.workspace.getConfiguration("datapackSandbox").get("strict", false), sandbox, stopOnEntry: false });
  };

  context.subscriptions.push(
    output, diagnostics.collection, client, panel, tests, status,
    vscode.commands.registerCommand("datapackSandbox.runCurrentMcfunction", (uri?: vscode.Uri) => safe(() => runCurrent(uri))),
    vscode.commands.registerCommand("datapackSandbox.runCurrentWithPack", (uri?: vscode.Uri) => safe(() => runCurrent(uri, true))),
    vscode.commands.registerCommand("datapackSandbox.runCurrentInActiveSandbox", (uri?: vscode.Uri) => safe(() => runCurrent(uri, true, "active"))),
    vscode.commands.registerCommand("datapackSandbox.debugCurrentMcfunction", (uri?: vscode.Uri) => safe(() => debug(uri))),
    vscode.commands.registerCommand("datapackSandbox.debugCurrentInActiveSandbox", (uri?: vscode.Uri) => safe(() => debug(uri, "active"))),
    vscode.commands.registerCommand("datapackSandbox.runManifest", (uri?: vscode.Uri) => safe(() => runManifest(uri))),
    vscode.commands.registerCommand("datapackSandbox.runManifestStrict", (uri?: vscode.Uri) => safe(() => runManifest(uri, true))),
    vscode.commands.registerCommand("datapackSandbox.runManifestInActiveSandbox", (uri?: vscode.Uri) => safe(() => runManifest(uri, false, "active"))),
    vscode.commands.registerCommand("datapackSandbox.debugManifestTrace", (uri?: vscode.Uri) => safe(() => debug(uri))),
    vscode.commands.registerCommand("datapackSandbox.openSandboxPanel", () => panel.show()),
    vscode.commands.registerCommand("datapackSandbox.startSandbox", (uri?: vscode.Uri) => safe(async () => { const state = await activeSandbox.start(uri ?? vscode.window.activeTextEditor?.document.uri); updateStatusBar(status, state); void vscode.window.showInformationMessage(`Datapack Sandbox ${state.version} started.`); })),
    vscode.commands.registerCommand("datapackSandbox.stopSandbox", () => { activeSandbox.stop(); void vscode.window.showInformationMessage("Datapack Sandbox stopped."); }),
    vscode.commands.registerCommand("datapackSandbox.reloadTests", () => tests.discover()),
    vscode.commands.registerCommand("datapackSandbox.refreshResources", () => safe(() => resources.refresh())),
    vscode.commands.registerCommand("datapackSandbox.generateManifest", (uri?: vscode.Uri) => safe(() => generateManifest(uri))),
    vscode.languages.registerCodeLensProvider([{ language: "mcfunction" }, { language: "json", pattern: "**/*.dps.json" }], new DatapackCodeLensProvider()),
    vscode.languages.registerCompletionItemProvider({ language: "mcfunction" }, new SandboxCompletionProvider(client), " ", ":", "@"),
    vscode.window.registerTreeDataProvider("datapackSandbox.resources", resources),
    vscode.debug.registerDebugAdapterDescriptorFactory("datapack-sandbox", new TraceDebugAdapterFactory(cli, activeSandbox)),
    vscode.debug.registerDebugConfigurationProvider("datapack-sandbox", new TraceDebugConfigurationProvider()),
    vscode.workspace.onDidCreateFiles(() => tests.discover()),
    vscode.workspace.onDidDeleteFiles(() => tests.discover()),
  );
  await tests.discover();
}

export function deactivate(): void {}

async function generateManifest(uri?: vscode.Uri): Promise<void> {
  const target = resolveUri(uri, ".mcfunction");
  if (!target) return;
  const context = inferFunctionContext(target.fsPath);
  const defaultUri = vscode.Uri.file(path.join(path.dirname(target.fsPath), `${path.basename(target.fsPath, ".mcfunction")}.dps.json`));
  const destination = await vscode.window.showSaveDialog({ defaultUri, filters: { "Datapack Sandbox Manifest": ["dps.json"] } });
  if (!destination) return;
  const manifest = { version: vscode.workspace.getConfiguration("datapackSandbox").get("defaultVersion", "26.2"), ...(context.packRoot ? { packs: [path.relative(path.dirname(destination.fsPath), context.packRoot) || "."] } : {}), steps: [{ mcfunction: path.relative(path.dirname(destination.fsPath), target.fsPath).replace(/\\/g, "/") }], assertions: [] };
  await fs.writeFile(destination.fsPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
  await vscode.window.showTextDocument(destination);
}

function resolveUri(uri?: vscode.Uri, suffix?: string): vscode.Uri | undefined {
  const target = uri ?? vscode.window.activeTextEditor?.document.uri;
  if (!target || (suffix && !target.fsPath.toLowerCase().endsWith(suffix))) { void vscode.window.showWarningMessage(`Open or select a ${suffix ?? ".mcfunction or .dps.json"} file first.`); return undefined; }
  if (!target.fsPath.endsWith(".mcfunction") && !isManifest(target.fsPath)) { void vscode.window.showWarningMessage("Datapack Sandbox can debug .mcfunction and .dps.json files."); return undefined; }
  return target;
}

async function safe(action: () => Promise<unknown>): Promise<void> { try { await action(); } catch (error) { void vscode.window.showErrorMessage(error instanceof Error ? error.message : String(error)); } }
function updateStatusBar(item: vscode.StatusBarItem, state?: Record<string, unknown>): void { item.text = state ? `$(beaker) DPS ${state.version} • Ready` : `$(beaker) DPS • Stopped`; item.tooltip = "Open Datapack Sandbox control panel"; }
