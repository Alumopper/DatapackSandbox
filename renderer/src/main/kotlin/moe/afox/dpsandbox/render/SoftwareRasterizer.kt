package moe.afox.dpsandbox.render

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

internal class SoftwareRasterizer {
    fun render(
        view: WorldView,
        scene: RenderScene,
        request: RenderRequest,
    ): IntArray {
        val pixels = background(view, request)
        val depth = FloatArray(request.width * request.height) { Float.POSITIVE_INFINITY }
        val projected = scene.triangles.flatMap { project(it, scene.camera, request) }
        projected.filter { it.source.texture.materialPass != MaterialPass.TRANSLUCENT }.forEach { triangle ->
            rasterize(triangle, pixels, depth, request, view, writeDepth = true)
        }
        projected
            .filter { it.source.texture.materialPass == MaterialPass.TRANSLUCENT }
            .sortedByDescending { it.averageDepth }
            .forEach { triangle -> rasterize(triangle, pixels, depth, request, view, writeDepth = false) }
        if (request.showHud || request.showDebugOverlay) drawCrosshair(pixels, request.width, request.height)
        if (request.showDebugOverlay) drawDebugFrame(pixels, request.width, request.height)
        return pixels
    }

    private fun project(
        triangle: SceneTriangle,
        camera: ResolvedCamera,
        request: RenderRequest,
    ): List<ProjectedTriangle> {
        val yaw = Math.toRadians(camera.yaw)
        val pitch = Math.toRadians(camera.pitch)
        val forward = Vec3(-sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch)).normalized()
        val candidateRight = forward.cross(Vec3.UP)
        val right = if (candidateRight.dot(candidateRight) <= 1e-12) Vec3(1.0, 0.0, 0.0) else candidateRight.normalized()
        val up = right.cross(forward).normalized()
        val cameraFacing = camera.position - triangle.a.position
        if (triangle.normal.dot(cameraFacing) <= 1e-9) return emptyList()
        val focal = request.height / (2.0 * tan(Math.toRadians(request.fieldOfViewDegrees) / 2.0))

        fun cameraVertex(value: SceneVertex): CameraVertex {
            val relative = value.position - camera.position
            return CameraVertex(
                x = relative.dot(right),
                y = relative.dot(up),
                z = relative.dot(forward),
                u = value.uv.x,
                v = value.uv.y,
            )
        }

        fun projectVertex(value: CameraVertex): ProjectedVertex =
            ProjectedVertex(
                x = request.width / 2.0 + value.x * focal / value.z,
                y = request.height / 2.0 - value.y * focal / value.z,
                z = value.z,
                u = value.u,
                v = value.v,
            )

