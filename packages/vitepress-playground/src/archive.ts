import { Unzip, UnzipInflate } from 'fflate'
import type { UnzipFile } from 'fflate'
import type { PlaygroundImportEntry, PlaygroundImportKind } from './types'

const zipPathDecoder = new TextDecoder()

export interface ImportArchiveLimits {
  maximumImportBytes: number
  maximumImportFiles: number
}

/** Normalizes browser-owned paths before they enter either runtime engine. */
export function normalizeImportEntries(entries: PlaygroundImportEntry[]): PlaygroundImportEntry[] {
  const seen = new Set<string>()
  return entries.map((entry) => {
    if (!entry || typeof entry.path !== 'string') throw importError('INVALID_REQUEST', 'Imported entry has no path')
    const path = normalizeImportPath(entry.path)
    if (seen.has(path)) throw importError('IMPORT_CONFLICT', `Duplicate imported path '${path}'`)
    seen.add(path)
    if (!(entry.bytes instanceof ArrayBuffer)) throw importError('INVALID_REQUEST', `Imported entry '${path}' has no ArrayBuffer`)
    return { path, bytes: entry.bytes }
  })
}

export function normalizeImportPath(raw: string): string {
  const portable = raw.replaceAll('\\', '/')
  if (!portable || portable.startsWith('/') || /^[A-Za-z]:($|\/)/.test(portable) || portable.includes('\0')) {
    throw importError('IMPORT_PATH_INVALID', `Absolute or invalid imported path '${raw}'`)
  }
  const parts = portable.split('/').filter(Boolean)
  if (parts.length === 0 || parts.some((part) => part === '.' || part === '..' || /[\u0000-\u001f]/.test(part))) {
    throw importError('IMPORT_PATH_INVALID', `Imported path traversal is not allowed: '${raw}'`)
  }
  return parts.join('/')
}

/**
 * Streams a ZIP through bounded buffers. Once any limit fails, active file
 * decoders are terminated and callbacks stop retaining decompressed chunks.
 */
export async function extractZipEntries(
  bytes: ArrayBuffer,
  kind: PlaygroundImportKind,
  limits: ImportArchiveLimits,
): Promise<PlaygroundImportEntry[]> {
  validateLimits(limits)
  if (bytes.byteLength > limits.maximumImportBytes) {
    throw importError('IMPORT_SIZE_LIMIT', 'Compressed archive exceeds the import-size limit')
  }
  preflightZip(new Uint8Array(bytes), kind, limits)
  return await new Promise((resolve, reject) => {
    const result: PlaygroundImportEntry[] = []
    const activeFiles = new Set<UnzipFile>()
    let inflated = 0
    let files = 0
    let pending = 0
    let inputFinished = false
    let settled = false
    const finish = () => {
      if (!settled && inputFinished && pending === 0) {
        settled = true
        resolve(result)
      }
    }
    const fail = (error: unknown) => {
      if (settled) return
      settled = true
      for (const file of activeFiles) {
        try {
          file.terminate()
        } catch {
          // Cleanup must not replace the limit or archive error reported below.
        }
      }
      activeFiles.clear()
      reject(error)
    }
    const abort = (error: Error): never => {
      fail(error)
      // Throwing exits fflate's synchronous inflate loop. Its wrapper reports
      // the error once more, and the settled guard discards that callback.
      throw error
    }
    const unzip = new Unzip((file) => {
      if (settled || file.name.endsWith('/') || (kind === 'client-jar' && !file.name.startsWith('assets/'))) return
      files += 1
      if (files > limits.maximumImportFiles) {
        fail(importError('IMPORT_FILE_LIMIT', `Archive exceeds the ${limits.maximumImportFiles} file limit`))
        return
      }
      pending += 1
      activeFiles.add(file)
      const chunks: Uint8Array[] = []
      let length = 0
      file.ondata = (error, chunk, final) => {
        if (settled) return
        if (error) {
          fail(error)
          return
        }
        if (chunk.length > limits.maximumImportBytes - inflated) {
          abort(importError('IMPORT_SIZE_LIMIT', `Expanded archive exceeds the ${limits.maximumImportBytes} byte limit`))
        }
        inflated += chunk.length
        length += chunk.length
        chunks.push(chunk)
        if (final) {
          const joined = new Uint8Array(length)
          let offset = 0
          for (const value of chunks) {
            joined.set(value, offset)
            offset += value.length
          }
          result.push({ path: file.name, bytes: joined.buffer })
          activeFiles.delete(file)
          pending -= 1
          finish()
        }
      }
      if (kind === 'client-jar') {
        queueMicrotask(() => {
          if (!settled) file.start()
        })
      } else {
        file.start()
      }
    })
    unzip.register(UnzipInflate)
    const input = new Uint8Array(bytes)
    let inputOffset = 0
    const pushNextChunk = () => {
      if (settled) return
      try {
        const end = Math.min(input.length, inputOffset + 256 * 1024)
        unzip.push(input.subarray(inputOffset, end), end === input.length)
        inputOffset = end
        if (end === input.length) {
          inputFinished = true
          finish()
        } else {
          setTimeout(pushNextChunk, 0)
        }
      } catch (error) {
        fail(importError('IMPORT_ARCHIVE_INVALID', errorMessage(error)))
      }
    }
    pushNextChunk()
  })
}

