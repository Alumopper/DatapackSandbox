package moe.afox.dpsandbox.render

import com.google.gson.JsonObject
import moe.afox.dpsandbox.core.BlockPos
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.Position
import moe.afox.dpsandbox.core.SandboxPlayer
import moe.afox.dpsandbox.core.toJson

/** Immutable block input copied from the mutable sandbox world. */
data class RenderBlock(
    val position: BlockPos,
    val id: String,
    val properties: Map<String, String>,
    val nbt: JsonObject = JsonObject(),
)

/** Immutable entity input copied from the mutable sandbox world. */
data class RenderEntity(
    val uuid: String,
    val type: String,
    val name: String?,
    val position: Position,
    val yaw: Double,
    val pitch: Double,
    val dimension: String,
    val special: JsonObject?,
    val nbt: JsonObject = JsonObject(),
    val equipment: Map<String, String> = emptyMap(),
)

/** Immutable view captured at the start of a render. */
data class WorldView(
    val profile: String,
    val gameTime: Long,
    val dayTime: Long,
    val weather: String,
    val seed: Long,
    val blocks: List<RenderBlock>,
    val entities: List<RenderEntity>,
    val biomes: Map<BlockPos, String>,
    val worldSpawn: Position,
) {
    companion object {
        /** Copies all currently renderable public world state. */
        @JvmStatic
        fun capture(sandbox: DatapackSandbox): WorldView {
            val world = sandbox.world
            val playerNames = world.players.values.associateBy(SandboxPlayer::uuid)
            return WorldView(
                profile = sandbox.profile.id,
                gameTime = world.gameTime,
                dayTime = world.dayTime,
                weather = world.weather,
                seed = world.seed,
                blocks =
                    world.blocks.entries
                        .sortedBy { it.key }
                        .map { (position, block) ->
                            RenderBlock(position, block.id.toString(), block.properties.toSortedMap(), block.nbt.deepCopy())
                        },
                entities =
                    world.entities
                        .sortedWith(compareBy({ it.type.toString() }, { it.uuid }))
                        .map { entity ->
                            RenderEntity(
                                uuid = entity.uuid,
                                type = entity.type.toString(),
                                name = playerNames[entity.uuid]?.name,
                                position = entity.position.copy(),
                                yaw = entity.yaw,
                                pitch = entity.pitch,
                                dimension = entity.dimension.toString(),
                                special =
                                    entity
                                        .toJson(sandbox.profile)
                                        .asJsonObject
                                        .get("special")
                                        ?.takeIf { it.isJsonObject }
                                        ?.asJsonObject
                                        ?.deepCopy(),
                                nbt = entity.nbt.deepCopy(),
                                equipment = entity.equipment.toSortedMap().mapValues { (_, item) -> item.id.toString() },
                            )
                        },
                biomes = world.biomes.toSortedMap().mapValues { (_, biome) -> biome.toString() },
                worldSpawn = world.worldSpawn.position.copy(),
            )
        }
    }
}
