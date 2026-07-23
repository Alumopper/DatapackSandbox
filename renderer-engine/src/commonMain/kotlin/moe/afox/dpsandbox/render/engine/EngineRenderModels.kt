package moe.afox.dpsandbox.render.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Immutable, platform-neutral block input for the clean-room renderer. */
data class EngineRenderBlock(
    val x: Int,
    val y: Int,
    val z: Int,
    val id: String,
    val properties: Map<String, String> = emptyMap(),
)

/** Immutable, platform-neutral entity input for approximate entity geometry. */
data class EngineRenderEntity(
    val uuid: String,
    val type: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Double = 0.0,
    val pitch: Double = 0.0,
    val display: EngineDisplayData? = null,
)

/** Normalized display-entity state shared by JVM differential tests and the browser renderer. */
data class EngineDisplayData(
    val transformation: List<Double> = IDENTITY_TRANSFORMATION,
    val billboard: String = "fixed",
    val brightnessSky: Int? = null,
    val brightnessBlock: Int? = null,
    val viewRange: Double = 1.0,
    val shadowRadius: Double = 0.0,
    val shadowStrength: Double = 1.0,
    val cullingWidth: Double = 0.0,
    val cullingHeight: Double = 0.0,
    val glowColor: Int = 0,
    val blockId: String? = null,
    val blockProperties: Map<String, String> = emptyMap(),
    val itemId: String? = null,
    val itemDisplay: String = "none",
    val text: String = "",
    val lineWidth: Int = 200,
    val background: Int = 0x40000000,
    val textOpacity: Int = 255,
    val textShadow: Boolean = false,
    val seeThrough: Boolean = false,
    val defaultBackground: Boolean = false,
    val alignment: String = "center",
    val textColor: Int = 0xffffffff.toInt(),
) {
    init {
        require(transformation.size == 16) { "Display transformation must contain 16 values" }
        require(transformation.all(Double::isFinite)) { "Display transformation values must be finite" }
        require(billboard in setOf("fixed", "vertical", "horizontal", "center")) { "Unknown display billboard '$billboard'" }
    }

    companion object {
        val IDENTITY_TRANSFORMATION =
            listOf(
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0,
            )
    }
}

/** Platform-neutral world input copied before rasterization begins. */
data class EngineRenderWorld(
    val dayTime: Long = 0,
    val weather: String = "clear",
    val seed: Long = 0,
    val blocks: List<EngineRenderBlock> = emptyList(),
    val entities: List<EngineRenderEntity> = emptyList(),
    val entityCount: Int = entities.size,
)

data class EngineRenderFrame(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
    val visibleBlocks: Int,
    val visibleEntities: Int,
    val triangles: Int,
    val cameraDescription: String,
    val visualParity: Boolean = false,
)

internal data class EngineVec2(
    val x: Double,
    val y: Double,
)

internal data class EngineVec3(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    operator fun plus(other: EngineVec3) = EngineVec3(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: EngineVec3) = EngineVec3(x - other.x, y - other.y, z - other.z)

    operator fun times(scale: Double) = EngineVec3(x * scale, y * scale, z * scale)

    fun dot(other: EngineVec3): Double = x * other.x + y * other.y + z * other.z

    fun cross(other: EngineVec3): EngineVec3 =
        EngineVec3(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x,
        )

    fun normalized(): EngineVec3 {
        val length = sqrt(dot(this))
        return if (length <= 1e-12) ZERO else this * (1.0 / length)
    }

    fun rotateAround(
        origin: EngineVec3,
        axis: Char,
        degrees: Double,
    ): EngineVec3 {
        val radians = degrees * PI / 180.0
        val c = cos(radians)
        val s = sin(radians)
        val point = this - origin
        val rotated =
            when (axis.lowercaseChar()) {
                'x' -> EngineVec3(point.x, point.y * c - point.z * s, point.y * s + point.z * c)
                'y' -> EngineVec3(point.x * c + point.z * s, point.y, -point.x * s + point.z * c)
                'z' -> EngineVec3(point.x * c - point.y * s, point.x * s + point.y * c, point.z)
                else -> point
            }
        return rotated + origin
    }

    companion object {
        val ZERO = EngineVec3(0.0, 0.0, 0.0)
        val UP = EngineVec3(0.0, 1.0, 0.0)
    }
}

internal enum class EngineMaterialPass {
    OPAQUE,
    CUTOUT,
    TRANSLUCENT,
}

internal data class EngineTexture(
    val id: String,
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val materialPass: EngineMaterialPass,
) {
    fun sample(
        u: Double,
        v: Double,
    ): Int {
        val wrappedU = u - floor(u)
        val wrappedV = v - floor(v)
        val x = (wrappedU * width).toInt().coerceIn(0, width - 1)
        val y = (wrappedV * height).toInt().coerceIn(0, height - 1)
        return pixels[y * width + x]
    }
}

internal data class EngineSceneVertex(
    val position: EngineVec3,
    val uv: EngineVec2,
    val billboardLocal: EngineVec3? = null,
    val billboardPivot: EngineVec3? = null,
    val billboardMode: Int = 0,
)

internal data class EngineSceneTriangle(
    val a: EngineSceneVertex,
    val b: EngineSceneVertex,
    val c: EngineSceneVertex,
    val texture: EngineTexture,
    val tint: Int = -1,
    val emissive: Boolean = false,
    val lightOverride: Double? = null,
    val seeThrough: Boolean = false,
) {
    val normal: EngineVec3 = (b.position - a.position).cross(c.position - a.position).normalized()
}

internal data class EngineCamera(
    val position: EngineVec3,
    val yaw: Double,
    val pitch: Double,
    val description: String,
)

internal data class EngineRenderScene(
    val camera: EngineCamera,
    val triangles: List<EngineSceneTriangle>,
    val visibleBlocks: Int,
    val visibleEntities: Int,
)
