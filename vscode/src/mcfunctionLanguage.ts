import * as fs from "node:fs/promises";
import * as path from "node:path";
import * as vscode from "vscode";
import { CliRunner } from "./cli";
import { completionEditPlan, completionReplacementEnd, prepareCommandLine } from "./completionUx";
import { collectLogicalCommands, findFunctionReference, LogicalCommand, resourceIdAt, tokenAt } from "./mcfunctionDocument";
import { inferFunctionContext } from "./functionContext";
import { ResourceEntry, ResourceReport } from "./model";
import { matchingResourceEntries, normalizeResourceId } from "./resourceNavigation";
import { SandboxClient, describeSandboxError } from "./sandboxClient";

interface CompletionSuggestion {
  value: string;
  description?: string;
  group?: string;
  start: number;
  end: number;
  appendSpace?: boolean;
  behavior?: string;
}

interface CompletionResponse {
  suggestions: CompletionSuggestion[];
  inlineHint?: string;
  multilineHints?: string[];
}

interface CommandCheck {
  valid: boolean;
  severity: string;
  code?: string;
  message: string;
}

interface VersionMetadata {
  default: string;
  versions: Array<{ id: string; packFormat: string }>;
}

interface LanguageSession {
  client: SandboxClient;
  ready: Promise<Record<string, unknown>>;
  resources?: Promise<ResourceEntry[]>;
}

const selectorDescriptions: Record<string, string> = {
  "@a": "All players in the current execution context.",
  "@e": "Entities matching the optional selector arguments.",
  "@n": "The nearest entity in the current execution context.",
  "@p": "The nearest player in the current execution context.",
  "@r": "A random player matching the optional selector arguments.",
  "@s": "The current command executor. A server-context function may not have one.",
};

const languageSelector: vscode.DocumentSelector = { language: "mcfunction", scheme: "file" };
const CHECK_BATCH_SIZE = 128;

/** Smart `.mcfunction` features backed by an isolated DSB Serve process per pack/profile. */
export class McfunctionLanguageService implements vscode.CompletionItemProvider, vscode.HoverProvider, vscode.DefinitionProvider, vscode.CodeActionProvider, vscode.Disposable {
  readonly diagnostics = vscode.languages.createDiagnosticCollection("datapack-sandbox-language");
  private readonly sessions = new Map<string, LanguageSession>();
  private readonly timers = new Map<string, NodeJS.Timeout>();
  private readonly revisions = new Map<string, number>();
  private reloadTimer?: NodeJS.Timeout;
  private warnedUnavailable = false;
  private disposed = false;

  constructor(private readonly cli: CliRunner, private readonly output: vscode.OutputChannel) {}

  register(): vscode.Disposable[] {
    // Direct providers follow VS Code's stable Language API model:
    // https://code.visualstudio.com/api/language-extensions/programmatic-language-features
    const disposables = [
      vscode.languages.registerCompletionItemProvider(languageSelector, this, " ", ":", "@", "#", "[", "{", "=", ",", "~", "^", "$", "\""),
      vscode.languages.registerHoverProvider(languageSelector, this),
      vscode.languages.registerDefinitionProvider(languageSelector, this),
      vscode.languages.registerCodeActionsProvider(languageSelector, this, { providedCodeActionKinds: [vscode.CodeActionKind.QuickFix] }),
      vscode.workspace.onDidOpenTextDocument((document) => this.scheduleDiagnostics(document)),
      vscode.workspace.onDidChangeTextDocument((event) => this.scheduleDiagnostics(event.document)),
      vscode.workspace.onDidCloseTextDocument((document) => this.closeDocument(document)),
      vscode.workspace.onDidSaveTextDocument((document) => this.datapackFilesChanged([document.uri])),
      vscode.workspace.onDidCreateFiles((event) => this.datapackFilesChanged(event.files)),
      vscode.workspace.onDidDeleteFiles((event) => this.datapackFilesChanged(event.files)),
      vscode.workspace.onDidRenameFiles((event) => this.datapackFilesChanged(event.files.flatMap((file) => [file.oldUri, file.newUri]))),
      vscode.workspace.onDidChangeConfiguration((event) => {
        if ([
          "datapackSandbox.language.enabled",
          "datapackSandbox.defaultVersion",
          "datapackSandbox.packPaths",
          "datapackSandbox.defaultPlayerName",
          "datapackSandbox.javaPath",
          "datapackSandbox.cliJarPath",
        ].some((section) => event.affectsConfiguration(section))) this.invalidateSessions();
        else if ([
          "datapackSandbox.language.diagnostics",
          "datapackSandbox.language.diagnosticDelay",
        ].some((section) => event.affectsConfiguration(section))) {
          for (const document of vscode.workspace.textDocuments) this.scheduleDiagnostics(document);
        }
      }),
    ];
    for (const document of vscode.workspace.textDocuments) this.scheduleDiagnostics(document);
    return disposables;
  }

