package moe.afox.dpsandbox.playground

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

internal fun Application.playgroundModule(
    config: PlaygroundConfig,
    backendFactory: SandboxBackendFactory = ServeProcessFactory(config),
) {
    install(WebSockets) {
        pingPeriodMillis = 20.seconds.inWholeMilliseconds
        timeoutMillis = 20.seconds.inWholeMilliseconds
        maxFrameSize = (config.limits.maximumCellBytes + 32_768).toLong()
        masking = false
    }
    val registry = SessionRegistry(config.limits.maximumSessions)
    val gson = Gson()
    routing {
        get("/health") {
            val origin = call.request.header(HttpHeaders.Origin)
            if (origin != null && config.originAllowed(origin)) call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, origin)
            call.respondText(
                gson.toJson(
                    JsonObject().also { json ->
                        json.addProperty("status", "ok")
                        json.addProperty("activeSessions", registry.active)
                    },
                ),
                ContentType.Application.Json,
            )
        }
        options("/health") {
            val origin = call.request.header(HttpHeaders.Origin)
            if (config.originAllowed(origin)) {
                call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, origin!!)
                call.response.headers.append(HttpHeaders.AccessControlAllowMethods, "GET, OPTIONS")
            }
            call.respondText("")
        }
        webSocket("/v1/playground") {
            val origin = call.request.header(HttpHeaders.Origin)
            if (!config.originAllowed(origin)) {
                send(gson.toJson(errorEvent(null, code = "ORIGIN_NOT_ALLOWED", message = "WebSocket origin is not allowed", recoverable = false)))
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Origin not allowed"))
                return@webSocket
            }

            val sendMutex = kotlinx.coroutines.sync.Mutex()

            suspend fun sendJson(json: JsonObject) {
                sendMutex.lock()
                try {
                    send(Frame.Text(gson.toJson(json)))
                } finally {
                    sendMutex.unlock()
                }
            }

            var session: PlaygroundSession? = null
            var execution: Job? = null
            var slotHeld = false
            val rateLimiter = RequestRateLimiter(config.limits.maximumRequestsPerMinute)
            var closeRequested = false
            try {
                while (!closeRequested) {
                    val frame =
                        try {
                            withTimeoutOrNull(config.limits.idleTimeout) { incoming.receive() }
                        } catch (_: ClosedReceiveChannelException) {
                            break
                        }
                    if (frame == null) {
                        sendJson(
                            event("session.closed") {
                                addProperty("code", "IDLE_TIMEOUT")
                                addProperty("message", "The anonymous playground session expired after being idle")
                            },
                        )
                        close(CloseReason(CloseReason.Codes.NORMAL, "Idle timeout"))
                        break
                    }
                    if (frame !is Frame.Text) {
                        sendJson(errorEvent(null, code = "INVALID_REQUEST", message = "Only text WebSocket frames are accepted", recoverable = true))
                        continue
                    }
                    val request =
                        try {
                            PlaygroundRequest.parse(frame.readText(), config.limits.maximumCellBytes + 16_384)
                        } catch (error: ProtocolException) {
                            sendJson(errorEvent(null, code = error.code, message = error.message, recoverable = error.recoverable))
                            continue
                        }
                    if (!rateLimiter.tryAcquire()) {
                        sendJson(
                            errorEvent(
                                request.id,
                                request.body.string("cellId"),
                                "RATE_LIMIT",
                                "Too many requests; retry after the one-minute window advances",
                                true,
                            ),
                        )
                        continue
                    }

                    try {
                        when (request.type) {
                            "session.create" -> {
                                if (session != null) throw ProtocolException("SESSION_EXISTS", "This connection already owns a session")
                                if (!registry.tryAcquire()) throw ProtocolException("SERVER_BUSY", "The server has reached its session limit")
                                slotHeld = true
                                try {
                                    session = PlaygroundSession.create(request, config, backendFactory)
                                } catch (error: Exception) {
                                    registry.release()
                                    slotHeld = false
                                    throw error
                                }
                                sendJson(sessionReady(request.id, session, config, "created"))
                            }

                            "cell.execute" -> {
                                val active = session.requireSession()
                                if (execution?.isActive == true) throw ProtocolException("BUSY", "A cell is already running")
                                val cellId = request.body.requiredCellId()
                                val source = request.body.requiredString("source")
                                val render = parseRenderOptions(request.body.objectValue("render"), active.defaultRender)
                                if (render.auto) active.validateRender(render)
                                execution =
                                    launch {
                                        executeCell(request.id, cellId, source, render, active, ::sendJson)
                                    }
                            }

                            "cell.complete" -> {
                                val active = session.requireAvailable(execution)
                                val cellId = request.body.requiredCellId()
                                val buffer = request.body.string("source") ?: request.body.string("buffer").orEmpty()
                                val response = active.complete(buffer, request.body.int("cursor") ?: buffer.length).requireBackendSuccess()
                                val result = response.get("result")
                                val output =
                                    event("cell.output", request.id) {
                                        addProperty("cellId", cellId)
                                        addProperty("kind", "completion")
                                        add("result", result)
                                    }
                                if (!active.outputWithinLimit(output)) {
                                    throw ProtocolException("OUTPUT_LIMIT", "Completion output exceeded the configured response-size limit")
                                }
                                sendJson(output)
                            }

                            "cell.check" -> {
                                val active = session.requireAvailable(execution)
                                val cellId = request.body.requiredCellId()
                                val diagnostics = active.check(request.body.requiredString("source"))
                                val output =
                                    event("diagnostic", request.id) {
                                        addProperty("cellId", cellId)
                                        add("diagnostics", diagnostics)
                                    }
                                if (!active.outputWithinLimit(output)) {
                                    throw ProtocolException("OUTPUT_LIMIT", "Diagnostics exceeded the configured response-size limit")
                                }
                                sendJson(output)
                            }

                            "cell.render" -> {
                                val active = session.requireAvailable(execution)
                                val cellId = request.body.string("cellId")?.validateCellId()
                                val options = parseRenderOptions(request.body.objectValue("render") ?: request.body, active.defaultRender)
                                active.validateRender(options)
                                sendRender(request.id, cellId, active, options, ::sendJson)
                            }

                            "session.interrupt" -> {
                                val active = session.requireSession()
                                if (execution?.isActive == true) {
                                    active.requestInterrupt()
                                    sendJson(event("cell.status", request.id) { addProperty("status", "interrupting") })
                                    active.interrupt().requireBackendSuccess()
                                } else {
                                    sendJson(event("cell.status", request.id) { addProperty("status", "idle") })
                                }
                            }

                            "session.reset" -> {
                                val active = session.requireSession()
                                if (execution?.isActive == true) {
                                    runCatching { active.interrupt() }
                                    execution.cancelAndJoin()
                                }
                                active.reset().requireBackendSuccess()
                                sendJson(sessionReady(request.id, active, config, "reset"))
                            }

                            "session.close" -> {
                                sendJson(
                                    event("session.closed", request.id) {
                                        session?.let { addProperty("sessionId", it.id) }
                                        addProperty("code", "CLOSED")
                                    },
                                )
                                closeRequested = true
                                close(CloseReason(CloseReason.Codes.NORMAL, "Session closed"))
                            }

                            else -> throw ProtocolException("INVALID_REQUEST", "Unknown request type '${request.type}'")
                        }
                    } catch (error: ProtocolException) {
                        sendJson(
                            errorEvent(
                                request.id,
                                request.body.string("cellId"),
                                error.code,
                                error.message,
                                error.recoverable,
                                error.details,
                            ),
                        )
                    } catch (_: TimeoutCancellationException) {
                        val stopped = session?.interruptAfterTimeout() ?: true
                        if (!stopped) session.close()
                        sendJson(
                            if (stopped) {
                                errorEvent(
                                    request.id,
                                    request.body.string("cellId"),
                                    "EXECUTION_TIMEOUT",
                                    "The playground operation exceeded its time limit",
                                    true,
                                )
                            } else {
                                errorEvent(
                                    request.id,
                                    request.body.string("cellId"),
                                    "SESSION_LOST",
                                    "The timed-out sandbox process did not stop and was destroyed",
                                    false,
                                )
                            },
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: BackendUnavailableException) {
                        sendJson(
                            errorEvent(
                                request.id,
                                request.body.string("cellId"),
                                "SESSION_LOST",
                                error.message ?: "The sandbox process was lost",
                                false,
                            ),
                        )
                    } catch (error: Exception) {
                        sendJson(
                            errorEvent(
                                request.id,
                                request.body.string("cellId"),
                                "INTERNAL_ERROR",
                                error.message ?: "Unexpected playground error",
                                false,
                            ),
                        )
                    }
                }
            } finally {
                execution?.cancel()
                session?.close()
                if (slotHeld) registry.release()
            }
        }
    }
}

