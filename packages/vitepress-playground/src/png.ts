import { unzlibSync } from 'fflate'

export interface PngTextureMetadata {
  width: number
  sourceHeight: number
  displayHeight: number
  inflatedBytes: number
}

export interface DecodedPngTexture {
  width: number
  height: number
  rgba: Uint8ClampedArray
}

interface PngHeader extends PngTextureMetadata {
  bitDepth: number
  colorType: number
  channels: number
  rowBytes: number
  filterBytesPerPixel: number
  interlace: number
}

interface PngChunks {
  compressed: Uint8Array
  palette?: Uint8Array
  transparency?: Uint8Array
}

const PNG_SIGNATURE = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10])
const ADLER_MODULUS = 65_521
const CRC32_TABLE = buildCrc32Table()

/**
 * Reads enough PNG metadata to put a hard upper bound on native and fallback
 * decoder allocations. The source height is kept because animated Minecraft
 * textures stack frames vertically even though only the first frame is drawn.
 */
export function inspectPngTexture(bytes: Uint8Array, maximumBytes: number): PngTextureMetadata | undefined {
  try {
    const header = readHeader(bytes, maximumBytes)
    return {
      width: header.width,
      sourceHeight: header.sourceHeight,
      displayHeight: header.displayHeight,
      inflatedBytes: header.inflatedBytes,
    }
  } catch {
    return undefined
  }
}

/**
 * Decodes standard, non-interlaced PNG color formats without DOM canvas APIs.
 * This is the compatibility path for WebKit Workers without OffscreenCanvas.
 */
export function decodePngTexture(bytes: Uint8Array, maximumBytes: number): DecodedPngTexture | undefined {
  try {
    const header = readHeader(bytes, maximumBytes)
    if (header.interlace !== 0) return undefined
    const chunks = readChunks(bytes)
    const inflated = unzlibSync(chunks.compressed, { out: new Uint8Array(header.inflatedBytes) })
    if (inflated.byteLength !== header.inflatedBytes || adler32(inflated) !== readUint32(chunks.compressed, chunks.compressed.length - 4)) {
      return undefined
    }
    return convertPixels(header, chunks, inflated)
  } catch {
    return undefined
  }
}

function readHeader(bytes: Uint8Array, maximumBytes: number): PngHeader {
  if (!Number.isSafeInteger(maximumBytes) || maximumBytes <= 0 || bytes.length < 33) throw new Error('Invalid PNG bounds')
  for (let index = 0; index < PNG_SIGNATURE.length; index += 1) {
    if (bytes[index] !== PNG_SIGNATURE[index]) throw new Error('Invalid PNG signature')
  }
  if (readUint32(bytes, 8) !== 13 || readChunkType(bytes, 12) !== 'IHDR') throw new Error('Missing PNG header')
  if (crc32(bytes, 12, 29) !== readUint32(bytes, 29)) throw new Error('Corrupted PNG header')

  const width = readUint32(bytes, 16)
  const sourceHeight = readUint32(bytes, 20)
  const bitDepth = bytes[24]
  const colorType = bytes[25]
  const compression = bytes[26]
  const filter = bytes[27]
  const interlace = bytes[28]
  if (width === 0 || sourceHeight === 0 || compression !== 0 || filter !== 0 || (interlace !== 0 && interlace !== 1)) {
    throw new Error('Unsupported PNG header')
  }

  const channels = channelsFor(colorType, bitDepth)
  const bitsPerPixel = channels * bitDepth
  const rowBytes = checkedCeilDiv(width * bitsPerPixel, 8)
  const inflatedBytes = interlace === 0
    ? checkedProduct(sourceHeight, rowBytes + 1)
    : adam7InflatedBytes(width, sourceHeight, bitsPerPixel)
  const decodedBytes = checkedProduct(checkedProduct(width, sourceHeight), 4)
  if (inflatedBytes > maximumBytes || decodedBytes > maximumBytes) throw new Error('PNG exceeds decode budget')

  const displayHeight = sourceHeight > width && sourceHeight % width === 0 ? width : sourceHeight
  return {
    width,
    sourceHeight,
    displayHeight,
    bitDepth,
    colorType,
    channels,
    rowBytes,
    filterBytesPerPixel: Math.max(1, checkedCeilDiv(bitsPerPixel, 8)),
    interlace,
    inflatedBytes,
  }
}

function channelsFor(colorType: number, bitDepth: number): number {
  const channels = colorType === 0 ? 1 : colorType === 2 ? 3 : colorType === 3 ? 1 : colorType === 4 ? 2 : colorType === 6 ? 4 : 0
  const validDepth = colorType === 0 || colorType === 3
    ? bitDepth === 1 || bitDepth === 2 || bitDepth === 4 || bitDepth === 8 || (colorType === 0 && bitDepth === 16)
    : bitDepth === 8 || bitDepth === 16
  if (channels === 0 || !validDepth) throw new Error('Unsupported PNG color format')
  return channels
}