  async provideCompletionItems(document: vscode.TextDocument, position: vscode.Position, token: vscode.CancellationToken): Promise<vscode.CompletionList | undefined> {
    if (!this.enabled(document) || token.isCancellationRequested) return undefined;
    const prepared = prepareCommandLine(document.lineAt(position.line).text, position.character);
    if (!prepared) return undefined;
    try {
      const session = this.sessionFor(document);
      const state = await session.ready;
      if (token.isCancellationRequested) return undefined;
      const result = await session.client.request<CompletionResponse>("completions", { buffer: prepared.buffer, cursor: prepared.cursor });
      if (token.isCancellationRequested) return undefined;
      const profile = String(state.version ?? "");
      const line = document.lineAt(position.line).text;
      const items = result.suggestions.map((suggestion, index) => completionItem(suggestion, result, profile, line, position.line, prepared.offset, index));
      return new vscode.CompletionList(items, true);
    } catch (error) {
      this.reportUnavailable(error);
      return undefined;
    }
  }

  async provideHover(document: vscode.TextDocument, position: vscode.Position, token: vscode.CancellationToken): Promise<vscode.Hover | undefined> {
    if (!this.enabled(document) || token.isCancellationRequested) return undefined;
    const line = document.lineAt(position.line).text;
    const current = tokenAt(line, position.character);
    if (!current) return undefined;
    const range = new vscode.Range(position.line, current.start, position.line, current.end);

    if (current.kind === "selector") {
      const selector = current.text.slice(0, 2).toLowerCase();
      const description = selectorDescriptions[selector];
      return description ? new vscode.Hover([new vscode.MarkdownString(`**${selector}** — ${description}`)], range) : undefined;
    }
    if (current.kind === "resource") {
      const functionReference = findFunctionReference(line, position.character);
      const reference = functionReference ?? resourceIdAt(line, position.character);
      if (!reference) return undefined;
      const locations = await this.definitionLocations(document, reference.id, reference.tag, Boolean(functionReference), token);
      const markdown = new vscode.MarkdownString();
      const kind = functionReference ? (reference.tag ? "Function tag" : "Function") : (reference.tag ? "Resource tag" : "Datapack resource");
      markdown.appendMarkdown(`**${kind}** \`${reference.tag ? "#" : ""}${reference.id}\``);
      markdown.appendMarkdown(locations.length ? `\n\nResolved to ${locations.length} loaded file${locations.length === 1 ? "" : "s"}.` : "\n\nNo matching file-backed resource was found in the loaded directory packs.");
      return new vscode.Hover(markdown, range);
    }

    try {
      const session = this.sessionFor(document);
      const state = await session.ready;
      const result = await session.client.request<CompletionResponse>("completions", { buffer: current.text, cursor: current.text.length });
      if (token.isCancellationRequested) return undefined;
      const markdown = new vscode.MarkdownString();
      markdown.appendMarkdown(`**${current.text}** — Minecraft command modeled by Datapack Sandbox.`);
      if (result.inlineHint) markdown.appendCodeblock(`${current.text} ${result.inlineHint}`.trim(), "mcfunction");
      markdown.appendMarkdown(`\nProfile: \`${String(state.version ?? "unknown")}\``);
      return new vscode.Hover(markdown, range);
    } catch (error) {
      this.reportUnavailable(error);
      return undefined;
    }
  }

