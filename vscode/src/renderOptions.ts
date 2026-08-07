import * as path from "node:path";
import * as vscode from "vscode";

/** Builds serve-render parameters from workspace-relative VS Code settings. */
export function configuredRenderOptions(): Record<string, unknown> {
  const config = vscode.workspace.getConfiguration("datapackSandbox");
  const root = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? process.cwd();
  const resolve = (value: string): string | undefined => value.trim() ? path.resolve(root, value) : undefined;
  const minecraftAssets = resolve(config.get<string>("render.minecraftAssetsPath", ""));
  return {
    width: config.get<number>("render.width", 960),
    height: config.get<number>("render.height", 540),
    fieldOfView: config.get<number>("render.fieldOfView", 70),
    renderDistance: config.get<number>("render.distance", 128),
    strictAssets: config.get<boolean>("render.strictAssets", false),
    ...(minecraftAssets ? { minecraftAssets } : {}),
    resourcePacks: config.get<string[]>("render.resourcePackPaths", []).map((entry) => resolve(entry)).filter((entry): entry is string => Boolean(entry)),
  };
}
