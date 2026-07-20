package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.ChunkPos
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.DeflaterOutputStream

abstract class ManifestRunnerTestSupport {
    protected fun writeSchedulePack(root: Path): Path {
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "scheduled assertion test"
              }
            }
            """.trimIndent(),
        )
        val functionRoot = root.resolve("data").resolve("demo").resolve("function")
        Files.createDirectories(functionRoot)
        Files.writeString(
            functionRoot.resolve("main.mcfunction"),
            """
            scoreboard objectives add runs dummy
            schedule function demo:later 5t append
            schedule function demo:later 5t append
            """.trimIndent(),
        )
        Files.writeString(functionRoot.resolve("later.mcfunction"), "scoreboard players add #later runs 1")
        return root
    }

    protected fun writePack(
        root: Path,
        packFormat: String,
        functionDir: String,
        scoreTarget: String,
        scoreValue: Int,
    ): Path {
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": $packFormat,
                "description": "temporary test pack"
              }
            }
            """.trimIndent(),
        )
        val functionRoot = root.resolve("data").resolve("demo").resolve(functionDir)
        Files.createDirectories(functionRoot)
        Files.writeString(
            functionRoot.resolve("load.mcfunction"),
            """
            scoreboard objectives add runs dummy
            scoreboard players set $scoreTarget runs $scoreValue
            """.trimIndent(),
        )
        val tagRoot =
            root
                .resolve("data")
                .resolve("minecraft")
                .resolve("tags")
                .resolve(functionDir)
        Files.createDirectories(tagRoot)
        Files.writeString(tagRoot.resolve("load.json"), """{"values":["demo:load"]}""")
        return root
    }

    protected fun writeNamedFunctionPack(
        root: Path,
        functionName: String,
        scoreTarget: String,
        scoreValue: Int,
    ): Path {
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "temporary named function pack"
              }
            }
            """.trimIndent(),
        )
        val functionRoot = root.resolve("data").resolve("demo").resolve("function")
        Files.createDirectories(functionRoot)
        Files.writeString(
            functionRoot.resolve("$functionName.mcfunction"),
            "scoreboard players set $scoreTarget runs $scoreValue",
        )
        return root
    }

    protected fun writeWeightedLootPack(root: Path): Path {
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "weighted loot seed test"
              }
            }
            """.trimIndent(),
        )
        val lootRoot = root.resolve("data").resolve("demo").resolve("loot_table")
        Files.createDirectories(lootRoot)
        Files.writeString(
            lootRoot.resolve("choice.json"),
            """
            {
              "pools": [
                {
                  "rolls": 1,
                  "entries": [
                    { "type": "minecraft:item", "name": "minecraft:diamond", "weight": 1 },
                    { "type": "minecraft:item", "name": "minecraft:emerald", "weight": 1 }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        return root
    }

    protected fun writeMissingReferencePack(root: Path): Path {
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "missing resource reference test"
              }
            }
            """.trimIndent(),
        )
        val tagRoot =
            root
                .resolve("data")
                .resolve("minecraft")
                .resolve("tags")
                .resolve("function")
        Files.createDirectories(tagRoot)
        Files.writeString(tagRoot.resolve("load.json"), """{"values":["demo:missing_load"]}""")

        val advancementRoot = root.resolve("data").resolve("demo").resolve("advancement")
        Files.createDirectories(advancementRoot)
        Files.writeString(
            advancementRoot.resolve("child.json"),
            """
            {
              "parent": "demo:missing_parent",
              "criteria": {
                "tick": {
                  "trigger": "minecraft:tick"
                }
              }
            }
            """.trimIndent(),
        )

        val predicateRoot = root.resolve("data").resolve("demo").resolve("predicate")
        Files.createDirectories(predicateRoot)
        Files.writeString(
            predicateRoot.resolve("uses_missing.json"),
            """
            {
              "condition": "minecraft:reference",
              "name": "demo:missing_predicate"
            }
            """.trimIndent(),
        )

        val lootRoot = root.resolve("data").resolve("demo").resolve("loot_table")
        Files.createDirectories(lootRoot)
        Files.writeString(
            lootRoot.resolve("outer.json"),
            """
            {
              "conditions": [
                {
                  "condition": "minecraft:reference",
                  "name": "demo:missing_loot_condition"
                }
              ],
              "pools": [
                {
                  "rolls": 1,
                  "entries": [
                    {
                      "type": "minecraft:loot_table",
                      "value": "demo:missing_nested"
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val itemModifierRoot = root.resolve("data").resolve("demo").resolve("item_modifier")
        Files.createDirectories(itemModifierRoot)
        Files.writeString(
            itemModifierRoot.resolve("conditional.json"),
            """
            {
              "function": "minecraft:set_count",
              "count": 1,
              "conditions": [
                {
                  "condition": "minecraft:reference",
                  "name": "demo:missing_item_modifier_condition"
                }
              ]
            }
            """.trimIndent(),
        )
        return root
    }

    protected fun manifestBlockChunkNbt(): ByteArray =
        nbtRoot {
            tagInt("xPos", 0)
            tagInt("zPos", 0)
            tagListCompound("sections") {
                compound {
                    tagByte("Y", 4)
                    tagCompound("block_states") {
                        tagListCompound("palette") {
                            compound {
                                tagString("Name", "minecraft:stone")
                            }
                        }
                    }
                }
            }
        }

    protected fun manifestEntityChunkNbt(): ByteArray =
        nbtRoot {
            tagListCompound("Entities") {
                compound {
                    tagString("id", "minecraft:pig")
                    tagListDouble("Pos", 2.0, 64.0, 2.0)
                    tagListString("Tags", "imported")
                }
            }
        }

    protected fun writeManifestRegion(
        regionDir: Path,
        chunk: ChunkPos,
        nbt: ByteArray,
    ) {
        Files.createDirectories(regionDir)
        val payload = zlib(nbt)
        val chunkRecord = ByteArray(5 + payload.size)
        ByteBuffer.wrap(chunkRecord).order(ByteOrder.BIG_ENDIAN).putInt(payload.size + 1)
        chunkRecord[4] = 2
        payload.copyInto(chunkRecord, destinationOffset = 5)

        val sectors = (chunkRecord.size + 4095) / 4096
        val bytes = ByteArray((2 + sectors) * 4096)
        val localX = Math.floorMod(chunk.x, 32)
        val localZ = Math.floorMod(chunk.z, 32)
        val locationIndex = (localX + localZ * 32) * 4
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(locationIndex, (2 shl 8) or sectors)
        chunkRecord.copyInto(bytes, destinationOffset = 2 * 4096)

        val regionX = Math.floorDiv(chunk.x, 32)
        val regionZ = Math.floorDiv(chunk.z, 32)
        Files.write(regionDir.resolve("r.$regionX.$regionZ.mca"), bytes)
    }

    private fun zlib(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun nbtRoot(block: NbtWriter.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeByte(TAG_COMPOUND)
        data.writeNbtString("")
        NbtWriter(data).compoundPayload(block)
        return out.toByteArray()
    }

    private class NbtWriter(
        private val data: DataOutputStream,
    ) {
        fun compoundPayload(block: NbtWriter.() -> Unit) {
            block()
            data.writeByte(TAG_END)
        }

        fun compound(block: NbtWriter.() -> Unit) {
            compoundPayload(block)
        }

        fun tagByte(
            name: String,
            value: Int,
        ) {
            tag(TAG_BYTE, name)
            data.writeByte(value)
        }

        fun tagInt(
            name: String,
            value: Int,
        ) {
            tag(TAG_INT, name)
            data.writeInt(value)
        }

        fun tagString(
            name: String,
            value: String,
        ) {
            tag(TAG_STRING, name)
            writeNbtString(value)
        }

        fun tagCompound(
            name: String,
            block: NbtWriter.() -> Unit,
        ) {
            tag(TAG_COMPOUND, name)
            compoundPayload(block)
        }

        fun tagListCompound(
            name: String,
            block: ListBuilder.() -> Unit,
        ) {
            val entries = ListBuilder().apply(block).entries
            tag(TAG_LIST, name)
            data.writeByte(TAG_COMPOUND)
            data.writeInt(entries.size)
            entries.forEach { compoundPayload(it) }
        }

        fun tagListDouble(
            name: String,
            vararg values: Double,
        ) {
            tag(TAG_LIST, name)
            data.writeByte(TAG_DOUBLE)
            data.writeInt(values.size)
            values.forEach(data::writeDouble)
        }

        fun tagListString(
            name: String,
            vararg values: String,
        ) {
            tag(TAG_LIST, name)
            data.writeByte(TAG_STRING)
            data.writeInt(values.size)
            values.forEach(::writeNbtString)
        }

        private fun tag(
            type: Int,
            name: String,
        ) {
            data.writeByte(type)
            writeNbtString(name)
        }

        private fun writeNbtString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            data.writeShort(bytes.size)
            data.write(bytes)
        }
    }

    private class ListBuilder {
        val entries = mutableListOf<NbtWriter.() -> Unit>()

        fun compound(block: NbtWriter.() -> Unit) {
            entries += block
        }
    }

    protected fun Path.toEscapedPath(): String = toAbsolutePath().normalize().toString().replace("\\", "\\\\")

    private fun DataOutputStream.writeNbtString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeShort(bytes.size)
        write(bytes)
    }

    private companion object {
        const val TAG_END = 0
        const val TAG_BYTE = 1
        const val TAG_INT = 3
        const val TAG_DOUBLE = 6
        const val TAG_STRING = 8
        const val TAG_LIST = 9
        const val TAG_COMPOUND = 10
    }
}
