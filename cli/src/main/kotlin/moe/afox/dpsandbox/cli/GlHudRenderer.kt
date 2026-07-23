package moe.afox.dpsandbox.cli

import org.lwjgl.opengl.GL11C.GL_BLEND
import org.lwjgl.opengl.GL11C.GL_DEPTH_TEST
import org.lwjgl.opengl.GL11C.GL_FLOAT
import org.lwjgl.opengl.GL11C.GL_NEAREST
import org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA
import org.lwjgl.opengl.GL11C.GL_RED
import org.lwjgl.opengl.GL11C.GL_SRC_ALPHA
import org.lwjgl.opengl.GL11C.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S
import org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T
import org.lwjgl.opengl.GL11C.GL_TRIANGLES
import org.lwjgl.opengl.GL11C.GL_UNPACK_ALIGNMENT
import org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11C.glBindTexture
import org.lwjgl.opengl.GL11C.glBlendFunc
import org.lwjgl.opengl.GL11C.glDeleteTextures
import org.lwjgl.opengl.GL11C.glDisable
import org.lwjgl.opengl.GL11C.glDrawArrays
import org.lwjgl.opengl.GL11C.glEnable
import org.lwjgl.opengl.GL11C.glGenTextures
import org.lwjgl.opengl.GL11C.glPixelStorei
import org.lwjgl.opengl.GL11C.glTexImage2D
import org.lwjgl.opengl.GL11C.glTexParameteri
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
import org.lwjgl.opengl.GL20C.glUniform1i
import org.lwjgl.opengl.GL20C.glUniform2f
import org.lwjgl.opengl.GL20C.glUniform4f
import org.lwjgl.opengl.GL20C.glUseProgram
import org.lwjgl.opengl.GL20C.glVertexAttribPointer
import org.lwjgl.opengl.GL30C.GL_R8
import org.lwjgl.opengl.GL30C.glBindVertexArray
import org.lwjgl.opengl.GL30C.glDeleteVertexArrays
import org.lwjgl.opengl.GL30C.glGenVertexArrays
import org.lwjgl.system.MemoryUtil.memAlloc
import org.lwjgl.system.MemoryUtil.memFree
import kotlin.math.roundToInt

