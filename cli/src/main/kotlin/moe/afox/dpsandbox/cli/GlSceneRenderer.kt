package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.render.CompiledRealtimeGpuScene
import moe.afox.dpsandbox.render.RealtimeGpuMaterialBatch
import moe.afox.dpsandbox.render.RealtimeGpuSceneSection
import moe.afox.dpsandbox.render.RealtimeRenderCamera
import org.lwjgl.opengl.GL11C.GL_BLEND
import org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11C.GL_DEPTH_BUFFER_BIT
import org.lwjgl.opengl.GL11C.GL_DEPTH_TEST
import org.lwjgl.opengl.GL11C.GL_FLOAT
import org.lwjgl.opengl.GL11C.GL_NEAREST
import org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA
import org.lwjgl.opengl.GL11C.GL_RGBA
import org.lwjgl.opengl.GL11C.GL_SRC_ALPHA
import org.lwjgl.opengl.GL11C.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11C.GL_TRIANGLES
import org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11C.GL_UNSIGNED_INT
import org.lwjgl.opengl.GL11C.glBindTexture
import org.lwjgl.opengl.GL11C.glBlendFunc
import org.lwjgl.opengl.GL11C.glClear
import org.lwjgl.opengl.GL11C.glClearColor
import org.lwjgl.opengl.GL11C.glDeleteTextures
import org.lwjgl.opengl.GL11C.glDepthMask
import org.lwjgl.opengl.GL11C.glDisable
import org.lwjgl.opengl.GL11C.glDrawElements
import org.lwjgl.opengl.GL11C.glEnable
import org.lwjgl.opengl.GL11C.glGenTextures
import org.lwjgl.opengl.GL11C.glTexImage2D
import org.lwjgl.opengl.GL11C.glTexParameteri
import org.lwjgl.opengl.GL11C.glViewport
import org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER
import org.lwjgl.opengl.GL15C.GL_ELEMENT_ARRAY_BUFFER
import org.lwjgl.opengl.GL15C.GL_STATIC_DRAW
import org.lwjgl.opengl.GL15C.glBindBuffer
import org.lwjgl.opengl.GL15C.glBufferData
import org.lwjgl.opengl.GL15C.glDeleteBuffers
import org.lwjgl.opengl.GL15C.glGenBuffers
import org.lwjgl.opengl.GL20C.GL_COMPILE_STATUS
import org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER
import org.lwjgl.opengl.GL20C.GL_LINK_STATUS
import org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER
import org.lwjgl.opengl.GL20C.glAttachShader
import org.lwjgl.opengl.GL20C.glCompileShader
import org.lwjgl.opengl.GL20C.glCreateProgram
import org.lwjgl.opengl.GL20C.glCreateShader
import org.lwjgl.opengl.GL20C.glDeleteProgram
import org.lwjgl.opengl.GL20C.glDeleteShader
import org.lwjgl.opengl.GL20C.glEnableVertexAttribArray
import org.lwjgl.opengl.GL20C.glGetProgramInfoLog
import org.lwjgl.opengl.GL20C.glGetProgrami
import org.lwjgl.opengl.GL20C.glGetShaderInfoLog
import org.lwjgl.opengl.GL20C.glGetShaderi
import org.lwjgl.opengl.GL20C.glGetUniformLocation
import org.lwjgl.opengl.GL20C.glLinkProgram
import org.lwjgl.opengl.GL20C.glShaderSource
import org.lwjgl.opengl.GL20C.glUniform1f
import org.lwjgl.opengl.GL20C.glUniform1i
import org.lwjgl.opengl.GL20C.glUniformMatrix4fv
import org.lwjgl.opengl.GL20C.glUseProgram
import org.lwjgl.opengl.GL20C.glVertexAttribPointer
import org.lwjgl.opengl.GL30C.glBindVertexArray
import org.lwjgl.opengl.GL30C.glDeleteVertexArrays
import org.lwjgl.opengl.GL30C.glGenVertexArrays
import org.lwjgl.system.MemoryUtil

