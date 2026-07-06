package moe.afox.dpsandbox.core

import com.google.gson.JsonObject

/**
 * Recorded keyboard or mouse input for a sandbox player.
 *
 * Input events are observable test fixtures. They do not simulate a real client
 * input loop, physics, or movement by themselves.
 */
data class PlayerInput(
    /** Input device name, usually `keyboard` or `mouse`. */
    val device: String,
    /** Key or button code, for example `key.jump` or `left`. */
    val code: String,
    /** Input action such as `press`, `release`, `click`, or `move`. */
    val action: String = "press",
    /** Optional pointer x coordinate recorded as metadata. */
    val x: Double? = null,
    /** Optional pointer y coordinate recorded as metadata. */
    val y: Double? = null,
) {
    /**
     * Serializes this input record into snapshot JSON.
     */
    fun toJson(): JsonObject =
        JsonObject().also { json ->
            json.addProperty("device", device)
            json.addProperty("code", code)
            json.addProperty("action", action)
            x?.let { json.addProperty("x", it) }
            y?.let { json.addProperty("y", it) }
        }
}

/**
 * Sandbox-visible player behavior event.
 *
 * Events feed advancement triggers, predicate contexts, loot contexts, and
 * snapshots. They represent high-level datapack-visible actions rather than
 * low-level client packets or vanilla physics.
 */
data class PlayerEvent(
    /** Player that owns the event. The player must exist when the event is handled. */
    val playerName: String,
    /** Event type. Hyphenated names are normalized to underscores by [normalized]. */
    val type: String,
    /** Optional item context, used by item and inventory events. */
    val item: ItemStack? = null,
    /** Optional entity context, used by interaction, kill, and damage-source events. */
    val entity: SandboxEntity? = null,
    /** Optional damage amount context for damage and death events. */
    val damageAmount: Double? = null,
    /** Optional damage source id for damage and death events. */
    val damageSource: ResourceLocation? = null,
    /** Optional block id context, used by place/break block events. */
    val block: ResourceLocation? = null,
    /** Optional source dimension for dimension-change events. */
    val fromDimension: ResourceLocation? = null,
    /** Optional destination dimension for dimension-change events. */
    val toDimension: ResourceLocation? = null,
    /** Optional recipe id for recipe events. */
    val recipe: ResourceLocation? = null,
    /** Optional keyboard or mouse input metadata. */
    val input: PlayerInput? = null,
    /** Optional target block position for place/break block events. */
    val blockPos: BlockPos? = null,
) {
    /**
     * Returns a copy with [type] normalized from hyphen naming to underscore naming.
     */
    fun normalized(): PlayerEvent =
        copy(type = type.replace('-', '_'))
}

/**
 * Factory helpers for common [PlayerEvent] values.
 */
object PlayerEvents {
    /**
     * Creates a player event from the compact CLI/REPL shape.
     *
     * @param playerName Target player name.
     * @param type Event type. Hyphen and underscore names are both accepted.
     * @param id Optional resource id or input code interpreted by [type].
     * @param action Optional keyboard/mouse action override.
     */
    @JvmStatic
    @JvmOverloads
    fun shorthand(playerName: String, type: String, id: String? = null, action: String? = null): PlayerEvent {
        val normalizedType = type.replace('-', '_')
        return when {
            normalizedType.isKeyboardInputType() -> keyInput(playerName, id ?: "unknown", action ?: defaultKeyboardAction(normalizedType), normalizedType)
            normalizedType.isMouseInputType() -> mouseInput(playerName, id ?: "left", action ?: defaultMouseAction(normalizedType), type = normalizedType)
            else -> vanillaVisibleEvent(playerName, normalizedType, id, action)
        }
    }

    /**
     * Creates a keyboard input event.
     *
     * The returned event records input metadata and may be consumed by custom
     * sandbox advancement triggers. It does not move the player or simulate
     * vanilla client behavior.
     */
    @JvmStatic
    @JvmOverloads
    fun keyInput(playerName: String, key: String, action: String = "press", type: String = "key_input"): PlayerEvent =
        PlayerEvent(
            playerName = playerName,
            type = type.replace('-', '_'),
            input = PlayerInput(device = "keyboard", code = key, action = action),
        )

    /**
     * Creates a mouse input event.
     *
     * Optional [x] and [y] values are recorded as metadata only.
     */
    @JvmStatic
    @JvmOverloads
    fun mouseInput(
        playerName: String,
        button: String,
        action: String = "click",
        x: Double? = null,
        y: Double? = null,
        type: String = "mouse_input",
    ): PlayerEvent =
        PlayerEvent(
            playerName = playerName,
            type = type.replace('-', '_'),
            input = PlayerInput(device = "mouse", code = button, action = action, x = x, y = y),
        )

    private fun vanillaVisibleEvent(playerName: String, type: String, id: String?, detail: String?): PlayerEvent {
        val resource = id?.let(ResourceLocation::parse)
        val damageAmount = if (type in damageEventTypes) {
            detail?.toDoubleOrNull() ?: detail?.let {
                throw IllegalArgumentException("Damage event detail must be a number, got '$it'")
            }
        } else {
            null
        }
        return PlayerEvent(
            playerName = playerName,
            type = type,
            item = if (type in itemEventTypes) resource?.let { ItemStack(it) } else null,
            entity = if (type.contains("kill") || type.contains("interact")) resource?.let { SandboxEntity(type = it) } else null,
            damageAmount = damageAmount,
            damageSource = if (type in damageEventTypes) resource else null,
            block = if (type.contains("block")) resource else null,
            recipe = if (type.contains("recipe")) resource else null,
            fromDimension = if (type == "changed_dimension") resource else null,
            toDimension = if (type == "changed_dimension") detail?.let(ResourceLocation::parse) else null,
        )
    }

    private fun String.isKeyboardInputType(): Boolean =
        this in setOf("key_input", "keyboard_input", "key_pressed", "key_released")

    private fun String.isMouseInputType(): Boolean =
        this in setOf("mouse_input", "mouse_button", "mouse_clicked", "mouse_released", "mouse_moved")

    private fun defaultKeyboardAction(type: String): String =
        when (type) {
            "key_released" -> "release"
            else -> "press"
        }

    private fun defaultMouseAction(type: String): String =
        when (type) {
            "mouse_released" -> "release"
            "mouse_moved" -> "move"
            else -> "click"
        }

    private val itemEventTypes = setOf(
        "item_used",
        "using_item",
        "item_used_on_block",
        "item_consumed",
        "consume_item",
        "inventory_changed",
        "item_picked_up",
        "item_added",
    )

    private val damageEventTypes = setOf("damage", "death")
}
