package moe.afox.dpsandbox.core

/**
 * Logical output channels used by [OutputEvent].
 */
enum class OutputChannel(val id: String) {
    CHAT("chat"),
    TITLE("title"),
    SOUND("sound"),
    VISUAL("visual"),
    DATA("data"),
    DEBUG("debug"),
    WORLDGEN("worldgen"),
    WARNING("warning"),
}

/**
 * Common vanilla command roots used by trace assertions.
 */
enum class CommandRoot(val id: String) {
    ADVANCEMENT("advancement"),
    ATTRIBUTE("attribute"),
    BAN("ban"),
    BAN_IP("ban-ip"),
    BANLIST("banlist"),
    BOSSBAR("bossbar"),
    CLEAR("clear"),
    CLONE("clone"),
    DAMAGE("damage"),
    DATA("data"),
    DATAPACK("datapack"),
    DEBUG("debug"),
    DEFAULTGAMEMODE("defaultgamemode"),
    DEOP("deop"),
    DIFFICULTY("difficulty"),
    EFFECT("effect"),
    ENCHANT("enchant"),
    EXECUTE("execute"),
    EXPERIENCE("experience"),
    FILL("fill"),
    FILLBIOME("fillbiome"),
    FORCELOAD("forceload"),
    FUNCTION("function"),
    GAMEMODE("gamemode"),
    GAMERULE("gamerule"),
    GIVE("give"),
    HELP("help"),
    ITEM("item"),
    JFR("jfr"),
    KICK("kick"),
    KILL("kill"),
    LIST("list"),
    LOCATE("locate"),
    LOOT("loot"),
    ME("me"),
    MSG("msg"),
    OP("op"),
    PARDON("pardon"),
    PARDON_IP("pardon-ip"),
    PARTICLE("particle"),
    PERF("perf"),
    PLACE("place"),
    PLAYSOUND("playsound"),
    PUBLISH("publish"),
    RANDOM("random"),
    RECIPE("recipe"),
    RELOAD("reload"),
    RETURN("return"),
    RIDE("ride"),
    ROTATE("rotate"),
    SAVE_ALL("save-all"),
    SAVE_OFF("save-off"),
    SAVE_ON("save-on"),
    SAY("say"),
    SCHEDULE("schedule"),
    SCOREBOARD("scoreboard"),
    SEED("seed"),
    SETBLOCK("setblock"),
    SETIDLETIMEOUT("setidletimeout"),
    SETWORLDSPAWN("setworldspawn"),
    SPAWNPOINT("spawnpoint"),
    SPECTATE("spectate"),
    SPREADPLAYERS("spreadplayers"),
    STOP("stop"),
    STOPSOUND("stopsound"),
    SUMMON("summon"),
    TAG("tag"),
    TEAM("team"),
    TEAMMSG("teammsg"),
    TELL("tell"),
    TELLRAW("tellraw"),
    TICK("tick"),
    TIME("time"),
    TITLE("title"),
    TM("tm"),
    TP("tp"),
    TRANSFER("transfer"),
    TRIGGER("trigger"),
    W("w"),
    WEATHER("weather"),
    WHITELIST("whitelist"),
    WORLDBORDER("worldborder"),
    XP("xp"),
}

enum class SandboxWeather(val id: String) {
    CLEAR("clear"),
    RAIN("rain"),
    THUNDER("thunder"),
}

enum class SandboxDifficulty(val id: String) {
    PEACEFUL("peaceful"),
    EASY("easy"),
    NORMAL("normal"),
    HARD("hard"),
}

enum class SandboxGameMode(val id: String) {
    SURVIVAL("survival"),
    CREATIVE("creative"),
    ADVENTURE("adventure"),
    SPECTATOR("spectator"),
}

enum class PlayerInputDevice(val id: String) {
    KEYBOARD("keyboard"),
    MOUSE("mouse"),
}

enum class PlayerInputAction(val id: String) {
    PRESS("press"),
    RELEASE("release"),
    CLICK("click"),
    MOVE("move"),
    SCROLL("scroll"),
}

