package net.datapacksandbox.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object NbtSchemas {
    val generatedEntityKeys = setOf("id", "UUID", "Pos", "Tags")
    private val generatedBlockEntityKeys = setOf("id", "x", "y", "z")
    private val loadedSchema: LoadedNbtSchemas? = loadGeneratedSchema()

    private val commonEntityKeys = generatedEntityKeys + setOf(
        "Motion",
        "Rotation",
        "FallDistance",
        "Fire",
        "Air",
        "OnGround",
        "NoGravity",
        "Invulnerable",
        "PortalCooldown",
        "Silent",
        "Glowing",
        "CustomName",
        "CustomNameVisible",
        "Passengers",
        "Team",
        "Health",
        "HurtTime",
        "HurtByTimestamp",
        "DeathTime",
        "AbsorptionAmount",
        "Attributes",
        "ActiveEffects",
        "Brain",
        "HandItems",
        "ArmorItems",
        "HandDropChances",
        "ArmorDropChances",
        "CanPickUpLoot",
        "PersistenceRequired",
        "LeftHanded",
        "Leash",
        "NoAI",
        "Age",
        "ForcedAge",
        "InLove",
        "CannotEnterHiveTicks",
        "Saddle",
        "IsBaby",
        "CanBreakDoors",
        "ConversionTime",
        "DrownedConversionTime",
        "InWaterTime",
        "Item",
        "PickupDelay",
        "Owner",
        "Thrower",
    )

    private val playerKeys = commonEntityKeys + setOf(
        "Name",
        "Dimension",
        "playerGameType",
        "previousPlayerGameType",
        "SelectedItemSlot",
        "SelectedItem",
        "Inventory",
        "EnderItems",
        "abilities",
        "XpLevel",
        "XpP",
        "XpTotal",
        "XpSeed",
        "foodLevel",
        "foodTickTimer",
        "foodSaturationLevel",
        "foodExhaustionLevel",
        "recipeBook",
        "seenCredits",
        "ShoulderEntityLeft",
        "ShoulderEntityRight",
        "enteredNetherPosition",
        "RootVehicle",
        "SpawnX",
        "SpawnY",
        "SpawnZ",
        "SpawnDimension",
        "SpawnForced",
        "warden_spawn_tracker",
    )

    private val commonBlockEntityKeys = generatedBlockEntityKeys + setOf(
        "CustomName",
        "Lock",
        "LootTable",
        "LootTableSeed",
        "keepPacked",
        "components",
    )

    private val containerKeys = commonBlockEntityKeys + setOf("Items")
    private val furnaceKeys = containerKeys + setOf("BurnTime", "CookTime", "CookTimeTotal", "RecipesUsed")
    private val signKeys = commonBlockEntityKeys + setOf("front_text", "back_text", "is_waxed")
    private val fallbackItemStackKeys = setOf("Slot", "slot", "id", "count", "components")
    private val broadKnownBlockEntityKeys = setOf(
        "Items",
        "BurnTime",
        "CookTime",
        "CookTimeTotal",
        "RecipesUsed",
        "primary_effect",
        "secondary_effect",
        "Levels",
        "SpawnData",
        "SpawnPotentials",
        "Delay",
        "MinSpawnDelay",
        "MaxSpawnDelay",
        "SpawnCount",
        "MaxNearbyEntities",
        "RequiredPlayerRange",
        "SpawnRange",
        "RecordItem",
        "Book",
        "Page",
        "patterns",
        "note_block_sound",
        "profile",
        "powered",
        "OutputSignal",
        "last_vibration_frequency",
        "warning_level",
        "listener",
        "final_state",
        "joint",
        "pool",
        "name",
        "target",
        "ignore_entities",
        "show_air",
        "show_bounding_box",
        "mode",
        "posX",
        "posY",
        "posZ",
        "sizeX",
        "sizeY",
        "sizeZ",
        "integrity",
        "seed",
        "author",
        "metadata",
    )

    private val fallbackBlockEntitySchemas: List<BlockEntitySchema> = listOf(
        BlockEntitySchema({ it.path == "chest" || it.path == "trapped_chest" }, { ResourceLocation("minecraft", "chest") }, containerKeys) {
            it.ensureArray("Items")
        },
        BlockEntitySchema({ it.path == "barrel" }, { ResourceLocation("minecraft", "barrel") }, containerKeys) {
            it.ensureArray("Items")
        },
        BlockEntitySchema({ it.path.endsWith("shulker_box") }, { ResourceLocation("minecraft", "shulker_box") }, containerKeys) {
            it.ensureArray("Items")
        },
        BlockEntitySchema({ it.path in setOf("dispenser", "dropper", "hopper") }, { id -> ResourceLocation("minecraft", id.path) }, containerKeys) {
            it.ensureArray("Items")
        },
        BlockEntitySchema({ it.path in setOf("furnace", "blast_furnace", "smoker") }, { id -> ResourceLocation("minecraft", id.path) }, furnaceKeys) {
            it.ensureArray("Items")
        },
        BlockEntitySchema({ it.path in setOf("brewing_stand", "crafter", "decorated_pot") }, { id -> ResourceLocation("minecraft", id.path) }, containerKeys) {
            it.ensureArray("Items")
        },
        BlockEntitySchema({ it.path.endsWith("hanging_sign") }, { ResourceLocation("minecraft", "hanging_sign") }, signKeys),
        BlockEntitySchema({ it.path.endsWith("sign") }, { ResourceLocation("minecraft", "sign") }, signKeys),
        BlockEntitySchema({ it.path in setOf("beacon", "bed", "bell", "campfire", "command_block", "comparator", "conduit", "daylight_detector", "enchanting_table", "end_gateway", "end_portal", "ender_chest", "jigsaw", "jukebox", "lectern", "mob_spawner", "piston", "sculk_catalyst", "sculk_sensor", "sculk_shrieker", "skull", "structure_block", "trial_spawner", "vault") }, { id -> ResourceLocation("minecraft", id.path) }, commonBlockEntityKeys + broadKnownBlockEntityKeys),
    )

    fun entityNbt(entity: SandboxEntity, location: SourceLocation? = null): JsonObject {
        val json = JsonObject()
        addEntityDefaults(entity, json)
        entity.nbt.entrySet().forEach { (key, value) -> json.add(key, value.deepCopy()) }
        json.addProperty("id", entity.type.toString())
        json.addProperty("UUID", entity.uuid)
        json.add("Pos", entity.position.toNbtArray())
        if (entity.tags.isNotEmpty()) {
            val tagsJson = JsonArray()
            entity.tags.sorted().forEach { tagsJson.add(it) }
            json.add("Tags", tagsJson)
        }
        validateEntity(entity, json, location)
        return json
    }

    fun writeEntityNbt(entity: SandboxEntity, updated: JsonObject, location: SourceLocation? = null) {
        validateEntity(entity, updated, location)
        updated.getAsJsonArray("Pos")?.let { entity.position = it.toPosition() }
        updated.getAsJsonArray("Tags")?.let { tags ->
            entity.tags.replaceWith(tags.mapNotNull { tag -> tag.takeIf { it.isJsonPrimitive }?.asString })
        }

        entity.nbt.clearObject()
        updated.entrySet()
            .filterNot { (key, _) -> key in generatedEntityKeys }
            .filterNot { (key, value) -> isDefaultEntityKey(key) && isDefaultEntityValue(key, value) }
            .forEach { (key, value) -> entity.nbt.add(key, value.deepCopy()) }
    }

    fun validateEntity(entity: SandboxEntity, nbt: JsonObject, location: SourceLocation? = null) {
        val allowed = allowedEntityKeys(entity)
        val unknown = nbt.entrySet().map { it.key }.filterNot { it in allowed }.sorted()
        if (unknown.isNotEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "Unknown NBT field(s) for entity ${entity.type}: ${unknown.joinToString()}; custom entity NBT fields are not allowed",
                location,
            )
        }
        validateItemArray(nbt.get("HandItems"), "entity ${entity.type}.HandItems", location)
        validateItemArray(nbt.get("ArmorItems"), "entity ${entity.type}.ArmorItems", location)
        nbt.getAsJsonObject("Item")?.let { validateItemStack(it, "entity ${entity.type}.Item", location) }
    }

    fun blockEntityNbt(block: SandboxBlock, pos: BlockPos, location: SourceLocation? = null): JsonObject {
        val schema = requireBlockEntitySchema(block, pos, location)
        val json = JsonObject()
        json.addProperty("id", schema.blockEntityId(block.id).toString())
        json.addProperty("x", pos.x)
        json.addProperty("y", pos.y)
        json.addProperty("z", pos.z)
        schema.addDefaults(json)
        block.nbt.entrySet().forEach { (key, value) -> json.add(key, value.deepCopy()) }
        validateBlockEntity(block, pos, json, location)
        return json
    }

    fun writeBlockEntityNbt(block: SandboxBlock, pos: BlockPos, updated: JsonObject, location: SourceLocation? = null) {
        validateBlockEntity(block, pos, updated, location)
        block.nbt.clearObject()
        updated.entrySet()
            .filterNot { (key, _) -> key in generatedBlockEntityKeys }
            .filterNot { (key, value) -> key == "Items" && value.isJsonArray && value.asJsonArray.size() == 0 }
            .forEach { (key, value) -> block.nbt.add(key, value.deepCopy()) }
    }

    fun validateBlockEntity(block: SandboxBlock, pos: BlockPos, nbt: JsonObject, location: SourceLocation? = null) {
        val schema = requireBlockEntitySchema(block, pos, location)
        val unknown = nbt.entrySet().map { it.key }.filterNot { it in schema.allowedKeys }.sorted()
        if (unknown.isNotEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "Unknown NBT field(s) for block entity ${schema.blockEntityId(block.id)} at $pos: ${unknown.joinToString()}; custom block NBT fields are not allowed",
                location,
            )
        }
        validateItemArray(nbt.get("Items"), "block entity ${schema.blockEntityId(block.id)} at $pos Items", location)
    }

    fun hasBlockEntity(block: SandboxBlock): Boolean = schemaForBlock(block.id) != null

    private fun requireBlockEntitySchema(block: SandboxBlock, pos: BlockPos, location: SourceLocation?): BlockEntitySchema =
        schemaForBlock(block.id) ?: throw SandboxException(
            DiagnosticCode.COMMAND_ERROR,
            "Block ${block.id} at $pos does not expose block entity NBT",
            location,
        )

    private fun schemaForBlock(id: ResourceLocation): BlockEntitySchema? =
        if (id.namespace == "minecraft") {
            loadedSchema?.blockEntitySchemaForBlock(id) ?: fallbackBlockEntitySchemas.firstOrNull { it.matches(id) }
        } else {
            null
        }

    private fun addEntityDefaults(entity: SandboxEntity, json: JsonObject) {
        val allowed = allowedEntityKeys(entity)
        json.addIfAllowed(allowed, "Motion", zeroVector())
        json.addIfAllowed(allowed, "Rotation", JsonArray().also {
            it.add(0.0)
            it.add(0.0)
        })
        json.addPropertyIfAllowed(allowed, "FallDistance", 0.0)
        json.addPropertyIfAllowed(allowed, "Fire", -20)
        json.addPropertyIfAllowed(allowed, "Air", 300)
        json.addPropertyIfAllowed(allowed, "OnGround", false)
        json.addPropertyIfAllowed(allowed, "NoGravity", false)
        json.addPropertyIfAllowed(allowed, "Invulnerable", false)
        json.addPropertyIfAllowed(allowed, "PortalCooldown", 0)
        json.addPropertyIfAllowed(allowed, "Silent", false)
        json.addPropertyIfAllowed(allowed, "Glowing", false)
        if (entity.type != ResourceLocation("minecraft", "marker")) {
            json.addPropertyIfAllowed(allowed, "Health", defaultHealth(entity.type))
            json.addPropertyIfAllowed(allowed, "HurtTime", 0)
            json.addPropertyIfAllowed(allowed, "HurtByTimestamp", 0)
            json.addPropertyIfAllowed(allowed, "DeathTime", 0)
            json.addPropertyIfAllowed(allowed, "AbsorptionAmount", 0.0)
            json.addIfAllowed(allowed, "ActiveEffects", JsonArray())
            json.addIfAllowed(allowed, "HandItems", JsonArray().also {
                it.add(JsonObject())
                it.add(JsonObject())
            })
            json.addIfAllowed(allowed, "ArmorItems", JsonArray().also { repeat(4) { _ -> it.add(JsonObject()) } })
            json.addIfAllowed(allowed, "HandDropChances", JsonArray().also {
                it.add(0.085)
                it.add(0.085)
            })
            json.addIfAllowed(allowed, "ArmorDropChances", JsonArray().also { repeat(4) { _ -> it.add(0.085) } })
            json.addPropertyIfAllowed(allowed, "CanPickUpLoot", false)
            json.addPropertyIfAllowed(allowed, "PersistenceRequired", false)
        }
    }

    private fun defaultHealth(type: ResourceLocation): Double =
        when (type.toString()) {
            "minecraft:pig", "minecraft:cow", "minecraft:sheep", "minecraft:chicken" -> 10.0
            "minecraft:player", "minecraft:zombie", "minecraft:skeleton", "minecraft:creeper" -> 20.0
            else -> 20.0
        }

    private fun isDefaultEntityKey(key: String): Boolean =
        key in setOf(
            "Motion",
            "Rotation",
            "FallDistance",
            "Fire",
            "Air",
            "OnGround",
            "NoGravity",
            "Invulnerable",
            "PortalCooldown",
            "Silent",
            "Glowing",
            "HurtTime",
            "HurtByTimestamp",
            "DeathTime",
            "AbsorptionAmount",
            "ActiveEffects",
            "HandItems",
            "ArmorItems",
            "HandDropChances",
            "ArmorDropChances",
            "CanPickUpLoot",
            "PersistenceRequired",
        )

    private fun isDefaultEntityValue(key: String, value: JsonElement): Boolean =
        when (key) {
            "FallDistance", "AbsorptionAmount" -> value.isNumber(0.0)
            "Fire" -> value.isNumber(-20.0)
            "Air" -> value.isNumber(300.0)
            "PortalCooldown", "HurtTime", "HurtByTimestamp", "DeathTime" -> value.isNumber(0.0)
            "OnGround", "NoGravity", "Invulnerable", "Silent", "Glowing", "CanPickUpLoot", "PersistenceRequired" -> value.isBoolean(false)
            "Motion", "Rotation", "ActiveEffects", "HandItems", "ArmorItems", "HandDropChances", "ArmorDropChances" -> true
            else -> false
        }

    private fun validateItemArray(value: JsonElement?, label: String, location: SourceLocation?) {
        if (value == null) return
        if (!value.isJsonArray) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be a list of item compounds", location)
        }
        value.asJsonArray.forEachIndexed { index, element ->
            if (element.isJsonObject && element.asJsonObject.entrySet().isEmpty()) return@forEachIndexed
            if (!element.isJsonObject) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label[$index] must be an item compound", location)
            }
            validateItemStack(element.asJsonObject, "$label[$index]", location)
        }
    }

    private fun validateItemStack(item: JsonObject, label: String, location: SourceLocation?) {
        val itemStackKeys = loadedSchema?.itemStackFields?.let { it + setOf("slot") } ?: fallbackItemStackKeys
        val unknown = item.entrySet().map { it.key }.filterNot { it in itemStackKeys }.sorted()
        if (unknown.isNotEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "Unknown NBT field(s) for item stack $label: ${unknown.joinToString()}; use id, count, Slot, and components",
                location,
            )
        }
        item.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.let { ResourceLocation.parse(it) }
        item.get("components")?.let {
            if (!it.isJsonObject) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item stack $label components must be an object", location)
            }
        }
    }

    private data class BlockEntitySchema(
        val matches: (ResourceLocation) -> Boolean,
        val blockEntityId: (ResourceLocation) -> ResourceLocation,
        val allowedKeys: Set<String>,
        val addDefaults: (JsonObject) -> Unit = {},
    )

    private data class LoadedNbtSchemas(
        val itemStackFields: Set<String>,
        val entitySchemas: Map<ResourceLocation, Set<String>>,
        val blockEntitySchemas: Map<ResourceLocation, Set<String>>,
        val blockToBlockEntity: Map<ResourceLocation, ResourceLocation>,
    ) {
        fun blockEntitySchemaForBlock(id: ResourceLocation): BlockEntitySchema? {
            val blockEntityId = blockToBlockEntity[id] ?: return null
            val keys = blockEntitySchemas[blockEntityId] ?: return null
            val allowed = keys + generatedBlockEntityKeys
            return BlockEntitySchema(
                matches = { it == id },
                blockEntityId = { blockEntityId },
                allowedKeys = allowed,
                addDefaults = { json ->
                    if ("Items" in allowed) json.ensureArray("Items")
                },
            )
        }
    }

    private fun allowedEntityKeys(entity: SandboxEntity): Set<String> {
        val schemaId = if (entity is SandboxPlayer) ResourceLocation("minecraft", "player") else entity.type
        val fallback = if (entity is SandboxPlayer) playerKeys else commonEntityKeys
        val loaded = loadedSchema?.entitySchemas?.get(schemaId)
        val synthetic = if (entity is SandboxPlayer) setOf("Name") else emptySet()
        return (loaded ?: fallback) + generatedEntityKeys + synthetic
    }

    private fun loadGeneratedSchema(): LoadedNbtSchemas? {
        val stream = NbtSchemas::class.java.classLoader.getResourceAsStream("vanilla-nbt-schemas.json") ?: return null
        return runCatching {
            InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                val root = JsonParser.parseReader(reader).asJsonObject
                LoadedNbtSchemas(
                    itemStackFields = root.stringSet("itemStackFields"),
                    entitySchemas = root.schemaMap("entitySchemas"),
                    blockEntitySchemas = root.schemaMap("blockEntitySchemas"),
                    blockToBlockEntity = root.resourceLocationMap("blockToBlockEntity"),
                )
            }
        }.getOrNull()
    }

    private fun JsonObject.stringSet(name: String): Set<String> =
        get(name)
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString }
            ?.toSet()
            .orEmpty()

    private fun JsonObject.schemaMap(name: String): Map<ResourceLocation, Set<String>> {
        val json = get(name)?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
        val result = linkedMapOf<ResourceLocation, Set<String>>()
        json.entrySet().forEach { (id, value) ->
            val resource = runCatching { ResourceLocation.parse(id) }.getOrNull() ?: return@forEach
            val fields = value.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { it.takeIf { field -> field.isJsonPrimitive }?.asString }
                ?.toSet()
                .orEmpty()
            result[resource] = fields
        }
        return result
    }

    private fun JsonObject.resourceLocationMap(name: String): Map<ResourceLocation, ResourceLocation> {
        val json = get(name)?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
        val result = linkedMapOf<ResourceLocation, ResourceLocation>()
        json.entrySet().forEach { (key, value) ->
            if (!value.isJsonPrimitive) return@forEach
            val source = runCatching { ResourceLocation.parse(key) }.getOrNull() ?: return@forEach
            val target = runCatching { ResourceLocation.parse(value.asString) }.getOrNull() ?: return@forEach
            result[source] = target
        }
        return result
    }
}

