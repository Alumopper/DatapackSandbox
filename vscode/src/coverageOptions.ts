import * as vscode from "vscode";

export interface CoverageSettings {
  minimumLine?: number;
  minimumFunction?: number;
  include?: readonly string[];
  exclude?: readonly string[];
}

/** Converts editor settings into the exact parameter names accepted by Serve. */
export function buildCoverageOptions(settings: CoverageSettings): Record<string, unknown> {
  return {
    ...(settings.minimumLine === undefined ? {} : { minimumLine: settings.minimumLine }),
    ...(settings.minimumFunction === undefined ? {} : { minimumFunction: settings.minimumFunction }),
    include: [...(settings.include ?? [])],
    exclude: [...(settings.exclude ?? [])],
  };
}

export function configuredCoverageOptions(): Record<string, unknown> {
  const config = vscode.workspace.getConfiguration("datapackSandbox");
  return buildCoverageOptions({
    minimumLine: config.get<number>("coverage.minimumLine", 0),
    minimumFunction: config.get<number>("coverage.minimumFunction", 0),
    include: config.get<string[]>("coverage.include", []),
    exclude: config.get<string[]>("coverage.exclude", []),
  });
}