private suspend fun executeCell(
    requestId: JsonElement,
    cellId: String,
    source: String,
    render: RenderOptions,
    session: PlaygroundSession,
    send: suspend (JsonObject) -> Unit,
) {
    send(
        event("cell.status", requestId) {
            addProperty("cellId", cellId)
            addProperty("status", "running")
        },
    )
    try {
        val response = session.execute(cellId, source)
        if (response.get("ok")?.asBoolean != true) {
            val backendError = response.getAsJsonObject("error")
            val interrupted = session.consumeInterrupt() || backendError?.string("code") == "EXECUTION_INTERRUPTED"
            val code = if (interrupted) "INTERRUPTED" else backendError?.string("code") ?: "COMMAND_ERROR"
            val message = backendError?.string("message") ?: "Cell execution failed"
            val location = backendError?.getAsJsonObject("location")
            send(
                event("diagnostic", requestId) {
                    addProperty("cellId", cellId)
                    add(
                        "diagnostics",
                        JsonArray().also { diagnostics ->
                            diagnostics.add(
                                backendErrorDiagnostic(
                                    location?.int("line") ?: 1,
                                    location?.string("command") ?: "",
                                    backendError,
                                ),
                            )
                        },
                    )
                },
            )
            send(errorEvent(requestId, cellId, code, message, true, backendError?.takeIf(session::outputWithinLimit)))
            return
        }
        val result = response.getAsJsonObject("result")
        val summary = executionSummary(result)
        val output =
            event("cell.output", requestId) {
                addProperty("cellId", cellId)
                addProperty("kind", "execution")
                addProperty("summary", summary)
                add("result", result)
            }
        if (!session.outputWithinLimit(output)) {
            send(
                errorEvent(
                    requestId,
                    cellId,
                    "OUTPUT_LIMIT",
                    "Cell output exceeded the configured response-size limit",
                    true,
                    JsonObject().also { it.addProperty("summary", summary) },
                ),
            )
            return
        }
        send(output)
        if (render.auto) sendRender(requestId, cellId, session, render, send)
    } catch (error: TimeoutCancellationException) {
        if (session.interruptAfterTimeout()) {
            session.markResetRequired()
            send(
                errorEvent(
                    requestId,
                    cellId,
                    "RESET_REQUIRED",
                    "Cell execution exceeded the time limit; reset the session before continuing",
                    true,
                    JsonObject().also { it.addProperty("cause", "EXECUTION_TIMEOUT") },
                ),
            )
        } else {
            session.close()
            send(
                errorEvent(
                    requestId,
                    cellId,
                    "SESSION_LOST",
                    "The timed-out sandbox process did not stop and was destroyed",
                    false,
                ),
            )
        }
    } catch (error: ProtocolException) {
        send(errorEvent(requestId, cellId, error.code, error.message, error.recoverable, error.details))
    } catch (error: BackendUnavailableException) {
        send(errorEvent(requestId, cellId, "SESSION_LOST", error.message ?: "The sandbox process was lost", false))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        send(errorEvent(requestId, cellId, "SESSION_LOST", error.message ?: "The sandbox process was lost", false))
    } finally {
        send(
            event("cell.status", requestId) {
                addProperty("cellId", cellId)
                addProperty("status", "idle")
            },
        )
    }
}