internal class GlHudRenderer(
    resources: MinecraftClientResources,
) : AutoCloseable {
    private val program = linkGlProgram(HUD_VERTEX_SHADER, HUD_FRAGMENT_SHADER)
    private val solidProgram = linkGlProgram(SOLID_VERTEX_SHADER, SOLID_FRAGMENT_SHADER)
    private val vertexArray = glGenVertexArrays()
    private val vertices = glGenBuffers()
    private val font = resources.defaultFont()
    private val fontTexture = glGenTextures()
    private var width = 1
    private var height = 1

    init {
        val bitmap = memAlloc(font.image.width * font.image.height)
        try {
            for (y in 0 until font.image.height) {
                for (x in 0 until font.image.width) bitmap.put((font.image.getRGB(x, y) ushr 24).toByte())
            }
            bitmap.flip()
            glBindTexture(GL_TEXTURE_2D, fontTexture)
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
            glTexImage2D(
                GL_TEXTURE_2D,
                0,
                GL_R8,
                font.image.width,
                font.image.height,
                0,
                GL_RED,
                GL_UNSIGNED_BYTE,
                bitmap,
            )
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        } finally {
            memFree(bitmap)
        }
        glBindVertexArray(vertexArray)
        glBindBuffer(GL_ARRAY_BUFFER, vertices)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, FONT_VERTEX_BYTES, 0L)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, FONT_VERTEX_BYTES, 2L * Float.SIZE_BYTES)
        glBindVertexArray(0)
    }

    fun begin(
        width: Int,
        height: Int,
    ) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        glDisable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
    }

    fun rectangle(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        color: Int,
    ) {
        if (width <= 0 || height <= 0) return
        val x0 = x.toFloat()
        val y0 = y.toFloat()
        val x1 = (x + width).toFloat()
        val y1 = (y + height).toFloat()
        val vertexData =
            floatArrayOf(
                x0,
                y0,
                0f,
                0f,
                x1,
                y0,
                0f,
                0f,
                x1,
                y1,
                0f,
                0f,
                x0,
                y0,
                0f,
                0f,
                x1,
                y1,
                0f,
                0f,
                x0,
                y1,
                0f,
                0f,
            )
        glUseProgram(solidProgram)
        glUniform2f(glGetUniformLocation(solidProgram, "u_resolution"), this.width.toFloat(), this.height.toFloat())
        uploadColor(solidProgram, color)
        uploadAndDraw(vertexData)
        glUseProgram(0)
    }

    fun textWidth(
        text: String,
        scale: Float = 1f,
    ): Float {
        val pixelScale = pixelScale(scale)
        return text
            .codePoints()
            .mapToDouble { codePoint -> glyph(codePoint).advance.toDouble() }
            .sum()
            .toFloat() * pixelScale
    }

    fun textHeight(scale: Float = 1f): Float = MINECRAFT_LINE_HEIGHT * pixelScale(scale)

    fun text(
        text: String,
        x: Float,
        y: Float,
        color: Int = 0xffe7f1ed.toInt(),
        scale: Float = 1f,
    ) {
        if (text.isEmpty()) return
        val pixelScale = pixelScale(scale)
        val codePoints = text.codePoints().toArray()
        val vertexData = FloatArray(codePoints.size * VERTICES_PER_GLYPH * FLOATS_PER_FONT_VERTEX)
        var cursor = x.roundToInt().toFloat()
        var offset = 0
        codePoints.forEach { codePoint ->
            val glyph = glyph(codePoint)
            val x0 = cursor
            val y0 = y.roundToInt() + glyph.topOffset * pixelScale
            val x1 = x0 + glyph.renderedWidth * pixelScale
            val y1 = y0 + glyph.renderedHeight * pixelScale
            val u0 = glyph.x.toFloat() / font.image.width
            val v0 = glyph.y.toFloat() / font.image.height
            val u1 = (glyph.x + glyph.width).toFloat() / font.image.width
            val v1 = (glyph.y + glyph.height).toFloat() / font.image.height
            offset = vertexData.putVertex(offset, x0, y0, u0, v0)
            offset = vertexData.putVertex(offset, x1, y0, u1, v0)
            offset = vertexData.putVertex(offset, x1, y1, u1, v1)
            offset = vertexData.putVertex(offset, x0, y0, u0, v0)
            offset = vertexData.putVertex(offset, x1, y1, u1, v1)
            offset = vertexData.putVertex(offset, x0, y1, u0, v1)
            cursor += glyph.advance * pixelScale
        }
        glUseProgram(program)
        glUniform2f(glGetUniformLocation(program, "u_resolution"), width.toFloat(), height.toFloat())
        uploadColor(program, color)
        glUniform1i(glGetUniformLocation(program, "u_font"), 0)
        glBindTexture(GL_TEXTURE_2D, fontTexture)
        uploadAndDraw(vertexData.copyOf(offset))
        glUseProgram(0)
    }

    fun shadowedText(
        text: String,
        x: Float,
        y: Float,
        color: Int = 0xffffffff.toInt(),
        scale: Float = 1f,
    ) {
        val offset = pixelScale(scale)
        val alpha = color ushr 24 and 0xff
        val shadow =
            (alpha shl 24) or
                ((color ushr 18 and 0x3f) shl 16) or
                ((color ushr 10 and 0x3f) shl 8) or
                (color ushr 2 and 0x3f)
        text(text, x + offset, y + offset, shadow, scale)
        text(text, x, y, color, scale)
    }

    fun end() {
        glDisable(GL_BLEND)
        glEnable(GL_DEPTH_TEST)
    }

    override fun close() {
        glDeleteTextures(fontTexture)
        glDeleteBuffers(vertices)
        glDeleteVertexArrays(vertexArray)
        glDeleteProgram(solidProgram)
        glDeleteProgram(program)
    }

    private fun glyph(codePoint: Int): MinecraftBitmapGlyph = font.glyphs[codePoint] ?: font.fallback ?: font.glyphs.values.first()

    private fun pixelScale(scale: Float): Float =
        (BASE_TEXT_PIXELS * scale.coerceIn(0.5f, 4f) / MINECRAFT_LINE_HEIGHT).roundToInt().coerceIn(1, 8).toFloat()

    private fun uploadColor(
        targetProgram: Int,
        color: Int,
    ) {
        glUniform4f(
            glGetUniformLocation(targetProgram, "u_color"),
            (color ushr 16 and 0xff) / 255f,
            (color ushr 8 and 0xff) / 255f,
            (color and 0xff) / 255f,
            (color ushr 24 and 0xff) / 255f,
        )
    }

    private fun uploadAndDraw(vertexData: FloatArray) {
        glBindVertexArray(vertexArray)
        glBindBuffer(GL_ARRAY_BUFFER, vertices)
        glBufferData(GL_ARRAY_BUFFER, vertexData, GL_DYNAMIC_DRAW)
        glDrawArrays(GL_TRIANGLES, 0, vertexData.size / FLOATS_PER_FONT_VERTEX)
        glBindVertexArray(0)
    }

    private fun FloatArray.putVertex(
        offset: Int,
        x: Float,
        y: Float,
        u: Float,
        v: Float,
    ): Int {
        this[offset] = x
        this[offset + 1] = y
        this[offset + 2] = u
        this[offset + 3] = v
        return offset + FLOATS_PER_FONT_VERTEX
    }

    companion object {
        private const val MINECRAFT_LINE_HEIGHT = 9f
        private const val BASE_TEXT_PIXELS = 11f
        private const val FLOATS_PER_FONT_VERTEX = 4
        private const val FONT_VERTEX_BYTES = FLOATS_PER_FONT_VERTEX * Float.SIZE_BYTES
        private const val VERTICES_PER_GLYPH = 6
    }
}

private const val HUD_VERTEX_SHADER = """
    #version 330 core
    layout(location = 0) in vec2 a_position;
    layout(location = 1) in vec2 a_uv;
    uniform vec2 u_resolution;
    out vec2 v_uv;
    void main() {
        vec2 position = vec2(
            a_position.x / u_resolution.x * 2.0 - 1.0,
            1.0 - a_position.y / u_resolution.y * 2.0
        );
        gl_Position = vec4(position, 0.0, 1.0);
        v_uv = a_uv;
    }
"""

private const val HUD_FRAGMENT_SHADER = """
    #version 330 core
    in vec2 v_uv;
    uniform sampler2D u_font;
    uniform vec4 u_color;
    out vec4 outColor;
    void main() {
        float coverage = texture(u_font, v_uv).r;
        if (coverage <= 0.01) discard;
        outColor = vec4(u_color.rgb, u_color.a * coverage);
    }
"""

private const val SOLID_VERTEX_SHADER = """
    #version 330 core
    layout(location = 0) in vec2 a_position;
    uniform vec2 u_resolution;
    void main() {
        vec2 position = vec2(
            a_position.x / u_resolution.x * 2.0 - 1.0,
            1.0 - a_position.y / u_resolution.y * 2.0
        );
        gl_Position = vec4(position, 0.0, 1.0);
    }
"""

private const val SOLID_FRAGMENT_SHADER = """
    #version 330 core
    uniform vec4 u_color;
    out vec4 outColor;
    void main() {
        outColor = u_color;
    }
"""