function adam7InflatedBytes(width: number, height: number, bitsPerPixel: number): number {
  const startsX = [0, 4, 0, 2, 0, 1, 0]
  const startsY = [0, 0, 4, 0, 2, 0, 1]
  const stepsX = [8, 8, 4, 4, 2, 2, 1]
  const stepsY = [8, 8, 8, 4, 4, 2, 2]
  let total = 0
  for (let pass = 0; pass < startsX.length; pass += 1) {
    const passWidth = passLength(width, startsX[pass], stepsX[pass])
    const passHeight = passLength(height, startsY[pass], stepsY[pass])
    if (passWidth === 0 || passHeight === 0) continue
    total = checkedSum(total, checkedProduct(passHeight, checkedCeilDiv(passWidth * bitsPerPixel, 8) + 1))
  }
  return total
}

function passLength(length: number, start: number, step: number): number {
  return length <= start ? 0 : Math.ceil((length - start) / step)
}

function readChunks(bytes: Uint8Array): PngChunks {
  const idat: Uint8Array[] = []
  let compressedBytes = 0
  let palette: Uint8Array | undefined
  let transparency: Uint8Array | undefined
  let offset = 8
  let reachedEnd = false
  while (offset + 12 <= bytes.length) {
    const length = readUint32(bytes, offset)
    const dataStart = offset + 8
    const dataEnd = dataStart + length
    const next = dataEnd + 4
    if (!Number.isSafeInteger(next) || next > bytes.length) throw new Error('Truncated PNG chunk')
    const type = readChunkType(bytes, offset + 4)
    const data = bytes.subarray(dataStart, dataEnd)
    if (crc32(bytes, offset + 4, dataEnd) !== readUint32(bytes, dataEnd)) throw new Error('Corrupted PNG chunk')
    if (type === 'IDAT') {
      compressedBytes = checkedSum(compressedBytes, data.length)
      idat.push(data)
    } else if (type === 'PLTE') {
      palette = data
    } else if (type === 'tRNS') {
      transparency = data
    } else if (type === 'IEND') {
      reachedEnd = true
      break
    }
    offset = next
  }
  if (!reachedEnd || idat.length === 0 || compressedBytes < 6) throw new Error('Incomplete PNG data')

  const compressed = new Uint8Array(compressedBytes)
  let writeOffset = 0
  for (const chunk of idat) {
    compressed.set(chunk, writeOffset)
    writeOffset += chunk.length
  }
  return { compressed, palette, transparency }
}

function convertPixels(header: PngHeader, chunks: PngChunks, inflated: Uint8Array): DecodedPngTexture {
  const rgba = new Uint8ClampedArray(header.width * header.displayHeight * 4)
  let previous = new Uint8Array(header.rowBytes)
  let current = new Uint8Array(header.rowBytes)
  let inputOffset = 0
  for (let y = 0; y < header.sourceHeight; y += 1) {
    const filter = inflated[inputOffset]
    inputOffset += 1
    unfilterRow(current, inflated.subarray(inputOffset, inputOffset + header.rowBytes), previous, header.filterBytesPerPixel, filter)
    inputOffset += header.rowBytes
    if (y < header.displayHeight) writeRgbaRow(rgba, y * header.width * 4, current, header, chunks)
    const swap = previous
    previous = current
    current = swap
  }
  return { width: header.width, height: header.displayHeight, rgba }
}

function unfilterRow(target: Uint8Array, source: Uint8Array, previous: Uint8Array, bytesPerPixel: number, filter: number): void {
  if (filter > 4) throw new Error('Unsupported PNG filter')
  for (let index = 0; index < source.length; index += 1) {
    const left = index >= bytesPerPixel ? target[index - bytesPerPixel] : 0
    const up = previous[index]
    const upperLeft = index >= bytesPerPixel ? previous[index - bytesPerPixel] : 0
    const predictor = filter === 0
      ? 0
      : filter === 1
        ? left
        : filter === 2
          ? up
          : filter === 3
            ? Math.floor((left + up) / 2)
            : paeth(left, up, upperLeft)
    target[index] = (source[index] + predictor) & 0xff
  }
}

function paeth(left: number, up: number, upperLeft: number): number {
  const estimate = left + up - upperLeft
  const leftDistance = Math.abs(estimate - left)
  const upDistance = Math.abs(estimate - up)
  const upperLeftDistance = Math.abs(estimate - upperLeft)
  return leftDistance <= upDistance && leftDistance <= upperLeftDistance ? left : upDistance <= upperLeftDistance ? up : upperLeft
}

