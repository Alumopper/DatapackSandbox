import { ServeHello } from "./model";

export interface ServePartial {
  commandsCompleted?: number;
  outputs?: unknown[];
  traces?: unknown[];
  eventTraces?: unknown[];
  snapshotDiffs?: unknown[];
  state?: Record<string, unknown>;
}

export interface ServeError {
  code?: string;
  message?: string;
  version?: string;
  command?: string;
  location?: { file?: string; line?: number; command?: string };
  partial?: ServePartial;
}

export interface ServeResponse {
  id: string | null;
  ok: boolean;
  result?: unknown;
  error?: ServeError;
}

/** Normalize the pre-fix 1.1.0 hello, whose null id was omitted by Gson. */
export function parseServeResponse(value: unknown): ServeResponse | undefined {
  if (isServeResponse(value)) return value;
  if (!value || typeof value !== "object" || Object.prototype.hasOwnProperty.call(value, "id")) return undefined;
  const response = value as Partial<ServeResponse>;
  if (response.ok !== true || !isServeHello(response.result)) return undefined;
  return { ...response, id: null } as ServeResponse;
}

export function isServeHello(value: unknown): value is ServeHello {
  if (!value || typeof value !== "object") return false;
  const hello = value as Partial<ServeHello>;
  return hello.protocol === "dps-jsonl"
    && typeof hello.defaultVersion === "string"
    && Array.isArray(hello.versions)
    && hello.versions.every((version) => typeof version === "string")
    && Boolean(hello.capabilities && typeof hello.capabilities === "object")
    && Object.values(hello.capabilities ?? {}).every((capability) => typeof capability === "boolean" || typeof capability === "string");
}

function isServeResponse(value: unknown): value is ServeResponse {
  if (!value || typeof value !== "object") return false;
  const response = value as Partial<ServeResponse>;
  return (response.id === null || typeof response.id === "string") && typeof response.ok === "boolean";
}
