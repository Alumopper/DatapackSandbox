import * as vscode from "vscode";
import { ResourceEntry, ResourceReport } from "./model";
import { SandboxClient } from "./sandboxClient";

export class ResourceTreeProvider implements vscode.TreeDataProvider<ResourceNode> {
  private readonly emitter = new vscode.EventEmitter<ResourceNode | undefined>();
  readonly onDidChangeTreeData = this.emitter.event;
  private report?: ResourceReport;
  constructor(private readonly client: SandboxClient) {}

  async refresh(): Promise<void> {
    this.report = await this.client.request<ResourceReport>("resources");
    this.emitter.fire(undefined);
  }

  getTreeItem(element: ResourceNode): vscode.TreeItem { return element; }
  getChildren(element?: ResourceNode): ResourceNode[] {
    if (!this.report) return [new ResourceNode("Start a sandbox to browse resources", vscode.TreeItemCollapsibleState.None)];
    if (!element) {
      const grouped = new Map<string, ResourceEntry[]>();
      for (const entry of this.report.resources ?? []) { const values = grouped.get(entry.type) ?? []; values.push(entry); grouped.set(entry.type, values); }
      return [...grouped.entries()].sort(([left], [right]) => left.localeCompare(right)).map(([type, entries]) => new ResourceNode(`${type} (${entries.length})`, vscode.TreeItemCollapsibleState.Collapsed, entries));
    }
    return (element.entries ?? []).sort((a, b) => a.id.localeCompare(b.id)).map((entry) => {
      const node = new ResourceNode(entry.id, vscode.TreeItemCollapsibleState.None);
      node.description = entry.active ? entry.behavior : "overridden";
      node.tooltip = `${entry.pack}\n${entry.file}`;
      node.command = entry.file && !entry.file.startsWith("<") ? { command: "vscode.open", title: "Open Resource", arguments: [vscode.Uri.file(entry.file)] } : undefined;
      return node;
    });
  }
}

class ResourceNode extends vscode.TreeItem { constructor(label: string, state: vscode.TreeItemCollapsibleState, readonly entries?: ResourceEntry[]) { super(label, state); } }