internal class GlSceneRenderer(
    initialFieldOfView: Double,
) : AutoCloseable {
    private val program = linkGlProgram(VERTEX_SHADER_SOURCE, FRAGMENT_SHADER_SOURCE)
    private val sky = GlSkyRenderer()
    private val blocks = GlMesh()
    private val entities = GlMesh()
    private var texture = 0
    private var sceneReceivedNanos = System.nanoTime()
    private var tickRate = 20
    var fieldOfView = initialFieldOfView
    var triangleCount = 0
        private set

    fun updateEnvironment(environment: JvmViewportEnvironment) {
        sky.updateEnvironment(environment)
    }

    fun updateScene(
        scene: CompiledRealtimeGpuScene,
        tickRate: Int,
    ) {
        this.tickRate = tickRate
        blocks.upload(scene.blocks, interpolateFromPrevious = false)
        entities.upload(scene.entities, interpolateFromPrevious = true)
        uploadTexture(scene)
        triangleCount = (scene.blocks.indices.size + scene.entities.indices.size) / 3
        sceneReceivedNanos = System.nanoTime()
    }

    fun draw(
        camera: RealtimeRenderCamera,
        width: Int,
        height: Int,
    ) {
        glViewport(0, 0, width, height)
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        sky.draw(camera, fieldOfView, width, height)
        glEnable(GL_DEPTH_TEST)
        glUseProgram(program)
        glUniformMatrix4fv(
            glGetUniformLocation(program, "u_projection"),
            false,
            perspectiveMatrix(fieldOfView, width.toFloat() / height.coerceAtLeast(1)),
        )
        glUniformMatrix4fv(glGetUniformLocation(program, "u_view"), false, viewMatrix(camera))
        glUniform1i(glGetUniformLocation(program, "u_texture"), 0)
        glBindTexture(GL_TEXTURE_2D, texture)
        blocks.draw(program, interpolation = 1f)
        val tickNanos = 1_000_000_000.0 / tickRate.coerceAtLeast(1)
        val interpolation = ((System.nanoTime() - sceneReceivedNanos) / tickNanos).toFloat().coerceIn(0f, 1f)
        entities.draw(program, interpolation)
        glBindVertexArray(0)
        glUseProgram(0)
    }

    override fun close() {
        sky.close()
        blocks.close()
        entities.close()
        if (texture != 0) glDeleteTextures(texture)
        glDeleteProgram(program)
    }

    private fun uploadTexture(scene: CompiledRealtimeGpuScene) {
        if (texture == 0) texture = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, texture)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        val bytes = MemoryUtil.memAlloc(scene.atlas.rgba.size)
        try {
            bytes.put(scene.atlas.rgba).flip()
            glTexImage2D(
                GL_TEXTURE_2D,
                0,
                GL_RGBA,
                scene.atlas.width,
                scene.atlas.height,
                0,
                GL_RGBA,
                GL_UNSIGNED_BYTE,
                bytes,
            )
        } finally {
            MemoryUtil.memFree(bytes)
        }
    }

    private class GlMesh : AutoCloseable {
        private val vertexArray = glGenVertexArrays()
        private val vertices = glGenBuffers()
        private val previousVertices = glGenBuffers()
        private val indices = glGenBuffers()
        private var currentVertices = FloatArray(0)
        private var batches: List<RealtimeGpuMaterialBatch> = emptyList()

        fun upload(
            section: RealtimeGpuSceneSection,
            interpolateFromPrevious: Boolean,
        ) {
            val previous =
                currentVertices.takeIf { interpolateFromPrevious && it.size == section.vertices.size }
                    ?: section.vertices
            currentVertices = section.vertices
            batches = section.batches
            glBindVertexArray(vertexArray)
            glBindBuffer(GL_ARRAY_BUFFER, vertices)
            glBufferData(GL_ARRAY_BUFFER, section.vertices, GL_STATIC_DRAW)
            bindCurrentAttributes()
            glBindBuffer(GL_ARRAY_BUFFER, previousVertices)
            glBufferData(GL_ARRAY_BUFFER, previous, GL_STATIC_DRAW)
            glEnableVertexAttribArray(5)
            glVertexAttribPointer(5, 3, GL_FLOAT, false, STRIDE_BYTES, 0L)
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices)
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, section.indices, GL_STATIC_DRAW)
            glBindVertexArray(0)
        }

        fun draw(
            program: Int,
            interpolation: Float,
        ) {
            if (batches.isEmpty()) return
            glBindVertexArray(vertexArray)
            glUniform1f(glGetUniformLocation(program, "u_interpolation"), interpolation)
            batches.forEach { batch ->
                val translucent = batch.pass == "translucent"
                glUniform1f(glGetUniformLocation(program, "u_alphaCutoff"), if (batch.pass == "cutout") 0.1f else 0f)
                if (translucent) {
                    glEnable(GL_BLEND)
                    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
                    glDepthMask(false)
                } else {
                    glDisable(GL_BLEND)
                    glDepthMask(true)
                }
                if (batch.seeThrough) glDisable(GL_DEPTH_TEST) else glEnable(GL_DEPTH_TEST)
                glDrawElements(GL_TRIANGLES, batch.indexCount, GL_UNSIGNED_INT, batch.indexOffset.toLong() * Int.SIZE_BYTES)
            }
            glDisable(GL_BLEND)
            glDepthMask(true)
            glEnable(GL_DEPTH_TEST)
        }

        override fun close() {
            glDeleteBuffers(vertices)
            glDeleteBuffers(previousVertices)
            glDeleteBuffers(indices)
            glDeleteVertexArrays(vertexArray)
        }

        private fun bindCurrentAttributes() {
            attribute(0, 3, 0)
            attribute(1, 2, 3)
            attribute(2, 3, 5)
            attribute(3, 3, 8)
            attribute(4, 1, 11)
        }

        private fun attribute(
            location: Int,
            size: Int,
            floatOffset: Int,
        ) {
            glEnableVertexAttribArray(location)
            glVertexAttribPointer(location, size, GL_FLOAT, false, STRIDE_BYTES, floatOffset.toLong() * Float.SIZE_BYTES)
        }

        companion object {
            private const val STRIDE_BYTES = CompiledRealtimeGpuScene.FLOATS_PER_VERTEX * Float.SIZE_BYTES
        }
    }
}

