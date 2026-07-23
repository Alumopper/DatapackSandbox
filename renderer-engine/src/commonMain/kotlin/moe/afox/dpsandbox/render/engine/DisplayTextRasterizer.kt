package moe.afox.dpsandbox.render.engine

import kotlin.math.max

/** Platform-neutral ARGB bitmap used for display-entity text and optional font atlases. */
data class DisplayTextBitmap(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(width > 0 && height > 0)
        require(pixels.size == width * height)
    }
}

/** Deterministic Minecraft-style pixel text rasterizer shared by JVM and Web. */
object DisplayTextRasterizer {
    fun render(
        text: String,
        lineWidth: Int = 200,
        alignment: String = "center",
        background: Int = 0x40000000,
        defaultBackground: Boolean = false,
        textOpacity: Int = 255,
        shadow: Boolean = false,
        textColor: Int = 0xffffffff.toInt(),
        fontAtlas: DisplayTextBitmap? = null,
    ): DisplayTextBitmap {
        val glyphs = text.map { glyph(it, fontAtlas) }
        val maximumWidth = lineWidth.coerceAtLeast(1)
        val lines = mutableListOf<MutableList<Glyph>>()
        var current = mutableListOf<Glyph>()
        var currentWidth = 0
        fun commit() {
            lines += current
            current = mutableListOf()
            currentWidth = 0
        }
        glyphs.forEach { glyph ->
            if (glyph.character == '\n') {
                commit()
            } else {
                val advance = glyph.width + 1
                if (current.isNotEmpty() && currentWidth + advance > maximumWidth) commit()
                current += glyph
                currentWidth += advance
            }
        }
        if (current.isNotEmpty() || lines.isEmpty()) commit()

        val lineWidths = lines.map { line -> line.sumOf { it.width + 1 }.coerceAtLeast(1) - 1 }
        val contentWidth = lineWidths.maxOrNull()?.coerceAtLeast(1) ?: 1
        val lineHeight = max(8, lines.maxOfOrNull { line -> line.maxOfOrNull(Glyph::height) ?: 7 } ?: 7) + 1
        val width = contentWidth + 2
        val height = lines.size * lineHeight + 1
        val bg = if (defaultBackground) 0x40000000 else background
        val pixels = IntArray(width * height) { bg }
        val opacity = textOpacity and 0xff
        val color = (opacity shl 24) or (textColor and 0x00ffffff)

        lines.forEachIndexed { lineIndex, line ->
            val linePixels = lineWidths[lineIndex]
            var x =
                when (alignment) {
                    "left" -> 1
                    "right" -> 1 + contentWidth - linePixels
                    else -> 1 + (contentWidth - linePixels) / 2
                }
            val y = 1 + lineIndex * lineHeight
            line.forEach { glyph ->
                if (shadow) drawGlyph(pixels, width, height, x + 1, y + 1, glyph, (opacity * 3 / 4 shl 24))
                drawGlyph(pixels, width, height, x, y, glyph, color)
                x += glyph.width + 1
            }
        }
        return DisplayTextBitmap(width, height, pixels)
    }

    private fun drawGlyph(
        output: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        glyph: Glyph,
        color: Int,
    ) {
        glyph.pixels.forEachIndexed { index, alpha ->
            if (alpha == 0) return@forEachIndexed
            val targetX = x + index % glyph.width
            val targetY = y + index / glyph.width
            if (targetX !in 0 until width || targetY !in 0 until height) return@forEachIndexed
            val sourceAlpha = color ushr 24 and 0xff
            output[targetY * width + targetX] = (sourceAlpha * alpha / 255 shl 24) or (color and 0x00ffffff)
        }
    }

