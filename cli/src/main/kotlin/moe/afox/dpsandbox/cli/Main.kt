package moe.afox.dpsandbox.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.PlayerEvents
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.UnsupportedFeatureMode
import moe.afox.dpsandbox.core.VersionProfiles
import moe.afox.dpsandbox.core.createSandbox
import moe.afox.dpsandbox.core.toJson
import moe.afox.dpsandbox.core.toPlayerJson
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.system.exitProcess

fun main(args: Array<String>) = DatapackSandboxCli()
    .subcommands(ReplCommand(), CheckCommand(), RunCommand(), LootCommand(), AdvancementCommand(), EventCommand(), VersionCommand())
    .main(args)

class DatapackSandboxCli : CliktCommand(
    name = "datapack-sandbox",
) {
    override fun run() = Unit
}

class ReplCommand : CliktCommand(name = "repl") {
    private val version by option("--version", "-v").default(VersionProfiles.default.id)
    private val packs by option("--pack", "-p").path(mustExist = true).multiple(required = true)
    private val watch by option("--watch").flag(default = false)
    private val unsupported by option("--unsupported").default("warn")

    override fun run() {
        try {
            Repl(version, packs, watch, unsupportedFeatureMode = unsupportedFeatureMode(unsupported)).run()
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }
}

class CheckCommand : CliktCommand(name = "check") {
    private val inputs by argument("input").path(mustExist = true).multiple(required = true)
    private val failFast by option("--fail-fast").flag(default = false)
    private val verbose by option("--verbose").flag(default = false)
    private val snapshotOnFail by option("--snapshot-on-fail").flag(default = false)
    private val seed by option("--seed").long().default(0)
    private val unsupported by option("--unsupported").default("warn")

    override fun run() {
        try {
            val manifests = ManifestRunner.discover(inputs)
            var failed = false
            manifests.forEach { manifest ->
                val result = ManifestRunner.run(
                    manifest,
                    ManifestOptions(
                        seed = seed,
                        verbose = verbose,
                        snapshotOnFail = snapshotOnFail,
                        unsupportedFeatureMode = unsupportedFeatureMode(unsupported),
                    ),
                )
                if (result.passed) {
                    println(ConsoleStyle.green("PASS ${manifest}"))
                } else {
                    failed = true
                    println(ConsoleStyle.red("FAIL ${manifest}"))
                    result.messages.forEach { println("  - $it") }
                    if (failFast) exitProcess(ExitCodes.ASSERTION_FAILED)
                }
                if (verbose && result.outputs.isNotEmpty()) {
                    OutputRenderer.print(result.outputs)
                }
            }
            if (failed) exitProcess(ExitCodes.ASSERTION_FAILED)
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }
}

class RunCommand : CliktCommand(name = "run") {
    private val version by option("--version", "-v").default(VersionProfiles.default.id)
    private val packs by option("--pack", "-p").path(mustExist = true).multiple(required = true)
    private val shouldLoad by option("--load").flag(default = false)
    private val ticks by option("--ticks").int().default(0)
    private val functions by option("--function", "-f").multiple()
    private val commands by option("--command", "-c").multiple()
    private val commandFile by option("--command-file").path(mustExist = true)
    private val snapshot by option("--snapshot").flag(default = false)
    private val snapshotFile by option("--snapshot-file").path()
    private val unsupported by option("--unsupported").default("warn")

    override fun run() {
        try {
            val sandbox = createSandbox(version, packs, unsupportedFeatureMode = unsupportedFeatureMode(unsupported))
            var total = 0
            if (shouldLoad) total += sandbox.runLoad().commandsExecuted
            if (ticks > 0) total += sandbox.runTicks(ticks).commandsExecuted
            functions.forEach { total += sandbox.runFunction(it).commandsExecuted }
            commandFile?.let { file ->
                Files.readAllLines(file, StandardCharsets.UTF_8)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .forEach { total += sandbox.executeCommand(it).commandsExecuted }
            }
            commands.forEach { total += sandbox.executeCommand(it).commandsExecuted }
            OutputRenderer.print(sandbox.world.outputs)
            println(ConsoleStyle.green("OK version=${sandbox.profile.id} gameTime=${sandbox.world.gameTime} commands=$total entities=${sandbox.world.entities.size}"))
            if (snapshot) println(sandbox.snapshotString())
            snapshotFile?.let {
                Files.writeString(it, sandbox.snapshotString(), StandardCharsets.UTF_8)
                println(ConsoleStyle.green("snapshot written: $it"))
            }
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }
}

class LootCommand : CliktCommand(name = "loot") {
    private val version by option("--version", "-v").default(VersionProfiles.default.id)
    private val packs by option("--pack", "-p").path(mustExist = true).multiple(required = true)
    private val table by option("--table").default("minecraft:empty")
    private val context by option("--context").default("minecraft:empty")
    private val player by option("--player")
    private val seed by option("--seed").long().default(0)