private fun Position.toNbtArray(): JsonArray =
    JsonArray().also {
        it.add(x)
        it.add(y)
        it.add(z)
    }

private fun JsonArray.toPosition(): Position =
    Position(
        if (size() > 0) get(0).asDouble else 0.0,
        if (size() > 1) get(1).asDouble else 0.0,
        if (size() > 2) get(2).asDouble else 0.0,
    )

private fun zeroVector(): JsonArray =
    JsonArray().also {
        it.add(0.0)
        it.add(0.0)
        it.add(0.0)
    }

private fun JsonObject.ensureArray(key: String) {
    if (!has(key)) add(key, JsonArray())
}

private fun JsonObject.addIfAllowed(allowedKeys: Set<String>, key: String, value: JsonElement) {
    if (key in allowedKeys) add(key, value)
}

private fun JsonObject.addPropertyIfAllowed(allowedKeys: Set<String>, key: String, value: Number) {
    if (key in allowedKeys) addProperty(key, value)
}

private fun JsonObject.addPropertyIfAllowed(allowedKeys: Set<String>, key: String, value: Boolean) {
    if (key in allowedKeys) addProperty(key, value)
}

private fun JsonElement.isNumber(expected: Double): Boolean =
    isJsonPrimitive && asJsonPrimitive.isNumber && asDouble == expected

private fun JsonElement.isBoolean(expected: Boolean): Boolean =
    isJsonPrimitive && asJsonPrimitive.isBoolean && asBoolean == expected

private fun <T> MutableSet<T>.replaceWith(values: Iterable<T>) {
    clear()
    addAll(values)
}

private fun JsonObject.clearObject() {
    entrySet().map { it.key }.toList().forEach { remove(it) }
}