private suspend fun sendRender(
    requestId: JsonElement,
    cellId: String?,
    session: PlaygroundSession,
    options: RenderOptions,
    send: suspend (JsonObject) -> Unit,
) {
    val response = session.render(options).requireBackendSuccess()
    val result = response.getAsJsonObject("result")
    val output =
        event("cell.render", requestId) {
            cellId?.let { addProperty("cellId", it) }
            result.entrySet().forEach { (name, value) -> add(name, value.deepCopy()) }
        }
    if (!session.outputWithinLimit(output)) {
        send(errorEvent(requestId, cellId, "OUTPUT_LIMIT", "Rendered PNG exceeded the configured response-size limit", true))
        return
    }
    send(output)
}

private fun executionSummary(result: JsonObject): String {
    val commands = result.int("commands") ?: 0
    val outputs = result.getAsJsonArray("outputs")?.size() ?: 0
    val changes = result.getAsJsonArray("snapshotDiffs")?.size() ?: 0
    return "Executed $commands command${if (commands == 1) "" else "s"}; $outputs output${if (outputs == 1) "" else "s"}; $changes state change${if (changes == 1) "" else "s"}."
}

private fun sessionReady(
    requestId: JsonElement,
    session: PlaygroundSession,
    config: PlaygroundConfig,
    reason: String,
): JsonObject =
    event("session.ready", requestId) {
        addProperty("sessionId", session.id)
        addProperty("version", session.version)
        session.preset?.let { addProperty("preset", it) }
        addProperty("reason", reason)
        add("profiles", JsonArray().also { profiles -> config.allowedProfiles.sorted().forEach(profiles::add) })
        add(
            "presets",
            JsonArray().also { presets ->
                config.presets.keys
                    .sorted()
                    .forEach(presets::add)
            },
        )
        add(
            "limits",
            JsonObject().also { limits ->
                limits.addProperty("idleTimeoutMs", config.limits.idleTimeout.inWholeMilliseconds)
                limits.addProperty("executionTimeoutMs", config.limits.executionTimeout.inWholeMilliseconds)
                limits.addProperty("maximumCellBytes", config.limits.maximumCellBytes)
                limits.addProperty("maximumOutputBytes", config.limits.maximumOutputBytes)
                limits.addProperty("maximumRenderWidth", config.limits.maximumRenderWidth)
                limits.addProperty("maximumRenderHeight", config.limits.maximumRenderHeight)
            },
        )
        add(
            "capabilities",
            JsonObject().also { capabilities ->
                capabilities.addProperty("completion", true)
                capabilities.addProperty("diagnostics", true)
                capabilities.addProperty("render", true)
                capabilities.addProperty("renderMimeType", "image/png")
                capabilities.addProperty("visualParity", false)
            },
        )
    }

