package moe.afox.dpsandbox.render

import java.awt.image.BufferedImage
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class Vec2(
    val x: Double,
    val y: Double,
)

internal data class Vec3(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)

    operator fun times(scale: Double) = Vec3(x * scale, y * scale, z * scale)

    fun dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z

    fun cross(other: Vec3): Vec3 =
        Vec3(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x,
        )

    fun normalized(): Vec3 {
        val length = sqrt(dot(this))
        return if (length <= 1e-12) ZERO else this * (1.0 / length)
    }

    fun rotateAround(
        origin: Vec3,
        axis: Char,
        degrees: Double,
    ): Vec3 {
        val radians = Math.toRadians(degrees)
        val c = cos(radians)
        val s = sin(radians)
        val p = this - origin
        val rotated =
            when (axis.lowercaseChar()) {
                'x' -> Vec3(p.x, p.y * c - p.z * s, p.y * s + p.z * c)
                'y' -> Vec3(p.x * c + p.z * s, p.y, -p.x * s + p.z * c)
                'z' -> Vec3(p.x * c - p.y * s, p.x * s + p.y * c, p.z)
                else -> p
            }
        return rotated + origin
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val UP = Vec3(0.0, 1.0, 0.0)
    }
}

internal enum class MaterialPass {
    OPAQUE,
    CUTOUT,
    TRANSLUCENT,
}

internal data class TextureData(
    val id: String,
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val materialPass: MaterialPass,
) {
    fun sample(
        u: Double,
        v: Double,
    ): Int {
        val wrappedU = u - kotlin.math.floor(u)
        val wrappedV = v - kotlin.math.floor(v)
        val x = (wrappedU * width).toInt().coerceIn(0, width - 1)
        val y = (wrappedV * height).toInt().coerceIn(0, height - 1)
        return pixels[y * width + x]
    }

    companion object {
        fun fromImage(
            id: String,
            image: BufferedImage,
        ): TextureData {
            val pixels = IntArray(image.width * image.height)
            image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
            var hasTransparent = false
            var hasPartial = false
            pixels.forEach { color ->
                val alpha = color ushr 24 and 0xff
                if (alpha == 0) hasTransparent = true
                if (alpha in 1..254) hasPartial = true
            }
            val pass =
                when {
                    hasPartial -> MaterialPass.TRANSLUCENT
                    hasTransparent -> MaterialPass.CUTOUT
                    else -> MaterialPass.OPAQUE
                }
            return TextureData(id, image.width, image.height, pixels, pass)
        }
    }
}

internal data class SceneVertex(
    val position: Vec3,
    val uv: Vec2,
)

internal data class SceneTriangle(
    val a: SceneVertex,
    val b: SceneVertex,
    val c: SceneVertex,
    val texture: TextureData,
    val tint: Int = 0xffffffff.toInt(),
    val emissive: Boolean = false,
) {
    var lightOverride: Double? = null
    var seeThrough: Boolean = false
    val normal: Vec3 = (b.position - a.position).cross(c.position - a.position).normalized()
}

internal data class ResolvedCamera(
    val position: Vec3,
    val yaw: Double,
    val pitch: Double,
    val dimension: String,
    val description: String,
)

internal data class RenderScene(
    val camera: ResolvedCamera,
    val triangles: List<SceneTriangle>,
    val visibleBlocks: Int,
    val visibleEntities: Int,
)
