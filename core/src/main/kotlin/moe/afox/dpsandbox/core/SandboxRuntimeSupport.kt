package moe.afox.dpsandbox.core

import com.google.gson.JsonObject

internal sealed interface DataTargetSpec {
    data class Storage(
        val id: ResourceLocation,
    ) : DataTargetSpec

    data class Entities(
        val entities: List<SandboxEntity>,
    ) : DataTargetSpec

    data class Block(
        val pos: BlockPos,
    ) : DataTargetSpec
}

internal data class BlockArgument(
    val id: ResourceLocation,
    val properties: Map<String, String> = emptyMap(),
    val nbt: JsonObject = JsonObject(),
) {
    fun toBlock(
        pos: BlockPos,
        profile: VersionProfile,
        location: SourceLocation?,
    ): SandboxBlock? {
        if (id == ResourceLocation("minecraft", "air")) return null
        val block = SandboxBlock(id, properties.toMutableMap())
        if (nbt.entrySet().isNotEmpty()) {
            val updated = block.fullNbt(pos, profile, location)
            JsonPaths.merge(updated, null, nbt)
            block.writeFullNbt(pos, profile, updated, location)
        }
        return block
    }
}

internal fun SandboxBlock.copyForClone(): SandboxBlock =
    SandboxBlock(
        id = id,
        properties = properties.toMutableMap(),
        nbt = nbt.deepCopy(),
    )
