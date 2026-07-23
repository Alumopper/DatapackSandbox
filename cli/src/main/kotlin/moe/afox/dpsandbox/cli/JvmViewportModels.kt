package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.Position
import moe.afox.dpsandbox.render.RealtimeRenderCamera
import kotlin.math.cos
import kotlin.math.sin

internal data class JvmViewportOptions(
    val version: String = "26.2",
    val width: Int = 1_200,
    val height: Int = 720,
    val targetFps: Int = 60,
    val tickRate: Int = 20,
    val autoplay: Boolean = false,
    val inputPlayer: String = "Steve",
    val fieldOfView: Double = 70.0,
    val moveSpeed: Double = 6.0,
    val mouseSensitivity: Double = 0.12,
    val uiScale: Double = 1.4,
) {
    init {
        require(version.isNotBlank()) { "Version must not be blank" }
        require(width in 320..3840) { "Viewport width must be between 320 and 3840" }
        require(height in 180..2160) { "Viewport height must be between 180 and 2160" }
        require(targetFps in 15..144) { "Target FPS must be between 15 and 144" }
        require(tickRate in 1..100) { "Tick rate must be between 1 and 100" }
        require(fieldOfView in 10.0..150.0) { "Field of view must be between 10 and 150 degrees" }
        require(moveSpeed in 0.1..100.0) { "Move speed must be between 0.1 and 100" }
        require(mouseSensitivity in 0.01..1.0) { "Mouse sensitivity must be between 0.01 and 1" }
        require(uiScale in 0.8..2.5) { "UI scale must be between 0.8 and 2.5" }
        require(inputPlayer.isNotBlank()) { "Input player must not be blank" }
    }
}

internal data class JvmViewportEnvironment(
    val dayTime: Long = 6_000,
    val weather: String = "clear",
)

internal class JvmViewportCamera(
    initialSpeed: Double,
) {
    var position = Position(0.0, 0.0, 0.0)
        private set
    var yaw = 0.0
        private set
    var pitch = 0.0
        private set
    var dimension = "minecraft:overworld"
        private set
    var speed = initialSpeed
        private set
    var automatic = true
        private set

    fun applySuggested(camera: RealtimeRenderCamera) {
        if (!automatic) return
        apply(camera, automatic = true)
    }

    fun reset(camera: RealtimeRenderCamera) {
        apply(camera, automatic = true)
    }

    fun move(
        forward: Double,
        right: Double,
        up: Double,
        deltaSeconds: Double,
    ) {
        if (deltaSeconds <= 0.0 || (forward == 0.0 && right == 0.0 && up == 0.0)) return
        val radians = Math.toRadians(yaw)
        val forwardX = -sin(radians)
        val forwardZ = cos(radians)
        val rightX = -cos(radians)
        val rightZ = -sin(radians)
        val scale = speed * deltaSeconds
        position =
            Position(
                position.x + (forwardX * forward + rightX * right) * scale,
                position.y + up * scale,
                position.z + (forwardZ * forward + rightZ * right) * scale,
            )
        automatic = false
    }

    fun look(
        deltaYaw: Double,
        deltaPitch: Double,
    ) {
        yaw = normalizeDegrees(yaw + deltaYaw)
        pitch = (pitch + deltaPitch).coerceIn(-89.0, 89.0)
        automatic = false
    }

    fun adjustSpeed(wheelRotation: Int) {
        speed = (speed * Math.pow(1.12, -wheelRotation.toDouble())).coerceIn(0.1, 100.0)
    }

    fun setSpeed(value: Double) {
        speed = value.coerceIn(0.1, 100.0)
    }

    fun snapshot(): RealtimeRenderCamera = RealtimeRenderCamera(position, yaw, pitch, dimension)

    private fun apply(
        camera: RealtimeRenderCamera,
        automatic: Boolean,
    ) {
        position = camera.position
        yaw = camera.yaw
        pitch = camera.pitch
        dimension = camera.dimension
        this.automatic = automatic
    }

    private fun normalizeDegrees(value: Double): Double {
        var normalized = value % 360.0
        if (normalized > 180.0) normalized -= 360.0
        if (normalized < -180.0) normalized += 360.0
        return normalized
    }
}
