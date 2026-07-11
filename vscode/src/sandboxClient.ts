import { ChildProcessWithoutNullStreams, spawn } from "node:child_process";
import * as readline from "node:readline";
import * as vscode from "vscode";
import { CliRunner } from "./cli";

interface Pending { resolve(value: unknown): void; reject(error: Error): void; }
interface Response { id: string | null; ok: boolean; result?: unknown; error?: { code?: string; message?: string }; }

export class SandboxClient implements vscode.Disposable {
  private child?: ChildProcessWithoutNullStreams;
  private sequence = 0;
  private readonly pending = new Map<string, Pending>();
  private hello?: unknown;
  private state?: Record<string, unknown>;
  private readonly stateEmitter = new vscode.EventEmitter<Record<string, unknown> | undefined>();
  readonly onDidChangeState = this.stateEmitter.event;

  constructor(private readonly cli: CliRunner, private readonly output: vscode.OutputChannel) {}
  get activeState(): Record<string, unknown> | undefined { return this.state; }
  get hasActiveSandbox(): boolean { return Boolean(this.state); }

  async start(): Promise<unknown> {
    if (this.child) return this.hello;
    const jar = await this.cli.ensureJar();
    const config = vscode.workspace.getConfiguration("datapackSandbox");
    const child = spawn(config.get<string>("javaPath", "java"), ["-jar", jar, "serve"], { cwd: this.cli.workspaceRoot(), windowsHide: true });
    this.child = child;
    child.stderr.on("data", (chunk) => this.output.append(chunk.toString()));
    child.on("exit", () => this.stop(new Error("Datapack Sandbox server exited")));
    readline.createInterface({ input: child.stdout }).on("line", (line) => this.receive(line));
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error("Timed out waiting for Datapack Sandbox server")), 15000);
      const listener = this.onDidChangeState(() => undefined);
      const poll = () => {
        if (this.hello) { clearTimeout(timeout); listener.dispose(); resolve(this.hello); }
        else if (this.child) setTimeout(poll, 10);
      };
      poll();
    });
  }

  async request<T>(method: string, params: Record<string, unknown> = {}): Promise<T> {
    await this.start();
    const id = String(++this.sequence);
    return new Promise<T>((resolve, reject) => {
      this.pending.set(id, { resolve: (value) => resolve(value as T), reject });
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
    if (response.id === null) { this.hello = response.result; return; }
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
    } else pending.reject(new Error(`${response.error?.code ?? "SERVE_ERROR"}: ${response.error?.message ?? "Unknown server error"}`));
  }

  private setState(state: Record<string, unknown>): void { this.state = state; this.stateEmitter.fire(state); }
  private stop(error: Error): void {
    const child = this.child; this.child = undefined; child?.kill();
    for (const pending of this.pending.values()) pending.reject(error);
    this.pending.clear(); this.state = undefined; this.stateEmitter.fire(undefined);
  }
}