    private fun glyph(
        character: Char,
        atlas: DisplayTextBitmap?,
    ): Glyph {
        if (character == '\n') return Glyph(character, 0, 0, IntArray(0))
        atlasGlyph(character, atlas)?.let { return it }
        if (character == ' ') return Glyph(character, 3, 7, IntArray(21))
        val rows = BUILTIN[character.uppercaseChar()] ?: BUILTIN.getValue('?')
        val glyphWidth = rows.maxOf { it.length }
        val pixels = IntArray(glyphWidth * rows.size)
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, value -> if (value != '0') pixels[y * glyphWidth + x] = 255 }
        }
        return Glyph(character, glyphWidth, rows.size, pixels)
    }

    private fun atlasGlyph(
        character: Char,
        atlas: DisplayTextBitmap?,
    ): Glyph? {
        if (atlas == null || character.code !in 0..255 || atlas.width % 16 != 0 || atlas.height % 16 != 0) return null
        val cellWidth = atlas.width / 16
        val cellHeight = atlas.height / 16
        val sourceX = character.code % 16 * cellWidth
        val sourceY = character.code / 16 * cellHeight
        var lastOpaque = -1
        for (x in 0 until cellWidth) {
            if ((0 until cellHeight).any { y -> atlas.pixels[(sourceY + y) * atlas.width + sourceX + x] ushr 24 != 0 }) lastOpaque = x
        }
        val glyphWidth = (lastOpaque + 1).coerceAtLeast(if (character == ' ') cellWidth / 2 else 1)
        val pixels = IntArray(glyphWidth * cellHeight)
        for (y in 0 until cellHeight) {
            for (x in 0 until glyphWidth) {
                pixels[y * glyphWidth + x] = atlas.pixels[(sourceY + y) * atlas.width + sourceX + x] ushr 24 and 0xff
            }
        }
        return Glyph(character, glyphWidth, cellHeight, pixels)
    }

    private data class Glyph(
        val character: Char,
        val width: Int,
        val height: Int,
        val pixels: IntArray,
    )

    private val BUILTIN =
        mapOf(
            'A' to listOf("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
            'B' to listOf("11110", "10001", "10001", "11110", "10001", "10001", "11110"),
            'C' to listOf("01111", "10000", "10000", "10000", "10000", "10000", "01111"),
            'D' to listOf("11110", "10001", "10001", "10001", "10001", "10001", "11110"),
            'E' to listOf("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
            'F' to listOf("11111", "10000", "10000", "11110", "10000", "10000", "10000"),
            'G' to listOf("01111", "10000", "10000", "10111", "10001", "10001", "01111"),
            'H' to listOf("10001", "10001", "10001", "11111", "10001", "10001", "10001"),
            'I' to listOf("11111", "00100", "00100", "00100", "00100", "00100", "11111"),
            'J' to listOf("00111", "00010", "00010", "00010", "10010", "10010", "01100"),
            'K' to listOf("10001", "10010", "10100", "11000", "10100", "10010", "10001"),
            'L' to listOf("10000", "10000", "10000", "10000", "10000", "10000", "11111"),
            'M' to listOf("10001", "11011", "10101", "10101", "10001", "10001", "10001"),
            'N' to listOf("10001", "11001", "10101", "10011", "10001", "10001", "10001"),
            'O' to listOf("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
            'P' to listOf("11110", "10001", "10001", "11110", "10000", "10000", "10000"),
            'Q' to listOf("01110", "10001", "10001", "10001", "10101", "10010", "01101"),
            'R' to listOf("11110", "10001", "10001", "11110", "10100", "10010", "10001"),
            'S' to listOf("01111", "10000", "10000", "01110", "00001", "00001", "11110"),
            'T' to listOf("11111", "00100", "00100", "00100", "00100", "00100", "00100"),
            'U' to listOf("10001", "10001", "10001", "10001", "10001", "10001", "01110"),
            'V' to listOf("10001", "10001", "10001", "10001", "10001", "01010", "00100"),
            'W' to listOf("10001", "10001", "10001", "10101", "10101", "10101", "01010"),
            'X' to listOf("10001", "10001", "01010", "00100", "01010", "10001", "10001"),
            'Y' to listOf("10001", "10001", "01010", "00100", "00100", "00100", "00100"),
            'Z' to listOf("11111", "00001", "00010", "00100", "01000", "10000", "11111"),
            '0' to listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
            '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
            '2' to listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
            '3' to listOf("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
            '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
            '5' to listOf("11111", "10000", "10000", "11110", "00001", "00001", "11110"),
            '6' to listOf("01110", "10000", "10000", "11110", "10001", "10001", "01110"),
            '7' to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
            '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
            '9' to listOf("01110", "10001", "10001", "01111", "00001", "00001", "01110"),
            '.' to listOf("0", "0", "0", "0", "0", "1", "1"),
            ',' to listOf("00", "00", "00", "00", "00", "01", "10"),
            ':' to listOf("0", "1", "1", "0", "1", "1", "0"),
            '!' to listOf("1", "1", "1", "1", "1", "0", "1"),
            '-' to listOf("000", "000", "000", "111", "000", "000", "000"),
            '_' to listOf("00000", "00000", "00000", "00000", "00000", "00000", "11111"),
            '/' to listOf("00001", "00010", "00100", "01000", "10000", "00000", "00000"),
            '?' to listOf("01110", "10001", "00001", "00010", "00100", "00000", "00100"),
        )
}
