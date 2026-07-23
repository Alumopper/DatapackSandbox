package moe.afox.dpsandbox.render.engine

/** One RGBA frame consumed by the deterministic shared GIF encoder. */
data class EngineGifFrame(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
    val delayCentiseconds: Int = 25,
) {
    init {
        require(width > 0 && height > 0) { "GIF frame dimensions must be positive" }
        require(rgba.size == width * height * 4) { "GIF RGBA buffer does not match ${width}x$height" }
        require(delayCentiseconds in 1..65_535) { "GIF frame delay must be between 1 and 65535 centiseconds" }
    }
}

/**
 * Small deterministic GIF89a encoder shared by JVM and Kotlin/JS.
 *
 * Frames are quantized to one deterministic adaptive global palette. Keeping the
 * palette and LZW implementation in common code preserves byte-for-byte
 * cross-platform output without adding an image-codec dependency.
 */
object AnimatedGifEncoder {
    fun encode(
        frames: List<EngineGifFrame>,
        repeat: Int = 0,
    ): ByteArray {
        require(frames.isNotEmpty()) { "At least one GIF frame is required" }
        require(repeat in 0..65_535) { "GIF repeat count must be between 0 and 65535" }
        val width = frames.first().width
        val height = frames.first().height
        require(width <= 65_535 && height <= 65_535) { "GIF dimensions must fit unsigned 16-bit values" }
        require(frames.all { it.width == width && it.height == height }) { "All GIF frames must use the same dimensions" }

        val palette = buildPalette(frames)
        val output = ByteSink()
        output.ascii("GIF89a")
        output.short(width)
        output.short(height)
        output.byte(0xf7) // Global 256-color table, 8-bit color resolution.
        output.byte(0)
        output.byte(0)
        writePalette(output, palette.colors)
        writeLoopExtension(output, repeat)
        frames.forEach { frame -> writeFrame(output, frame, palette.lookup) }
        output.byte(0x3b)
        return output.toByteArray()
    }

    private fun writePalette(
        output: ByteSink,
        colors: IntArray,
    ) {
        colors.forEach { color ->
            output.byte(color ushr 16)
            output.byte(color ushr 8)
            output.byte(color)
        }
    }

    private fun writeLoopExtension(
        output: ByteSink,
        repeat: Int,
    ) {
        output.bytes(0x21, 0xff, 0x0b)
        output.ascii("NETSCAPE2.0")
        output.bytes(0x03, 0x01)
        output.short(repeat)
        output.byte(0)
    }

    private fun writeFrame(
        output: ByteSink,
        frame: EngineGifFrame,
        paletteLookup: ByteArray,
    ) {
        output.bytes(0x21, 0xf9, 0x04, 0x00)
        output.short(frame.delayCentiseconds)
        output.bytes(0x00, 0x00)

        output.byte(0x2c)
        output.short(0)
        output.short(0)
        output.short(frame.width)
        output.short(frame.height)
        output.byte(0)

        val indices = ByteArray(frame.width * frame.height)
        var source = 0
        indices.indices.forEach { target ->
            val red = frame.rgba[source].toInt() and 0xff
            val green = frame.rgba[source + 1].toInt() and 0xff
            val blue = frame.rgba[source + 2].toInt() and 0xff
            indices[target] = paletteLookup[histogramKey(red, green, blue)]
            source += 4
        }

        output.byte(8)
        val compressed = lzw(indices)
        var offset = 0
        while (offset < compressed.size) {
            val length = minOf(255, compressed.size - offset)
            output.byte(length)
            output.bytes(compressed, offset, length)
            offset += length
        }
        output.byte(0)
    }

