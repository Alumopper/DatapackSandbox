export interface LogicalCommand {
  line: number;
  endLine: number;
  startCharacter: number;
  command: string;
  leadingSlash: boolean;
  macro: boolean;
}

export interface FunctionReference {
  id: string;
  tag: boolean;
  start: number;
  end: number;
}

export interface ResourceReference {
  id: string;
  tag: boolean;
  start: number;
  end: number;
}

export interface McfunctionToken {
  text: string;
  start: number;
  end: number;
  kind: "command" | "resource" | "selector";
}

/**
 * Joins continuation lines exactly like DatapackLoader: surrounding whitespace is
 * removed and a trailing backslash inserts one space before the next fragment.
 */
export function collectLogicalCommands(text: string): LogicalCommand[] {
  const lines = text.split(/\r?\n/);
  const result: LogicalCommand[] = [];
  let fragments: string[] | undefined;
  let startLine = 0;
  let startCharacter = 0;

  lines.forEach((rawLine, line) => {
    const withoutBom = rawLine.replace(/^\uFEFF/, "");
    const stripped = withoutBom.trim();
    if (!fragments && (!stripped || stripped.startsWith("#"))) return;

    if (!fragments) {
      fragments = [];
      startLine = line;
      startCharacter = Math.max(0, withoutBom.search(/\S/));
    }
    const continues = stripped.endsWith("\\");
    const fragment = continues ? stripped.slice(0, -1).trimEnd() : stripped;
    if (fragment) fragments.push(fragment);
    if (continues) return;

    result.push(buildLogicalCommand(fragments.join(" "), startLine, line, startCharacter));
    fragments = undefined;
  });

  if (fragments) result.push(buildLogicalCommand(fragments.join(" "), startLine, lines.length - 1, startCharacter));
  return result.filter((entry) => Boolean(entry.command));
}

export function findFunctionReference(line: string, character: number): FunctionReference | undefined {
  const pattern = /(?:^|\s)function\s+(#?(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+)/gi;
  for (const match of line.matchAll(pattern)) {
    const raw = match[1];
    const start = (match.index ?? 0) + match[0].lastIndexOf(raw);
    const end = start + raw.length;
    if (character < start || character > end) continue;
    return { id: raw.replace(/^#/, ""), tag: raw.startsWith("#"), start, end };
  }
  return undefined;
}

/** Find a namespaced or contextually unqualified datapack resource under the cursor. */
export function resourceIdAt(line: string, character: number): ResourceReference | undefined {
  const pattern = /#?(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+/gi;
  for (const match of line.matchAll(pattern)) {
    const raw = match[0];
    const start = match.index ?? 0;
    const end = start + raw.length;
    if (character < start || character > end) continue;
    return { id: raw.replace(/^#/, ""), tag: raw.startsWith("#"), start, end };
  }
  return undefined;
}

export function tokenAt(line: string, character: number): McfunctionToken | undefined {
  const selectorPattern = /@[a-z](?:\[[^\]]*\])?/gi;
  for (const match of line.matchAll(selectorPattern)) {
    const token = matchedToken(match, "selector");
    if (contains(token, character)) return token;
  }

  const reference = findFunctionReference(line, character);
  if (reference) return { text: `${reference.tag ? "#" : ""}${reference.id}`, start: reference.start, end: reference.end, kind: "resource" };

  const resource = resourceIdAt(line, character);
  if (resource && (resource.tag || resource.id.includes(":"))) {
    return { text: `${resource.tag ? "#" : ""}${resource.id}`, start: resource.start, end: resource.end, kind: "resource" };
  }

  const root = /^(\s*)[$/]?([a-z_][a-z0-9_]*)/i.exec(line);
  if (root) {
    const start = root[1].length + (line.slice(root[1].length).match(/^[$/]/) ? 1 : 0);
    const token = { text: root[2], start, end: start + root[2].length, kind: "command" as const };
    if (contains(token, character)) return token;
  }
  return undefined;
}

function buildLogicalCommand(raw: string, line: number, endLine: number, startCharacter: number): LogicalCommand {
  const leadingSlash = raw.startsWith("/");
  const withoutSlash = leadingSlash ? raw.slice(1).trimStart() : raw;
  const macro = withoutSlash.startsWith("$");
  return {
    line,
    endLine,
    startCharacter,
    command: macro ? withoutSlash.slice(1).trimStart() : withoutSlash,
    leadingSlash,
    macro,
  };
}

function matchedToken(match: RegExpMatchArray, kind: McfunctionToken["kind"]): McfunctionToken {
  const start = match.index ?? 0;
  return { text: match[0], start, end: start + match[0].length, kind };
}

function contains(token: Pick<McfunctionToken, "start" | "end">, character: number): boolean {
  return character >= token.start && character <= token.end;
}