  async provideDefinition(document: vscode.TextDocument, position: vscode.Position, token: vscode.CancellationToken): Promise<vscode.Location[] | undefined> {
    if (!this.enabled(document) || token.isCancellationRequested) return undefined;
    const line = document.lineAt(position.line).text;
    const functionReference = findFunctionReference(line, position.character);
    const reference = functionReference ?? resourceIdAt(line, position.character);
    if (!reference) return undefined;
    const locations = await this.definitionLocations(document, reference.id, reference.tag, Boolean(functionReference), token);
    return locations.length ? locations : undefined;
  }

  provideCodeActions(document: vscode.TextDocument, _range: vscode.Range, context: vscode.CodeActionContext): vscode.CodeAction[] {
    return context.diagnostics.filter((diagnostic) => diagnostic.code === "MCFUNCTION_LEADING_SLASH").map((diagnostic) => {
      const action = new vscode.CodeAction("Remove unsupported leading slash", vscode.CodeActionKind.QuickFix);
      action.diagnostics = [diagnostic];
      action.isPreferred = true;
      action.edit = new vscode.WorkspaceEdit();
      action.edit.delete(document.uri, diagnostic.range);
      return action;
    });
  }

  dispose(): void {
    this.disposed = true;
    for (const timer of this.timers.values()) clearTimeout(timer);
    if (this.reloadTimer) clearTimeout(this.reloadTimer);
    this.timers.clear();
    for (const session of this.sessions.values()) session.client.dispose();
    this.sessions.clear();
    this.diagnostics.dispose();
  }

  private scheduleDiagnostics(document: vscode.TextDocument): void {
    if (document.languageId !== "mcfunction" || document.uri.scheme !== "file" || this.disposed) return;
    const key = document.uri.toString();
    const revision = (this.revisions.get(key) ?? 0) + 1;
    this.revisions.set(key, revision);
    const current = this.timers.get(key);
    if (current) clearTimeout(current);
    const delay = vscode.workspace.getConfiguration("datapackSandbox", document.uri).get<number>("language.diagnosticDelay", 300);
    this.timers.set(key, setTimeout(() => {
      this.timers.delete(key);
      void this.refreshDiagnostics(document, revision);
    }, Math.max(100, Math.min(2000, delay))));
  }

  private async refreshDiagnostics(document: vscode.TextDocument, revision: number): Promise<void> {
    const key = document.uri.toString();
    if (!this.enabled(document) || !this.diagnosticsEnabled(document)) {
      this.diagnostics.delete(document.uri);
      return;
    }
    const commands = collectLogicalCommands(document.getText());
    const diagnostics = leadingSlashDiagnostics(document, commands);
    const checkable = commands.filter((command) => !command.macro || !command.command.includes("$("));
    if (!checkable.length) {
      if (this.isCurrent(document, key, revision)) this.diagnostics.set(document.uri, diagnostics);
      return;
    }

    try {
      const session = this.sessionFor(document);
      await session.ready;
      const checks = await this.checkCommands(session.client, checkable.map((entry) => entry.command));
      if (!this.isCurrent(document, key, revision)) return;
      checks.forEach((check, index) => {
        if (check.valid || check.code === "MISSING_CONTEXT") return;
        const command = checkable[index];
        if (!command) return;
        const diagnostic = new vscode.Diagnostic(commandRange(document, command), check.message || "Invalid command", vscode.DiagnosticSeverity.Error);
        diagnostic.code = check.code;
        diagnostic.source = `Datapack Sandbox ${String(session.client.activeState?.version ?? "")}`.trim();
        diagnostics.push(diagnostic);
      });
      this.diagnostics.set(document.uri, diagnostics);
    } catch (error) {
      if (!this.isCurrent(document, key, revision)) return;
      this.diagnostics.set(document.uri, diagnostics);
      this.reportUnavailable(error);
    }
  }

