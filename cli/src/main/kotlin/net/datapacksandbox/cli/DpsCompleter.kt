package net.datapacksandbox.cli

import net.datapacksandbox.core.DatapackSandbox
import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.LineReader
import org.jline.reader.ParsedLine

data class CompletionSuggestion(
    val value: String,
    val description: String = "",
    val group: String = "values",
    val appendSpace: Boolean = false,
)

class DpsCompleter(private val sandbox: () -> DatapackSandbox) : Completer {
    override fun complete(reader: LineReader, line: ParsedLine, candidates: MutableList<Candidate>) {
        suggestions(line.line(), line.cursor()).forEachIndexed { index, suggestion ->
            candidates += Candidate(
                suggestion.value,
                suggestion.value,
                suggestion.group,
                suggestion.description.takeIf { it.isNotBlank() },
                if (suggestion.appendSpace) " " else null,
                null,
                true,
                index,
            )
        }
    }

    fun suggestions(buffer: String, cursor: Int = buffer.length): List<CompletionSuggestion> {
        val context = CompletionContext.parse(buffer, cursor)
        val words = context.words
        val first = context.first
        val box = sandbox()
        val options = when {
            first.isBlank() || context.wordIndex == 0 -> DpsCommandCatalog.rootCommands
            first == "help" -> DpsCommandCatalog.rootCommands
            first == "function" && context.wordIndex == 1 -> box.datapack.functions.keys.mapResource("functions")
            first == "inspect" && context.wordIndex == 1 -> inspectTargets.suggest("inspect targets", appendSpace = true)
            first == "inspect" && words.getOrNull(1) == "player" -> playerTargets(includeSelectors = false).suggest("players")
            first == "inspect" && words.getOrNull(1) == "storage" -> storageTargets().suggest("storages")
            first == "player" && context.wordIndex == 1 -> playerTargets(includeSelectors = false).suggest("players")
            first == "event" -> eventSuggestions(words, context)
            first in chatTargetCommands && context.wordIndex == 1 -> playerTargets().suggest("players/selectors", appendSpace = true)
            first == "title" -> titleSuggestions(words, context)
            first == "playsound" -> playSoundSuggestions(words, context)
            first == "scoreboard" -> scoreboardSuggestions(words, context)
            first == "execute" -> executeSuggestions(words, context)
            first in setOf("tp", "teleport") -> teleportSuggestions(words, context)
            first == "setblock" && context.wordIndex == 4 -> box.profile.registryView.blocks.mapResource("blocks")
            first == "fill" -> fillSuggestions(words, context)
            first == "data" -> dataSuggestions(words, context)
            first == "tag" -> tagSuggestions(words, context)
            first == "summon" && context.wordIndex == 1 -> box.profile.registryView.entityTypes.mapResource("entity types")
            first == "kill" && context.wordIndex == 1 -> entityTargets().suggest("entities/selectors")
            first == "advancement" -> advancementSuggestions(words, context)
            first == "schedule" -> scheduleSuggestions(words, context)
            first == "bossbar" -> bossbarSuggestions(words, context)
            first == "clear" -> clearSuggestions(words, context)
            first == "clone" -> cloneSuggestions(words, context)
            first == "damage" -> damageSuggestions(words, context)
            first == "effect" -> effectSuggestions(words, context)
            first == "enchant" -> enchantSuggestions(words, context)
            first in setOf("experience", "xp") -> experienceSuggestions(words, context)
            first == "gamerule" -> gameruleSuggestions(words, context)
            first == "give" -> giveSuggestions(words, context)
            first == "item" -> itemSuggestions(words, context)
            first == "random" -> randomSuggestions(words, context)
            first == "recipe" -> recipeSuggestions(words, context)
            first == "ride" -> rideSuggestions(words, context)
            first == "rotate" -> rotateSuggestions(words, context)
            first == "team" -> teamSuggestions(words, context)
            first == "time" -> timeSuggestions(words, context)
            first == "weather" -> weatherSuggestions(words, context)
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
            val exact = DpsCommandCatalog.rootCommands.firstOrNull {
                it.value == context.prefix.removePrefix("/")
            }
            if (exact != null) {
                return DpsCommandCatalog.usageSuffix(exact.value)
            }
        }

        val suggestions = suggestions(buffer, cursor)
            .take(6)
        if (suggestions.isEmpty()) return ""

        val values = suggestions.map {
            if (context.wordIndex == 0) it.value.removePrefix("/") else it.value
        }
        return values.joinToString(prefix = "[", postfix = "]", separator = " ")
    }

