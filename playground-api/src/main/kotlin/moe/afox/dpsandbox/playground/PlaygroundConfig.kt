package moe.afox.dpsandbox.playground

import com.google.gson.JsonParser
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class PlaygroundLimits(
    val idleTimeout: Duration = 10.minutes,
    val executionTimeout: Duration = 5.seconds,
    val maximumCellBytes: Int = 64 * 1024,
    val maximumOutputBytes: Int = 1024 * 1024,
    val maximumRenderWidth: Int = 1920,
    val maximumRenderHeight: Int = 1080,
    val maximumRequestsPerMinute: Int = 120,
    val maximumSessions: Int = 64,
    val maximumCommands: Int = 10_000,
    val maximumOutputEvents: Int = 2_000,
) {
    init {
        require(idleTimeout.isPositive()) { "Idle timeout must be positive" }
        require(executionTimeout.isPositive()) { "Execution timeout must be positive" }
        require(maximumCellBytes > 0) { "Maximum cell size must be positive" }
        require(maximumOutputBytes > 0) { "Maximum output size must be positive" }
        require(maximumRenderWidth in 16..4096) { "Maximum render width must be between 16 and 4096" }
        require(maximumRenderHeight in 16..4096) { "Maximum render height must be between 16 and 4096" }
        require(maximumRequestsPerMinute > 0) { "Request rate limit must be positive" }
        require(maximumSessions > 0) { "Maximum sessions must be positive" }
        require(maximumCommands > 0) { "Maximum command count must be positive" }
        require(maximumOutputEvents > 0) { "Maximum output event count must be positive" }
    }
}

data class PlaygroundPreset(
    val id: String,
    val packs: List<Path>,
)

data class PlaygroundConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val javaCommand: String = "java",
    val cliJar: Path,
    val allowedOrigins: Set<String> = setOf("http://127.0.0.1:5173", "http://localhost:5173"),
    val allowedProfiles: Set<String> = setOf("26.2"),
    val presets: Map<String, PlaygroundPreset> = emptyMap(),
    val limits: PlaygroundLimits = PlaygroundLimits(),
) {
    init {
        require(port in 1..65_535) { "Port must be between 1 and 65535" }
        require(allowedProfiles.isNotEmpty()) { "At least one Minecraft profile must be enabled" }
    }

    fun originAllowed(origin: String?): Boolean = origin != null && ("*" in allowedOrigins || origin in allowedOrigins)

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): PlaygroundConfig {
            val cliJar = Path.of(environment["DPS_CLI_JAR"] ?: "/app/datapack-sandbox-cli.jar").toAbsolutePath().normalize()
            require(cliJar.exists() && cliJar.isRegularFile()) {
                "DPS_CLI_JAR must point to the standalone CLI jar: $cliJar"
            }
            val presets = environment["DPS_PRESETS_FILE"]?.takeIf(String::isNotBlank)?.let(::readPresets).orEmpty()
            return PlaygroundConfig(
                host = environment["DPS_HOST"] ?: "0.0.0.0",
                port = environment.positiveInt("DPS_PORT", 8080),
                javaCommand = environment["DPS_JAVA_COMMAND"] ?: "java",
                cliJar = cliJar,
                allowedOrigins = environment.csv("DPS_ALLOWED_ORIGINS", setOf("http://127.0.0.1:5173", "http://localhost:5173")),
                allowedProfiles = environment.csv("DPS_ALLOWED_PROFILES", setOf("26.2")),
                presets = presets,
                limits =
                    PlaygroundLimits(
                        idleTimeout = environment.positiveLong("DPS_IDLE_TIMEOUT_MS", 600_000).milliseconds,
                        executionTimeout = environment.positiveLong("DPS_EXECUTION_TIMEOUT_MS", 5_000).milliseconds,
                        maximumCellBytes = environment.positiveInt("DPS_MAX_CELL_BYTES", 65_536),
                        maximumOutputBytes = environment.positiveInt("DPS_MAX_OUTPUT_BYTES", 1_048_576),
                        maximumRenderWidth = environment.positiveInt("DPS_MAX_RENDER_WIDTH", 1_920),
                        maximumRenderHeight = environment.positiveInt("DPS_MAX_RENDER_HEIGHT", 1_080),
                        maximumRequestsPerMinute = environment.positiveInt("DPS_REQUESTS_PER_MINUTE", 120),
                        maximumSessions = environment.positiveInt("DPS_MAX_SESSIONS", 64),
                        maximumCommands = environment.positiveInt("DPS_MAX_COMMANDS", 10_000),
                        maximumOutputEvents = environment.positiveInt("DPS_MAX_OUTPUT_EVENTS", 2_000),
                    ),
            )
        }

        private fun readPresets(rawPath: String): Map<String, PlaygroundPreset> {
            val path = Path.of(rawPath).toAbsolutePath().normalize()
            require(path.exists() && path.isRegularFile()) { "DPS_PRESETS_FILE does not exist: $path" }
            val root = JsonParser.parseString(path.toFile().readText(Charsets.UTF_8))
            require(root.isJsonObject) { "DPS_PRESETS_FILE must contain a JSON object" }
            return root.asJsonObject.entrySet().associate { (id, value) ->
                require(PRESET_ID.matches(id)) { "Invalid preset id '$id'" }
                require(value.isJsonObject) { "Preset '$id' must be an object" }
                val packs = value.asJsonObject.getAsJsonArray("packs") ?: error("Preset '$id' must define packs")
                val paths =
                    packs.mapIndexed { index, element ->
                        require(element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                            "Preset '$id' packs[$index] must be a path string"
                        }
                        Path.of(element.asString).toAbsolutePath().normalize().also { pack ->
                            require(pack.exists() && pack.isDirectory()) { "Preset '$id' pack does not exist: $pack" }
                        }
                    }
                id to PlaygroundPreset(id, paths)
            }
        }

        private val PRESET_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    }
}

private fun Map<String, String>.csv(
    name: String,
    default: Set<String>,
): Set<String> =
    get(name)
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.toSet()
        ?.takeIf(Set<String>::isNotEmpty)
        ?: default

private fun Map<String, String>.positiveInt(
    name: String,
    default: Int,
): Int {
    val raw = get(name) ?: return default
    val value = raw.toIntOrNull() ?: error("$name must be a positive integer")
    require(value > 0) { "$name must be a positive integer" }
    return value
}

private fun Map<String, String>.positiveLong(
    name: String,
    default: Long,
): Long {
    val raw = get(name) ?: return default
    val value = raw.toLongOrNull() ?: error("$name must be a positive integer")
    require(value > 0) { "$name must be a positive integer" }
    return value
}
