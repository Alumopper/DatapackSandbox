package moe.afox.dpsandbox.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BinaryNbtSecurityTest {
    @Test
    fun `rejects collections that exceed the element budget before allocating them`() {
        val payload =
            nbtRoot {
                writeByte(9)
                writeUTF("items")
                writeByte(1)
                writeInt(4)
                repeat(4) { writeByte(it) }
            }

        val error =
            assertFailsWith<SandboxException> {
                BinaryNbt.read(
                    ByteArrayInputStream(payload),
                    BinaryNbtLimits(maximumBytes = 1024, maximumElements = 5, maximumDepth = 16),
                )
            }

        assertTrue(error.message.contains("element count exceeds limit"), error.message)
    }

    @Test
    fun `rejects deeply nested compounds`() {
        val payload =
            nbtRoot {
                repeat(4) {
                    writeByte(10)
                    writeUTF("nested")
                }
                repeat(4) { writeByte(0) }
            }

        val error =
            assertFailsWith<SandboxException> {
                BinaryNbt.read(
                    ByteArrayInputStream(payload),
                    BinaryNbtLimits(maximumBytes = 1024, maximumElements = 32, maximumDepth = 3),
                )
            }

        assertTrue(error.message.contains("nesting depth exceeds limit"), error.message)
    }

    @Test
    fun `limits decompressed NBT bytes`() {
        val error =
            assertFailsWith<SandboxException> {
                BinaryNbt.read(
                    ByteArrayInputStream(nbtRoot {}),
                    BinaryNbtLimits(maximumBytes = 3, maximumElements = 8, maximumDepth = 8),
                )
            }

        assertTrue(error.message.contains("byte limit"), error.message)
    }

    @Test
    fun `rejects chunks that cross their declared Anvil sector allocation`() {
        val path = Files.createTempFile("dps-anvil-sector-boundary", ".mca")
        val region = ByteArray(4 * 4096)
        ByteBuffer
            .wrap(region)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt((2 shl 8) or 1)
            .position(2 * 4096)
            .putInt(5000)
        Files.write(path, region)

        val error =
            AnvilRegionFile(path).use { file ->
                assertFailsWith<SandboxException> { file.readChunk(ChunkPos(0, 0)) }
            }

        assertTrue(error.message.contains("Invalid chunk length"), error.message)
    }

    @Test
    fun `rejects custom dimensions that escape the save directory`() {
        val save = Files.createTempDirectory("dps-save-dimension-boundary")

        val error =
            assertFailsWith<SandboxException> {
                MinecraftSaveImporter.importInto(
                    SandboxWorld(),
                    VersionProfiles.default,
                    MinecraftSaveImportOptions(
                        path = save,
                        dimension = ResourceLocation("..", ".."),
                        chunks = listOf(ChunkPos(0, 0)),
                    ),
                )
            }

        assertTrue(error.message.contains("escapes the Minecraft save directory"), error.message)
    }

    private fun nbtRoot(block: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeByte(10)
                output.writeUTF("")
                output.block()
                output.writeByte(0)
            }
            bytes.toByteArray()
        }
}
