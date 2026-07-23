package moe.afox.dpsandbox.cli

import com.google.gson.JsonObject
import moe.afox.dpsandbox.core.OutputEvent
import moe.afox.dpsandbox.render.RealtimeRenderCamera
import org.lwjgl.opengl.GL11C.GL_BLEND
import org.lwjgl.opengl.GL11C.GL_DEPTH_TEST
import org.lwjgl.opengl.GL11C.GL_FLOAT
import org.lwjgl.opengl.GL11C.GL_LINEAR
import org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA
import org.lwjgl.opengl.GL11C.GL_POINTS
import org.lwjgl.opengl.GL11C.GL_RGBA
import org.lwjgl.opengl.GL11C.GL_SRC_ALPHA
import org.lwjgl.opengl.GL11C.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S
import org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T
import org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11C.glBindTexture
import org.lwjgl.opengl.GL11C.glBlendFunc
import org.lwjgl.opengl.GL11C.glDeleteTextures
import org.lwjgl.opengl.GL11C.glDepthMask
import org.lwjgl.opengl.GL11C.glDisable
import org.lwjgl.opengl.GL11C.glDrawArrays
import org.lwjgl.opengl.GL11C.glEnable
import org.lwjgl.opengl.GL11C.glGenTextures
import org.lwjgl.opengl.GL11C.glTexImage2D
import org.lwjgl.opengl.GL11C.glTexParameteri
import org.lwjgl.opengl.GL11C.glTexSubImage2D
import org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER
import org.lwjgl.opengl.GL15C.GL_DYNAMIC_DRAW
import org.lwjgl.opengl.GL15C.glBindBuffer
import org.lwjgl.opengl.GL15C.glBufferData
import org.lwjgl.opengl.GL15C.glDeleteBuffers
import org.lwjgl.opengl.GL15C.glGenBuffers
import org.lwjgl.opengl.GL20C.glDeleteProgram
import org.lwjgl.opengl.GL20C.glEnableVertexAttribArray
import org.lwjgl.opengl.GL20C.glGetUniformLocation
import org.lwjgl.opengl.GL20C.glUniform1f
import org.lwjgl.opengl.GL20C.glUniform1i
import org.lwjgl.opengl.GL20C.glUniformMatrix4fv
import org.lwjgl.opengl.GL20C.glUseProgram
import org.lwjgl.opengl.GL20C.glVertexAttribPointer
import org.lwjgl.opengl.GL30C.glBindVertexArray
import org.lwjgl.opengl.GL30C.glDeleteVertexArrays
import org.lwjgl.opengl.GL30C.glGenVertexArrays
import org.lwjgl.opengl.GL32C.GL_PROGRAM_POINT_SIZE
import org.lwjgl.system.MemoryUtil.memAlloc
import org.lwjgl.system.MemoryUtil.memCalloc
import org.lwjgl.system.MemoryUtil.memFree
import java.awt.image.BufferedImage
import java.util.Random
import kotlin.math.max
import kotlin.math.tan