function writeRgbaRow(target: Uint8ClampedArray, targetOffset: number, row: Uint8Array, header: PngHeader, chunks: PngChunks): void {
  for (let x = 0; x < header.width; x += 1) {
    const sampleOffset = x * header.channels
    const first = readSample(row, sampleOffset, header.bitDepth)
    const output = targetOffset + x * 4
    if (header.colorType === 0) {
      const gray = scaleSample(first, header.bitDepth)
      target[output] = gray
      target[output + 1] = gray
      target[output + 2] = gray
      target[output + 3] = grayscaleAlpha(first, chunks.transparency)
    } else if (header.colorType === 2) {
      const green = readSample(row, sampleOffset + 1, header.bitDepth)
      const blue = readSample(row, sampleOffset + 2, header.bitDepth)
      target[output] = scaleSample(first, header.bitDepth)
      target[output + 1] = scaleSample(green, header.bitDepth)
      target[output + 2] = scaleSample(blue, header.bitDepth)
      target[output + 3] = truecolorAlpha(first, green, blue, chunks.transparency)
    } else if (header.colorType === 3) {
      writePalettePixel(target, output, first, chunks)
    } else if (header.colorType === 4) {
      const gray = scaleSample(first, header.bitDepth)
      target[output] = gray
      target[output + 1] = gray
      target[output + 2] = gray
      target[output + 3] = scaleSample(readSample(row, sampleOffset + 1, header.bitDepth), header.bitDepth)
    } else {
      target[output] = scaleSample(first, header.bitDepth)
      target[output + 1] = scaleSample(readSample(row, sampleOffset + 1, header.bitDepth), header.bitDepth)
      target[output + 2] = scaleSample(readSample(row, sampleOffset + 2, header.bitDepth), header.bitDepth)
      target[output + 3] = scaleSample(readSample(row, sampleOffset + 3, header.bitDepth), header.bitDepth)
    }
  }
}

function readSample(row: Uint8Array, sampleIndex: number, bitDepth: number): number {
  if (bitDepth < 8) {
    const bitOffset = sampleIndex * bitDepth
    const shift = 8 - bitDepth - (bitOffset & 7)
    return (row[bitOffset >> 3] >> shift) & ((1 << bitDepth) - 1)
  }
  if (bitDepth === 8) return row[sampleIndex]
  const offset = sampleIndex * 2
  return (row[offset] << 8) | row[offset + 1]
}

function scaleSample(sample: number, bitDepth: number): number {
  if (bitDepth === 8) return sample
  if (bitDepth === 16) return sample >>> 8
  return Math.round(sample * 255 / ((1 << bitDepth) - 1))
}

function grayscaleAlpha(sample: number, transparency: Uint8Array | undefined): number {
  return transparency?.length === 2 && sample === readUint16(transparency, 0) ? 0 : 255
}

function truecolorAlpha(red: number, green: number, blue: number, transparency: Uint8Array | undefined): number {
  return transparency?.length === 6
    && red === readUint16(transparency, 0)
    && green === readUint16(transparency, 2)
    && blue === readUint16(transparency, 4)
    ? 0
    : 255
}

function writePalettePixel(target: Uint8ClampedArray, offset: number, index: number, chunks: PngChunks): void {
  const paletteOffset = index * 3
  if (!chunks.palette || chunks.palette.length === 0 || chunks.palette.length % 3 !== 0 || paletteOffset + 2 >= chunks.palette.length) {
    throw new Error('Invalid PNG palette')
  }
  target[offset] = chunks.palette[paletteOffset]
  target[offset + 1] = chunks.palette[paletteOffset + 1]
  target[offset + 2] = chunks.palette[paletteOffset + 2]
  target[offset + 3] = chunks.transparency?.[index] ?? 255
}

function adler32(bytes: Uint8Array): number {
  let first = 1
  let second = 0
  for (let offset = 0; offset < bytes.length; offset += 5_552) {
    const end = Math.min(offset + 5_552, bytes.length)
    for (let index = offset; index < end; index += 1) {
      first += bytes[index]
      second += first
    }
    first %= ADLER_MODULUS
    second %= ADLER_MODULUS
  }
  return ((second << 16) | first) >>> 0
}

function buildCrc32Table(): Uint32Array {
  const table = new Uint32Array(256)
  for (let index = 0; index < table.length; index += 1) {
    let value = index
    for (let bit = 0; bit < 8; bit += 1) value = (value >>> 1) ^ (value & 1 ? 0xedb88320 : 0)
    table[index] = value >>> 0
  }
  return table
}

function crc32(bytes: Uint8Array, start: number, end: number): number {
  let value = 0xffffffff
  for (let index = start; index < end; index += 1) value = CRC32_TABLE[(value ^ bytes[index]) & 0xff] ^ (value >>> 8)
  return (value ^ 0xffffffff) >>> 0
}

function readChunkType(bytes: Uint8Array, offset: number): string {
  return String.fromCharCode(bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3])
}

function readUint16(bytes: Uint8Array, offset: number): number {
  return (bytes[offset] << 8) | bytes[offset + 1]
}

function readUint32(bytes: Uint8Array, offset: number): number {
  if (offset < 0 || offset + 4 > bytes.length) throw new Error('Truncated PNG integer')
  return ((bytes[offset] * 0x1000000) + (bytes[offset + 1] << 16) + (bytes[offset + 2] << 8) + bytes[offset + 3]) >>> 0
}

function checkedCeilDiv(value: number, divisor: number): number {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error('PNG dimensions overflow')
  return Math.ceil(value / divisor)
}

function checkedProduct(left: number, right: number): number {
  const result = left * right
  if (!Number.isSafeInteger(result) || result < 0) throw new Error('PNG dimensions overflow')
  return result
}

function checkedSum(left: number, right: number): number {
  const result = left + right
  if (!Number.isSafeInteger(result) || result < 0) throw new Error('PNG dimensions overflow')
  return result
}
