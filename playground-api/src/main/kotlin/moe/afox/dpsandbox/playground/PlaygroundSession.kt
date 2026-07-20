package moe.afox.dpsandbox.playground

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

internal data class RenderOptions(
    val auto: Boolean = false,
    val width: Int = 960,
    val height: Int = 540,
)

internal class PlaygroundSession private constructor(
    val id: String,
    val version: String,
    val preset: String?,
    val siteId: String?,
    val defaultRender: RenderOptions,
    private val config: PlaygroundConfig,
    private val backend: SandboxBackend,
) : AutoCloseable {
    private val resetRequired = AtomicBoolean()
    private val interrupted = AtomicBoolean()

    val isAlive: Boolean
        get() = backend.isAlive

    val requiresReset: Boolean
        get() = resetRequired.get()

    fun requestInterrupt() {
        interrupted.set(true)
    }

    fun consumeInterrupt(): Boolean = interrupted.getAndSet(false)

    fun markResetRequired() {
        resetRequired.set(true)
    }

    suspend fun execute(
        cellId: String,
        source: String,
    ): JsonObject {
        ensureUsable()
        validateCell(source)
        val functionId = "playground:cell_${cellId.sha256Prefix()}"
        val upsert =
            JsonObject().also { params ->
                params.addProperty("id", functionId)
                params.addProperty("text", source)
                params.addProperty("sourceName", "<playground:$cellId>")
            }
        return withTimeout(config.limits.executionTimeout) {
            backend.request("upsertFunctionSource", upsert, config.limits.executionTimeout).requireBackendSuccess()
            backend.request(
                "runFunction",
                JsonObject().also { it.addProperty("id", functionId) },
                config.limits.executionTimeout,
            )
        }
    }

    suspend fun complete(
        buffer: String,
        cursor: Int,
    ): JsonObject {
        ensureUsable()
        validateCell(buffer)
        return backend.request(
            "completions",
            JsonObject().also { params ->
                params.addProperty("buffer", buffer)
                params.addProperty("cursor", cursor.coerceIn(0, buffer.length))
            },
            config.limits.executionTimeout,
        )
    }

    suspend fun check(source: String): JsonArray {
        ensureUsable()
        validateCell(source)
        return withTimeout(config.limits.executionTimeout) {
            val diagnostics = JsonArray()
            source.lineSequence().forEachIndexed { index, rawLine ->
                val command = rawLine.removePrefix("\uFEFF").trim().removePrefix("/")
                if (command.isBlank() || command.startsWith("#")) return@forEachIndexed
                val response =
                    backend.request(
                        "checkCommand",
                        JsonObject().also { it.addProperty("command", command) },
                        config.limits.executionTimeout,
                    )
                if (response.get("ok")?.asBoolean != true) {
                    diagnostics.add(backendErrorDiagnostic(index + 1, command, response.getAsJsonObject("error")))
                } else {
                    val result = response.getAsJsonObject("result")
                    if (result.get("valid")?.asBoolean != true) {
                        diagnostics.add(
                            JsonObject().also { item ->
                                item.addProperty("line", index + 1)
                                item.addProperty("from", 0)
                                item.addProperty("to", rawLine.length)
                                item.addProperty("severity", result.string("severity") ?: "error")
                                item.addProperty("code", result.string("code") ?: "COMMAND_ERROR")
                                item.addProperty("message", result.string("message") ?: "Command is invalid")
                                item.addProperty("command", command)
                            },
                        )
                    }
                }
            }
            diagnostics
        }
    }

    suspend fun render(options: RenderOptions): JsonObject {
        ensureUsable()
        validateRender(options)
        return backend.request(
            "render",
            JsonObject().also { params ->
                params.addProperty("width", options.width)
                params.addProperty("height", options.height)
            },
            config.limits.executionTimeout,
        )
    }

    suspend fun interrupt(): JsonObject {
        requestInterrupt()
        return backend.interrupt(2.seconds)
    }

    suspend fun reset(): JsonObject {
        if (!backend.isAlive) throw ProtocolException("SESSION_LOST", "The sandbox process is no longer running", recoverable = false)
        val response = backend.request("resetWorld", timeout = config.limits.executionTimeout)
        if (response.get("ok")?.asBoolean == true) {
            resetRequired.set(false)
            interrupted.set(false)
        }
        return response
    }

    suspend fun interruptAfterTimeout(): Boolean =
        withContext(NonCancellable) {
            runCatching {
                backend.interrupt(2.seconds).requireBackendSuccess()
                backend.request("state", timeout = 2.seconds).get("ok")?.asBoolean == true
            }.getOrDefault(false)
        }

    fun validateRender(options: RenderOptions) {
        if (options.width !in 16..config.limits.maximumRenderWidth || options.height !in 16..config.limits.maximumRenderHeight) {
            throw ProtocolException(
                "RENDER_SIZE_LIMIT",
                "Render size ${options.width}x${options.height} exceeds the configured ${config.limits.maximumRenderWidth}x${config.limits.maximumRenderHeight} limit",
            )
        }
    }

    fun outputWithinLimit(value: JsonElement): Boolean =
        Gson().toJson(value).toByteArray(StandardCharsets.UTF_8).size <= config.limits.maximumOutputBytes

    private fun validateCell(source: String) {
        val bytes = source.toByteArray(StandardCharsets.UTF_8).size
        if (bytes > config.limits.maximumCellBytes) {
            throw ProtocolException("CELL_TOO_LARGE", "Cell is $bytes bytes; maximum is ${config.limits.maximumCellBytes}")
        }
    }

    private fun ensureUsable() {
        if (!backend.isAlive) throw ProtocolException("SESSION_LOST", "The sandbox process is no longer running", recoverable = false)
        if (resetRequired.get()) throw ProtocolException("RESET_REQUIRED", "Reset the session before running another operation")
    }

    override fun close() {
        backend.close()
    }

    companion object {
        suspend fun create(
            request: PlaygroundRequest,
            config: PlaygroundConfig,
            factory: SandboxBackendFactory,
        ): PlaygroundSession {
            val version =
                request.body.string("version")
                    ?: "26.2".takeIf(config.allowedProfiles::contains)
                    ?: config.allowedProfiles.sorted().last()
            if (version !in config.allowedProfiles) {
                throw ProtocolException("PROFILE_NOT_ALLOWED", "Profile '$version' is not enabled on this server")
            }
            val presetId = request.body.string("preset")
            val preset =
                presetId?.let {
                    config.presets[it] ?: throw ProtocolException("PRESET_NOT_ALLOWED", "Preset '$it' is not enabled on this server")
                }
            val siteId =
                request.body.string("siteId")?.also { value ->
                    if (value.length > 128) throw ProtocolException("INVALID_REQUEST", "siteId must be at most 128 characters")
                    if (value.any(Char::isISOControl)) throw ProtocolException("INVALID_REQUEST", "siteId must not contain control characters")
                }
            val render = parseRenderOptions(request.body.objectValue("render"), RenderOptions())
            val backend = factory.open()
            try {
                val params =
                    JsonObject().also { json ->
                        json.addProperty("version", version)
                        json.add("packs", JsonArray().also { packs -> preset?.packs?.forEach { packs.add(it.toString()) } })
                        json.addProperty("unsupported", "warn")
                        json.add(
                            "limits",
                            JsonObject().also { limits ->
                                limits.addProperty("maxCommands", config.limits.maximumCommands)
                                limits.addProperty("maxOutputEvents", config.limits.maximumOutputEvents)
                            },
                        )
                    }
                backend.request("createSandbox", params, 10.seconds).requireBackendSuccess()
                if (preset != null) backend.request("load", timeout = config.limits.executionTimeout).requireBackendSuccess()
                return PlaygroundSession(
                    id = UUID.randomUUID().toString(),
                    version = version,
                    preset = presetId,
                    siteId = siteId,
                    defaultRender = render,
                    config = config,
                    backend = backend,
                )
            } catch (error: Exception) {
                backend.close()
                throw error
            }
        }
    }
}

