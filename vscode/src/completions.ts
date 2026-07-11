import * as vscode from "vscode";
import { SandboxClient } from "./sandboxClient";

export class SandboxCompletionProvider implements vscode.CompletionItemProvider {
  constructor(private readonly client: SandboxClient) {}
  async provideCompletionItems(document: vscode.TextDocument, position: vscode.Position): Promise<vscode.CompletionList | undefined> {
    if (!this.client.activeState) return undefined;
    const line = document.lineAt(position.line).text;
    const result = await this.client.request<{ suggestions: Array<{ value: string; description?: string; start: number; end: number; appendSpace?: boolean; behavior?: string }>; inlineHint?: string }>("completions", { buffer: line, cursor: position.character });
    return new vscode.CompletionList(result.suggestions.map((suggestion) => {
      const item = new vscode.CompletionItem(suggestion.value, vscode.CompletionItemKind.Keyword);
      item.insertText = `${suggestion.value}${suggestion.appendSpace ? " " : ""}`;
      item.range = new vscode.Range(position.line, suggestion.start, position.line, suggestion.end);
      item.detail = suggestion.description || result.inlineHint;
      item.documentation = suggestion.behavior ? `Behavior: ${suggestion.behavior}` : undefined;
      return item;
    }));
  }
}
