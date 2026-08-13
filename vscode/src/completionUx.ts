export interface CompletionEditPlan {
  text: string;
  cursorOffset?: number;
  retrigger: boolean;
}

export interface PreparedCommandLine {
  buffer: string;
  cursor: number;
  offset: number;
}

const CONTINUATION_SUFFIXES = [" ", ":", "=", "[", "{", ","];

export function prepareCommandLine(line: string, cursor: number): PreparedCommandLine | undefined {
  const boundedCursor = Math.max(0, Math.min(cursor, line.length));
  const first = line.search(/\S/);
  if (first < 0) return { buffer: "", cursor: 0, offset: boundedCursor };
  if (line.slice(first).startsWith("#")) return undefined;
  if (line[first] === "/") return undefined;
  const markerLength = line[first] === "$" ? 1 : 0;
  const offset = first + markerLength;
  if (boundedCursor < offset) return undefined;
  return { buffer: line.slice(offset), cursor: boundedCursor - offset, offset };
}

/** Plan an insertion that keeps command-tree completion moving after acceptance. */
export function completionEditPlan(value: string, appendSpace: boolean): CompletionEditPlan {
  const text = `${value}${appendSpace ? " " : ""}`;
  const emptyString = value.indexOf('""');
  if (emptyString >= 0) return { text, cursorOffset: emptyString + 1, retrigger: true };
  if (value === "[]" || value === "{}") return { text, cursorOffset: 1, retrigger: true };
  return {
    text,
    retrigger: CONTINUATION_SUFFIXES.some((suffix) => text.endsWith(suffix)),
  };
}

/** Extend replacement through an already typed token suffix without eating the next argument. */
export function completionReplacementEnd(line: string, cursor: number, value: string): number {
  let end = Math.max(0, Math.min(cursor, line.length));
  const before = line.slice(0, end).toLowerCase();
  const candidate = value.toLowerCase();
  for (let length = Math.min(candidate.length, before.length); length > 0; length -= 1) {
    if (!before.endsWith(candidate.slice(0, length))) continue;
    const remainder = candidate.slice(length);
    if (remainder && line.slice(end, end + remainder.length).toLowerCase() === remainder) return end + remainder.length;
    break;
  }
  while (end < line.length && /[a-z0-9_./-]/i.test(line[end])) end += 1;

  const trailingSyntax = /[^a-z0-9_./-]+$/i.exec(value)?.[0] ?? "";
  if (trailingSyntax && line.startsWith(trailingSyntax, end)) end += trailingSyntax.length;
  return end;
}