internal fun parseRenderOptions(
    value: JsonObject?,
    defaults: RenderOptions,
): RenderOptions =
    value?.let {
        RenderOptions(
            auto = it.boolean("auto") ?: defaults.auto,
            width = it.int("width") ?: defaults.width,
            height = it.int("height") ?: defaults.height,
        )
    } ?: defaults

internal fun JsonObject.requireBackendSuccess(): JsonObject {
    if (get("ok")?.asBoolean == true) return this
    val error = getAsJsonObject("error")
    throw ProtocolException(
        code = error?.string("code") ?: "SESSION_LOST",
        message = error?.string("message") ?: "The sandbox backend rejected the request",
        recoverable = error != null,
        details = error,
    )
}

internal fun backendErrorDiagnostic(
    line: Int,
    command: String,
    error: JsonObject?,
): JsonObject =
    JsonObject().also { item ->
        val location = error?.getAsJsonObject("location")
        item.addProperty("line", location?.int("line") ?: line)
        item.addProperty("from", 0)
        item.addProperty("to", command.length)
        item.addProperty("severity", "error")
        item.addProperty("code", error?.string("code") ?: "COMMAND_ERROR")
        item.addProperty("message", error?.string("message") ?: "Command failed")
        item.addProperty("command", error?.string("command") ?: command)
    }

private fun String.sha256Prefix(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .take(10)
        .joinToString("") { "%02x".format(it) }
