export interface EventPage<T> {
  from: number;
  total: number;
  items: T[];
}

/**
 * Appends a serve-protocol event page without duplicating history. Undefined
 * means the server history was replaced (for example by checkpoint restore),
 * so the caller must request a fresh page from offset zero.
 */
export function appendEventPage<T>(current: readonly T[], page: EventPage<T>): T[] | undefined {
  if (!Number.isSafeInteger(page.from) || !Number.isSafeInteger(page.total) || page.from < 0 || page.total < 0) return undefined;
  if (page.from !== current.length || page.total < current.length || page.from + page.items.length !== page.total) return undefined;
  return [...current, ...page.items];
}
