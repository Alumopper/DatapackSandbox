package moe.afox.dpsandbox.playground

import com.google.gson.Gson
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal interface SandboxBackend : AutoCloseable {
    val isAlive: Boolean

    suspend fun request(
        method: String,
        params: JsonObject = JsonObject(),
        timeout: Duration,
    ): JsonObject

    suspend fun interrupt(timeout: Duration = 2.seconds): JsonObject = request("interrupt", timeout = timeout)
}

internal fun interface SandboxBackendFactory {
    suspend fun open(): SandboxBackend
}

internal class ServeProcessFactory(
    private val config: PlaygroundConfig,
) : SandboxBackendFactory {
    override suspend fun open(): SandboxBackend = ServeProcess.start(config.javaCommand, config.cliJar.toString())
}

internal class BackendUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class ServeProcess private constructor(
    private val process: Process,
) : SandboxBackend {
    private val gson = Gson()
    private val output: BufferedWriter = process.outputWriter(StandardCharsets.UTF_8)
    private val sequence = AtomicLong()
    private val closed = AtomicBoolean()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val writeMutex = Mutex()
    private val hello = CompletableDeferred<JsonObject>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val standardError = StringBuilder()

    override val isAlive: Boolean
        get() = !closed.get() && process.isAlive

    private fun startReaders() {
        scope.launch {
            try {
                process.inputReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.filter(String::isNotBlank).forEach(::routeResponse)
                }
                failPending("The serve process closed its output stream")
            } catch (_: CancellationException) {
                // Normal when the owning WebSocket closes the process.
            } catch (error: Exception) {
                failPending("Failed while reading the serve process", error)
            }
        }
        scope.launch {
            process.errorReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    synchronized(standardError) {
                        if (standardError.length < MAX_STDERR_CHARS) standardError.appendLine(line)
                    }
                }
            }
        }
        process.onExit().thenRun {
            failPending("The serve process exited with code ${process.exitValue()}${stderrSuffix()}")
        }
    }

    private fun routeResponse(line: String) {
        val response =
            try {
                JsonParser.parseString(line).asJsonObject
            } catch (error: Exception) {
                failPending("The serve process returned invalid JSON", error)
                return
            }
        val id = response.get("id")
        if (id == null || id is JsonNull) {
            if (!hello.isCompleted) hello.complete(response)
            return
        }
        pending.remove(id.asString)?.complete(response)
    }

    override suspend fun request(
        method: String,
        params: JsonObject,
        timeout: Duration,
    ): JsonObject {
        if (!isAlive) throw BackendUnavailableException("The serve process is not running${stderrSuffix()}")
        val id = sequence.incrementAndGet().toString()
        val response = CompletableDeferred<JsonObject>()
        pending[id] = response
        val request =
            JsonObject().also { json ->
                json.addProperty("id", id)
                json.addProperty("method", method)
                json.add("params", params)
            }
        try {
            try {
                writeMutex.withLock {
                    withContext(Dispatchers.IO) {
                        output.write(gson.toJson(request))
                        output.newLine()
                        output.flush()
                    }
                }
            } catch (error: IOException) {
                throw BackendUnavailableException("Failed to write to the serve process${stderrSuffix()}", error)
            }
            return withTimeout(timeout) { response.await() }
        } finally {
            pending.remove(id, response)
        }
    }

    private fun failPending(
        message: String,
        cause: Throwable? = null,
    ) {
        val failure = BackendUnavailableException(message, cause)
        if (!hello.isCompleted) hello.completeExceptionally(failure)
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
    }

    private fun stderrSuffix(): String =
        synchronized(standardError) {
            standardError
                .toString()
                .trim()
                .takeIf(String::isNotEmpty)
                ?.let { ": ${it.takeLast(2_000)}" }
                .orEmpty()
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { output.close() }
        process.descendants().use { children -> children.forEach { child -> runCatching { child.destroy() } } }
        process.destroy()
        if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
            process.descendants().use { children -> children.forEach { child -> runCatching { child.destroyForcibly() } } }
            process.destroyForcibly()
        }
        failPending("The serve process was closed")
        scope.cancel()
    }

    companion object {
        private const val MAX_STDERR_CHARS = 32_768

        suspend fun start(
            javaCommand: String,
            cliJar: String,
        ): ServeProcess {
            val process =
                withContext(Dispatchers.IO) {
                    ProcessBuilder(javaCommand, "-jar", cliJar, "serve", "--protocol", "jsonl").start()
                }
            val backend = ServeProcess(process)
            backend.startReaders()
            try {
                val greeting = withTimeout(10.seconds) { backend.hello.await() }
                if (greeting.get("ok")?.asBoolean != true) {
                    throw BackendUnavailableException("The serve process rejected protocol startup: $greeting")
                }
                return backend
            } catch (error: CancellationException) {
                backend.close()
                throw error
            } catch (error: Exception) {
                backend.close()
                throw BackendUnavailableException("The serve process did not become ready", error)
            }
        }
    }
}
