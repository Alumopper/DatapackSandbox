package moe.afox.dpsandbox.render

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BoundedImageReaderTest {
    @Test
    fun `rejects oversized dimensions before allocating a pixel buffer`() {
        assertNull(BoundedImageReader.read(pngHeader(width = 100_000, height = 100_000)))
    }

    @Test
    fun `render requests limit total pixel allocation`() {
        assertFailsWith<IllegalArgumentException> { RenderRequest(width = 8192, height = 8192) }
        RenderRequest(width = 8192, height = 2048)
    }

    private fun pngHeader(
        width: Int,
        height: Int,
    ): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(PNG_SIGNATURE)
                output.writeChunk(
                    "IHDR",
                    ByteArrayOutputStream().use { headerBytes ->
                        DataOutputStream(headerBytes).use { header ->
                            header.writeInt(width)
                            header.writeInt(height)
                            header.write(byteArrayOf(8, 6, 0, 0, 0))
                        }
                        headerBytes.toByteArray()
                    },
                )
                output.writeChunk("IEND", byteArrayOf())
            }
            bytes.toByteArray()
        }

    private fun DataOutputStream.writeChunk(
        type: String,
        data: ByteArray,
    ) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val checksum = CRC32().also { crc -> crc.update(typeBytes + data) }
        writeInt(data.size)
        write(typeBytes)
        write(data)
        writeInt(checksum.value.toInt())
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
