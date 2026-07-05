package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.ResourceLocation

class DpsCompletionEngine(private val sandbox: () -> DatapackSandbox) {
    fun suggestions(buffer: String, cursor: Int = buffer.length): List<CompletionSuggestion> {
        val context = CompletionContext.parse(buffer, cursor)
        val words = context.words
        val first = context.first
        val box = sandbox()
        val rootCommands = DpsCommandCatalog.rootCommands(box.profile)
        val options = when {
            first.isBlank() || context.wordIndex == 0 -> rootCommands
            first == "help" -> rootCommands
            first == "load" && context.wordIndex == 1 -> listOf("fixture").suggest("load actions", appendSpace = true)
            first == "function" && context.wordIndex == 1 -> box.datapack.functions.keys.mapResource("functions")
            first == "trace" && context.wordIndex == 1 -> listOf("on", "off", "status").suggest("trace modes")
            first == "diff" && context.wordIndex == 1 -> listOf("last").suggest("diff targets")
            first == "rerun" && context.wordIndex == 1 -> listOf("last").suggest("rerun targets")
            first == "reset" && context.wordIndex == 1 -> listOf("world").suggest("reset targets")
            first == "inspect" -> inspectSuggestions(words, context)
            first == "player" && context.wordIndex == 1 -> playerTargets(includeSelectors = false).suggest("players")
            first == "event" -> eventSuggestions(words, context)
            first in chatTargetCommands && context.wordIndex == 1 -> playerTargets().suggest("players/selectors", appendSpace = true)
            first == "title" -> titleSuggestions(context)
            first == "playsound" -> playSoundSuggestions(context)
            first == "scoreboard" -> scoreboardSuggestions(words, context)
            first == "execute" -> executeSuggestions(words, context)
            first in setOf("tp", "teleport") -> teleportSuggestions(context)
            first == "setblock" && context.wordIndex == 4 -> box.profile.registryView.blocks.mapResource("blocks")
            first == "fill" -> fillSuggestions(context)
            first == "data" -> dataSuggestions(words, context)
            first == "tag" -> tagSuggestions(context)
            first == "summon" && context.wordIndex == 1 -> box.profile.registryView.entityTypes.mapResource("entity types")
            first == "kill" && context.wordIndex == 1 -> entityTargets().suggest("entities/selectors")
            first == "advancement" -> advancementSuggestions(words, context)
            first == "schedule" -> scheduleSuggestions(context)
            first == "bossbar" -> bossbarSuggestions(words, context)
            first == "clear" -> clearSuggestions(context)
            first == "clone" -> cloneSuggestions(words, context)
            first == "damage" -> damageSuggestions(context)
            first == "effect" -> effectSuggestions(context)
            first == "enchant" -> enchantSuggestions(context)
            first in setOf("experience", "xp") -> experienceSuggestions(context)
            first == "gamerule" -> gameruleSuggestions(context)
            first == "give" -> giveSuggestions(context)
            first == "item" -> itemSuggestions(words, context)
            first == "random" -> randomSuggestions(context)
            first == "recipe" -> recipeSuggestions(context)
            first == "ride" -> rideSuggestions(context)
            first == "rotate" -> rotateSuggestions(context)
            first == "team" -> teamSuggestions(words, context)
            first == "time" -> timeSuggestions(words, context)
            first == "weather" -> weatherSuggestions(context)
            else -> emptyList()
        }
        return context.filter(options)
    }

    fun inlineHint(buffer: String, cursor: Int = buffer.length): String {
        val context = CompletionContext.parse(buffer, cursor)
        if (context.words.isEmpty()) {
            return "[load tick function inspect help]"
        }

        if (context.wordIndex == 0 && !context.endsWithWhitespace) {
            val exact = DpsCommandCatalog.rootCommands(sandbox().profile).firstOrNull {
                it.value == context.prefix.removePrefix("/")
            }
            if (exact != null) {
                return DpsCommandCatalog.usageSuffix(exact.value)
            }
        }

        val suggestions = suggestions(buffer, cursor).take(6)
        if (suggestions.isEmpty()) return ""

        val values = suggestions.map {
            if (context.wordIndex == 0) it.value.removePrefix("/") else it.value
        }
        return values.joinToString(prefix = "[", postfix = "]", separator = " ")
    }

