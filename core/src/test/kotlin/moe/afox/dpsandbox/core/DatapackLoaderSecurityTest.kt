package moe.afox.dpsandbox.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatapackLoaderSecurityTest {
    @Test
    fun `rejects a zip entry that exceeds the expanded single-entry budget`() {
        val pack = createPackZip("data/demo/function/oversized.mcfunction", 65 * MEBIBYTE)
        try {
            val error = assertFailsWith<SandboxException> { DatapackLoader.load(listOf(pack), VersionProfiles.default) }

            assertTrue(error.message.orEmpty().contains("single-entry limit"), error.message)
        } finally {
            Files.deleteIfExists(pack)
        }
    }

    @Test
    fun `rejects oversized text before parsing or splitting it into lines`() {
        val pack = createPackZip("data/demo/function/oversized.mcfunction", 17 * MEBIBYTE)
        try {
            val error = assertFailsWith<SandboxException> { DatapackLoader.load(listOf(pack), VersionProfiles.default) }

            assertTrue(error.message.orEmpty().contains("text resource"), error.message)
            assertTrue(error.message.orEmpty().contains("byte limit"), error.message)
        } finally {
            Files.deleteIfExists(pack)
        }
    }

    @Test
    fun `rejects malformed UTF-8 instead of replacing invalid bytes`() {
        val pack = createPackZip("data/demo/function/invalid.mcfunction", byteArrayOf(0xc3.toByte(), 0x28))
        try {
            val error = assertFailsWith<SandboxException> { DatapackLoader.load(listOf(pack), VersionProfiles.default) }

            assertTrue(error.message.orEmpty().contains("not valid UTF-8"), error.message)
        } finally {
            Files.deleteIfExists(pack)
        }
    }

    @Test
    fun `rejects unsafe and ambiguous zip entry paths`() {
        val pack = createPackZip("../data/demo/function/escape.mcfunction", byteArrayOf())
        try {
            val error = assertFailsWith<SandboxException> { DatapackLoader.load(listOf(pack), VersionProfiles.default) }

            assertTrue(error.message.orEmpty().contains("unsafe archive path"), error.message)
        } finally {
            Files.deleteIfExists(pack)
        }
    }

    @Test
    fun `rejects directory resources that resolve outside the pack root`() {
        val temporaryRoot = Files.createTempDirectory("dps-pack-link-boundary-")
        try {
            val pack = Files.createDirectories(temporaryRoot.resolve("pack"))
            Files.writeString(pack.resolve("pack.mcmeta"), """{"pack":{"pack_format":107.1,"description":"link test"}}""")
            val functionDirectory = Files.createDirectories(pack.resolve("data/demo/function"))
            val external = Files.writeString(temporaryRoot.resolve("outside.mcfunction"), "say outside")
            try {
                Files.createSymbolicLink(functionDirectory.resolve("linked.mcfunction"), external)
            } catch (_: Exception) {
                // Windows may require Developer Mode or elevated symlink privileges.
                return
            }

            val error = assertFailsWith<SandboxException> { DatapackLoader.load(listOf(pack), VersionProfiles.default) }

            assertTrue(error.message.orEmpty().contains("outside the datapack directory"), error.message)
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    private fun createPackZip(
        resourceName: String,
        resourceBytes: Int,
    ): Path =
        createPackZip(resourceName) { zip ->
            val chunk = ByteArray(8 * 1024) { '#'.code.toByte() }
            var remaining = resourceBytes
            while (remaining > 0) {
                val count = minOf(remaining, chunk.size)
                zip.write(chunk, 0, count)
                remaining -= count
            }
        }

    private fun createPackZip(
        resourceName: String,
        content: ByteArray,
    ): Path = createPackZip(resourceName) { zip -> zip.write(content) }

    private fun createPackZip(
        resourceName: String,
        writeResource: (ZipOutputStream) -> Unit,
    ): Path {
        val pack = Files.createTempFile("dps-input-budget-", ".zip")
        ZipOutputStream(Files.newOutputStream(pack)).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            zip.putNextEntry(ZipEntry("pack.mcmeta"))
            zip.write("""{"pack":{"pack_format":107.1,"description":"input budget test"}}""".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(resourceName))
            writeResource(zip)
            zip.closeEntry()
        }
        return pack
    }

    private companion object {
        const val MEBIBYTE = 1024 * 1024
    }
}
