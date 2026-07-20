package moe.afox.dpsandbox.core.command
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import moe.afox.dpsandbox.core.CommandToken
import moe.afox.dpsandbox.core.CommandTokenizer
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.EntitySelectors
import moe.afox.dpsandbox.core.ExecutionContext
import moe.afox.dpsandbox.core.PlayerEvent
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxEntity
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SandboxPlayer
import moe.afox.dpsandbox.core.SourceLocation
import moe.afox.dpsandbox.core.unsupportedFeature

internal fun DatapackSandbox.executeTag(
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 3, "tag <targets> <add|remove|list> [name]", location)
    val entities = EntitySelectors.select(world, tokens[1].text, context, location)
    when (tokens[2].text) {
        "add" -> {
            requireSize(tokens, 4, "tag <targets> add <name>", location)
            entities.forEach { it.tags += tokens[3].text }
        }
        "remove" -> {
            requireSize(tokens, 4, "tag <targets> remove <name>", location)
            entities.forEach { it.tags -= tokens[3].text }
        }
        "list" -> Unit
        else -> unsupportedFeature("Unsupported tag action '${tokens[2].text}'", profile.id, location)
    }
}

internal fun DatapackSandbox.executeSummon(
    command: String,
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 2, "summon <entity> [x y z] [nbt]", location)
    val type = ResourceLocation.parse(tokens[1].text)
    var position = context.position
    var nbtStartIndex = 2
    if (isCoordinateTriple(tokens, 2)) {
        position = parsePosition(tokens, 2, context, location)
        nbtStartIndex = 5
    }
    val nbt =
        if (tokens.size >
            nbtStartIndex
        ) {
            parseSummonNbt(CommandTokenizer.tailFrom(command, tokens[nbtStartIndex]), location)
        } else {
            JsonObject()
        }
    val tags = extractTags(nbt).toMutableSet()
    val entity = SandboxEntity(type = type, position = position, tags = tags, dimension = context.dimension)
    val fullNbt = entity.fullNbt(profile, location)
    nbt.entrySet().forEach { (key, value) -> fullNbt.add(key, value.deepCopy()) }
    entity.writeFullNbt(profile, fullNbt, location)
    world.entities += entity
    world.recordOutput(
        "summon",
        "data",
        targets = listOf(entity.scoreHolder),
        text = "1",
        payload =
            JsonObject().also { payload ->
                payload.addProperty("count", 1)
                payload.addProperty("target", entity.scoreHolder)
                payload.addProperty("uuid", entity.uuid)
                payload.addProperty("type", entity.type.toString())
                payload.addProperty("dimension", entity.dimension.toString())
                payload.addDimensionMetadata("dimensionResource", entity.dimension, location)
                payload.add("position", positionOutput(entity.position))
                payload.add("tags", JsonArray().also { tagsArray -> entity.tags.forEach { tagsArray.add(it) } })
                payload.add("nbt", nbt.deepCopy())
                entityVariantPayloads(entity.type, fullNbt, location)?.let { payload.add("variantResources", it) }
            },
    )
}

internal fun DatapackSandbox.entityVariantPayloads(
    entityType: ResourceLocation,
    nbt: JsonObject,
    location: SourceLocation?,
): JsonArray? {
    val variants = JsonArray()
    entityVariantResourceFields(entityType).forEach { (field, kind) ->
        val value = nbt.get(field)?.takeIf { it.isJsonPrimitive } ?: return@forEach
        val id = runCatching { ResourceLocation.parse(value.asString) }.getOrNull() ?: return@forEach
        val resource = datapack.rawResources[kind]?.get(id) ?: return@forEach
        if (!resource.root.isJsonObject) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "Entity variant resource '$id' of type '$kind' must be a JSON object",
                location,
            )
        }
        variants.add(
            JsonObject().also { payload ->
                payload.addProperty("type", kind)
                payload.addProperty("field", field)
                payload.addProperty("id", id.toString())
                payload.addProperty("resource", resource.file)
                payload.addProperty("version", resource.version ?: profile.id)
                payload.add("definition", resource.root.deepCopy())
            },
        )
    }
    return variants.takeIf { it.size() > 0 }
}