  private async checkCommands(client: SandboxClient, commands: string[]): Promise<CommandCheck[]> {
    const result: CommandCheck[] = [];
    if (!client.supports("commandDiagnostics")) {
      for (const command of commands) result.push(await client.request<CommandCheck>("checkCommand", { command }));
      return result;
    }
    for (let offset = 0; offset < commands.length; offset += CHECK_BATCH_SIZE) {
      const batch = commands.slice(offset, offset + CHECK_BATCH_SIZE);
      const response = await client.request<{ checks: CommandCheck[] }>("checkCommands", { commands: batch });
      result.push(...response.checks);
    }
    return result;
  }

  private sessionFor(document: vscode.TextDocument): LanguageSession {
    const config = vscode.workspace.getConfiguration("datapackSandbox", document.uri);
    const workspaceRoot = vscode.workspace.getWorkspaceFolder(document.uri)?.uri.fsPath ?? this.cli.workspaceRoot() ?? path.dirname(document.uri.fsPath);
    const inferredRoot = inferFunctionContext(document.uri.fsPath).packRoot;
    const packs = uniquePaths([
      ...(inferredRoot ? [inferredRoot] : []),
      ...config.get<string[]>("packPaths", []).map((entry) => path.resolve(workspaceRoot, entry)),
    ]);
    const configuredVersion = config.get<string>("defaultVersion", "").trim();
    const defaultPlayerName = config.get<string>("defaultPlayerName", "Steve").trim() || "Steve";
    const key = JSON.stringify({ configuredVersion, defaultPlayerName, packs: packs.map(pathKey) });
    const existing = this.sessions.get(key);
    if (existing) return existing;

    const client = new SandboxClient(this.cli, this.output);
    const session: LanguageSession = {
      client,
      ready: this.initializeSession(client, configuredVersion, inferredRoot, packs, defaultPlayerName),
    };
    this.sessions.set(key, session);
    void session.ready.catch(() => {
      if (this.sessions.get(key) === session) this.sessions.delete(key);
      client.dispose();
    });
    return session;
  }

  private async initializeSession(client: SandboxClient, configuredVersion: string, packRoot: string | undefined, packs: string[], defaultPlayerName: string): Promise<Record<string, unknown>> {
    const version = configuredVersion || await inferPackVersion(client, packRoot);
    return client.create(version || undefined, packs, [], defaultPlayerName, "error");
  }

  private async definitionLocations(
    document: vscode.TextDocument,
    id: string,
    tag: boolean,
    functionOnly: boolean,
    token: vscode.CancellationToken,
  ): Promise<vscode.Location[]> {
    const normalized = normalizeResourceId(id);
    if (!normalized) return [];
    const locations: vscode.Location[] = [];
    try {
      const session = this.sessionFor(document);
      const resources = await this.resourcesFor(session);
      if (token.isCancellationRequested) return [];
      for (const resource of matchingResourceEntries(resources, id, tag, functionOnly)) {
        try {
          if ((await fs.stat(resource.file)).isFile()) locations.push(new vscode.Location(vscode.Uri.file(resource.file), new vscode.Position(0, 0)));
        } catch {}
      }
    } catch (error) { this.reportUnavailable(error); }
    return uniqueLocations(locations);
  }

  private resourcesFor(session: LanguageSession): Promise<ResourceEntry[]> {
    session.resources ??= session.ready
      .then(() => session.client.request<ResourceReport>("resources"))
      .then((report) => report.resources ?? []);
    return session.resources;
  }

  private invalidateSessions(): void {
    if (this.reloadTimer) clearTimeout(this.reloadTimer);
    this.reloadTimer = undefined;
    for (const session of this.sessions.values()) session.client.dispose();
    this.sessions.clear();
    for (const document of vscode.workspace.textDocuments) this.scheduleDiagnostics(document);
  }

