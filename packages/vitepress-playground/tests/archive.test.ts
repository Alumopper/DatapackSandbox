// @vitest-environment node

import { strToU8, zipSync } from 'fflate'
import { describe, expect, it } from 'vitest'
import { extractZipEntries, normalizeImportEntries } from '../src/archive'

const limits = { maximumImportBytes: 128 * 1024, maximumImportFiles: 16 }

describe('bounded browser archive imports', () => {
  it('normalizes safe paths and rejects traversal before loading resources', () => {
    const bytes = new ArrayBuffer(1)

    expect(normalizeImportEntries([{ path: 'pack\\data/demo/function/main.mcfunction', bytes }])[0].path)
      .toBe('pack/data/demo/function/main.mcfunction')
    expect(() => normalizeImportEntries([{ path: '../outside.mcfunction', bytes }]))
      .toThrow(/traversal/)
  })

  it('extracts ordinary archives within both budgets', async () => {
    const archive = zipSync({ 'data/demo/function/main.mcfunction': strToU8('say safe') })

    const entries = await extractZipEntries(toArrayBuffer(archive), 'datapack', limits)

    expect(entries).toHaveLength(1)
    expect(entries[0].path).toBe('data/demo/function/main.mcfunction')
    expect(new TextDecoder().decode(entries[0].bytes)).toBe('say safe')
  })

  it('uses actual streamed bytes when ZIP headers forge a smaller size', async () => {
    const expanded = new Uint8Array(2 * 1024 * 1024).fill('A'.charCodeAt(0))
    const archive = zipSync({ 'data/demo/function/bomb.mcfunction': expanded }, { level: 9 })
    forgeExpandedSizes(archive, 1)
    expect(archive.byteLength).toBeLessThan(limits.maximumImportBytes)

    await expect(extractZipEntries(toArrayBuffer(archive), 'datapack', limits))
      .rejects.toMatchObject({ code: 'IMPORT_SIZE_LIMIT' })
  })

  it('rejects duplicate normalized archive paths during preflight', async () => {
    const archive = zipSync({
      'data/demo/function/main.mcfunction': strToU8('say first'),
      'data//demo/function/main.mcfunction': strToU8('say second'),
    })

    await expect(extractZipEntries(toArrayBuffer(archive), 'datapack', limits))
      .rejects.toMatchObject({ code: 'IMPORT_CONFLICT' })
  })
})

function forgeExpandedSizes(bytes: Uint8Array, size: number): void {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  for (let offset = 0; offset + 28 <= bytes.length; offset += 1) {
    const signature = view.getUint32(offset, true)
    if (signature === 0x04034b50) view.setUint32(offset + 22, size, true)
    if (signature === 0x02014b50) view.setUint32(offset + 24, size, true)
  }
}

function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer
}
