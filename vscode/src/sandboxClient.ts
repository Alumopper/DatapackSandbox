import { ChildProcessWithoutNullStreams, spawn } from "node:child_process";
import * as readline from "node:readline";
import * as vscode from "vscode";
import { CliRunner } from "./cli";

interface Pending { method: string; resolve(value: unknown): void; reject(error: Error): void; }
interface ServeError { code?: string; message?: string; version?: string; command?: string; location?: { file?: string; line?: number; command?: string }; }
interface Response { id: string | null; ok: boolean; result?: unknown; error?: ServeError; }

export interface SandboxErrorDetails {
  title: string;
  message: string;
  code?: string;
  hint?: string;
  detail?: string;
}

export class SandboxClientError extends Error {
  constructor(readonly details: SandboxErrorDetails) {
    super(details.message);
    this.name = "SandboxClientError";
  }
}

export function describeSandboxError(error: unknown): SandboxErrorDetails {
  if (error instanceof SandboxClientError) return error.details;
  return { title: "Datapack Sandbox operation failed", message: error instanceof Error ? error.message : String(error) };
}

export class SandboxClient implements vscode.Disposable {
  private child?: ChildProcessWithoutNullStreams;
  private sequence = 0;
  private readonly pending = new Map<string, Pending>();
  private hello?: unknown;
  private state?: Record<string, unknown>;
  private startup?: { promise: Promise<unknown>; resolve(value: unknown): void; reject(error: Error): void; timer: NodeJS.Timeout };
  private startupStderr = "";
  private readonly stateEmitter = new vscode.EventEmitter<Record<string, unknown> | undefined>();
  readonly onDidChangeState = this.stateEmitter.event;

  constructor(private readonly cli: CliRunner, private readonly output: vscode.OutputChannel) {}
  get activeState(): Record<string, unknown> | undefined { return this.state; }
  get hasActiveSandbox(): boolean { return Boolean(this.state); }

  async start(): Promise<unknown> {
    if (this.child && this.hello) return this.hello;
    if (this.startup) return this.startup.promise;
    let jar: string;
    try {
      jar = await this.cli.ensureJar();
    } catch (error) {
      throw new SandboxClientError({
        title: "Datapack Sandbox CLI was not found",
        message: error instanceof Error ? error.message : String(error),
        hint: "Reinstall the extension or configure datapackSandbox.cliJarPath to a valid CLI JAR.",
      });
    }
    const config = vscode.workspace.getConfiguration("datapackSandbox");
    const javaPath = config.get<string>("javaPath", "java");
    const child = spawn(javaPath, ["-jar", jar, "serve"], { cwd: this.cli.workspaceRoot(), windowsHide: true });
    this.child = child;
    this.hello = undefined;
    this.startupStderr = "";
    let resolveStartup!: (value: unknown) => void;
    let rejectStartup!: (error: Error) => void;
    const promise = new Promise<unknown>((resolve, reject) => { resolveStartup = resolve; rejectStartup = reject; });
    const timer = setTimeout(() => this.stop(new SandboxClientError({
      title: "Datapack Sandbox took too long to start",
      message: "The CLI did not become ready within 15 seconds.",
      detail: this.startupStderr.trim() || `Java executable: ${javaPath}\nCLI JAR: ${jar}`,
      hint: "Confirm that Java 25 is installed, then check the Datapack Sandbox output channel.",
    })), 15000);
    this.startup = { promise, resolve: resolveStartup, reject: rejectStartup, timer };
    child.stderr.on("data", (chunk) => { const text = chunk.toString(); this.startupStderr += text; this.output.append(text); });
    child.once("error", (error: NodeJS.ErrnoException) => {
      if (this.child !== child) return;
      this.stop(new SandboxClientError({
        title: "Java could not be started",
        message: error.code === "ENOENT" ? `Java executable '${javaPath}' was not found.` : error.message,
        detail: `Java executable: ${javaPath}\nCLI JAR: ${jar}`,
        hint: "Install Java 25 or set datapackSandbox.javaPath to the full Java executable path.",
      }));
    });
    child.once("exit", (code, signal) => {
      if (this.child !== child) return;
      this.stop(new SandboxClientError({
        title: "Datapack Sandbox CLI stopped unexpectedly",
        message: `The CLI exited before the operation completed${code === null ? "" : ` (exit code ${code})`}.`,
        detail: this.startupStderr.trim() || `Signal: ${signal ?? "none"}\nJava executable: ${javaPath}`,
        hint: "Confirm that the configured executable is Java 25 and open the Datapack Sandbox output channel for details.",
      }));
    });
    readline.createInterface({ input: child.stdout }).on("line", (line) => this.receive(line));
    return promise;
  }