        val clipped = clipNear(listOf(cameraVertex(triangle.a), cameraVertex(triangle.b), cameraVertex(triangle.c)), request.nearPlane)
        if (clipped.size < 3 || clipped.all { it.z > request.renderDistance }) return emptyList()
        val first = projectVertex(clipped.first())
        return (1 until clipped.lastIndex).mapNotNull { index ->
            val projected = ProjectedTriangle(first, projectVertex(clipped[index]), projectVertex(clipped[index + 1]), triangle)
            projected.takeIf { it.a.isFinite() && it.b.isFinite() && it.c.isFinite() }
        }
    }

    private fun clipNear(
        input: List<CameraVertex>,
        near: Double,
    ): List<CameraVertex> {
        if (input.isEmpty()) return emptyList()
        val output = mutableListOf<CameraVertex>()
        var previous = input.last()
        var previousInside = previous.z >= near
        input.forEach { current ->
            val currentInside = current.z >= near
            when {
                currentInside && previousInside -> output += current
                currentInside && !previousInside -> {
                    output += previous.interpolate(current, (near - previous.z) / (current.z - previous.z))
                    output += current
                }
                !currentInside && previousInside ->
                    output += previous.interpolate(current, (near - previous.z) / (current.z - previous.z))
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
        request: RenderRequest,
        view: WorldView,
        writeDepth: Boolean,
    ) {
        val width = request.width
        val height = request.height
        val minX = floor(min(triangle.a.x, min(triangle.b.x, triangle.c.x))).toInt().coerceIn(0, width - 1)
        val maxX = ceil(max(triangle.a.x, max(triangle.b.x, triangle.c.x))).toInt().coerceIn(0, width - 1)
        val minY = floor(min(triangle.a.y, min(triangle.b.y, triangle.c.y))).toInt().coerceIn(0, height - 1)
        val maxY = ceil(max(triangle.a.y, max(triangle.b.y, triangle.c.y))).toInt().coerceIn(0, height - 1)
        val area = edge(triangle.a.x, triangle.a.y, triangle.b.x, triangle.b.y, triangle.c.x, triangle.c.y)
        if (kotlin.math.abs(area) < 1e-9) return
        val inverseArea = 1.0 / area
        val inverseZa = 1.0 / triangle.a.z
        val inverseZb = 1.0 / triangle.b.z
        val inverseZc = 1.0 / triangle.c.z
        val brightness = faceBrightness(triangle.source.normal, view)

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val px = x + 0.5
                val py = y + 0.5
                val wa = edge(triangle.b.x, triangle.b.y, triangle.c.x, triangle.c.y, px, py) * inverseArea
                val wb = edge(triangle.c.x, triangle.c.y, triangle.a.x, triangle.a.y, px, py) * inverseArea
                val wc = 1.0 - wa - wb
                if (wa < -1e-8 || wb < -1e-8 || wc < -1e-8) continue
                val inverseZ = wa * inverseZa + wb * inverseZb + wc * inverseZc
                if (inverseZ <= 0.0) continue
                val z = (1.0 / inverseZ).toFloat()
                if (z > request.renderDistance) continue
                val index = y * width + x
                if (z >= depth[index]) continue
                val u = (wa * triangle.a.u * inverseZa + wb * triangle.b.u * inverseZb + wc * triangle.c.u * inverseZc) / inverseZ
                val v = (wa * triangle.a.v * inverseZa + wb * triangle.b.v * inverseZb + wc * triangle.c.v * inverseZc) / inverseZ
                var color = triangle.source.texture.sample(u, v)
                color = multiplyColor(color, triangle.source.tint)
                if (!triangle.source.emissive) color = shade(color, brightness)
                if (!request.transparentBackground) color = applyFog(color, z.toDouble(), view, request.renderDistance)
                val alpha = color ushr 24 and 0xff
                if (alpha == 0 || (triangle.source.texture.materialPass == MaterialPass.CUTOUT && alpha < 128)) continue
                pixels[index] = if (alpha == 255) color else blend(color, pixels[index])
                if (writeDepth) depth[index] = z
            }
        }
    }

    private fun background(
        view: WorldView,
        request: RenderRequest,
    ): IntArray {
        if (request.transparentBackground) return IntArray(request.width * request.height)
        val daylight = daylight(view.dayTime) * weatherFactor(view.weather)
        val top = color(0xff, (70 * daylight).toInt(), (120 * daylight).toInt(), (200 * daylight).toInt())
        val bottom = color(0xff, (155 * daylight).toInt(), (190 * daylight).toInt(), (225 * daylight).toInt())
        return IntArray(request.width * request.height) { index ->
            val y = index / request.width
            val t = y.toDouble() / (request.height - 1).coerceAtLeast(1)
            mix(top, bottom, t)
        }
    }

    private fun faceBrightness(
        normal: Vec3,
        view: WorldView,
    ): Double {
        val light = Vec3(-0.45, 0.85, -0.3).normalized()
        val directional = max(0.0, normal.dot(light))
        return (0.38 + directional * 0.62) * daylight(view.dayTime) * weatherFactor(view.weather)
    }

    private fun daylight(dayTime: Long): Double {
        val phase = (Math.floorMod(dayTime, 24000L) - 6000.0) / 24000.0 * Math.PI * 2.0
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
        view: WorldView,
        renderDistance: Double,
    ): Int {
        val start = renderDistance * 0.65
        val amount = ((distance - start) / (renderDistance - start).coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
        if (amount <= 0.0) return value
        val daylight = daylight(view.dayTime) * weatherFactor(view.weather)
        val fog = color(0xff, (125 * daylight).toInt(), (160 * daylight).toInt(), (205 * daylight).toInt())
        val mixed = mix(value or (0xff shl 24), fog, amount * amount)
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
            0xff,
            ((foreground ushr 16 and 0xff) * alpha + (background ushr 16 and 0xff) * inverse).toInt(),
            ((foreground ushr 8 and 0xff) * alpha + (background ushr 8 and 0xff) * inverse).toInt(),
            ((foreground and 0xff) * alpha + (background and 0xff) * inverse).toInt(),
        )
    }

    private fun mix(
        first: Int,
        second: Int,
        t: Double,
    ): Int =
        color(
            ((first ushr 24 and 0xff) * (1.0 - t) + (second ushr 24 and 0xff) * t).toInt(),
            ((first ushr 16 and 0xff) * (1.0 - t) + (second ushr 16 and 0xff) * t).toInt(),
            ((first ushr 8 and 0xff) * (1.0 - t) + (second ushr 8 and 0xff) * t).toInt(),
            ((first and 0xff) * (1.0 - t) + (second and 0xff) * t).toInt(),
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

    private fun drawCrosshair(
        pixels: IntArray,
        width: Int,
        height: Int,
    ) {
        val centerX = width / 2
        val centerY = height / 2
        for (offset in -6..6) {
            if (centerX + offset in 0 until width) pixels[centerY * width + centerX + offset] = 0xffffffff.toInt()
            if (centerY + offset in 0 until height) pixels[(centerY + offset) * width + centerX] = 0xffffffff.toInt()
        }
    }

    private fun drawDebugFrame(
        pixels: IntArray,
        width: Int,
        height: Int,
    ) {
        val color = 0xffffd000.toInt()
        for (x in 0 until width) {
            pixels[x] = color
            pixels[(height - 1) * width + x] = color
        }
        for (y in 0 until height) {
            pixels[y * width] = color
            pixels[y * width + width - 1] = color
        }
    }

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
        val source: SceneTriangle,
    ) {
        val averageDepth: Double = (a.z + b.z + c.z) / 3.0
    }
}
