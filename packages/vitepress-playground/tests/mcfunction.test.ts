import { ensureSyntaxTree } from '@codemirror/language'
import { EditorState } from '@codemirror/state'
import { classHighlighter, highlightTree } from '@lezer/highlight'
import { describe, expect, it } from 'vitest'
import { mcfunctionLanguage } from '../src/mcfunction'

interface HighlightedSpan {
  text: string
  classes: string
}

function highlightedSpans(source: string): HighlightedSpan[] {
  const state = EditorState.create({ doc: source, extensions: [mcfunctionLanguage] })
  const tree = ensureSyntaxTree(state, source.length, 1000)
  if (!tree) throw new Error('mcfunction syntax tree did not finish')
  const spans: HighlightedSpan[] = []
  highlightTree(tree, classHighlighter, (from, to, classes) => {
    spans.push({ text: source.slice(from, to), classes })
  })
  return spans
}

describe('mcfunction syntax highlighting', () => {
  it('highlights comments only when hash starts the logical line', () => {
    const source = [
      '# top-level comment',
      '  # indented comment',
      'scoreboard players add #xxx xxx 1',
      'say trailing # text is still command input',
      'function #demo:group',
    ].join('\n')

    const spans = highlightedSpans(source)
    const comments = spans.filter((span) => span.classes.includes('tok-comment')).map((span) => span.text)

    expect(comments).toEqual(['# top-level comment', '# indented comment'])
    expect(comments.join('\n')).not.toContain('#xxx')
    expect(comments.join('\n')).not.toContain('# text is still command input')
    expect(comments.join('\n')).not.toContain('#demo:group')
  })
})