  async request<T>(method: string, params: Record<string, unknown> = {}): Promise<T> {
    await this.start();
    const id = String(++this.sequence);
    return new Promise<T>((resolve, reject) => {
      this.pending.set(id, { method, resolve: (value) => resolve(value as T), reject });
      this.child?.stdin.write(`${JSON.stringify({ id, method, params })}\n`);
    });
  }

  async create(version: string, packs: string[], functionSources: unknown[] = []): Promise<Record<string, unknown>> {
    const state = await this.request<Record<string, unknown>>("createSandbox", { version, packs, functionSources });
    this.setState(state); return state;
  }

  close(): void { this.stop(new Error("Datapack Sandbox was stopped")); }

  dispose(): void { this.stop(new Error("Datapack Sandbox client disposed")); this.stateEmitter.dispose(); }

  private receive(line: string): void {
    let response: Response;
    try { response = JSON.parse(line) as Response; } catch { this.output.appendLine(`[serve] invalid JSON: ${line}`); return; }
    if (response.id === null) {
      this.hello = response.result;
      const startup = this.startup;
      if (startup) {
        clearTimeout(startup.timer);
        this.startup = undefined;
        startup.resolve(response.result);
      }
      return;
    }
    const pending = this.pending.get(String(response.id));
    if (!pending) return;
    this.pending.delete(String(response.id));
    if (response.ok) {
      if (response.result && typeof response.result === "object") {
        const result = response.result as Record<string, unknown>;
        if ("version" in result) this.setState(result);
        else if (result.state && typeof result.state === "object" && "version" in result.state) this.setState(result.state as Record<string, unknown>);
      }
      pending.resolve(response.result);
    } else pending.reject(requestError(pending.method, response.error));
  }

  private setState(state: Record<string, unknown>): void { this.state = state; this.stateEmitter.fire(state); }
  private stop(error: Error): void {
    const child = this.child; this.child = undefined; child?.kill();
    this.hello = undefined;
    const startup = this.startup;
    if (startup) { clearTimeout(startup.timer); this.startup = undefined; startup.reject(error); }
    for (const pending of this.pending.values()) pending.reject(error);
    this.pending.clear(); this.state = undefined; this.stateEmitter.fire(undefined);
  }
}

function requestError(method: string, error?: ServeError): SandboxClientError {
  const code = error?.code ?? "SERVE_ERROR";
  const titles: Record<string, string> = {
    createSandbox: "Sandbox could not be started",
    reload: "Datapacks could not be reloaded",
    runCommand: "Command could not be executed",
    runFunction: "Function could not be executed",
    runManifest: "Manifest test could not be executed",
    applyWorldFixture: "World fixture could not be applied",
    injectPlayerEvent: "Player event could not be injected",
    tick: "Sandbox ticks could not be advanced",
  };
  const hints: Record<string, string> = {
    INPUT_FORMAT: "Check the selected Minecraft profile, command syntax, and input paths.",
    VERSION_MISMATCH: "Select a profile compatible with the datapack metadata, or update pack.mcmeta.",
    RESOURCE_NOT_FOUND: "Check that the datapack path is loaded and that the referenced resource ID exists.",
    UNSUPPORTED_FEATURE: "This behavior is not modeled by the selected Datapack Sandbox profile.",
    COMMAND_ERROR: "Review the command arguments and the current sandbox world state.",
    MISSING_CONTEXT: "The command needs a player, entity, block, or execution context that is not present.",
  };
  return new SandboxClientError({
    title: titles[method] ?? "Datapack Sandbox request failed",
    message: error?.message ?? "The sandbox returned an unknown error.",
    code,
    detail: [
      error?.version ? `Minecraft profile: ${error.version}` : "",
      error?.location?.file ? `Location: ${error.location.file}${error.location.line ? `:${error.location.line}` : ""}` : "",
      error?.command ?? error?.location?.command ? `Command: ${error.command ?? error.location?.command}` : "",
    ].filter(Boolean).join("\n") || undefined,
    hint: hints[code] ?? "Open the Datapack Sandbox output channel for additional details.",
  });
}
