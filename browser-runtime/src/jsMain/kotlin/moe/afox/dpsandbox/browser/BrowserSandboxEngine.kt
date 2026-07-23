@file:OptIn(ExperimentalJsExport::class)

package moe.afox.dpsandbox.browser

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import moe.afox.dpsandbox.engine.BrowserLimits
import moe.afox.dpsandbox.engine.EngineSession
import moe.afox.dpsandbox.render.engine.EngineRenderBlock
import moe.afox.dpsandbox.render.engine.EngineDisplayData
import moe.afox.dpsandbox.render.engine.EngineRenderEntity
import moe.afox.dpsandbox.render.engine.EngineRenderFrame
import moe.afox.dpsandbox.render.engine.EngineRenderWorld
import moe.afox.dpsandbox.render.engine.AnimatedGifEncoder
import moe.afox.dpsandbox.render.engine.EngineGifFrame
import moe.afox.dpsandbox.render.engine.SoftwareWorldRenderer
import moe.afox.dpsandbox.render.engine.RealtimeRenderScene
import moe.afox.dpsandbox.render.engine.RealtimeMaterialBatch

/** Narrow structured-clone friendly boundary consumed by the module Worker. */
@JsExport
class BrowserSandboxEngine(
    val version: String,
    rootsCsv: String,
    blocksCsv: String,
    itemsCsv: String,
    entitiesCsv: String,
    maximumCellBytes: Int,
    maximumOutputBytes: Int,
    maximumCommands: Int,
    maximumOutputEvents: Int,
    maximumRenderWidth: Int,
    maximumRenderHeight: Int,
    maximumCheckpoints: Int,
    maximumCheckpointBytes: Int,
    maximumAnimationFrames: Int,
    maximumAnimationBytes: Int,
) {
    private val limits =
        BrowserLimits(
            maximumCellBytes = maximumCellBytes,
            maximumOutputBytes = maximumOutputBytes,
            maximumCommands = maximumCommands,
            maximumOutputEvents = maximumOutputEvents,
            maximumRenderWidth = maximumRenderWidth,
            maximumRenderHeight = maximumRenderHeight,
            maximumCheckpoints = maximumCheckpoints,
            maximumCheckpointBytes = maximumCheckpointBytes,
            maximumAnimationFrames = maximumAnimationFrames,
            maximumAnimationBytes = maximumAnimationBytes,
        )
    private val session =
        EngineSession(version, limits).also { engine ->
            engine.configure(rootsCsv.csv(), blocksCsv.csv(), itemsCsv.csv(), entitiesCsv.csv())
        }
    private val renderer = SoftwareWorldRenderer()
    private var lastFrame: EngineRenderFrame? = null
    private var realtimeScene: RealtimeRenderScene? = null
    private val animationFrames = mutableListOf<EngineGifFrame>()
    private var animationBytes = 0

    fun beginExecution() = session.beginExecution()

    fun executeLine(
        source: String,
        line: Int,
    ) = session.executeLine(source, line)

    fun executeLineSafe(
        source: String,
        line: Int,
    ): String = session.executeLineSafeJson(source, line)

    fun finishExecution(): String = session.finishExecutionJson()

    fun check(source: String): String = session.checkJson(source)

    fun complete(
        source: String,
        cursor: Int,
    ): String = session.completionJson(source, cursor)

    fun interrupt() = session.interrupt()

    fun reset() {
        session.reset()
        animationFrames.clear()
        animationBytes = 0
        lastFrame = null
        realtimeScene = null
    }

    fun saveCheckpoint(name: String): String = session.saveCheckpointResultJson(name)

    fun restoreCheckpoint(name: String): String = session.restoreCheckpointResultJson(name).also { lastFrame = null }

    fun deleteCheckpoint(name: String): String = session.deleteCheckpointResultJson(name)

    fun checkpointNames(): String = session.checkpointNamesJson()

    fun upsertTexture(
        id: String,
        width: Int,
        height: Int,
        rgba: ByteArray,
    ) {
        renderer.registerTexture(id, width, height, rgba)
        lastFrame = null
        realtimeScene = null
    }

    fun upsertRenderAsset(
        path: String,
        text: String,
    ) {
        renderer.registerAssetText(path, text)
        lastFrame = null
        realtimeScene = null
    }

    fun upsertFunction(
        id: String,
        source: String,
    ) = session.upsertFunction(id, source)

    fun clearFunctions() = session.clearFunctions()

    fun setFunctionTag(
        id: String,
        valuesCsv: String,
    ) = session.setFunctionTag(id, valuesCsv.csv())

    fun runLoad(): String = session.runLoadJson().also { realtimeScene = null }

    fun runTicks(
        count: Int,
        tickFunction: String?,
    ): String = session.runSimulationTicksJson(count, tickFunction).also { realtimeScene = null }

    fun dispatchInput(
        player: String,
        device: String,
        code: String,
        action: String,
        x: Double?,
        y: Double?,
    ): String = session.dispatchInputJson(player, device, code, action, x, y).also { realtimeScene = null }

    fun snapshot(): String = session.snapshotJson()

    fun renderRgba(
        width: Int,
        height: Int,
    ): ByteArray {
        require(width <= limits.maximumRenderWidth && height <= limits.maximumRenderHeight) {
            "Render size ${width}x$height exceeds the configured limit"
        }
        return renderer.render(renderWorld(), width, height).also { lastFrame = it }.rgba
    }

    fun renderMetadata(
        width: Int,
        height: Int,
    ): String {
        val frame = lastFrame?.takeIf { it.width == width && it.height == height } ?: renderer.render(renderWorld(), width, height)
        lastFrame = frame
        return """{"width":$width,"height":$height,"visibleBlocks":${frame.visibleBlocks},"visibleEntities":${frame.visibleEntities},"triangles":${frame.triangles},"cameraDescription":"${frame.cameraDescription}","lightingModel":"approximate-perspective","visualParity":false}"""
    }

    fun captureAnimationFrame(
        width: Int,
        height: Int,
        delayCentiseconds: Int,
    ): Int {
        require(animationFrames.size < limits.maximumAnimationFrames) {
            "Animation exceeds the ${limits.maximumAnimationFrames} frame limit"
        }
        val frameBytes = width.toLong() * height.toLong() * 4L
        require(animationBytes.toLong() + frameBytes <= limits.maximumAnimationBytes.toLong()) {
            "Animation exceeds the ${limits.maximumAnimationBytes} byte limit"
        }
        val frame = renderer.render(renderWorld(), width, height).also { lastFrame = it }
        animationFrames += EngineGifFrame(width, height, frame.rgba.copyOf(), delayCentiseconds)
        animationBytes += frame.rgba.size
        return animationFrames.size
    }

    fun exportAnimation(repeat: Int): ByteArray = AnimatedGifEncoder.encode(animationFrames, repeat)

    fun clearAnimation() {
        animationFrames.clear()
        animationBytes = 0
    }

    fun animationFrameCount(): Int = animationFrames.size

    fun compileRealtimeScene(
        width: Int,
        height: Int,
    ): String {
        val scene = renderer.compileRealtime(renderWorld(), width, height)
        realtimeScene = scene
        return """{"vertexStride":${scene.vertexStride},"camera":{"position":${numberArray(scene.cameraPosition)},"yaw":${scene.cameraYaw},"pitch":${scene.cameraPitch}},"bounds":{"minimum":${numberArray(scene.boundsMinimum)},"maximum":${numberArray(scene.boundsMaximum)}},"atlas":{"width":${scene.atlas.width},"height":${scene.atlas.height}},"blocks":{"batches":${batchJson(scene.blocks.batches)},"vertices":${scene.blocks.vertices.size},"indices":${scene.blocks.indices.size}},"entities":{"batches":${batchJson(scene.entities.batches)},"vertices":${scene.entities.vertices.size},"indices":${scene.entities.indices.size}},"visibleBlocks":${scene.visibleBlocks},"visibleEntities":${scene.visibleEntities}}"""
    }

    fun realtimeBlockVertices(): FloatArray = requireRealtimeScene().blocks.vertices

    fun realtimeBlockIndices(): IntArray = requireRealtimeScene().blocks.indices

    fun realtimeEntityVertices(): FloatArray = requireRealtimeScene().entities.vertices

    fun realtimeEntityIndices(): IntArray = requireRealtimeScene().entities.indices

    fun realtimeAtlasRgba(): ByteArray = requireRealtimeScene().atlas.rgba

    private fun requireRealtimeScene(): RealtimeRenderScene =
        realtimeScene ?: error("Compile a realtime scene before requesting its buffers")

    private fun batchJson(batches: List<RealtimeMaterialBatch>): String =
        batches.joinToString(prefix = "[", postfix = "]") { batch ->
            """{"pass":"${batch.pass}","indexOffset":${batch.indexOffset},"indexCount":${batch.indexCount},"seeThrough":${batch.seeThrough}}"""
        }

    private fun numberArray(values: List<Double>): String = values.joinToString(prefix = "[", postfix = "]")

    private fun renderWorld(): EngineRenderWorld =
        EngineRenderWorld(
            dayTime = session.dayTime(),
            weather = session.weather(),
            blocks = session.renderBlocks().map { EngineRenderBlock(it.x, it.y, it.z, it.id, it.properties) },
            entities =
                session.renderEntities().map {
                    EngineRenderEntity(
                        uuid = it.uuid,
                        type = it.type,
                        x = it.x,
                        y = it.y,
                        z = it.z,
                        yaw = it.yaw,
                        pitch = it.pitch,
                        display =
                            it.display?.let { display ->
                                EngineDisplayData(
                                    transformation = display.transformation,
                                    billboard = display.billboard,
                                    brightnessSky = display.brightnessSky,
                                    brightnessBlock = display.brightnessBlock,
                                    viewRange = display.viewRange,
                                    shadowRadius = display.shadowRadius,
                                    shadowStrength = display.shadowStrength,
                                    cullingWidth = display.width,
                                    cullingHeight = display.height,
                                    glowColor = display.glowColor,
                                    blockId = display.blockId,
                                    blockProperties = display.blockProperties,
                                    itemId = display.itemId,
                                    itemDisplay = display.itemDisplay,
                                    text = display.text,
                                    lineWidth = display.lineWidth,
                                    background = display.background,
                                    textOpacity = display.textOpacity,
                                    textShadow = display.textShadow,
                                    seeThrough = display.seeThrough,
                                    defaultBackground = display.defaultBackground,
                                    alignment = display.alignment,
                                    textColor = display.textColor,
                                )
                            },
                    )
                },
        )

    private fun String.csv(): List<String> = split(',').filter(String::isNotBlank)
}
