import { zlibSync } from 'fflate'
import { describe, expect, it } from 'vitest'
import { decodePngTexture, inspectPngTexture } from '../src/png'

describe('PNG texture fallback', () => {
  it('decodes RGBA pixels and selects the first stacked animation frame', () => {
    const pixels = new Uint8Array([
      255, 0, 0, 255, 0, 255, 0, 255,
      0, 0, 255, 255, 255, 255, 255, 255,
      16, 16, 16, 255, 16, 16, 16, 255,
      32, 32, 32, 255, 32, 32, 32, 255,
    ])
    const png = rgbaPng(2, 4, pixels)

    expect(inspectPngTexture(png, 1_024)).toMatchObject({ width: 2, sourceHeight: 4, displayHeight: 2 })
    expect(decodePngTexture(png, 1_024)).toEqual({
      width: 2,
      height: 2,
      rgba: new Uint8ClampedArray(pixels.subarray(0, 16)),
    })
  })

  it('rejects textures whose decoded allocation exceeds the configured budget', () => {
    const png = rgbaPng(2, 2, new Uint8Array(16))

    expect(inspectPngTexture(png, 15)).toBeUndefined()
    expect(decodePngTexture(png, 15)).toBeUndefined()
  })

  it('rejects a forged zlib checksum', () => {
    const png = rgbaPng(1, 1, new Uint8Array([255, 0, 0, 255]), true)

    expect(decodePngTexture(png, 1_024)).toBeUndefined()
  })
})

function rgbaPng(width: number, height: number, pixels: Uint8Array, forgeChecksum = false): Uint8Array {
  const header = new Uint8Array(13)
  writeUint32(header, 0, width)
  writeUint32(header, 4, height)
  header.set([8, 6, 0, 0, 0], 8)

  const scanlines = new Uint8Array(height * (width * 4 + 1))
  for (let row = 0; row < height; row += 1) {
    scanlines.set(pixels.subarray(row * width * 4, (row + 1) * width * 4), row * (width * 4 + 1) + 1)
  }
  const compressed = zlibSync(scanlines)
  if (forgeChecksum) compressed[compressed.length - 1] ^= 0xff
  return concatenate([
    new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]),
    pngChunk('IHDR', header),
    pngChunk('IDAT', compressed),
    pngChunk('IEND', new Uint8Array()),
  ])
}

function pngChunk(type: string, data: Uint8Array): Uint8Array {
  const chunk = new Uint8Array(data.length + 12)
  writeUint32(chunk, 0, data.length)
  chunk.set(new TextEncoder().encode(type), 4)
  chunk.set(data, 8)
  writeUint32(chunk, data.length + 8, crc32(chunk.subarray(4, data.length + 8)))
  return chunk
}

function concatenate(parts: Uint8Array[]): Uint8Array {
  const result = new Uint8Array(parts.reduce((total, part) => total + part.length, 0))
  let offset = 0
  for (const part of parts) {
    result.set(part, offset)
    offset += part.length
  }
  return result
}

function crc32(bytes: Uint8Array): number {
  let crc = 0xffffffff
  for (const byte of bytes) {
    crc ^= byte
    for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0)
  }
  return (crc ^ 0xffffffff) >>> 0
}

function writeUint32(bytes: Uint8Array, offset: number, value: number): void {
  bytes[offset] = value >>> 24
  bytes[offset + 1] = value >>> 16
  bytes[offset + 2] = value >>> 8
  bytes[offset + 3] = value
}
