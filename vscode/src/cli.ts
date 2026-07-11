import { spawn } from "node:child_process";
import * as fs from "node:fs/promises";
import * as os from "node:os";
import * as path from "node:path";
import * as vscode from "vscode";
import { buildCheckArgs, buildRunArgs } from "./commands";
import { inferFunctionContext } from "./functionContext";
import { ManifestReport, RunReport } from "./model";
import { readManifestReport, readRunReport } from "./reports";

export interface CliResult<T> { exitCode: number; stdout: string; stderr: string; report: T; }
export interface RunOverrides { version?: string; packs?: string[]; strict?: boolean; }

export class CliRunner {
  private readonly extensionRoot = path.resolve(__dirname, "..");

  constructor(private readonly output: vscode.OutputChannel) {}
  private config() { return vscode.workspace.getConfiguration("datapackSandbox"); }
  workspaceRoot(): string | undefined { return vscode.workspace.workspaceFolders?.[0]?.uri.fsPath; }

  async ensureJar(): Promise<string> {
    const configured = this.config().get<string>("cliJarPath", "").trim();
    const root = this.workspaceRoot();
    const bundledJar = path.join(this.extensionRoot, "bin", "datapack-sandbox-cli.jar");
    const workspaceJar = root ? path.join(root, "cli", "build", "libs", "datapack-sandbox-cli.jar") : undefined;
    const candidates = configured
      ? [path.resolve(root ?? process.cwd(), configured)]
      : [bundledJar, workspaceJar].filter((candidate): candidate is string => Boolean(candidate));

    for (const candidate of candidates) {
      try { await fs.access(candidate); return candidate; } catch {}
    }

    if (!configured && this.config().get<boolean>("autoBuildCliJar", true) && root && workspaceJar) {
      const wrapper = process.platform === "win32" ? "gradlew.bat" : "./gradlew";
      const build = await this.execute(wrapper, [":cli:fatJar"], root, false);
      if (build.exitCode !== 0) throw new Error(`Failed to build CLI jar (exit ${build.exitCode})`);
      await fs.access(workspaceJar);
      return workspaceJar;
    }

    throw new Error(`Datapack Sandbox CLI jar not found. Checked: ${candidates.join(", ")}`);
  }

  async runCurrent(file: string, forcePack = false, overrides: RunOverrides = {}): Promise<CliResult<RunReport>> {
    const context = inferFunctionContext(file);
    const reportFile = await this.tempFile("run-report.json");
    const packs = [...(overrides.packs ?? this.config().get<string[]>("packPaths", []))];
    if ((forcePack || context.packRoot) && context.packRoot) packs.unshift(context.packRoot);
    const args = buildRunArgs(context.file, context.id, reportFile, overrides.version ?? this.config().get("defaultVersion", "26.2"), [...new Set(packs)], this.config().get<string[]>("traceFilter", []), overrides.strict ?? this.config().get<boolean>("strict", false));
    const processResult = await this.runJar(args);
    return { ...processResult, report: await readRunReport(reportFile) };
  }

  async checkManifest(file: string, strict: boolean): Promise<CliResult<ManifestReport[]>> {
    const reportFile = await this.tempFile("manifest-report.json");
    const args = buildCheckArgs(file, reportFile, strict, this.config().get<string[]>("traceFilter", []));
    const processResult = await this.runJar(args);
    return { ...processResult, report: await readManifestReport(reportFile) };
  }

  async runJar(args: string[]): Promise<{ exitCode: number; stdout: string; stderr: string }> {
    const jar = await this.ensureJar();
    return this.execute(this.config().get<string>("javaPath", "java"), ["-jar", jar, ...args], this.workspaceRoot());
  }

  private async tempFile(name: string): Promise<string> {
    const configured = this.config().get<string>("reportDirectory", "").trim();
    const directory = configured ? path.resolve(this.workspaceRoot() ?? process.cwd(), configured) : os.tmpdir();
    await fs.mkdir(directory, { recursive: true });
    return path.join(directory, `dps-${process.pid}-${Date.now()}-${name}`);
  }

  private execute(command: string, args: string[], cwd?: string, log = true): Promise<{ exitCode: number; stdout: string; stderr: string }> {
    if (log) this.output.appendLine(`> ${command} ${args.map(quote).join(" ")}`);
    return new Promise((resolve, reject) => {
      const child = spawn(command, args, { cwd, windowsHide: true });
      let stdout = ""; let stderr = "";
      child.stdout.on("data", (chunk) => { const text = chunk.toString(); stdout += text; if (log) this.output.append(text); });
      child.stderr.on("data", (chunk) => { const text = chunk.toString(); stderr += text; if (log) this.output.append(text); });
      child.on("error", reject);
      child.on("close", (code) => resolve({ exitCode: code ?? -1, stdout, stderr }));
    });
  }
}

function quote(value: string): string { return /\s/.test(value) ? JSON.stringify(value) : value; }
