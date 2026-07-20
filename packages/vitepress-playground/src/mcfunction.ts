import type { StreamParser } from '@codemirror/language'
import { StreamLanguage } from '@codemirror/language'

interface McfunctionState {
  firstToken: boolean
}

const parser: StreamParser<McfunctionState> = {
  startState: () => ({ firstToken: true }),
  token(stream, state) {
    if (stream.sol()) state.firstToken = true
    if (stream.eatSpace()) return null
    if (stream.peek() === '#') {
      stream.skipToEnd()
      return 'comment'
    }
    if (stream.match(/^"(?:[^"\\]|\\.)*"?/)) {
      state.firstToken = false
      return 'string'
    }
    if (stream.match(/^@[pares](?:\[[^\]]*\])?/)) {
      state.firstToken = false
      return 'variableName.special'
    }
    if (stream.match(/^-?(?:\d+(?:\.\d+)?|\.\d+)(?:[bBdDfFlLsS])?/)) {
      state.firstToken = false
      return 'number'
    }
    if (stream.match(/^(?:true|false|null)\b/)) {
      state.firstToken = false
      return 'bool'
    }
    if (stream.match(/^#?[a-z0-9_.-]+:[a-z0-9_./-]+/i)) {
      state.firstToken = false
      return 'typeName'
    }
    if (stream.match(/^[{}\[\](),:=]/)) return 'punctuation'
    if (stream.match(/^[-+*/%<>!]+/)) return 'operator'
    if (stream.match(/^[^\s{}\[\](),:=]+/)) {
      const style = state.firstToken ? 'keyword' : null
      state.firstToken = false
      return style
    }
    stream.next()
    return null
  },
}

export const mcfunctionLanguage = StreamLanguage.define(parser)