enum class PlayerEventType(val id: String) {
    KEY_INPUT("key_input"),
    KEYBOARD_INPUT("keyboard_input"),
    KEY_PRESSED("key_pressed"),
    KEY_RELEASED("key_released"),
    MOUSE_INPUT("mouse_input"),
    MOUSE_BUTTON("mouse_button"),
    MOUSE_CLICKED("mouse_clicked"),
    MOUSE_RELEASED("mouse_released"),
    MOUSE_MOVED("mouse_moved"),
    ITEM_USED("item_used"),
    USING_ITEM("using_item"),
    ITEM_USED_ON_BLOCK("item_used_on_block"),
    ITEM_CONSUMED("item_consumed"),
    CONSUME_ITEM("consume_item"),
    INVENTORY_CHANGED("inventory_changed"),
    ITEM_PICKED_UP("item_picked_up"),
    ITEM_ADDED("item_added"),
    BLOCK_PLACED("block_placed"),
    BLOCK_BROKEN("block_broken"),
    ENTITY_KILLED_PLAYER("entity_killed_player"),
    PLAYER_KILLED_ENTITY("player_killed_entity"),
    PLAYER_INTERACTED_WITH_ENTITY("player_interacted_with_entity"),
    CHANGED_DIMENSION("changed_dimension"),
    RECIPE_UNLOCKED("recipe_unlocked"),
    RECIPE_CRAFTED("recipe_crafted"),
    DAMAGE("damage"),
    DEATH("death"),
}

enum class LootContextId(val id: String) {
    EMPTY("minecraft:empty"),
    ADVANCEMENT_REWARD("minecraft:advancement_reward"),
    COMMAND("minecraft:command"),
    SELECTOR("minecraft:selector"),
    CHEST("minecraft:chest"),
    FISHING("minecraft:fishing"),
    ENTITY("minecraft:entity"),
    EQUIPMENT("minecraft:equipment"),
    ARCHAEOLOGY("minecraft:archaeology"),
    VAULT("minecraft:vault"),
    GIFT("minecraft:gift"),
    BARTER("minecraft:barter"),
}

enum class ItemContainer(val id: String) {
    INVENTORY("inventory"),
    ENDER_ITEMS("enderItems"),
}

enum class EntityEquipmentSlot(val id: String) {
    MAINHAND("weapon.mainhand"),
    OFFHAND("weapon.offhand"),
    HEAD("armor.head"),
    CHEST("armor.chest"),
    LEGS("armor.legs"),
    FEET("armor.feet"),
}

enum class ScoreboardDisplaySlot(val id: String) {
    LIST("list"),
    SIDEBAR("sidebar"),
    BELOW_NAME("below_name"),
    SIDEBAR_TEAM_BLACK("sidebar.team.black"),
    SIDEBAR_TEAM_DARK_BLUE("sidebar.team.dark_blue"),
    SIDEBAR_TEAM_DARK_GREEN("sidebar.team.dark_green"),
    SIDEBAR_TEAM_DARK_AQUA("sidebar.team.dark_aqua"),
    SIDEBAR_TEAM_DARK_RED("sidebar.team.dark_red"),
    SIDEBAR_TEAM_DARK_PURPLE("sidebar.team.dark_purple"),
    SIDEBAR_TEAM_GOLD("sidebar.team.gold"),
    SIDEBAR_TEAM_GRAY("sidebar.team.gray"),
    SIDEBAR_TEAM_DARK_GRAY("sidebar.team.dark_gray"),
    SIDEBAR_TEAM_BLUE("sidebar.team.blue"),
    SIDEBAR_TEAM_GREEN("sidebar.team.green"),
    SIDEBAR_TEAM_AQUA("sidebar.team.aqua"),
    SIDEBAR_TEAM_RED("sidebar.team.red"),
    SIDEBAR_TEAM_LIGHT_PURPLE("sidebar.team.light_purple"),
    SIDEBAR_TEAM_YELLOW("sidebar.team.yellow"),
    SIDEBAR_TEAM_WHITE("sidebar.team.white"),
}

enum class ScoreboardRenderType(val id: String) {
    INTEGER("integer"),
    HEARTS("hearts"),
}

enum class BossbarColor(val id: String) {
    PINK("pink"),
    BLUE("blue"),
    RED("red"),
    GREEN("green"),
    YELLOW("yellow"),
    PURPLE("purple"),
    WHITE("white"),
}

enum class BossbarStyle(val id: String) {
    PROGRESS("progress"),
    NOTCHED_6("notched_6"),
    NOTCHED_10("notched_10"),
    NOTCHED_12("notched_12"),
    NOTCHED_20("notched_20"),
}

enum class TeamOption(val id: String) {
    COLOR("color"),
    FRIENDLY_FIRE("friendlyFire"),
    SEE_FRIENDLY_INVISIBLES("seeFriendlyInvisibles"),
    NAMETAG_VISIBILITY("nametagVisibility"),
    DEATH_MESSAGE_VISIBILITY("deathMessageVisibility"),
    COLLISION_RULE("collisionRule"),
    PREFIX("prefix"),
    SUFFIX("suffix"),
}
