import { ResourceEntry } from "./model";

export function normalizeResourceId(value: string): [string, string] | undefined {
  const raw = value.toLowerCase();
  if (!/^(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+$/.test(raw)) return undefined;
  const colon = raw.indexOf(":");
  return colon < 0 ? ["minecraft", raw] : [raw.slice(0, colon), raw.slice(colon + 1)];
}

export function matchingResourceEntries(
  resources: readonly ResourceEntry[],
  id: string,
  tag: boolean,
  functionOnly: boolean,
): ResourceEntry[] {
  const normalized = normalizeResourceId(id);
  if (!normalized) return [];
  const expected = `${normalized[0]}:${normalized[1]}`;
  return resources.filter((resource) => {
    const resourceId = normalizeResourceId(resource.id);
    if (!resourceId || `${resourceId[0]}:${resourceId[1]}` !== expected || resource.active === false) return false;
    const type = resource.type.toLowerCase();
    if (tag !== type.includes("tag")) return false;
    return !functionOnly || type.includes("function");
  });
}