    private fun eventSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> listOf("player").suggest("event target", appendSpace = true)
            2 -> playerTargets(includeSelectors = false).suggest("players", appendSpace = true)
            3 -> eventTypes.suggest("event types", appendSpace = true)
            4 -> when (words.getOrNull(3)) {
                "item_used", "item_consumed", "inventory_changed", "item_picked_up" -> sandbox().profile.registryView.items.mapResource("items")
                "killed_entity", "entity_killed_player" -> sandbox().profile.registryView.entityTypes.mapResource("entity types")
                "placed_block", "broke_block" -> sandbox().profile.registryView.blocks.mapResource("blocks")
                "recipe_unlocked" -> listOf("minecraft:bread", "minecraft:stick").suggest("recipes")
                "key_input", "key_pressed", "key_released" -> commonKeys.suggest("keys")
                "mouse_input", "mouse_clicked", "mouse_released", "mouse_moved" -> mouseButtons.suggest("mouse buttons")
                else -> emptyList()
            }
            5 -> if (words.getOrNull(3) in inputEventTypes) inputActions.suggest("input actions") else emptyList()
            else -> emptyList()
        }

    private fun titleSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> playerTargets().suggest("players/selectors", appendSpace = true)
            2 -> listOf("clear", "reset", "title", "subtitle", "actionbar", "times").suggest("title actions", appendSpace = true)
            else -> emptyList()
        }

    private fun playSoundSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
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
            previous == "run" -> DpsCommandCatalog.rootCommands
            previous in setOf("if", "unless") -> listOf("entity", "score", "data", "block").suggest("conditions", appendSpace = true)
            beforePrevious in setOf("if", "unless") && previous == "entity" -> entityTargets().suggest("entities/selectors", appendSpace = true)
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

    private fun teleportSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
        if (context.wordIndex == 1 || context.wordIndex == 2) entityTargets().suggest("entities/selectors", appendSpace = true) else emptyList()

    private fun fillSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
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
            words.getOrNull(context.wordIndex - 1) in setOf("set", "merge", "append", "prepend") -> listOf("value").suggest("data source", appendSpace = true)
            else -> emptyList()
        }

    private fun tagSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
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

    private fun scheduleSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
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

    private fun clearSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
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

    private fun damageSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> entityTargets().suggest("entities/selectors", appendSpace = true)
            3 -> sandbox().profile.registryView.damageTypes.mapResource("damage types")
            else -> emptyList()
        }

    private fun effectSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> listOf("give", "clear").suggest("effect actions", appendSpace = true)
            2 -> playerTargets().suggest("players/selectors", appendSpace = true)
            3 -> sandbox().profile.registryView.effects.mapResource("effects")
            6 -> booleans.suggest("booleans")
            else -> emptyList()
        }

    private fun enchantSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> playerTargets().suggest("players/selectors", appendSpace = true)
            2 -> sandbox().profile.registryView.enchantments.mapResource("enchantments")
            else -> emptyList()
        }

    private fun experienceSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> listOf("add", "set", "query").suggest("xp actions", appendSpace = true)
            2 -> playerTargets().suggest("players/selectors", appendSpace = true)
            4 -> listOf("points", "levels").suggest("xp units")
            else -> emptyList()
        }

    private fun gameruleSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> commonGamerules.suggest("gamerules", appendSpace = true)
            2 -> booleans.suggest("booleans")
            else -> emptyList()
        }

    private fun giveSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
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
            context.wordIndex == 5 && words.getOrNull(1) == "replace" -> listOf("with").suggest("item source", appendSpace = true)
            context.wordIndex == 6 && words.getOrNull(1) == "replace" -> sandbox().profile.registryView.items.mapResource("items")
            else -> emptyList()
        }

    private fun randomSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> listOf("value", "roll", "reset").suggest("random actions", appendSpace = true)
            else -> emptyList()
        }

    private fun recipeSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> listOf("give", "take").suggest("recipe actions", appendSpace = true)
            2 -> playerTargets().suggest("players/selectors", appendSpace = true)
            3 -> listOf("*", "minecraft:bread", "minecraft:stick").suggest("recipes")
            else -> emptyList()
        }

    private fun rideSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
        when (context.wordIndex) {
            1 -> entityTargets().suggest("entities/selectors", appendSpace = true)
            2 -> listOf("mount", "dismount").suggest("ride actions", appendSpace = true)
            3 -> entityTargets().suggest("vehicles")
            else -> emptyList()
        }

    private fun rotateSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
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

    private fun weatherSuggestions(words: List<String>, context: CompletionContext): List<CompletionSuggestion> =
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

    private fun knownTags(): List<String> =
        sandbox().world.entities.flatMap { it.tags }.distinct()

    private fun bossbarIds(): List<String> =
        sandbox().world.bossbars.keys.map { it.toString() }

    private fun teamNames(): List<String> =
        sandbox().world.teams.keys.toList()

    private fun soundsOrFallback(): List<String> =
        listOf("minecraft:entity.player.levelup", "minecraft:block.note_block.pling", "minecraft:ui.button.click")

    private fun Iterable<net.datapacksandbox.core.ResourceLocation>.mapResource(group: String): List<CompletionSuggestion> =
        map { CompletionSuggestion(it.toString(), group = group) }

    private fun Iterable<String>.suggest(group: String, appendSpace: Boolean = false): List<CompletionSuggestion> =
        map { CompletionSuggestion(it, group = group, appendSpace = appendSpace) }

    private data class CompletionContext(
        val words: List<String>,
        val wordIndex: Int,
        val prefix: String,
        val endsWithWhitespace: Boolean,
    ) {
        val first: String = words.firstOrNull()?.removePrefix("/")?.lowercase().orEmpty()

        fun filter(options: List<CompletionSuggestion>): List<CompletionSuggestion> {
            val rawPrefix = if (wordIndex == 0) prefix.removePrefix("/") else prefix
            val slashRoot = wordIndex == 0 && prefix.startsWith("/")
            return options.asSequence()
                .filter { rawPrefix.isBlank() || it.value.startsWith(rawPrefix) }
                .distinctBy { it.value }
                .sortedWith(compareBy<CompletionSuggestion> { it.value != rawPrefix }.thenBy { it.value })
                .map { if (slashRoot) it.copy(value = "/${it.value}") else it }
                .toList()
        }

        companion object {
            fun parse(buffer: String, cursor: Int): CompletionContext {
                val beforeCursor = buffer.take(cursor.coerceIn(0, buffer.length))
                if (beforeCursor.isBlank()) {
                    return CompletionContext(emptyList(), 0, "", beforeCursor.lastOrNull()?.isWhitespace() == true)
                }
                val tokens = tokenPattern.findAll(beforeCursor).map { it.value }.toList()
                val trailingWhitespace = beforeCursor.lastOrNull()?.isWhitespace() == true
                val wordIndex = if (trailingWhitespace) tokens.size else (tokens.size - 1).coerceAtLeast(0)
                val prefix = if (trailingWhitespace) "" else tokens.lastOrNull().orEmpty()
                return CompletionContext(tokens.mapIndexed { index, token -> if (index == 0) token.removePrefix("/") else token }, wordIndex, prefix, trailingWhitespace)
            }

            private val tokenPattern = Regex("""(?:"(?:\\.|[^"\\])*"?|'(?:\\.|[^'\\])*'?|\S+)""")
        }
    }

    companion object {
        private val selectors = listOf("@a", "@s", "@p", "@n", "@e")
        private val inspectTargets = listOf("score", "storage", "entities", "blocks", "player", "loot", "predicate", "advancement", "registry", "outputs")
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

object DpsCommandCatalog {
    val rootCommands: List<CompletionSuggestion> = listOf(
        command("load", "run #minecraft:load"),
        command("reload", "reload datapack files"),
        command("tick", "advance sandbox ticks"),
        command("function", "run a loaded function"),
        command("player", "create or reuse a player"),
        command("event", "inject a player event"),
        command("inspect", "inspect sandbox state"),
        command("snapshot", "print or write a snapshot"),
        command("help", "show help"),
        command("exit", "leave the REPL"),
        command("quit", "leave the REPL"),
        command("advancement", "grant, revoke, or test advancement progress"),
        command("bossbar", "edit stored bossbar state"),
        command("clear", "remove items from player inventories"),
        command("clone", "copy sparse sandbox blocks"),
        command("damage", "apply sandbox health damage"),
        command("data", "read or mutate storage/entity/block NBT"),
        command("effect", "give or clear player effects"),
        command("enchant", "write enchantment components"),
        command("execute", "run a command in a modified context"),
        command("experience", "edit player XP"),
        command("xp", "edit player XP"),
        command("fill", "fill sparse sandbox blocks"),
        command("function", "run a loaded function"),
        command("gamerule", "edit stored gamerule values"),
        command("give", "add items to players"),
        command("item", "replace entity item slots"),
        command("kill", "remove entities"),
        command("random", "generate deterministic random values"),
        command("recipe", "give or take player recipes"),
        command("ride", "edit riding relationships"),
        command("rotate", "edit entity yaw and pitch"),
        command("schedule", "schedule or clear functions"),
        command("scoreboard", "edit objectives and player scores"),
        command("setblock", "place one sparse sandbox block"),
        command("summon", "create an entity"),
        command("tag", "edit entity tags"),
        command("team", "edit team state"),
        command("teleport", "move entities"),
        command("tp", "move entities"),
        command("time", "edit world time state"),
        command("weather", "edit weather state"),
        command("tellraw", "record a raw JSON chat output"),
        command("title", "record title output"),
        command("say", "record chat output"),
        command("me", "record chat output"),
        command("msg", "record private chat output"),
        command("tell", "record private chat output"),
        command("w", "record private chat output"),
        command("teammsg", "record team chat output"),
        command("tm", "record team chat output"),
        command("playsound", "record a sound output"),
        command("stopsound", "record a stop-sound output"),
        command("particle", "record a visual output"),
        unsupported("attribute"),
        unsupported("datapack"),
        unsupported("debug"),
        unsupported("defaultgamemode"),
        unsupported("difficulty"),
        unsupported("fillbiome"),
        unsupported("forceload"),
        unsupported("gamemode"),
        unsupported("loot"),
        unsupported("place"),
        unsupported("reload"),
        unsupported("seed"),
        unsupported("spawnpoint"),
        unsupported("spectate"),
        unsupported("spreadplayers"),
        unsupported("trigger"),
        unsupported("worldborder"),
    ).distinctBy { it.value }.sortedBy { it.value }

    fun rootNames(): Set<String> = rootCommands.mapTo(sortedSetOf()) { it.value }

    fun usageSuffix(command: String): String =
        when (command) {
            "function" -> " <namespace:path>"
            "tick" -> " [count]"
            "inspect" -> " <score|storage|entities|blocks|player|loot|predicate|advancement|registry|outputs>"
            "event" -> " player <name> <type> [id] [action]"
            "scoreboard" -> " objectives|players ..."
            "execute" -> " as|at|if|unless|store ... run <command>"
            "data" -> " <get|modify|merge|remove> <storage|entity|block> ..."
            "bossbar" -> " <add|remove|list|get|set> ..."
            "give" -> " <players> <item> [count]"
            "effect" -> " <give|clear> <players> ..."
            "advancement" -> " <grant|revoke|test> <players> ..."
            "schedule" -> " <function|clear> ..."
            "setblock" -> " <x> <y> <z> <block>"
            "fill" -> " <from> <to> <block>"
            "weather" -> " <clear|rain|thunder> [duration]"
            "time" -> " <set|add|query> ..."
            else -> rootCommands.firstOrNull { it.value == command }?.description?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
        }

    private fun command(value: String, description: String): CompletionSuggestion =
        CompletionSuggestion(value, description, "commands", appendSpace = true)

    private fun unsupported(value: String): CompletionSuggestion =
        CompletionSuggestion(value, "vanilla command: warning unless --unsupported error is set", "vanilla warnings", appendSpace = true)
}