internal class GlParticleRenderer(
    private val resources: MinecraftClientResources,
) : AutoCloseable {
    private val program = linkGlProgram(PARTICLE_VERTEX_SHADER, PARTICLE_FRAGMENT_SHADER)
    private val vertexArray = glGenVertexArrays()
    private val vertices = glGenBuffers()
    private val atlas = glGenTextures()
    private val spriteSlots = mutableMapOf<String, Int>()
    private val particles = mutableListOf<GpuParticle>()
    private var nextSpriteSlot = 1
    private var burstSequence = 0L

    init {
        glBindTexture(GL_TEXTURE_2D, atlas)
        val empty = memCalloc(ATLAS_SIZE * ATLAS_SIZE * 4)
        try {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, ATLAS_SIZE, ATLAS_SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, empty)
        } finally {
            memFree(empty)
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        uploadSprite(0, fallbackSprite())

        glBindVertexArray(vertexArray)
        glBindBuffer(GL_ARRAY_BUFFER, vertices)
        attribute(0, 3, 0)
        attribute(1, 4, 3)
        attribute(2, 1, 7)
        attribute(3, 1, 8)
        glBindVertexArray(0)
    }

    fun spawn(
        event: OutputEvent,
        nowNanos: Long = System.nanoTime(),
    ) {
        val payload = event.payload?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        val name = payload.string("particle") ?: event.text
        val count = payload.int("renderCount", 1).coerceIn(1, MAX_PARTICLES_PER_BURST)
        val originX = payload.double("x")
        val originY = payload.double("y")
        val originZ = payload.double("z")
        val deltaX = payload.double("deltaX")
        val deltaY = payload.double("deltaY")
        val deltaZ = payload.double("deltaZ")
        val speed = payload.double("speed")
        val style = particleStyle(name)
        val availableSlots =
            resources
                .particleSprites(name)
                .map(::spriteSlot)
                .takeIf(List<Int>::isNotEmpty)
                ?.toIntArray() ?: intArrayOf(FALLBACK_SLOT)
        val seed =
            event.tick xor
                name.hashCode().toLong().shl(32) xor
                originX.toBits() xor
                originY.toBits().rotateLeft(13) xor
                originZ.toBits().rotateLeft(27) xor
                burstSequence++
        val random = Random(seed)
        val bornSeconds = nowNanos / NANOS_PER_SECOND
        repeat(count) {
            particles +=
                GpuParticle(
                    x = originX + random.centered() * deltaX,
                    y = originY + random.centered() * deltaY,
                    z = originZ + random.centered() * deltaZ,
                    velocityX = random.centered() * speed,
                    velocityY = random.centered() * speed + style.rise,
                    velocityZ = random.centered() * speed,
                    bornSeconds = bornSeconds,
                    style = style,
                    sprites = availableSlots,
                    frameOffset = random.nextInt(availableSlots.size),
                )
        }
        val overflow = particles.size - MAX_ACTIVE_PARTICLES
        if (overflow > 0) particles.subList(0, overflow).clear()
    }

    fun draw(
        camera: RealtimeRenderCamera,
        fieldOfView: Double,
        width: Int,
        height: Int,
        nowNanos: Long = System.nanoTime(),
    ) {
        if (particles.isEmpty()) return
        val nowSeconds = nowNanos / NANOS_PER_SECOND
        particles.removeAll { nowSeconds - it.bornSeconds >= it.style.lifetimeSeconds }
        if (particles.isEmpty()) return
        val data = FloatArray(particles.size * FLOATS_PER_PARTICLE)
        var offset = 0
        particles.forEach { particle ->
            val age = max(0.0, nowSeconds - particle.bornSeconds)
            val life = (age / particle.style.lifetimeSeconds).coerceIn(0.0, 1.0)
            val color = particle.style.color
            val frame = ((life * particle.sprites.size).toInt() + particle.frameOffset).coerceAtMost(Int.MAX_VALUE)
            data[offset++] = (particle.x + particle.velocityX * age).toFloat()
            data[offset++] =
                (particle.y + particle.velocityY * age - particle.style.gravity * age * age * 0.5).toFloat()
            data[offset++] = (particle.z + particle.velocityZ * age).toFloat()
            data[offset++] = (color ushr 16 and 0xff) / 255f
            data[offset++] = (color ushr 8 and 0xff) / 255f
            data[offset++] = (color and 0xff) / 255f
            data[offset++] = ((color ushr 24 and 0xff) / 255f * (1.0 - life)).toFloat()
            data[offset++] = particle.style.sizeWorld
            data[offset++] = particle.sprites[frame % particle.sprites.size].toFloat()
        }
        glUseProgram(program)
        glUniformMatrix4fv(
            glGetUniformLocation(program, "u_projection"),
            false,
            perspectiveMatrix(fieldOfView, width.toFloat() / height.coerceAtLeast(1)),
        )
        glUniformMatrix4fv(glGetUniformLocation(program, "u_view"), false, viewMatrix(camera))
        glUniform1f(
            glGetUniformLocation(program, "u_pointScale"),
            (height.coerceAtLeast(1) / (2.0 * tan(Math.toRadians(fieldOfView) / 2.0))).toFloat(),
        )
        glUniform1i(glGetUniformLocation(program, "u_particleAtlas"), 0)
        glBindTexture(GL_TEXTURE_2D, atlas)
        glBindVertexArray(vertexArray)
        glBindBuffer(GL_ARRAY_BUFFER, vertices)
        glBufferData(GL_ARRAY_BUFFER, data, GL_DYNAMIC_DRAW)
        glEnable(GL_PROGRAM_POINT_SIZE)
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        glDrawArrays(GL_POINTS, 0, particles.size)
        glDepthMask(true)
        glDisable(GL_BLEND)
        glBindVertexArray(0)
        glUseProgram(0)
    }

    override fun close() {
        glDeleteTextures(atlas)
        glDeleteBuffers(vertices)
        glDeleteVertexArrays(vertexArray)
        glDeleteProgram(program)
    }

    private fun spriteSlot(sprite: MinecraftParticleSprite): Int =
        spriteSlots.getOrPut(sprite.id) {
            if (nextSpriteSlot >= MAX_SPRITES) return@getOrPut FALLBACK_SLOT
            nextSpriteSlot++.also { slot -> uploadSprite(slot, sprite.image) }
        }

    private fun uploadSprite(
        slot: Int,
        image: BufferedImage,
    ) {
        val data = memAlloc(SPRITE_SIZE * SPRITE_SIZE * 4)
        try {
            for (y in 0 until SPRITE_SIZE) {
                val sourceY = y * image.height / SPRITE_SIZE
                for (x in 0 until SPRITE_SIZE) {
                    val sourceX = x * image.width / SPRITE_SIZE
                    val argb = image.getRGB(sourceX, sourceY)
                    data.put((argb ushr 16).toByte())
                    data.put((argb ushr 8).toByte())
                    data.put(argb.toByte())
                    data.put((argb ushr 24).toByte())
                }
            }
            data.flip()
            glBindTexture(GL_TEXTURE_2D, atlas)
            glTexSubImage2D(
                GL_TEXTURE_2D,
                0,
                slot % ATLAS_COLUMNS * SPRITE_SIZE,
                slot / ATLAS_COLUMNS * SPRITE_SIZE,
                SPRITE_SIZE,
                SPRITE_SIZE,
                GL_RGBA,
                GL_UNSIGNED_BYTE,
                data,
            )
        } finally {
            memFree(data)
        }
    }

    private fun fallbackSprite(): BufferedImage =
        BufferedImage(SPRITE_SIZE, SPRITE_SIZE, BufferedImage.TYPE_INT_ARGB).also { image ->
            val center = (SPRITE_SIZE - 1) / 2.0
            for (y in 0 until SPRITE_SIZE) {
                for (x in 0 until SPRITE_SIZE) {
                    val distance = kotlin.math.hypot(x - center, y - center) / center
                    val alpha = ((1.0 - distance).coerceIn(0.0, 1.0) * 255).toInt()
                    image.setRGB(x, y, alpha shl 24 or 0x00ffffff)
                }
            }
        }

    private fun attribute(
        location: Int,
        size: Int,
        floatOffset: Int,
    ) {
        glEnableVertexAttribArray(location)
        glVertexAttribPointer(
            location,
            size,
            GL_FLOAT,
            false,
            FLOATS_PER_PARTICLE * Float.SIZE_BYTES,
            floatOffset.toLong() * Float.SIZE_BYTES,
        )
    }

    private fun particleStyle(name: String): ParticleStyle =
        when {
            "large_smoke" in name -> ParticleStyle(0xffffffff.toInt(), 0.42f, 1.7, rise = 0.12)
            "smoke" in name -> ParticleStyle(0xffffffff.toInt(), 0.24f, 1.4, rise = 0.1)
            "cloud" in name -> ParticleStyle(0xffffffff.toInt(), 0.34f, 1.2, rise = 0.04)
            "flame" in name -> ParticleStyle(0xffffffff.toInt(), 0.2f, 1.0, rise = 0.22)
            "portal" in name -> ParticleStyle(0xffffffff.toInt(), 0.18f, 1.5)
            "heart" in name -> ParticleStyle(0xffffffff.toInt(), 0.28f, 1.4, rise = 0.12)
            "dust" in name -> ParticleStyle(0xffd64135.toInt(), 0.17f, 1.25, gravity = 0.04)
            "block" in name -> ParticleStyle(0xff9a8464.toInt(), 0.16f, 1.0, gravity = 0.45)
            "item" in name -> ParticleStyle(0xffffffff.toInt(), 0.16f, 1.0, gravity = 0.35)
            else -> ParticleStyle(0xffffffff.toInt(), 0.18f, 1.2, gravity = 0.08)
        }

    private fun Random.centered(): Double = nextDouble() * 2.0 - 1.0

    private data class GpuParticle(
        val x: Double,
        val y: Double,
        val z: Double,
        val velocityX: Double,
        val velocityY: Double,
        val velocityZ: Double,
        val bornSeconds: Double,
        val style: ParticleStyle,
        val sprites: IntArray,
        val frameOffset: Int,
    )

    private data class ParticleStyle(
        val color: Int,
        val sizeWorld: Float,
        val lifetimeSeconds: Double,
        val gravity: Double = 0.0,
        val rise: Double = 0.0,
    )

    companion object {
        private const val FLOATS_PER_PARTICLE = 9
        private const val MAX_PARTICLES_PER_BURST = 4_096
        private const val MAX_ACTIVE_PARTICLES = 16_384
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val SPRITE_SIZE = 32
        private const val ATLAS_COLUMNS = 16
        private const val ATLAS_SIZE = SPRITE_SIZE * ATLAS_COLUMNS
        private const val MAX_SPRITES = ATLAS_COLUMNS * ATLAS_COLUMNS
        private const val FALLBACK_SLOT = 0
    }
}