private fun PlaygroundSession?.requireSession(): PlaygroundSession =
    this ?: throw ProtocolException("SESSION_REQUIRED", "Create a session before using the playground")

private fun PlaygroundSession?.requireAvailable(execution: Job?): PlaygroundSession {
    val active = requireSession()
    if (execution?.isActive == true) throw ProtocolException("BUSY", "A cell is currently running")
    if (active.requiresReset) throw ProtocolException("RESET_REQUIRED", "Reset the session before continuing")
    return active
}

private fun JsonObject.requiredString(name: String): String =
    string(name) ?: throw ProtocolException("INVALID_REQUEST", "Request is missing string '$name'")

private fun JsonObject.requiredCellId(): String = requiredString("cellId").validateCellId()

private fun String.validateCellId(): String {
    if (isBlank() || length > 128 || any(Char::isISOControl)) {
        throw ProtocolException("INVALID_REQUEST", "cellId must contain 1 to 128 non-control characters")
    }
    return this
}

private class SessionRegistry(
    private val maximum: Int,
) {
    private val count = AtomicInteger()

    val active: Int
        get() = count.get()

    fun tryAcquire(): Boolean {
        while (true) {
            val current = count.get()
            if (current >= maximum) return false
            if (count.compareAndSet(current, current + 1)) return true
        }
    }

    fun release() {
        count.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }
}

internal class RequestRateLimiter(
    private val maximum: Int,
) {
    private val timestamps = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(nowNanos: Long = System.nanoTime()): Boolean {
        val cutoff = nowNanos - 60.seconds.inWholeNanoseconds
        while (timestamps.firstOrNull()?.let { it <= cutoff } == true) timestamps.removeFirst()
        if (timestamps.size >= maximum) return false
        timestamps.addLast(nowNanos)
        return true
    }
}