    override fun run() {
        try {
            val sandbox = createSandbox(version, packs)
            val sandboxPlayer = player?.let { sandbox.createPlayer(it) }
            val result = sandbox.generateLoot(ResourceLocation.parse(table), ResourceLocation.parse(context), sandboxPlayer, seed)
            println(JsonValues.render(com.google.gson.JsonArray().also { array ->
                result.items.forEach { array.add(it.toJson()) }
            }))
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }
}

class AdvancementCommand : CliktCommand(name = "advancement") {
    private val version by option("--version", "-v").default(VersionProfiles.default.id)
    private val packs by option("--pack", "-p").path(mustExist = true).multiple(required = true)
    private val args by argument("args").multiple(required = true)
    private val unsupported by option("--unsupported").default("warn")

    override fun run() {
        try {
            val sandbox = createSandbox(version, packs, unsupportedFeatureMode = unsupportedFeatureMode(unsupported))
            val action = args.getOrNull(0) ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "advancement action is required")
            val player = sandbox.createPlayer(args.getOrNull(1) ?: "Steve")
            val id = args.getOrNull(2)?.let(ResourceLocation::parse)
            when (action) {
                "list" -> sandbox.datapack.advancements.keys.forEach { println(it) }
                "progress" -> {
                    val progress = sandbox.advancements.progress(player, id ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "advancement id is required"))
                    println(JsonValues.render(player.toPlayerJson()))
                    println(progress.criteria)
                }
                "grant" -> sandbox.advancements.grant(player, id ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "advancement id is required"), args.getOrNull(3)).forEach { println(it) }
                "revoke" -> sandbox.advancements.revoke(player, id ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "advancement id is required"), args.getOrNull(3)).forEach { println(it) }
                else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unknown advancement action '$action'")
            }
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }
}

class EventCommand : CliktCommand(name = "event") {
    private val version by option("--version", "-v").default(VersionProfiles.default.id)
    private val packs by option("--pack", "-p").path(mustExist = true).multiple(required = true)
    private val args by argument("args").multiple(required = true)
    private val unsupported by option("--unsupported").default("warn")

    override fun run() {
        try {
            val sandbox = createSandbox(version, packs, unsupportedFeatureMode = unsupportedFeatureMode(unsupported))
            if (args.getOrNull(0) != "player") {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Usage: event player <name> <type> [id] [action]")
            }
            val player = sandbox.createPlayer(args.getOrNull(1) ?: "Steve")
            val eventType = args.getOrNull(2) ?: "tick"
            val event = PlayerEvents.shorthand(player.name, eventType, args.getOrNull(3), args.getOrNull(4))
            val updates = sandbox.handlePlayerEvent(event)
            val inputText = event.input?.let { ", input=${it.device}:${it.code}/${it.action}" }.orEmpty()
            println(ConsoleStyle.green("OK event player ${player.name} ${event.type}") + ConsoleStyle.dim(" (updates=${updates.size}$inputText)"))
            if (updates.isEmpty()) {
                println(ConsoleStyle.yellow("No advancement criteria changed. Check the event type/id against the advancement trigger conditions."))
            } else {
                updates.forEach { println(it) }
            }
            println(sandbox.snapshotString())
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }
}

class VersionCommand : CliktCommand(name = "version") {
    override fun run() {
        VersionProfiles.all.forEach { profile ->
            val marker = if (profile == VersionProfiles.default) " default" else ""
            println("${profile.id} java=${profile.javaMajor}$marker - ${profile.description}")
        }
    }
}

fun unsupportedFeatureMode(value: String): UnsupportedFeatureMode =
    when (value.lowercase()) {
        "warn", "warning" -> UnsupportedFeatureMode.WARN
        "ignore", "silent" -> UnsupportedFeatureMode.IGNORE
        "error", "strict" -> UnsupportedFeatureMode.ERROR
        else -> throw SandboxException(
            DiagnosticCode.INPUT_FORMAT,
            "Unsupported --unsupported mode '$value'; expected warn, ignore, or error",
        )
    }