internal fun DatapackSandbox.entityVariantResourceFields(entityType: ResourceLocation): List<Pair<String, String>> =
    when (entityType.toString()) {
        "minecraft:cat" -> listOf("variant" to "cat_variant", "Variant" to "cat_variant")
        "minecraft:chicken" -> listOf("variant" to "chicken_variant", "Variant" to "chicken_variant")
        "minecraft:cow" -> listOf("variant" to "cow_variant", "Variant" to "cow_variant")
        "minecraft:frog" -> listOf("variant" to "frog_variant", "Variant" to "frog_variant")
        "minecraft:painting" -> listOf("variant" to "painting_variant", "Variant" to "painting_variant")
        "minecraft:pig" -> listOf("variant" to "pig_variant", "Variant" to "pig_variant")
        "minecraft:wolf" ->
            listOf(
                "variant" to "wolf_variant",
                "Variant" to "wolf_variant",
                "sound_variant" to "wolf_sound_variant",
                "SoundVariant" to "wolf_sound_variant",
            )
        else -> emptyList()
    }

internal fun DatapackSandbox.executeKill(
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    val targetToken = tokens.getOrNull(1)?.text ?: "@s"
    val selected = EntitySelectors.select(world, targetToken, context, location).distinctBy { it.uuid }
    (context.entity as? SandboxPlayer)?.let { player ->
        selected.filterNot { it is SandboxPlayer }.forEach { target ->
            advancements.handle(PlayerEvent(player.name, "killed_entity", entity = target))
        }
    }
    world.recordOutput(
        "kill",
        "data",
        targets = selected.map { it.scoreHolder },
        text = selected.size.toString(),
        payload =
            JsonObject().also { payload ->
                payload.addProperty("count", selected.size)
                payload.add(
                    "targets",
                    JsonArray().also { targets ->
                        selected.forEach { entity ->
                            targets.add(
                                JsonObject().also { entry ->
                                    entry.addProperty("target", entity.scoreHolder)
                                    entry.addProperty("uuid", entity.uuid)
                                    entry.addProperty("type", entity.type.toString())
                                    entry.addProperty("dimension", entity.dimension.toString())
                                    entry.addDimensionMetadata("dimensionResource", entity.dimension, location)
                                    entry.addProperty("player", entity is SandboxPlayer)
                                },
                            )
                        }
                    },
                )
            },
    )
    val selectedUuids = selected.map { it.uuid }.toSet()
    world.entities.removeIf { it.uuid in selectedUuids }
}

internal fun DatapackSandbox.executeTeleport(
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 2, "${tokens[0].text} <targets> <location>|<destination>", location)
    when {
        tokens.size >= 4 && isCoordinateTriple(tokens, 1) -> {
            val entity =
                context.entity
                    ?: throw SandboxException(
                        DiagnosticCode.COMMAND_ERROR,
                        "${tokens[0].text} <location> requires an execution entity",
                        location,
                    )
            val position =
                parsePosition(
                    tokens,
                    1,
                    entity.position,
                    location,
                    context.yaw,
                    context.pitch,
                    anchoredPosition(entity.position, entity, context.anchor),
                )
            val rotation = parseOptionalTeleportRotation(tokens, 4, position, context, location)
            val moved = moveEntities(listOf(entity), position, rotation, context.dimension)
            recordTeleportOutput(tokens[0].text, moved)
        }
        tokens.size >= 5 && isCoordinateTriple(tokens, 2) -> {
            val targets = EntitySelectors.select(world, tokens[1].text, context, location)
            val position = parsePosition(tokens, 2, context, location)
            val rotation = parseOptionalTeleportRotation(tokens, 5, position, context, location)
            val moved = moveEntities(targets, position, rotation, context.dimension)
            recordTeleportOutput(tokens[0].text, moved)
        }
        tokens.size >= 3 -> {
            val targets = EntitySelectors.select(world, tokens[1].text, context, location)
            val destination =
                EntitySelectors.select(world, tokens[2].text, context, location).firstOrNull()
                    ?: throw SandboxException(
                        DiagnosticCode.COMMAND_ERROR,
                        "Teleport destination '${tokens[2].text}' did not match an entity",
                        location,
                    )
            val moved =
                moveEntities(
                    targets,
                    destination.position,
                    DatapackSandbox.Rotation(destination.yaw, destination.pitch),
                    destination.dimension,
                )
            recordTeleportOutput(tokens[0].text, moved)
        }
        else -> unsupportedFeature("Unsupported ${tokens[0].text} form", profile.id, location)
    }
}
