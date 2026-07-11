import * as path from "node:path";
import { FunctionContext } from "./model";

const resourceDirectories = new Set(["function", "functions"]);

export function inferFunctionContext(file: string): FunctionContext {
  const absolute = path.resolve(file);
  const parts = absolute.split(path.sep);
  for (let index = parts.length - 3; index >= 0; index -= 1) {
    if (parts[index] !== "data" || !resourceDirectories.has(parts[index + 2])) continue;
    const namespace = parts[index + 1];
    const relative = parts.slice(index + 3).join("/").replace(/\.mcfunction$/i, "");
    if (namespace && relative) return { file: absolute, id: `${namespace}:${relative}`, packRoot: parts.slice(0, index).join(path.sep) || path.parse(absolute).root };
  }
  return { file: absolute, id: `sandbox:${path.basename(absolute, path.extname(absolute)).replace(/[^a-z0-9_./-]/gi, "_").toLowerCase()}` };
}

export function isManifest(file: string): boolean { return file.toLowerCase().endsWith(".dps.json"); }
export function discoverManifestPaths(files: readonly string[]): string[] { return files.filter(isManifest).sort((left, right) => left.localeCompare(right)); }
