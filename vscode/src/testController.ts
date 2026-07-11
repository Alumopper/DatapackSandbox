import * as vscode from "vscode";
import { CliRunner } from "./cli";
import { DiagnosticPublisher } from "./diagnostics";
import { ActiveSandboxService } from "./activeSandbox";

export class ManifestTestController implements vscode.Disposable {
  readonly controller = vscode.tests.createTestController("datapackSandbox.manifests", "Datapack Sandbox Manifests");
  constructor(private readonly cli: CliRunner, private readonly diagnostics: DiagnosticPublisher, private readonly activeSandbox: ActiveSandboxService) {
    this.controller.createRunProfile("Run in Temporary Sandbox", vscode.TestRunProfileKind.Run, (request, token) => this.run(request, token, false, false), true);
    this.controller.createRunProfile("Run Strict in Temporary Sandbox", vscode.TestRunProfileKind.Run, (request, token) => this.run(request, token, true, false));
    this.controller.createRunProfile("Run in Active Sandbox", vscode.TestRunProfileKind.Run, (request, token) => this.run(request, token, false, true));
    this.controller.createRunProfile("Run Strict in Active Sandbox", vscode.TestRunProfileKind.Run, (request, token) => this.run(request, token, true, true));
  }

  async discover(): Promise<void> {
    const files = await vscode.workspace.findFiles("**/*.dps.json", "**/{node_modules,build,out}/**");
    const found = new Set(files.map((uri) => uri.toString()));
    for (const uri of files) if (!this.controller.items.get(uri.toString())) this.controller.items.add(this.controller.createTestItem(uri.toString(), vscode.workspace.asRelativePath(uri), uri));
    for (const [id] of this.controller.items) if (!found.has(id)) this.controller.items.delete(id);
  }

  dispose(): void { this.controller.dispose(); }

  private async run(request: vscode.TestRunRequest, token: vscode.CancellationToken, strict: boolean, useActiveSandbox: boolean): Promise<void> {
    const run = this.controller.createTestRun(request);
    const included = request.include ? [...request.include] : [...this.controller.items].map(([, item]) => item);
    const excluded = new Set(request.exclude?.map((item) => item.id));
    for (const item of included) {
      if (token.isCancellationRequested || excluded.has(item.id) || !item.uri) continue;
      run.started(item);
      const started = Date.now();
      try {
        const reports = useActiveSandbox ? await this.activeSandbox.runManifest(item.uri.fsPath, strict) : (await this.cli.checkManifest(item.uri.fsPath, strict)).report;
        const report = reports.find((entry) => entry.path === item.uri?.fsPath) ?? reports[0];
        if (report?.passed) run.passed(item, Date.now() - started);
        else run.failed(item, new vscode.TestMessage((report?.messages ?? ["Manifest failed"]).join("\n")), Date.now() - started);
        this.diagnostics.publishManifest(reports, item.uri);
      } catch (error) {
        run.errored(item, new vscode.TestMessage(error instanceof Error ? error.message : String(error)), Date.now() - started);
      }
    }
    run.end();
  }
}
