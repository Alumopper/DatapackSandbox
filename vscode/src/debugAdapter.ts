import * as path from "node:path";
import * as vscode from "vscode";
import { ActiveSandboxService } from "./activeSandbox";
import { CliRunner } from "./cli";
import { DebugValueStore, debugValueSummary, findInitialTraceIndex, isExpandable } from "./debugValues";
import { isManifest } from "./functionContext";
import { DiagnosticReport, OutputEvent, TraceEvent } from "./model";

interface DebugMessage { seq: number; type: "request"; command: string; arguments?: Record<string, unknown>; }

export class TraceDebugAdapterFactory implements vscode.DebugAdapterDescriptorFactory {
  constructor(private readonly cli: CliRunner, private readonly activeSandbox: ActiveSandboxService) {}
  createDebugAdapterDescriptor(): vscode.ProviderResult<vscode.DebugAdapterDescriptor> {
    return new vscode.DebugAdapterInlineImplementation(new TraceDebugAdapter(this.cli, this.activeSandbox));
  }
}

export class TraceDebugConfigurationProvider implements vscode.DebugConfigurationProvider {
  resolveDebugConfiguration(_folder: vscode.WorkspaceFolder | undefined, config: vscode.DebugConfiguration): vscode.ProviderResult<vscode.DebugConfiguration> {
    if (!config.program) config.program = "${file}";
    config.type = "datapack-sandbox";
    config.request = "launch";
    config.name ||= "Datapack Sandbox Trace";
    config.sandbox ||= "temporary";
    config.stopOnEntry ??= false;
    return config;
  }
}

class TraceDebugAdapter implements vscode.DebugAdapter {
  private readonly emitter = new vscode.EventEmitter<vscode.DebugProtocolMessage>();
  readonly onDidSendMessage = this.emitter.event;
  private traces: TraceEvent[] = [];
  private outputs: OutputEvent[] = [];
  private diagnostics: DiagnosticReport[] = [];
  private snapshot: unknown;
  private index = 0;
  private launchArguments?: Record<string, unknown>;
  private readonly breakpoints = new Map<string, Set<number>>();
  private readonly values = new DebugValueStore();

  constructor(private readonly cli: CliRunner, private readonly activeSandbox: ActiveSandboxService) {}
  dispose(): void { this.emitter.dispose(); }

  handleMessage(message: DebugMessage): void {
    if (message.type !== "request") return;
    switch (message.command) {
      case "initialize": this.respond(message, { supportsConfigurationDoneRequest: true, supportsEvaluateForHovers: true }); this.event("initialized"); break;
      case "setBreakpoints": this.setBreakpoints(message); break;
      case "launch": this.launchArguments = message.arguments ?? {}; this.respond(message); break;
      case "configurationDone": this.respond(message); void this.executeLaunch(); break;
      case "threads": this.respond(message, { threads: [{ id: 1, name: "Datapack Sandbox Trace" }] }); break;
      case "stackTrace": this.stackTrace(message); break;
      case "scopes": this.scopes(message); break;
      case "variables": this.variables(message); break;
      case "next": case "stepIn": case "stepOut": this.respond(message); this.move(1, false); break;
      case "continue": this.respond(message, { allThreadsContinued: true }); this.move(1, true); break;
      case "pause": this.respond(message); this.event("stopped", { reason: "pause", threadId: 1 }); break;
      case "evaluate": this.evaluate(message); break;
      case "disconnect": case "terminate": this.respond(message); this.event("terminated"); break;
      default: this.respond(message);
    }
  }

