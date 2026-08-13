import * as path from "node:path";
import * as vscode from "vscode";

/** Builds serve-render parameters from workspace-relative VS Code settings. */
export function configuredRenderOptions(): Record<string, unknown> {
  const config = vscode.workspace.getConfiguration("datapackSandbox");
  const root = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? process.cwd();
  const resolve = (value: string): string | undefined => value.trim() ? path.resolve(root, value) : undefined;
  const minecraftAssets = resolve(config.get<string>("render.minecraftAssetsPath", ""));
  const playerSkins = Object.fromEntries(
    Object.entries(config.get<Record<string, string>>("render.playerSkins", {}))
      .map(([name, value]) => [name, resolve(value)] as const)
      .filter((entry): entry is readonly [string, string] => Boolean(entry[1])),
  );
  const cameraPlayer = config.get<string>("render.cameraPlayer", "").trim();
  const cameraEntity = config.get<string>("render.cameraEntity", "").trim();
  const position = config.get<number[]>("render.cameraPosition", []);
  const camera = cameraPlayer
    ? { cameraPlayer }
    : cameraEntity
      ? { cameraEntity }
      : position.length === 3
        ? {
            position,
            yaw: config.get<number>("render.cameraYaw", 0),
            pitch: config.get<number>("render.cameraPitch", 0),
            dimension: config.get<string>("render.cameraDimension", "minecraft:overworld"),
          }
        : {};
  return {
    width: config.get<number>("render.width", 960),
    height: config.get<number>("render.height", 540),
    fieldOfView: config.get<number>("render.fieldOfView", 70),
    renderDistance: config.get<number>("render.distance", 128),
    strictAssets: config.get<boolean>("render.strictAssets", false),
    transparentBackground: config.get<boolean>("render.transparentBackground", false),
    showHud: config.get<boolean>("render.showHud", false),
    showDebugOverlay: config.get<boolean>("render.showDebugOverlay", false),
    ...(minecraftAssets ? { minecraftAssets } : {}),
    resourcePacks: config.get<string[]>("render.resourcePackPaths", []).map((entry) => resolve(entry)).filter((entry): entry is string => Boolean(entry)),
    playerSkins,
    ...camera,
  };
}