private fun JsonObject.double(
    name: String,
    fallback: Double = 0.0,
): Double = get(name)?.takeIf { it.isJsonPrimitive }?.asDouble ?: fallback

private fun JsonObject.int(
    name: String,
    fallback: Int,
): Int = get(name)?.takeIf { it.isJsonPrimitive }?.asInt ?: fallback

private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

private const val PARTICLE_VERTEX_SHADER = """
    #version 330 core
    layout(location = 0) in vec3 a_position;
    layout(location = 1) in vec4 a_color;
    layout(location = 2) in float a_size;
    layout(location = 3) in float a_sprite;
    uniform mat4 u_projection;
    uniform mat4 u_view;
    uniform float u_pointScale;
    out vec4 v_color;
    flat out float v_sprite;
    void main() {
        vec4 viewPosition = u_view * vec4(a_position, 1.0);
        gl_Position = u_projection * viewPosition;
        gl_PointSize = clamp(a_size * u_pointScale / max(0.05, -viewPosition.z), 1.0, 96.0);
        v_color = a_color;
        v_sprite = a_sprite;
    }
"""

private const val PARTICLE_FRAGMENT_SHADER = """
    #version 330 core
    in vec4 v_color;
    flat in float v_sprite;
    uniform sampler2D u_particleAtlas;
    out vec4 outColor;
    void main() {
        float slot = floor(v_sprite + 0.5);
        vec2 cell = vec2(mod(slot, 16.0), floor(slot / 16.0));
        vec2 localUv = vec2(gl_PointCoord.x, 1.0 - gl_PointCoord.y);
        vec4 sampled = texture(u_particleAtlas, (cell + localUv) / 16.0);
        if (sampled.a <= 0.01) discard;
        outColor = sampled * v_color;
    }
"""
