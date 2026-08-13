/** Normalizes panel input to the two player-event forms accepted by Serve. */
export function parsePlayerEventPayload(value: unknown): Record<string, unknown> {
  if (value && typeof value === "object" && !Array.isArray(value)) return value as Record<string, unknown>;
  if (typeof value !== "string") throw new Error("Player event must be a JSON object or Serve event string.");
  let parsed: unknown;
  try {
    parsed = JSON.parse(value);
  } catch {
    const event = value.trim();
    if (event.startsWith("player ")) return { event };
    throw new Error("Player event must be JSON or Serve text beginning with 'player '.");
  }
  if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) return parsed as Record<string, unknown>;
  if (typeof parsed === "string") return { event: parsed };
  throw new Error("Player event JSON must be an object or string.");
}
