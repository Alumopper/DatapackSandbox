import * as fs from "node:fs/promises";
import { ManifestReport, RunReport } from "./model";

export function parseRunReport(text: string): RunReport {
  const value = JSON.parse(text) as RunReport;
  if (!value || typeof value !== "object" || Array.isArray(value) || typeof value.passed !== "boolean") throw new Error("Invalid Datapack Sandbox run report");
  return value;
}

export function parseManifestReport(text: string): ManifestReport[] {
  const value = JSON.parse(text) as ManifestReport[];
  if (!Array.isArray(value) || value.some((item) => !item || typeof item.path !== "string" || typeof item.passed !== "boolean")) throw new Error("Invalid Datapack Sandbox manifest report");
  return value;
}

export async function readRunReport(file: string): Promise<RunReport> { return parseRunReport(await fs.readFile(file, "utf8")); }
export async function readManifestReport(file: string): Promise<ManifestReport[]> { return parseManifestReport(await fs.readFile(file, "utf8")); }