internal fun linkGlProgram(
    vertexSource: String,
    fragmentSource: String,
): Int {
    val vertex = compileGlShader(GL_VERTEX_SHADER, vertexSource)
    val fragment = compileGlShader(GL_FRAGMENT_SHADER, fragmentSource)
    val program = glCreateProgram()
    glAttachShader(program, vertex)
    glAttachShader(program, fragment)
    glLinkProgram(program)
    val linked = glGetProgrami(program, GL_LINK_STATUS)
    val log = glGetProgramInfoLog(program)
    glDeleteShader(vertex)
    glDeleteShader(fragment)
    check(linked != 0) { "OpenGL program link failed: $log" }
    return program
}

private fun compileGlShader(
    type: Int,
    source: String,
): Int {
    val shader = glCreateShader(type)
    glShaderSource(shader, source)
    glCompileShader(shader)
    check(glGetShaderi(shader, GL_COMPILE_STATUS) != 0) { "OpenGL shader compile failed: ${glGetShaderInfoLog(shader)}" }
    return shader
}

private const val VERTEX_SHADER_SOURCE = """
    #version 330 core
    layout(location = 0) in vec3 a_position;
    layout(location = 1) in vec2 a_uv;
    layout(location = 2) in vec3 a_normal;
    layout(location = 3) in vec3 a_tint;
    layout(location = 4) in float a_emissive;
    layout(location = 5) in vec3 a_previousPosition;
    uniform mat4 u_projection;
    uniform mat4 u_view;
    uniform float u_interpolation;
    out vec2 v_uv;
    out vec3 v_normal;
    out vec3 v_tint;
    out float v_emissive;
    void main() {
        vec3 position = mix(a_previousPosition, a_position, u_interpolation);
        gl_Position = u_projection * u_view * vec4(position, 1.0);
        v_uv = a_uv;
        v_normal = a_normal;
        v_tint = a_tint;
        v_emissive = a_emissive;
    }
"""

private const val FRAGMENT_SHADER_SOURCE = """
    #version 330 core
    in vec2 v_uv;
    in vec3 v_normal;
    in vec3 v_tint;
    in float v_emissive;
    uniform sampler2D u_texture;
    uniform float u_alphaCutoff;
    out vec4 outColor;
    void main() {
        vec4 sampled = texture(u_texture, v_uv) * vec4(v_tint, 1.0);
        if (sampled.a <= u_alphaCutoff) discard;
        vec3 lightDirection = normalize(vec3(-0.45, 0.85, -0.3));
        float light = mix(0.38 + max(dot(normalize(v_normal), lightDirection), 0.0) * 0.62, 1.0, v_emissive);
        outColor = vec4(sampled.rgb * light, sampled.a);
    }
"""
