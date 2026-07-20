export function buildRunArgs(file: string, id: string, reportFile: string, version: string | undefined, packs: string[], filters: string[], strict: boolean): string[] {
  const args = ["run"];
  if (version?.trim()) args.push("--version", version.trim());
  args.push("--report-file", reportFile);
  for (const filter of filters) args.push("--trace-filter", filter);
  for (const pack of packs) args.push("--pack", pack);
  args.push("--mcfunction", `${id}=${file}`, "--mcfunction-id", id);
  if (strict) args.push("--strict");
  return args;
}

export function buildCheckArgs(file: string, reportFile: string, strict: boolean, filters: string[]): string[] {
  const args = ["check", file, "--report-file", reportFile, "--snapshot-diff-on-fail"];
  if (strict) args.push("--strict");
  for (const filter of filters) args.push("--trace-filter", filter);
  return args;
}
