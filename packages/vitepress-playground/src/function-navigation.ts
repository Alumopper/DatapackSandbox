export interface FunctionReference {
  id: string
  from: number
  to: number
}

const FUNCTION_CALL = /\bfunction\s+(#?[a-z0-9_.-]+(?::[a-z0-9_./-]+)?)/g

export function functionReferences(source: string): FunctionReference[] {
  const references: FunctionReference[] = []
  FUNCTION_CALL.lastIndex = 0
  for (let match = FUNCTION_CALL.exec(source); match; match = FUNCTION_CALL.exec(source)) {
    const id = match[1]
    const offset = match[0].lastIndexOf(id)
    const from = match.index + offset
    references.push({ id, from, to: from + id.length })
  }
  return references
}

/** Returns a function resource reference only when the position is on the
 * argument of a real `function` command (including `execute ... run function`).
 */
export function functionReferenceAt(source: string, position: number): FunctionReference | undefined {
  return functionReferences(source).find((reference) => position >= reference.from && position <= reference.to)
}