  private datapackFilesChanged(files: readonly vscode.Uri[]): void {
    const sources = files.filter((uri) => isDatapackSource(uri.fsPath));
    if (!sources.length) return;
    if (sources.some((uri) => isPackMetadata(uri.fsPath))) {
      this.invalidateSessions();
      return;
    }
    if (this.reloadTimer) clearTimeout(this.reloadTimer);
    this.reloadTimer = setTimeout(() => {
      this.reloadTimer = undefined;
      void this.reloadSessions();
    }, 150);
  }

  private async reloadSessions(): Promise<void> {
    const sessions = [...this.sessions.entries()];
    await Promise.all(sessions.map(async ([key, session]) => {
      try {
        await session.ready;
        await session.client.request("reload", { keepWorld: false });
        session.resources = undefined;
      } catch (error) {
        if (this.sessions.get(key) === session) this.sessions.delete(key);
        session.client.dispose();
        const details = describeSandboxError(error);
        this.output.appendLine(`[mcfunction] Datapack resource refresh failed: ${details.message}`);
      }
    }));
    for (const document of vscode.workspace.textDocuments) this.scheduleDiagnostics(document);
  }

  private closeDocument(document: vscode.TextDocument): void {
    const key = document.uri.toString();
    const timer = this.timers.get(key);
    if (timer) clearTimeout(timer);
    this.timers.delete(key);
    this.revisions.delete(key);
    this.diagnostics.delete(document.uri);
  }

  private enabled(document: vscode.TextDocument): boolean {
    return vscode.workspace.getConfiguration("datapackSandbox", document.uri).get<boolean>("language.enabled", true);
  }

  private diagnosticsEnabled(document: vscode.TextDocument): boolean {
    return vscode.workspace.getConfiguration("datapackSandbox", document.uri).get<boolean>("language.diagnostics", true);
  }

  private isCurrent(document: vscode.TextDocument, key: string, revision: number): boolean {
    return !this.disposed && !document.isClosed && this.revisions.get(key) === revision;
  }

  private reportUnavailable(error: unknown): void {
    const details = describeSandboxError(error);
    this.output.appendLine(`[mcfunction] ${details.title}: ${details.message}`);
    if (this.warnedUnavailable || this.disposed) return;
    this.warnedUnavailable = true;
    void vscode.window.showWarningMessage(
      `Datapack Sandbox smart mcfunction support is unavailable: ${details.message} Syntax highlighting remains active.`,
      "Show Output",
      "Open Settings",
    ).then((choice) => {
      if (choice === "Show Output") this.output.show(true);
      else if (choice === "Open Settings") void vscode.commands.executeCommand("workbench.action.openSettings", "datapackSandbox.language");
    });
  }
}

function completionItem(
  suggestion: CompletionSuggestion,
  response: CompletionResponse,
  profile: string,
  lineText: string,
  line: number,
  offset: number,
  index: number,
): vscode.CompletionItem {
  const item = new vscode.CompletionItem(suggestion.value, completionKind(suggestion.group));
  const edit = completionEditPlan(suggestion.value, Boolean(suggestion.appendSpace));
  if (edit.cursorOffset === undefined) {
    item.insertText = edit.text;
  } else {
    const snippet = new vscode.SnippetString();
    snippet.appendText(edit.text.slice(0, edit.cursorOffset));
    snippet.appendTabstop(0);
    snippet.appendText(edit.text.slice(edit.cursorOffset));
    item.insertText = snippet;
  }
  const start = offset + suggestion.start;
  const insertEnd = offset + suggestion.end;
  const replaceEnd = completionReplacementEnd(lineText, insertEnd, edit.text);
  item.range = {
    inserting: new vscode.Range(line, start, line, insertEnd),
    replacing: new vscode.Range(line, start, line, replaceEnd),
  };
  if (edit.retrigger) {
    item.command = { command: "editor.action.triggerSuggest", title: "Continue mcfunction completion" };
  }
  item.detail = suggestion.description || response.inlineHint || undefined;
  item.sortText = String(index).padStart(5, "0");
  const documentation = new vscode.MarkdownString();
  if (suggestion.behavior) documentation.appendMarkdown(`DSB behavior: \`${suggestion.behavior}\``);
  if (profile) documentation.appendMarkdown(`${suggestion.behavior ? "  \n" : ""}Minecraft profile: \`${profile}\``);
  if (response.multilineHints?.length) documentation.appendMarkdown(`\n\n${response.multilineHints.map((hint) => `- ${hint}`).join("\n")}`);
  item.documentation = documentation.value ? documentation : undefined;
  return item;
}

