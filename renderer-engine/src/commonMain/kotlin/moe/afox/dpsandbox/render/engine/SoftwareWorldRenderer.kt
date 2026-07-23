package moe.afox.dpsandbox.render.engine

/**
 * Deterministic perspective software renderer shared by JVM tests and the browser Worker.
 * It follows the JAR renderer's clean-room camera, geometry, lighting, fog, texture and
 * depth-buffer semantics while continuing to report [EngineRenderFrame.visualParity] as false.
 */
class SoftwareWorldRenderer {
    private val textures = EngineTextureStore()
    private val realtimeCompiler = RealtimeSceneCompiler(textures)

    fun registerTexture(
        id: String,
        width: Int,
        height: Int,
        rgba: ByteArray,
    ) {
        textures.register(id, width, height, rgba)
        realtimeCompiler.invalidate()
    }

    fun registerAssetText(
        path: String,
        text: String,
    ) {
        textures.registerAssetText(path, text)
        realtimeCompiler.invalidate()
    }

    fun clearTextures() {
        textures.clear()
        realtimeCompiler.invalidate()
    }

    /** Compiles the shared clean-room scene into WebGL-friendly buffers without rasterizing it. */
    fun compileRealtime(
        world: EngineRenderWorld,
        width: Int,
        height: Int,
    ): RealtimeRenderScene = realtimeCompiler.compile(world, width, height)

    fun render(
        world: EngineRenderWorld,
        width: Int,
        height: Int,
    ): EngineRenderFrame {
        require(width in MIN_SIZE..MAX_SIZE) { "Render width must be between $MIN_SIZE and $MAX_SIZE" }
        require(height in MIN_SIZE..MAX_SIZE) { "Render height must be between $MIN_SIZE and $MAX_SIZE" }
        val scene = EngineSceneBuilder(textures).build(world, width, height)
        val pixels = EngineSoftwareRasterizer().render(world, scene, width, height)
        return EngineRenderFrame(
            width = width,
            height = height,
            rgba = toRgba(pixels),
            visibleBlocks = scene.visibleBlocks,
            visibleEntities = scene.visibleEntities,
            triangles = scene.triangles.size,
            cameraDescription = scene.camera.description,
        )
    }

    private fun toRgba(pixels: IntArray): ByteArray =
        ByteArray(pixels.size * 4).also { rgba ->
            pixels.forEachIndexed { index, value ->
                rgba[index * 4] = (value ushr 16 and 0xff).toByte()
                rgba[index * 4 + 1] = (value ushr 8 and 0xff).toByte()
                rgba[index * 4 + 2] = (value and 0xff).toByte()
                rgba[index * 4 + 3] = (value ushr 24 and 0xff).toByte()
            }
        }

    companion object {
        const val MIN_SIZE = 16
        const val MAX_SIZE = 4096
        const val MAX_VISIBLE_BLOCKS = 4_096
    }
}
