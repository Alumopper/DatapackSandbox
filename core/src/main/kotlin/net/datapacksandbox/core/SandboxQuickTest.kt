package net.datapacksandbox.core

import com.google.gson.JsonElement
import java.nio.file.Path

data class SandboxQuickTestReport(
    val passed: Boolean,
    val failures: List<String>,
    val outputs: List<OutputEvent>,
    val snapshot: JsonElement,
)

class SandboxQuickTestAssertionError(
    val report: SandboxQuickTestReport,
) : AssertionError(report.failures.joinToString(separator = "\n"))

class SandboxQuickTest private constructor(
    val sandbox: DatapackSandbox,
) {
    private val failures = mutableListOf<String>()

    fun load(): SandboxQuickTest = apply {
        sandbox.runLoad()
    }

    fun ticks(count: Int): SandboxQuickTest = apply {
        sandbox.runTicks(count)
    }

    fun function(id: String): SandboxQuickTest = apply {
        sandbox.runFunction(id)
    }

    fun command(command: String): SandboxQuickTest = apply {
        sandbox.executeCommand(command)
    }

    @JvmOverloads
    fun player(name: String = "Steve"): SandboxQuickTest = apply {
        sandbox.createPlayer(name)
    }

    fun event(event: PlayerEvent): SandboxQuickTest = apply {
        sandbox.handlePlayerEvent(event)
    }

    @JvmOverloads
    fun event(playerName: String, type: String, id: String? = null, action: String? = null): SandboxQuickTest = apply {
        sandbox.createPlayer(playerName)
        sandbox.handlePlayerEvent(PlayerEvents.shorthand(playerName, type, id, action))
    }

    @JvmOverloads
    fun keyInput(playerName: String, key: String, action: String = "press"): SandboxQuickTest = apply {
        sandbox.createPlayer(playerName)
        sandbox.handlePlayerEvent(PlayerEvents.keyInput(playerName, key, action))
    }

    @JvmOverloads
    fun mouseInput(playerName: String, button: String, action: String = "click", x: Double? = null, y: Double? = null): SandboxQuickTest = apply {
        sandbox.createPlayer(playerName)
        sandbox.handlePlayerEvent(PlayerEvents.mouseInput(playerName, button, action, x, y))
    }

    fun assertScore(target: String, objective: String, expected: Int): SandboxQuickTest = apply {
        val actual = sandbox.world.getScore(target, objective)
        if (actual != expected) {
            failures += "score $target $objective expected $expected but was $actual"
        }
    }

    fun assertStorageEquals(id: String, path: String?, expectedJson: String): SandboxQuickTest = apply {
        val storageId = ResourceLocation.parse(id)
        val expected = JsonValues.parse(expectedJson)
        val actual = sandbox.world.storages[storageId]?.let { JsonPaths.get(it, path) }
        if (actual != expected) {
            failures += "storage $storageId ${path ?: "<root>"} expected ${JsonValues.render(expected)} but was ${actual?.let(JsonValues::render) ?: "<missing>"}"
        }
    }

    fun assertPlayerXp(playerName: String, expected: Int): SandboxQuickTest = apply {
        val actual = sandbox.world.requirePlayer(playerName).xp
        if (actual != expected) {
            failures += "player $playerName xp expected $expected but was $actual"
        }
    }

    fun assertPlayerLastInput(playerName: String, device: String, code: String, action: String): SandboxQuickTest = apply {
        val actual = sandbox.world.requirePlayer(playerName).lastInput
        if (actual == null) {
            failures += "player $playerName last input expected $device:$code/$action but was <none>"
        } else if (actual.device != device || actual.code != code || actual.action != action) {
            failures += "player $playerName last input expected $device:$code/$action but was ${actual.device}:${actual.code}/${actual.action}"
        }
    }

    @JvmOverloads
    fun assertAdvancementDone(playerName: String, id: String, expected: Boolean = true): SandboxQuickTest = apply {
        val player = sandbox.world.requirePlayer(playerName)
        val advancementId = ResourceLocation.parse(id)
        val advancement = sandbox.datapack.advancements[advancementId]
        val progress = player.advancementProgress[advancementId]
        val actual = advancement != null && progress?.isDone(advancement.requirements) == true
        if (actual != expected) {
            failures += "advancement $advancementId for $playerName done expected $expected but was $actual"
        }
    }

    fun assertOutputContains(text: String): SandboxQuickTest = apply {
        if (sandbox.world.outputs.none { text in it.text }) {
            failures += "expected an output containing '$text'"
        }
    }

    fun report(): SandboxQuickTestReport =
        SandboxQuickTestReport(
            passed = failures.isEmpty(),
            failures = failures.toList(),
            outputs = sandbox.world.outputs.toList(),
            snapshot = sandbox.snapshotJson(),
        )

    fun requirePassed(): SandboxQuickTestReport {
        val report = report()
        if (!report.passed) throw SandboxQuickTestAssertionError(report)
        return report
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun create(
            packs: List<Path>,
            version: String = VersionProfiles.default.id,
            defaultPlayerName: String? = "Steve",
            unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
        ): SandboxQuickTest =
            SandboxQuickTest(createSandbox(version, packs, defaultPlayerName = defaultPlayerName, unsupportedFeatureMode = unsupportedFeatureMode))
    }
}

object DatapackSandboxTestApi {
    @JvmStatic
    @JvmOverloads
    fun scenario(
        packs: List<Path>,
        version: String = VersionProfiles.default.id,
        defaultPlayerName: String? = "Steve",
        unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
    ): SandboxQuickTest =
        SandboxQuickTest.create(packs, version, defaultPlayerName, unsupportedFeatureMode)

    @JvmStatic
    @JvmOverloads
    fun runCommands(
        packs: List<Path>,
        commands: List<String>,
        version: String = VersionProfiles.default.id,
    ): SandboxQuickTestReport {
        val scenario = scenario(packs, version)
        commands.forEach { scenario.command(it) }
        return scenario.report()
    }
}