function completionKind(group?: string): vscode.CompletionItemKind {
  const normalized = group?.toLowerCase() ?? "";
  if (normalized.includes("function") || normalized.includes("resource")) return vscode.CompletionItemKind.Reference;
  if (normalized.includes("player") || normalized.includes("selector") || normalized.includes("entity")) return vscode.CompletionItemKind.Variable;
  if (normalized.includes("block") || normalized.includes("item")) return vscode.CompletionItemKind.Value;
  return vscode.CompletionItemKind.Keyword;
}

function leadingSlashDiagnostics(document: vscode.TextDocument, commands: LogicalCommand[]): vscode.Diagnostic[] {
  return commands.filter((entry) => entry.leadingSlash).map((entry) => {
    const range = new vscode.Range(entry.line, entry.startCharacter, entry.line, entry.startCharacter + 1);
    const diagnostic = new vscode.Diagnostic(range, "Commands in .mcfunction files cannot start with '/'. Remove the slash.", vscode.DiagnosticSeverity.Error);
    diagnostic.code = "MCFUNCTION_LEADING_SLASH";
    diagnostic.source = "Datapack Sandbox";
    return diagnostic;
  });
}

function commandRange(document: vscode.TextDocument, command: LogicalCommand): vscode.Range {
  return new vscode.Range(command.line, command.startCharacter, command.endLine, document.lineAt(command.endLine).range.end.character);
}

async function inferPackVersion(client: SandboxClient, packRoot: string | undefined): Promise<string | undefined> {
  if (!packRoot) return undefined;
  try {
    const raw = await fs.readFile(path.join(packRoot, "pack.mcmeta"), "utf8");
    const root = JSON.parse(raw) as { pack?: { pack_format?: unknown } };
    const format = dataPackFormat(root.pack?.pack_format);
    if (!format) return undefined;
    const metadata = await client.request<VersionMetadata>("versions");
    return metadata.versions.filter((entry) => normalizePackFormat(entry.packFormat) === format).at(-1)?.id;
  } catch {
    return undefined;
  }
}

function dataPackFormat(value: unknown): string | undefined {
  if (typeof value === "number" && Number.isFinite(value)) return normalizePackFormat(String(value));
  if (Array.isArray(value) && value.length >= 1 && value.length <= 2 && value.every((part) => Number.isInteger(part))) {
    return normalizePackFormat(value.join("."));
  }
  return undefined;
}

function normalizePackFormat(value: string): string {
  return value.split(".").map((part) => String(Number(part))).join(".").replace(/\.0$/, "");
}

function uniqueLocations(values: vscode.Location[]): vscode.Location[] {
  const seen = new Set<string>();
  return values.filter((location) => {
    const key = location.uri.toString();
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function uniquePaths(values: string[]): string[] {
  const seen = new Set<string>();
  return values.map((entry) => path.resolve(entry)).filter((entry) => {
    const key = pathKey(entry);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function pathKey(value: string): string {
  const normalized = path.normalize(value);
  return process.platform === "win32" ? normalized.toLowerCase() : normalized;
}

function isDatapackSource(file: string): boolean {
  const normalized = file.replace(/\\/g, "/").toLowerCase();
  return normalized.endsWith("/pack.mcmeta") || normalized.endsWith(".mcfunction") || (/\/data\/[^/]+\//.test(normalized) && normalized.endsWith(".json"));
}

function isPackMetadata(file: string): boolean {
  return file.replace(/\\/g, "/").toLowerCase().endsWith("/pack.mcmeta");
}