  private async executeLaunch(): Promise<void> {
    try {
      const args = this.launchArguments ?? {};
      const program = String(args.program ?? "");
      if (!program) throw new Error("Debug configuration requires program");
      const useActive = args.sandbox === "active";
      if (isManifest(program)) {
        const reports = useActive ? await this.activeSandbox.runManifest(program, Boolean(args.strict)) : (await this.cli.checkManifest(program, Boolean(args.strict))).report;
        this.traces = reports.flatMap((report) => report.traces ?? report.attempts.flatMap((attempt) => attempt.traces ?? []));
        this.outputs = reports.flatMap((report) => report.outputs ?? []);
        this.diagnostics = reports.flatMap((report) => report.diagnostics ?? []);
        this.snapshot = reports.flatMap((report) => report.attempts).find((attempt) => attempt.snapshot)?.snapshot;
      } else {
        const report = useActive
          ? await this.activeSandbox.runFunction(program)
          : (await this.cli.runCurrent(program, true, {
              version: typeof args.version === "string" ? args.version : undefined,
              packs: Array.isArray(args.packs) ? args.packs.map(String) : undefined,
              strict: Boolean(args.strict),
            })).report;
        this.traces = report.traces ?? [];
        this.outputs = report.outputs ?? [];
        this.diagnostics = report.diagnostics ?? [];
        this.snapshot = report.snapshot;
      }

      if (!this.traces.length) { this.event("terminated"); return; }
      const initial = findInitialTraceIndex(this.traces, this.breakpoints, Boolean(args.stopOnEntry));
      if (initial < 0) { this.index = this.traces.length; this.event("terminated"); return; }
      this.index = initial;
      this.values.clear();
      this.event("stopped", { reason: Boolean(args.stopOnEntry) ? "entry" : this.isBreakpoint(this.traces[this.index]) ? "breakpoint" : "exception", threadId: 1, allThreadsStopped: true });
    } catch (error) {
      this.event("output", { category: "stderr", output: `${error instanceof Error ? error.message : String(error)}\n` });
      this.event("terminated");
    }
  }

  private setBreakpoints(request: DebugMessage): void {
    const source = request.arguments?.source as { path?: string } | undefined;
    const requested = (request.arguments?.breakpoints as Array<{ line: number }> | undefined) ?? [];
    if (source?.path) this.breakpoints.set(path.resolve(source.path), new Set(requested.map((item) => item.line)));
    this.respond(request, { breakpoints: requested.map((item, index) => ({ id: index + 1, verified: true, line: item.line })) });
  }

  private stackTrace(request: DebugMessage): void {
    const trace = this.traces[this.index];
    const frames = trace ? [{ id: 1, name: trace.command, line: trace.source?.line ?? 1, column: 1, source: trace.source?.file ? { name: path.basename(trace.source.file), path: trace.source.file } : undefined }, ...(trace.source?.functionStack ?? []).map((frame, index) => ({ id: index + 2, name: frame.id, line: 1, column: 1, source: frame.file ? { name: path.basename(frame.file), path: frame.file } : undefined }))] : [];
    this.respond(request, { stackFrames: frames, totalFrames: frames.length });
  }

  private scopes(request: DebugMessage): void {
    this.values.clear();
    const trace = this.traces[this.index];
    const traceReference = this.values.add({ trace, outputs: this.outputs, snapshotDiffs: trace?.snapshotDiffs ?? [], diagnostics: this.diagnostics });
    const stateReference = this.values.add(this.snapshot ?? {});
    this.respond(request, { scopes: [{ name: "Trace", variablesReference: traceReference, expensive: false }, { name: "Final State", variablesReference: stateReference, expensive: true }] });
  }

  private variables(request: DebugMessage): void {
    this.respond(request, { variables: this.values.variables(Number(request.arguments?.variablesReference ?? 0)) });
  }

  private evaluate(request: DebugMessage): void {
    const expression = String(request.arguments?.expression ?? "");
    const values: Record<string, unknown> = { trace: this.traces[this.index], outputs: this.outputs, diagnostics: this.diagnostics, snapshot: this.snapshot };
    const value = values[expression] ?? values;
    this.respond(request, { result: debugValueSummary(value), variablesReference: isExpandable(value) ? this.values.add(value) : 0 });
  }

  private move(offset: number, seekBreakpoint: boolean): void {
    let next = this.index + offset;
    if (seekBreakpoint) while (next < this.traces.length && !this.isBreakpoint(this.traces[next]) && this.traces[next].success) next += 1;
    if (next >= this.traces.length) { this.index = this.traces.length; this.event("terminated"); return; }
    this.index = next;
    this.values.clear();
    this.event("stopped", { reason: this.isBreakpoint(this.traces[next]) ? "breakpoint" : this.traces[next].success ? "step" : "exception", threadId: 1, allThreadsStopped: true });
  }

  private isBreakpoint(trace: TraceEvent): boolean {
    if (!trace.source?.file || !trace.source.line) return false;
    return this.breakpoints.get(path.resolve(trace.source.file))?.has(trace.source.line) ?? false;
  }

  private respond(request: DebugMessage, body?: unknown, message?: string): void { this.emitter.fire({ seq: 0, type: "response", request_seq: request.seq, success: !message, command: request.command, body, message }); }
  private event(event: string, body?: unknown): void { this.emitter.fire({ seq: 0, type: "event", event, body }); }
}
