package moe.afox.dpsandbox.render.engine

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

internal class EngineSoftwareRasterizer {
    fun render(
        world: EngineRenderWorld,
        scene: EngineRenderScene,
        width: Int,
        height: Int,
    ): IntArray {
        val pixels = background(world, width, height)
        val depth = FloatArray(width * height) { Float.POSITIVE_INFINITY }
        val projected = scene.triangles.flatMap { project(it, scene.camera, width, height) }
        projected.filter { it.source.texture.materialPass != EngineMaterialPass.TRANSLUCENT && !it.source.seeThrough }.forEach { triangle ->
            rasterize(triangle, pixels, depth, world, width, height, writeDepth = true)
        }
        projected
            .filter { it.source.texture.materialPass == EngineMaterialPass.TRANSLUCENT || it.source.seeThrough }
            .sortedByDescending(ProjectedTriangle::averageDepth)
            .forEach { triangle -> rasterize(triangle, pixels, depth, world, width, height, writeDepth = false) }
        return pixels
    }

    private fun project(
        triangle: EngineSceneTriangle,
        camera: EngineCamera,
        width: Int,
        height: Int,
    ): List<ProjectedTriangle> {
        val yaw = camera.yaw * PI / 180.0
        val pitch = camera.pitch * PI / 180.0
        val forward = EngineVec3(-sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch)).normalized()
        val candidateRight = forward.cross(EngineVec3.UP)
        val right = if (candidateRight.dot(candidateRight) <= 1e-12) EngineVec3(1.0, 0.0, 0.0) else candidateRight.normalized()
        val up = right.cross(forward).normalized()
        val cameraFacing = camera.position - triangle.a.position
        if (triangle.normal.dot(cameraFacing) <= 1e-9) return emptyList()
        val focal = height / (2.0 * tan(FIELD_OF_VIEW * PI / 360.0))

        fun cameraVertex(value: EngineSceneVertex): CameraVertex {
            val relative = value.position - camera.position
            return CameraVertex(relative.dot(right), relative.dot(up), relative.dot(forward), value.uv.x, value.uv.y)
        }

        fun projectVertex(value: CameraVertex): ProjectedVertex =
            ProjectedVertex(
                width / 2.0 + value.x * focal / value.z,
                height / 2.0 - value.y * focal / value.z,
                value.z,
                value.u,
                value.v,
            )

