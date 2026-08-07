import { describe, expect, it } from 'vitest'
import { functionReferenceAt, functionReferences } from '../src/function-navigation'

describe('functionReferenceAt', () => {
  it('finds direct and nested execute function calls only on their resource id', () => {
    const direct = 'function vve_demo:throw'
    expect(functionReferenceAt(direct, direct.indexOf('vve_demo') + 3)?.id).toBe('vve_demo:throw')

    const nested = 'execute as @s at @s run function vve:block/_apply_impulse with storage vve:io'
    expect(functionReferenceAt(nested, nested.indexOf('block/_apply') + 5)?.id).toBe('vve:block/_apply_impulse')
    expect(functionReferenceAt(nested, nested.indexOf('vve:io') + 2)).toBeUndefined()
    expect(functionReferences(`${direct}\n${nested}`).map((reference) => reference.id)).toEqual([
      'vve_demo:throw',
      'vve:block/_apply_impulse',
    ])
  })

  it('keeps function tags distinguishable so the Worker can explain why they cannot be opened', () => {
    const source = 'function #minecraft:tick'
    expect(functionReferenceAt(source, source.length)?.id).toBe('#minecraft:tick')
  })
})
