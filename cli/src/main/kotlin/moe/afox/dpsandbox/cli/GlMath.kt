package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.render.RealtimeRenderCamera
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal fun perspectiveMatrix(
    fieldOfViewDegrees: Double,
    aspect: Float,
    near: Float = 0.05f,
    far: Float = 1_024f,
): FloatArray {
    val f = (1.0 / tan(Math.toRadians(fieldOfViewDegrees) / 2.0)).toFloat()
    val nf = 1f / (near - far)
    return floatArrayOf(
        f / aspect,
        0f,
        0f,
        0f,
        0f,
        f,
        0f,
        0f,
        0f,
        0f,
        (far + near) * nf,
        -1f,
        0f,
        0f,
        2f * far * near * nf,
        0f,
    )
}

internal fun viewMatrix(camera: RealtimeRenderCamera): FloatArray {
    val eye = Vec3f(camera.position.x.toFloat(), camera.position.y.toFloat(), camera.position.z.toFloat())
    val yaw = Math.toRadians(camera.yaw)
    val pitch = Math.toRadians(camera.pitch)
    val direction =
        Vec3f(
            (-sin(yaw) * cos(pitch)).toFloat(),
            (-sin(pitch)).toFloat(),
            (cos(yaw) * cos(pitch)).toFloat(),
        )
    val backward = (direction * -1f).normalized()
    val right = Vec3f(0f, 1f, 0f).cross(backward).normalizedOr(Vec3f(1f, 0f, 0f))
    val up = backward.cross(right).normalizedOr(Vec3f(0f, 1f, 0f))
    return floatArrayOf(
        right.x,
        up.x,
        backward.x,
        0f,
        right.y,
        up.y,
        backward.y,
        0f,
        right.z,
        up.z,
        backward.z,
        0f,
        -right.dot(eye),
        -up.dot(eye),
        -backward.dot(eye),
        1f,
    )
}

private data class Vec3f(
    val x: Float,
    val y: Float,
    val z: Float,
) {
    operator fun times(scale: Float) = Vec3f(x * scale, y * scale, z * scale)

    fun dot(other: Vec3f): Float = x * other.x + y * other.y + z * other.z

    fun cross(other: Vec3f): Vec3f =
        Vec3f(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x,
        )

    fun normalized(): Vec3f {
        val length = sqrt(dot(this))
        return if (length <= 1e-8f) this else this * (1f / length)
    }

    fun normalizedOr(fallback: Vec3f): Vec3f = if (dot(this) <= 1e-8f) fallback else normalized()
}