        val clipped = clipNear(listOf(cameraVertex(triangle.a), cameraVertex(triangle.b), cameraVertex(triangle.c)))
        if (clipped.size < 3 || clipped.all { it.z > RENDER_DISTANCE }) return emptyList()
        val first = projectVertex(clipped.first())
        return (1 until clipped.lastIndex).mapNotNull { index ->
            val projected = ProjectedTriangle(first, projectVertex(clipped[index]), projectVertex(clipped[index + 1]), triangle)
            projected.takeIf { it.a.isFinite() && it.b.isFinite() && it.c.isFinite() }
        }
    }

    private fun clipNear(input: List<CameraVertex>): List<CameraVertex> {
        if (input.isEmpty()) return emptyList()
        val output = mutableListOf<CameraVertex>()
        var previous = input.last()
        var previousInside = previous.z >= NEAR_PLANE
        input.forEach { current ->
            val currentInside = current.z >= NEAR_PLANE
            when {
                currentInside && previousInside -> output += current
                currentInside && !previousInside -> {
                    output += previous.interpolate(current, (NEAR_PLANE - previous.z) / (current.z - previous.z))
                    output += current
                }
                !currentInside && previousInside -> output += previous.interpolate(current, (NEAR_PLANE - previous.z) / (current.z - previous.z))
            }
            previous = current
            previousInside = currentInside
        }
        return output
    }

    private fun rasterize(
        triangle: ProjectedTriangle,
        pixels: IntArray,
        depth: FloatArray,
        world: EngineRenderWorld,
        width: Int,
        height: Int,
        writeDepth: Boolean,
    ) {
        val minimumX = floor(min(triangle.a.x, min(triangle.b.x, triangle.c.x))).toInt().coerceIn(0, width - 1)
        val maximumX = ceil(max(triangle.a.x, max(triangle.b.x, triangle.c.x))).toInt().coerceIn(0, width - 1)
        val minimumY = floor(min(triangle.a.y, min(triangle.b.y, triangle.c.y))).toInt().coerceIn(0, height - 1)
        val maximumY = ceil(max(triangle.a.y, max(triangle.b.y, triangle.c.y))).toInt().coerceIn(0, height - 1)
        val area = edge(triangle.a.x, triangle.a.y, triangle.b.x, triangle.b.y, triangle.c.x, triangle.c.y)
        if (kotlin.math.abs(area) < 1e-9) return
        val inverseArea = 1.0 / area
        val inverseZa = 1.0 / triangle.a.z
        val inverseZb = 1.0 / triangle.b.z
        val inverseZc = 1.0 / triangle.c.z
        val brightness = triangle.source.lightOverride ?: faceBrightness(triangle.source.normal, world)

        for (y in minimumY..maximumY) {
            for (x in minimumX..maximumX) {
                val pixelX = x + 0.5
                val pixelY = y + 0.5
                val weightA = edge(triangle.b.x, triangle.b.y, triangle.c.x, triangle.c.y, pixelX, pixelY) * inverseArea
                val weightB = edge(triangle.c.x, triangle.c.y, triangle.a.x, triangle.a.y, pixelX, pixelY) * inverseArea
                val weightC = 1.0 - weightA - weightB
                if (weightA < -1e-8 || weightB < -1e-8 || weightC < -1e-8) continue
                val inverseZ = weightA * inverseZa + weightB * inverseZb + weightC * inverseZc
                if (inverseZ <= 0.0) continue
                val z = (1.0 / inverseZ).toFloat()
                if (z > RENDER_DISTANCE) continue
                val pixelIndex = y * width + x
                if (!triangle.source.seeThrough && z >= depth[pixelIndex]) continue
                val u = (weightA * triangle.a.u * inverseZa + weightB * triangle.b.u * inverseZb + weightC * triangle.c.u * inverseZc) / inverseZ
                val v = (weightA * triangle.a.v * inverseZa + weightB * triangle.b.v * inverseZb + weightC * triangle.c.v * inverseZc) / inverseZ
                var color = triangle.source.texture.sample(u, v)
                color = multiplyColor(color, triangle.source.tint)
                if (!triangle.source.emissive) color = shade(color, brightness)
                color = applyFog(color, z.toDouble(), world)
                val alpha = color ushr 24 and 0xff
                if (alpha == 0 || (triangle.source.texture.materialPass == EngineMaterialPass.CUTOUT && alpha < 128)) continue
                pixels[pixelIndex] = if (alpha == 255) color else blend(color, pixels[pixelIndex])
                if (writeDepth && !triangle.source.seeThrough) depth[pixelIndex] = z
            }
        }
    }

    private fun background(
        world: EngineRenderWorld,
        width: Int,
        height: Int,
    ): IntArray {
        val daylight = daylight(world.dayTime) * weatherFactor(world.weather)
        val top = color(255, (70 * daylight).toInt(), (120 * daylight).toInt(), (200 * daylight).toInt())
        val bottom = color(255, (155 * daylight).toInt(), (190 * daylight).toInt(), (225 * daylight).toInt())
        return IntArray(width * height) { index ->
            val y = index / width
            mix(top, bottom, y.toDouble() / (height - 1).coerceAtLeast(1))
        }
    }

    private fun faceBrightness(
        normal: EngineVec3,
        world: EngineRenderWorld,
    ): Double {
        val light = EngineVec3(-0.45, 0.85, -0.3).normalized()
        return (0.38 + max(0.0, normal.dot(light)) * 0.62) * daylight(world.dayTime) * weatherFactor(world.weather)
    }

    private fun daylight(dayTime: Long): Double {
        val normalized = ((dayTime % 24_000L) + 24_000L) % 24_000L
        val phase = (normalized - 6000.0) / 24000.0 * PI * 2.0
        return 0.18 + 0.82 * ((cos(phase) + 1.0) / 2.0)
    }

    private fun weatherFactor(weather: String): Double =
        when (weather.lowercase()) {
            "thunder" -> 0.58
            "rain" -> 0.76
            else -> 1.0
        }

    private fun applyFog(
        value: Int,
        distance: Double,
        world: EngineRenderWorld,
    ): Int {
        val start = RENDER_DISTANCE * 0.65
        val amount = ((distance - start) / (RENDER_DISTANCE - start)).coerceIn(0.0, 1.0)
        if (amount <= 0.0) return value
        val daylight = daylight(world.dayTime) * weatherFactor(world.weather)
        val fog = color(255, (125 * daylight).toInt(), (160 * daylight).toInt(), (205 * daylight).toInt())
        val mixed = mix(value or (255 shl 24), fog, amount * amount)
        return (value and 0xff000000.toInt()) or (mixed and 0x00ffffff)
    }

    private fun edge(
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
        px: Double,
        py: Double,
    ): Double = (px - ax) * (by - ay) - (py - ay) * (bx - ax)

    private fun multiplyColor(
        first: Int,
        second: Int,
    ): Int =
        color(
            (first ushr 24 and 0xff) * (second ushr 24 and 0xff) / 255,
            (first ushr 16 and 0xff) * (second ushr 16 and 0xff) / 255,
            (first ushr 8 and 0xff) * (second ushr 8 and 0xff) / 255,
            (first and 0xff) * (second and 0xff) / 255,
        )

    private fun shade(
        value: Int,
        brightness: Double,
    ): Int =
        color(
            value ushr 24 and 0xff,
            ((value ushr 16 and 0xff) * brightness).toInt(),
            ((value ushr 8 and 0xff) * brightness).toInt(),
            ((value and 0xff) * brightness).toInt(),
        )

    private fun blend(
        foreground: Int,
        background: Int,
    ): Int {
        val alpha = (foreground ushr 24 and 0xff) / 255.0
        val inverse = 1.0 - alpha
        return color(
            255,
            ((foreground ushr 16 and 0xff) * alpha + (background ushr 16 and 0xff) * inverse).toInt(),
            ((foreground ushr 8 and 0xff) * alpha + (background ushr 8 and 0xff) * inverse).toInt(),
            ((foreground and 0xff) * alpha + (background and 0xff) * inverse).toInt(),
        )
    }

    private fun mix(
        first: Int,
        second: Int,
        amount: Double,
    ): Int =
        color(
            ((first ushr 24 and 0xff) * (1.0 - amount) + (second ushr 24 and 0xff) * amount).toInt(),
            ((first ushr 16 and 0xff) * (1.0 - amount) + (second ushr 16 and 0xff) * amount).toInt(),
            ((first ushr 8 and 0xff) * (1.0 - amount) + (second ushr 8 and 0xff) * amount).toInt(),
            ((first and 0xff) * (1.0 - amount) + (second and 0xff) * amount).toInt(),
        )

    private fun color(
        alpha: Int,
        red: Int,
        green: Int,
        blue: Int,
    ): Int =
        (alpha.coerceIn(0, 255) shl 24) or
            (red.coerceIn(0, 255) shl 16) or
            (green.coerceIn(0, 255) shl 8) or
            blue.coerceIn(0, 255)

    private data class ProjectedVertex(
        val x: Double,
        val y: Double,
        val z: Double,
        val u: Double,
        val v: Double,
    ) {
        fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite() && u.isFinite() && v.isFinite()
    }

    private data class CameraVertex(
        val x: Double,
        val y: Double,
        val z: Double,
        val u: Double,
        val v: Double,
    ) {
        fun interpolate(
            other: CameraVertex,
            amount: Double,
        ): CameraVertex =
            CameraVertex(
                x + (other.x - x) * amount,
                y + (other.y - y) * amount,
                z + (other.z - z) * amount,
                u + (other.u - u) * amount,
                v + (other.v - v) * amount,
            )
    }

    private data class ProjectedTriangle(
        val a: ProjectedVertex,
        val b: ProjectedVertex,
        val c: ProjectedVertex,
        val source: EngineSceneTriangle,
    ) {
        val averageDepth: Double = (a.z + b.z + c.z) / 3.0
    }

    companion object {
        private const val FIELD_OF_VIEW = 70.0
        private const val NEAR_PLANE = 0.05
        private const val RENDER_DISTANCE = 128.0
    }
}
