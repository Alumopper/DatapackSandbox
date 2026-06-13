package moe.afox.dpsandbox.core

import com.google.gson.JsonObject

data class PlayerInput(
    val device: String,
    val code: String,
    val action: String = "press",
    val x: Double? = null,
    val y: Double? = null,
) {
    fun toJson(): JsonObject =
        JsonObject().also { json ->
            json.addProperty("device", device)
            json.addProperty("code", code)
            json.addProperty("action", action)
            x?.let { json.addProperty("x", it) }
            y?.let { json.addProperty("y", it) }
        }
}

data class PlayerEvent(
    val playerName: String,
    val type: String,
    val item: ItemStack? = null,
    val entity: SandboxEntity? = null,
    val block: ResourceLocation? = null,
    val fromDimension: ResourceLocation? = null,
    val toDimension: ResourceLocation? = null,
    val recipe: ResourceLocation? = null,
    val input: PlayerInput? = null,
) {
    fun normalized(): PlayerEvent =
        copy(type = type.replace('-', '_'))
}

object PlayerEvents {
    @JvmStatic
    @JvmOverloads
    fun shorthand(playerName: String, type: String, id: String? = null, action: String? = null): PlayerEvent {
        val normalizedType = type.replace('-', '_')
        return when {
            normalizedType.isKeyboardInputType() -> keyInput(playerName, id ?: "unknown", action ?: defaultKeyboardAction(normalizedType), normalizedType)
            normalizedType.isMouseInputType() -> mouseInput(playerName, id ?: "left", action ?: defaultMouseAction(normalizedType), type = normalizedType)
            else -> vanillaVisibleEvent(playerName, normalizedType, id)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun keyInput(playerName: String, key: String, action: String = "press", type: String = "key_input"): PlayerEvent =
        PlayerEvent(
            playerName = playerName,
            type = type.replace('-', '_'),
            input = PlayerInput(device = "keyboard", code = key, action = action),
        )

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

    private fun vanillaVisibleEvent(playerName: String, type: String, id: String?): PlayerEvent {
        val resource = id?.let(ResourceLocation::parse)
        return PlayerEvent(
            playerName = playerName,
            type = type,
            item = resource?.let { ItemStack(it) },
            entity = if (type.contains("kill")) resource?.let { SandboxEntity(type = it) } else null,
            block = if (type.contains("block")) resource else null,
            recipe = if (type.contains("recipe")) resource else null,
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
}