    private fun buildPalette(frames: List<EngineGifFrame>): GifPalette {
        val counts = IntArray(HISTOGRAM_SIZE)
        frames.forEach { frame ->
            var source = 0
            while (source < frame.rgba.size) {
                val red = frame.rgba[source].toInt() and 0xff
                val green = frame.rgba[source + 1].toInt() and 0xff
                val blue = frame.rgba[source + 2].toInt() and 0xff
                counts[histogramKey(red, green, blue)] += 1
                source += 4
            }
        }
        val points =
            counts.indices
                .filter { counts[it] > 0 }
                .mapTo(mutableListOf()) { index ->
                    HistogramColor(
                        index = index,
                        red = ((index ushr 10) and 0x1f) * 255 / 31,
                        green = ((index ushr 5) and 0x1f) * 255 / 31,
                        blue = (index and 0x1f) * 255 / 31,
                        count = counts[index],
                    )
                }
        val boxes = mutableListOf(ColorBox(points))
        while (boxes.size < PALETTE_SIZE) {
            var selected = -1
            var selectedScore = -1L
            boxes.forEachIndexed { index, box ->
                if (box.points.size > 1 && box.score > selectedScore) {
                    selected = index
                    selectedScore = box.score
                }
            }
            if (selected < 0) break
            val split = boxes.removeAt(selected).split()
            boxes.add(selected, split.second)
            boxes.add(selected, split.first)
        }

        val colors = IntArray(PALETTE_SIZE)
        boxes.forEachIndexed { index, box -> colors[index] = box.averageColor() }
        for (index in boxes.size until colors.size) colors[index] = colors[(boxes.size - 1).coerceAtLeast(0)]

        val lookup = ByteArray(HISTOGRAM_SIZE)
        points.forEach { point ->
            var bestIndex = 0
            var bestDistance = Int.MAX_VALUE
            boxes.indices.forEach { index ->
                val color = colors[index]
                val red = point.red - ((color ushr 16) and 0xff)
                val green = point.green - ((color ushr 8) and 0xff)
                val blue = point.blue - (color and 0xff)
                val distance = red * red + green * green + blue * blue
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestIndex = index
                }
            }
            lookup[point.index] = bestIndex.toByte()
        }
        return GifPalette(colors, lookup)
    }

    private fun histogramKey(
        red: Int,
        green: Int,
        blue: Int,
    ): Int = ((red ushr 3) shl 10) or ((green ushr 3) shl 5) or (blue ushr 3)

    private fun lzw(indices: ByteArray): ByteArray {
        val clearCode = 256
        val endCode = 257
        val bits = LsbBitSink()
        val dictionary = mutableMapOf<Int, Int>()
        var nextCode = 258
        var codeSize = 9

        fun reset() {
            dictionary.clear()
            nextCode = 258
            codeSize = 9
        }

        bits.write(clearCode, codeSize)
        if (indices.isEmpty()) {
            bits.write(endCode, codeSize)
            return bits.toByteArray()
        }

        var prefix = indices[0].toInt() and 0xff
        for (index in 1 until indices.size) {
            val suffix = indices[index].toInt() and 0xff
            val key = (prefix shl 8) or suffix
            val found = dictionary[key]
            if (found != null) {
                prefix = found
                continue
            }
            bits.write(prefix, codeSize)
            if (nextCode < 4096) {
                dictionary[key] = nextCode
                nextCode += 1
                // A GIF decoder installs the matching dictionary entry only after it
                // reads the following code. Keep the encoder one entry behind the
                // nominal power-of-two boundary so both sides change widths together.
                if (nextCode > (1 shl codeSize) && codeSize < 12) codeSize += 1
            } else {
                bits.write(clearCode, codeSize)
                reset()
            }
            prefix = suffix
        }
        bits.write(prefix, codeSize)
        bits.write(endCode, codeSize)
        return bits.toByteArray()
    }

    private class ByteSink {
        private val values = mutableListOf<Byte>()

        fun byte(value: Int) {
            values += value.toByte()
        }

        fun bytes(vararg input: Int) = input.forEach(::byte)

        fun bytes(
            input: ByteArray,
            offset: Int,
            length: Int,
        ) {
            for (index in offset until offset + length) values += input[index]
        }

        fun short(value: Int) {
            byte(value)
            byte(value ushr 8)
        }

        fun ascii(value: String) = value.forEach { byte(it.code) }

        fun toByteArray(): ByteArray = values.toByteArray()
    }

    private class LsbBitSink {
        private val bytes = mutableListOf<Byte>()
        private var pending = 0
        private var pendingBits = 0

        fun write(
            value: Int,
            width: Int,
        ) {
            pending = pending or (value shl pendingBits)
            pendingBits += width
            while (pendingBits >= 8) {
                bytes += pending.toByte()
                pending = pending ushr 8
                pendingBits -= 8
            }
        }

        fun toByteArray(): ByteArray {
            if (pendingBits > 0) {
                bytes += pending.toByte()
                pending = 0
                pendingBits = 0
            }
            return bytes.toByteArray()
        }
    }

    private data class GifPalette(
        val colors: IntArray,
        val lookup: ByteArray,
    )

    private data class HistogramColor(
        val index: Int,
        val red: Int,
        val green: Int,
        val blue: Int,
        val count: Int,
    )

    private class ColorBox(
        val points: MutableList<HistogramColor>,
    ) {
        private val redRange = points.maxOf { it.red } - points.minOf { it.red }
        private val greenRange = points.maxOf { it.green } - points.minOf { it.green }
        private val blueRange = points.maxOf { it.blue } - points.minOf { it.blue }
        private val weight = points.sumOf { it.count.toLong() }
        private val channel =
            when (maxOf(redRange, greenRange, blueRange)) {
                redRange -> 0
                greenRange -> 1
                else -> 2
            }
        val score: Long = maxOf(redRange, greenRange, blueRange).toLong() * weight

        fun split(): Pair<ColorBox, ColorBox> {
            points.sortWith { left, right ->
                val leftValue = left.channelValue(channel)
                val rightValue = right.channelValue(channel)
                if (leftValue == rightValue) left.index - right.index else leftValue - rightValue
            }
            val halfway = (weight + 1L) / 2L
            var cumulative = 0L
            var splitIndex = 1
            for (index in 0 until points.lastIndex) {
                cumulative += points[index].count
                splitIndex = index + 1
                if (cumulative >= halfway) break
            }
            return ColorBox(points.subList(0, splitIndex).toMutableList()) to
                ColorBox(points.subList(splitIndex, points.size).toMutableList())
        }

        fun averageColor(): Int {
            val red = (points.sumOf { it.red.toLong() * it.count } / weight).toInt()
            val green = (points.sumOf { it.green.toLong() * it.count } / weight).toInt()
            val blue = (points.sumOf { it.blue.toLong() * it.count } / weight).toInt()
            return (red shl 16) or (green shl 8) or blue
        }

        private fun HistogramColor.channelValue(channel: Int): Int =
            when (channel) {
                0 -> red
                1 -> green
                else -> blue
            }
    }

    private const val HISTOGRAM_SIZE = 32 * 32 * 32
    private const val PALETTE_SIZE = 256
}
