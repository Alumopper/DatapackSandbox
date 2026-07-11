import * as path from "node:path";
import { TraceEvent } from "./model";

export interface DebugVariable {
  name: string;
  value: string;
  type: string;
  variablesReference: number;
  indexedVariables?: number;
  namedVariables?: number;
}

export function findInitialTraceIndex(traces: TraceEvent[], breakpoints: Map<string, Set<number>>, stopOnEntry: boolean): number {
  if (!traces.length) return -1;
  if (stopOnEntry) return 0;
  const breakpointIndex = traces.findIndex((trace) => {
    if (!trace.source?.file || !trace.source.line) return false;
    return breakpoints.get(path.resolve(trace.source.file))?.has(trace.source.line) ?? false;
  });
  if (breakpointIndex >= 0) return breakpointIndex;
  return traces.findIndex((trace) => !trace.success);
}

export class DebugValueStore {
  private nextReference = 1;
  private readonly values = new Map<number, unknown>();

  clear(): void {
    this.nextReference = 1;
    this.values.clear();
  }

  add(value: unknown): number {
    const reference = this.nextReference++;
    this.values.set(reference, value);
    return reference;
  }

  variables(reference: number): DebugVariable[] {
    const value = this.values.get(reference);
    const entries = Array.isArray(value)
      ? value.map((item, index) => [`[${index}]`, item] as const)
      : value && typeof value === "object"
        ? Object.entries(value)
        : [];
    return entries.map(([name, child]) => ({
      name,
      value: debugValueSummary(child),
      type: debugValueType(child),
      variablesReference: isExpandable(child) ? this.add(child) : 0,
      ...(Array.isArray(child)
        ? { indexedVariables: child.length }
        : child && typeof child === "object"
          ? { namedVariables: Object.keys(child).length }
          : {}),
    }));
  }
}

export function isExpandable(value: unknown): value is object {
  return value !== null && typeof value === "object";
}

export function debugValueSummary(value: unknown): string {
  if (Array.isArray(value)) return `Array(${value.length})`;
  if (value && typeof value === "object") return `{${Object.keys(value).length} properties}`;
  if (typeof value === "string") return value;
  return String(value);
}

function debugValueType(value: unknown): string {
  return Array.isArray(value) ? "array" : value === null ? "null" : typeof value;
}
