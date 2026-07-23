package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.render.RealtimeRenderCamera
import org.lwjgl.opengl.GL11C.GL_BLEND
import org.lwjgl.opengl.GL11C.GL_DEPTH_TEST
import org.lwjgl.opengl.GL11C.GL_TRIANGLES
import org.lwjgl.opengl.GL11C.glDisable
import org.lwjgl.opengl.GL11C.glDrawArrays
import org.lwjgl.opengl.GL20C.glDeleteProgram
import org.lwjgl.opengl.GL20C.glGetUniformLocation
import org.lwjgl.opengl.GL20C.glUniform1f
import org.lwjgl.opengl.GL20C.glUniform1i
import org.lwjgl.opengl.GL20C.glUniform2f
import org.lwjgl.opengl.GL20C.glUseProgram
import org.lwjgl.opengl.GL30C.glBindVertexArray
import org.lwjgl.opengl.GL30C.glDeleteVertexArrays
import org.lwjgl.opengl.GL30C.glGenVertexArrays
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.tan

internal class GlSkyRenderer : AutoCloseable {
    private val program = linkGlProgram(SKY_VERTEX_SHADER, SKY_FRAGMENT_SHADER)
    private val vertexArray = glGenVertexArrays()
    private var environment = JvmViewportEnvironment()

    fun updateEnvironment(next: JvmViewportEnvironment) {
        environment = next
    }

    fun draw(
        camera: RealtimeRenderCamera,
        fieldOfView: Double,
        width: Int,
        height: Int,
    ) {
        val dayAngle = (environment.dayTime.mod(24_000L) / 24_000.0 - 0.25) * PI * 2.0
        val weatherStrength =
            when (environment.weather.lowercase()) {
                "thunder" -> 0.9f
                "rain" -> 0.62f
                else -> 0f
            }
        val dimension =
            when (camera.dimension.substringAfter(':')) {
                "the_nether" -> 1
                "the_end" -> 2
                else -> 0
            }
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)
        glUseProgram(program)
        glUniform2f(
            glGetUniformLocation(program, "u_yawPitch"),
            Math.toRadians(camera.yaw).toFloat(),
            Math.toRadians(camera.pitch).toFloat(),
        )
        glUniform1f(glGetUniformLocation(program, "u_aspect"), width.toFloat() / height.coerceAtLeast(1))
        glUniform1f(glGetUniformLocation(program, "u_tanHalfFov"), tan(Math.toRadians(fieldOfView) / 2.0).toFloat())
        glUniform1f(glGetUniformLocation(program, "u_dayAngle"), dayAngle.toFloat())
        glUniform1f(glGetUniformLocation(program, "u_sunHeight"), cos(dayAngle).toFloat())
        glUniform1f(glGetUniformLocation(program, "u_weather"), weatherStrength)
        glUniform1i(glGetUniformLocation(program, "u_dimension"), dimension)
        glBindVertexArray(vertexArray)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glBindVertexArray(0)
        glUseProgram(0)
    }

    override fun close() {
        glDeleteVertexArrays(vertexArray)
        glDeleteProgram(program)
    }
}

private const val SKY_VERTEX_SHADER = """
    #version 330 core
    out vec2 v_ndc;
    void main() {
        vec2 position = gl_VertexID == 0 ? vec2(-1.0, -1.0) :
                        gl_VertexID == 1 ? vec2( 3.0, -1.0) : vec2(-1.0, 3.0);
        v_ndc = position;
        gl_Position = vec4(position, 1.0, 1.0);
    }
"""

private const val SKY_FRAGMENT_SHADER = """
    #version 330 core
    in vec2 v_ndc;
    uniform vec2 u_yawPitch;
    uniform float u_aspect;
    uniform float u_tanHalfFov;
    uniform float u_dayAngle;
    uniform float u_sunHeight;
    uniform float u_weather;
    uniform int u_dimension;
    out vec4 outColor;

    float hash21(vec2 value) {
        value = fract(value * vec2(123.34, 456.21));
        value += dot(value, value + 45.32);
        return fract(value.x * value.y);
    }

    void main() {
        float yaw = u_yawPitch.x;
        float pitch = u_yawPitch.y;
        vec3 forward = vec3(-sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch));
        vec3 backward = -forward;
        vec3 right = normalize(cross(vec3(0.0, 1.0, 0.0), backward));
        vec3 up = normalize(cross(backward, right));
        vec3 direction = normalize(forward + right * v_ndc.x * u_aspect * u_tanHalfFov + up * v_ndc.y * u_tanHalfFov);

        if (u_dimension == 1) {
            float glow = 0.5 + 0.5 * max(direction.y, 0.0);
            outColor = vec4(mix(vec3(0.09, 0.008, 0.006), vec3(0.32, 0.035, 0.018), glow), 1.0);
            return;
        }
        if (u_dimension == 2) {
            float speck = step(0.9985, hash21(floor(direction.xz * 900.0 / max(0.15, abs(direction.y)))));
            outColor = vec4(vec3(0.018, 0.008, 0.028) + speck * vec3(0.28, 0.22, 0.38), 1.0);
            return;
        }

        float daylight = smoothstep(-0.18, 0.22, u_sunHeight);
        float horizon = pow(1.0 - clamp(abs(direction.y), 0.0, 1.0), 2.2);
        vec3 dayZenith = vec3(0.20, 0.48, 0.86);
        vec3 dayHorizon = vec3(0.63, 0.78, 0.94);
        vec3 nightZenith = vec3(0.008, 0.012, 0.045);
        vec3 nightHorizon = vec3(0.045, 0.055, 0.095);
        vec3 zenith = mix(nightZenith, dayZenith, daylight);
        vec3 horizonColor = mix(nightHorizon, dayHorizon, daylight);
        vec3 color = mix(zenith, horizonColor, horizon);

        vec3 sunDirection = normalize(vec3(0.18, cos(u_dayAngle), sin(u_dayAngle)));
        float sun = smoothstep(0.9991, 0.99975, dot(direction, sunDirection)) * daylight * (1.0 - u_weather);
        float moon = smoothstep(0.9988, 0.99955, dot(direction, -sunDirection)) * (1.0 - daylight) * (1.0 - u_weather);
        color += sun * vec3(1.0, 0.88, 0.55) + moon * vec3(0.55, 0.62, 0.78);

        if (direction.y > 0.04) {
            vec2 starCell = floor(direction.xz / direction.y * 720.0);
            float star = step(0.9987, hash21(starCell)) * (1.0 - daylight) * (1.0 - u_weather);
            color += star * vec3(0.75, 0.82, 1.0);
        }
        vec3 storm = vec3(dot(color, vec3(0.30, 0.59, 0.11))) * 0.48;
        color = mix(color, storm, u_weather);
        outColor = vec4(color, 1.0);
    }
"""