    fun multilineHints(buffer: String, cursor: Int = buffer.length) =
        DpsMultilineHints.describe(buffer, cursor, sandbox().profile)

    private fun eventSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> listOf("player").suggest("event target", appendSpace = true)
            2 -> playerTargets(includeSelectors = false).suggest("players", appendSpace = true)
            3 -> eventTypes.suggest("event types", appendSpace = true)
            4 -> when (words.getOrNull(3)) {
                "item_used", "item_consumed", "inventory_changed", "item_picked_up" -> sandbox().profile.registryView.items.mapResource("items")
                "entity_interacted", "killed_entity", "entity_killed_player" -> sandbox().profile.registryView.entityTypes.mapResource("entity types")
                "damage", "death" -> sandbox().profile.registryView.damageTypes.mapResource("damage types")
                "placed_block", "broke_block" -> sandbox().profile.registryView.blocks.mapResource("blocks")
                "changed_dimension" -> sandbox().profile.registryView.dimensions.mapResource("dimensions")
                "recipe_unlocked" -> listOf("minecraft:bread", "minecraft:stick").suggest("recipes")
                "key_input", "key_pressed", "key_released" -> commonKeys.suggest("keys")
                "mouse_input", "mouse_clicked", "mouse_released", "mouse_moved" -> mouseButtons.suggest("mouse buttons")
                else -> emptyList()
            }
            5 -> when (words.getOrNull(3)) {
                "changed_dimension" -> sandbox().profile.registryView.dimensions.mapResource("dimensions")
                in inputEventTypes -> inputActions.suggest("input actions")
                else -> emptyList()
            }
            else -> emptyList()
        }

    private fun inspectSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
        when {
            context.wordIndex == 1 -> inspectTargets.suggest("inspect targets", appendSpace = true)
            words.getOrNull(1) == "player" -> playerTargets(includeSelectors = false).suggest("players")
            words.getOrNull(1) == "storage" -> storageTargets().suggest("storages")
            words.getOrNull(1) == "raw" && context.wordIndex == 2 -> rawResourceKinds().suggest("raw resource types", appendSpace = true)
            words.getOrNull(1) == "raw" && context.wordIndex == 3 -> rawResourceIds(words.getOrNull(2)).suggest("raw resources")
            words.getOrNull(1) in setOf("resource", "resources") && context.wordIndex == 2 -> resourceIndexTypes().suggest("resource types")
            else -> emptyList()
        }

    private fun titleSuggestions(context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> playerTargets().suggest("players/selectors", appendSpace = true)
            2 -> listOf("clear", "reset", "title", "subtitle", "actionbar", "times").suggest("title actions", appendSpace = true)
            else -> emptyList()
        }

    private fun playSoundSuggestions(context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> soundsOrFallback().suggest("sounds", appendSpace = true)
            2 -> soundSources.suggest("sound sources", appendSpace = true)
            3 -> playerTargets().suggest("players/selectors", appendSpace = true)
            else -> emptyList()
        }

    private fun scoreboardSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
        when {
            context.wordIndex == 1 -> listOf("objectives", "players").suggest("scoreboard groups", appendSpace = true)
            words.getOrNull(1) == "objectives" && context.wordIndex == 2 ->
                listOf("add", "remove", "list").suggest("objective actions", appendSpace = true)
            words.getOrNull(1) == "players" && context.wordIndex == 2 ->
                listOf("set", "add", "remove", "get", "reset", "list", "enable", "operation").suggest("player score actions", appendSpace = true)
            words.getOrNull(1) == "players" && context.wordIndex == 3 -> scoreTargets().suggest("score holders", appendSpace = true)
            words.getOrNull(1) == "players" && context.wordIndex == 4 -> scoreboardObjectives().suggest("objectives", appendSpace = true)
            words.getOrNull(1) == "players" && words.getOrNull(2) == "operation" && context.wordIndex == 5 ->
                listOf("=", "+=", "-=", "*=", "/=", "%=", "<", ">").suggest("score operations", appendSpace = true)
            else -> emptyList()
        }

    private fun executeSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> {
        val previous = words.getOrNull(context.wordIndex - 1)
        val beforePrevious = words.getOrNull(context.wordIndex - 2)
        return when {
            previous in setOf("as", "at") -> entityTargets().suggest("entities/selectors", appendSpace = true)
            previous == "run" -> DpsCommandCatalog.rootCommands(sandbox().profile)
            previous in setOf("if", "unless") -> listOf("entity", "score", "data", "block", "blocks", "predicate", "function", "dimension", "biome", "loaded").suggest("conditions", appendSpace = true)
            beforePrevious in setOf("if", "unless") && previous == "entity" -> entityTargets().suggest("entities/selectors", appendSpace = true)
            beforePrevious in setOf("if", "unless") && previous == "predicate" -> sandbox().datapack.predicates.keys.mapResource("predicates")
            beforePrevious in setOf("if", "unless") && previous == "function" -> functionConditionTargets().suggest("functions/tags")
            beforePrevious in setOf("if", "unless") && previous == "dimension" -> sandbox().profile.registryView.dimensions.mapResource("dimensions")
            words.getOrNull(context.wordIndex - 4) == "biome" -> sandbox().profile.registryView.biomes.mapResource("biomes")
            previous == "in" -> sandbox().profile.registryView.dimensions.mapResource("dimensions")
            previous == "store" -> listOf("result", "success").suggest("store modes", appendSpace = true)
            beforePrevious == "store" && previous in setOf("result", "success") ->
                listOf("score", "storage", "entity", "block", "bossbar").suggest("store targets", appendSpace = true)
            context.wordIndex >= 1 -> listOf(
                "as",
                "at",
                "positioned",
                "align",
                "anchored",
                "facing",
                "in",
                "rotated",
                "store",
                "if",
                "unless",
                "run",
            ).suggest("execute subcommands", appendSpace = true)
            else -> emptyList()
        }
    }

    private fun teleportSuggestions(context: CompletionContext): List<CompletionSuggestion> =
        if (context.wordIndex == 1 || context.wordIndex == 2) entityTargets().suggest("entities/selectors", appendSpace = true) else emptyList()

    private fun fillSuggestions(context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            7 -> sandbox().profile.registryView.blocks.mapResource("blocks")
            8 -> listOf("replace", "keep", "destroy", "hollow", "outline").suggest("fill modes", appendSpace = true)
            else -> emptyList()
        }

    private fun dataSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
        when {
            context.wordIndex == 1 -> listOf("modify", "merge", "get", "remove").suggest("data actions", appendSpace = true)
            context.wordIndex == 2 -> listOf("storage", "entity", "block").suggest("data targets", appendSpace = true)
            words.getOrNull(2) == "entity" && context.wordIndex == 3 -> entityTargets().suggest("entities/selectors", appendSpace = true)
            words.getOrNull(2) == "storage" && context.wordIndex == 3 -> storageTargets().suggest("storages", appendSpace = true)
            words.getOrNull(context.wordIndex - 1) in setOf("set", "merge", "append", "prepend") -> listOf("value", "from", "string").suggest("data source", appendSpace = true)
            words.getOrNull(context.wordIndex - 2) == "insert" -> listOf("value", "from", "string").suggest("data source", appendSpace = true)
            words.getOrNull(context.wordIndex - 1) in setOf("from", "string") -> listOf("storage", "entity", "block").suggest("data source targets", appendSpace = true)
            else -> emptyList()
        }

    private fun tagSuggestions(context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> entityTargets().suggest("entities/selectors", appendSpace = true)
            2 -> listOf("add", "remove", "list").suggest("tag actions", appendSpace = true)
            else -> knownTags().suggest("tags")
        }

    private fun advancementSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
        when {
            context.wordIndex == 1 -> listOf("grant", "revoke", "test").suggest("advancement actions", appendSpace = true)
            context.wordIndex == 2 -> playerTargets().suggest("players/selectors", appendSpace = true)
            words.getOrNull(1) == "test" && context.wordIndex == 3 -> sandbox().datapack.advancements.keys.mapResource("advancements")
            context.wordIndex == 3 -> listOf("only", "everything", "from", "through", "until").suggest("advancement modes", appendSpace = true)
            context.wordIndex == 4 -> sandbox().datapack.advancements.keys.mapResource("advancements")
            else -> emptyList()
        }

    private fun scheduleSuggestions(context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> listOf("function", "clear").suggest("schedule actions", appendSpace = true)
            2 -> sandbox().datapack.functions.keys.mapResource("functions")
            4 -> listOf("append", "replace").suggest("schedule modes", appendSpace = true)
            else -> emptyList()
        }

    private fun bossbarSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
        when {
            context.wordIndex == 1 -> listOf("add", "remove", "list", "get", "set").suggest("bossbar actions", appendSpace = true)
            context.wordIndex == 2 && words.getOrNull(1) in setOf("remove", "get", "set") -> bossbarIds().suggest("bossbars", appendSpace = true)
            context.wordIndex == 3 && words.getOrNull(1) == "get" -> listOf("value", "max", "visible", "players").suggest("bossbar fields")
            context.wordIndex == 3 && words.getOrNull(1) == "set" -> listOf("name", "value", "max", "color", "style", "visible", "players").suggest("bossbar fields", appendSpace = true)
            context.wordIndex == 4 && words.getOrNull(3) == "color" -> bossbarColors.suggest("bossbar colors")
            context.wordIndex == 4 && words.getOrNull(3) == "style" -> bossbarStyles.suggest("bossbar styles")
            context.wordIndex == 4 && words.getOrNull(3) == "visible" -> booleans.suggest("booleans")
            context.wordIndex == 4 && words.getOrNull(3) == "players" -> playerTargets().suggest("players/selectors")
            else -> emptyList()
        }

    private fun clearSuggestions(context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> playerTargets().suggest("players/selectors", appendSpace = true)
            2 -> sandbox().profile.registryView.items.mapResource("items")
            else -> emptyList()
        }

    private fun cloneSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            10 -> listOf("replace", "masked", "filtered").suggest("clone masks", appendSpace = true)
            11 -> listOf("normal", "force", "move").suggest("clone modes", appendSpace = true)
            12 -> if (words.getOrNull(10) == "filtered") sandbox().profile.registryView.blocks.mapResource("blocks") else emptyList()
            else -> emptyList()
        }

    private fun damageSuggestions(context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> entityTargets().suggest("entities/selectors", appendSpace = true)
            3 -> sandbox().profile.registryView.damageTypes.mapResource("damage types")
            else -> emptyList()
        }

    private fun effectSuggestions(context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> listOf("give", "clear").suggest("effect actions", appendSpace = true)
            2 -> playerTargets().suggest("players/selectors", appendSpace = true)
            3 -> sandbox().profile.registryView.effects.mapResource("effects")
            6 -> booleans.suggest("booleans")
            else -> emptyList()
        }

    private fun enchantSuggestions(context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> playerTargets().suggest("players/selectors", appendSpace = true)
            2 -> sandbox().profile.registryView.enchantments.mapResource("enchantments")
            else -> emptyList()
        }

    private fun experienceSuggestions(context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> listOf("add", "set", "query").suggest("xp actions", appendSpace = true)
            2 -> playerTargets().suggest("players/selectors", appendSpace = true)
            4 -> listOf("points", "levels").suggest("xp units")
            else -> emptyList()
        }

    private fun gameruleSuggestions(context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> commonGamerules.suggest("gamerules", appendSpace = true)
            2 -> booleans.suggest("booleans")
            else -> emptyList()
        }

    private fun giveSuggestions(context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> playerTargets().suggest("players/selectors", appendSpace = true)
            2 -> sandbox().profile.registryView.items.mapResource("items")
            else -> emptyList()
        }

    private fun itemSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
        when {
            context.wordIndex == 1 -> listOf("replace", "modify").suggest("item actions", appendSpace = true)
            context.wordIndex == 2 -> listOf("entity", "block").suggest("item targets", appendSpace = true)
            words.getOrNull(2) == "entity" && context.wordIndex == 3 -> playerTargets().suggest("players/selectors", appendSpace = true)
            words.getOrNull(2) == "entity" && context.wordIndex == 4 -> inventorySlots.suggest("slots", appendSpace = true)
            words.getOrNull(2) == "block" && context.wordIndex == 6 -> inventorySlots.suggest("slots", appendSpace = true)
            words.getOrNull(2) == "block" && context.wordIndex == 7 && words.getOrNull(1) == "modify" -> sandbox().datapack.itemModifiers.keys.mapResource("item modifiers")
            words.getOrNull(2) == "block" && context.wordIndex == 7 && words.getOrNull(1) == "replace" -> listOf("with").suggest("item source", appendSpace = true)
            words.getOrNull(2) == "block" && context.wordIndex == 8 && words.getOrNull(1) == "replace" -> sandbox().profile.registryView.items.mapResource("items")
            words.getOrNull(2) == "entity" && context.wordIndex == 5 && words.getOrNull(1) == "modify" -> sandbox().datapack.itemModifiers.keys.mapResource("item modifiers")
            words.getOrNull(2) == "entity" && context.wordIndex == 5 && words.getOrNull(1) == "replace" -> listOf("with").suggest("item source", appendSpace = true)
            words.getOrNull(2) == "entity" && context.wordIndex == 6 && words.getOrNull(1) == "replace" -> sandbox().profile.registryView.items.mapResource("items")
            else -> emptyList()
        }

    private fun randomSuggestions(context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> listOf("value", "roll", "reset").suggest("random actions", appendSpace = true)
            else -> emptyList()
        }

    private fun recipeSuggestions(context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> listOf("give", "take").suggest("recipe actions", appendSpace = true)
            2 -> playerTargets().suggest("players/selectors", appendSpace = true)
            3 -> listOf("*", "minecraft:bread", "minecraft:stick").suggest("recipes")
            else -> emptyList()
        }

    private fun rideSuggestions(context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> entityTargets().suggest("entities/selectors", appendSpace = true)
            2 -> listOf("mount", "dismount").suggest("ride actions", appendSpace = true)
            3 -> entityTargets().suggest("vehicles")
            else -> emptyList()
        }

    private fun rotateSuggestions(context: CompletionContext): List<CompletionSuggestion> =
        if (context.wordIndex == 1) entityTargets().suggest("entities/selectors", appendSpace = true) else emptyList()

    private fun teamSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> listOf("add", "remove", "list", "join", "leave", "empty", "modify").suggest("team actions", appendSpace = true)
            2 -> teamNames().suggest("teams", appendSpace = true)
            3 -> if (words.getOrNull(1) == "modify") teamOptions.suggest("team options", appendSpace = true) else playerTargets(includeSelectors = false).suggest("members")
            else -> emptyList()
        }

    private fun timeSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> listOf("set", "add", "query").suggest("time actions", appendSpace = true)
            2 -> when (words.getOrNull(1)) {
                "set" -> listOf("day", "noon", "night", "midnight").suggest("time presets")
                "query" -> listOf("daytime", "gametime", "day").suggest("time queries")
                else -> emptyList()
            }
            else -> emptyList()
        }

    private fun weatherSuggestions(context: CompletionContext): List<CompletionSuggestion> =
        if (context.wordIndex == 1) listOf("clear", "rain", "thunder").suggest("weather states") else emptyList()

    private fun playerTargets(includeSelectors: Boolean = true): List<String> {
        val players = sandbox().world.players.keys.toList()
        return if (includeSelectors) selectors + players else players
    }

    private fun entityTargets(): List<String> {
        val box = sandbox()
        return selectors + box.world.players.keys + box.world.entities.map { it.uuid }
    }

    private fun scoreTargets(): List<String> =
        (playerTargets(includeSelectors = false) + sandbox().world.scores.keys.map { it.target }).distinct()

    private fun scoreboardObjectives(): List<String> =
        sandbox().world.objectives.keys.toList()

    private fun storageTargets(): List<String> =
        sandbox().world.storages.keys.map { it.toString() }

    private fun rawResourceKinds(): List<String> =
        sandbox().datapack.rawResources.keys.toList()

    private fun rawResourceIds(kind: String?): List<String> =
        kind
            ?.replace('-', '_')
            ?.let { sandbox().datapack.rawResources[it] }
            .orEmpty()
            .keys
            .map { it.toString() }

    private fun resourceIndexTypes(): List<String> =
        sandbox().datapack.resourceIndex.map { it.type }.distinct()

    private fun functionConditionTargets(): List<String> {
        val pack = sandbox().datapack
        val functions = pack.functions.keys.map { it.toString() }
        val tags = pack.tags.keys
            .filter { it.registry == "function" || it.registry == "functions" }
            .map { "#${it.id}" }
        return (functions + tags).distinct().sorted()
    }

    private fun knownTags(): List<String> =
        sandbox().world.entities.flatMap { it.tags }.distinct()

    private fun bossbarIds(): List<String> =
        sandbox().world.bossbars.keys.map { it.toString() }

    private fun teamNames(): List<String> =
        sandbox().world.teams.keys.toList()

    private fun soundsOrFallback(): List<String> =
        listOf("minecraft:entity.player.levelup", "minecraft:block.note_block.pling", "minecraft:ui.button.click")

    private fun Iterable<ResourceLocation>.mapResource(group: String): List<CompletionSuggestion> =
        map { CompletionSuggestion(it.toString(), group = group) }

    private fun Iterable<String>.suggest(group: String, appendSpace: Boolean = false): List<CompletionSuggestion> =
        map { CompletionSuggestion(it, group = group, appendSpace = appendSpace) }

    companion object {
        private val selectors = listOf("@a", "@s", "@p", "@n", "@e")
        private val inspectTargets = listOf(
            "score",
            "storage",
            "entities",
            "blocks",
            "player",
            "loot",
            "predicate",
            "advancement",
            "recipe",
            "item_modifier",
            "raw",
            "tags",
            "resources",
            "registry",
            "outputs",
        )
        private val chatTargetCommands = setOf("tellraw", "msg", "tell", "w", "stopsound")
        private val soundSources = listOf("master", "music", "record", "weather", "block", "hostile", "neutral", "player", "ambient", "voice")
        private val eventTypes = listOf(
            "tick",
            "item_used",
            "item_consumed",
            "inventory_changed",
            "item_picked_up",
            "key_input",
            "key_pressed",
            "key_released",
            "mouse_input",
            "mouse_clicked",
            "mouse_released",
            "mouse_moved",
            "entity_interacted",
            "damage",
            "death",
            "killed_entity",
            "entity_killed_player",
            "location",
            "changed_dimension",
            "placed_block",
            "broke_block",
            "recipe_unlocked",
            "effects_changed",
        )
        private val inputEventTypes = setOf("key_input", "key_pressed", "key_released", "mouse_input", "mouse_clicked", "mouse_released", "mouse_moved")
        private val inputActions = listOf("press", "release", "click", "move", "scroll")
        private val commonKeys = listOf("key.forward", "key.back", "key.left", "key.right", "key.jump", "key.sneak", "key.sprint", "key.use", "key.attack")
        private val mouseButtons = listOf("left", "right", "middle", "scroll")
        private val booleans = listOf("true", "false")
        private val bossbarColors = listOf("pink", "blue", "red", "green", "yellow", "purple", "white")
        private val bossbarStyles = listOf("progress", "notched_6", "notched_10", "notched_12", "notched_20")
        private val commonGamerules = listOf("doDaylightCycle", "doMobSpawning", "doWeatherCycle", "keepInventory", "randomTickSpeed", "sendCommandFeedback")
        private val inventorySlots = listOf("weapon.mainhand", "weapon.offhand", "hotbar.0", "hotbar.1", "hotbar.2", "container.0", "armor.head", "armor.chest", "armor.legs", "armor.feet")
        private val teamOptions = listOf("displayName", "color", "friendlyFire", "seeFriendlyInvisibles", "nametagVisibility", "deathMessageVisibility", "collisionRule", "prefix", "suffix")
    }
}