function preflightZip(bytes: Uint8Array, kind: PlaygroundImportKind, limits: ImportArchiveLimits): void {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const minimum = Math.max(0, bytes.length - 65_557)
  let end = -1
  for (let offset = bytes.length - 22; offset >= minimum; offset -= 1) {
    if (view.getUint32(offset, true) === 0x06054b50) {
      end = offset
      break
    }
  }
  if (end < 0) throw importError('IMPORT_ARCHIVE_INVALID', 'ZIP end-of-directory record is missing')
  const disk = view.getUint16(end + 4, true)
  const centralDisk = view.getUint16(end + 6, true)
  const diskEntries = view.getUint16(end + 8, true)
  const entries = view.getUint16(end + 10, true)
  const centralSize = view.getUint32(end + 12, true)
  const centralOffset = view.getUint32(end + 16, true)
  const commentLength = view.getUint16(end + 20, true)
  if (disk !== 0 || centralDisk !== 0 || diskEntries !== entries) {
    throw importError('IMPORT_ARCHIVE_INVALID', 'Multi-disk ZIP imports are not supported')
  }
  if (entries === 0xffff || centralSize === 0xffffffff || centralOffset === 0xffffffff) {
    throw importError('IMPORT_ARCHIVE_INVALID', 'ZIP64 imports are not supported in the browser playground')
  }
  if (end + 22 + commentLength > bytes.length) {
    throw importError('IMPORT_ARCHIVE_INVALID', 'ZIP end-of-directory record is truncated')
  }
  if (kind !== 'client-jar' && entries > limits.maximumImportFiles) {
    throw importError('IMPORT_FILE_LIMIT', `Archive exceeds the ${limits.maximumImportFiles} file limit`)
  }
  const centralEnd = centralOffset + centralSize
  if (centralEnd > end || centralOffset + 46 > bytes.length) {
    throw importError('IMPORT_ARCHIVE_INVALID', 'ZIP central directory is outside the archive')
  }
  const paths = new Set<string>()
  let offset = centralOffset
  let expanded = 0
  let includedFiles = 0
  for (let index = 0; index < entries; index += 1) {
    if (offset + 46 > centralEnd || view.getUint32(offset, true) !== 0x02014b50) {
      throw importError('IMPORT_ARCHIVE_INVALID', 'ZIP central directory entry is invalid')
    }
    const nameLength = view.getUint16(offset + 28, true)
    const extraLength = view.getUint16(offset + 30, true)
    const entryCommentLength = view.getUint16(offset + 32, true)
    const next = offset + 46 + nameLength + extraLength + entryCommentLength
    if (next > centralEnd) throw importError('IMPORT_ARCHIVE_INVALID', 'ZIP central directory entry is truncated')
    const name = zipPathDecoder.decode(bytes.subarray(offset + 46, offset + 46 + nameLength))
    const included = !name.endsWith('/') && (kind !== 'client-jar' || name.startsWith('assets/'))
    if (included) {
      const normalized = normalizeImportPath(name)
      if (paths.has(normalized)) throw importError('IMPORT_CONFLICT', `Duplicate imported path '${normalized}'`)
      paths.add(normalized)
      includedFiles += 1
      const size = view.getUint32(offset + 24, true)
      if (size > limits.maximumImportBytes - expanded) {
        throw importError('IMPORT_SIZE_LIMIT', `Expanded archive exceeds the ${limits.maximumImportBytes} byte limit`)
      }
      expanded += size
      if (includedFiles > limits.maximumImportFiles) {
        throw importError('IMPORT_FILE_LIMIT', `Archive exceeds the ${limits.maximumImportFiles} file limit`)
      }
    }
    offset = next
  }
}

function validateLimits(limits: ImportArchiveLimits): void {
  if (!Number.isSafeInteger(limits.maximumImportBytes) || limits.maximumImportBytes <= 0
    || !Number.isSafeInteger(limits.maximumImportFiles) || limits.maximumImportFiles <= 0) {
    throw importError('INVALID_REQUEST', 'Archive limits must be positive safe integers')
  }
}

function importError(code: string, message: string): Error & { code: string; recoverable: boolean } {
  return Object.assign(new Error(message), { code, recoverable: true })
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
