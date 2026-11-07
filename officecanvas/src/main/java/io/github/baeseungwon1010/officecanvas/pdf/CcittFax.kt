package io.github.baeseungwon1010.officecanvas.pdf

/**
 * CCITT fax data (7.4.6; ITU-T T.4 and T.6), the way scanners and fax software
 * store black and white pages: Group 3 one-dimensional (K = 0), mixed (K > 0)
 * and Group 4 two-dimensional (K < 0). Decoded to rows of one bit a pixel,
 * packed as the PDF's own samples would be: 0 is black unless BlackIs1.
 */
internal object CcittFax {

    /** The rows of a [columns] wide picture of (at most, if [rows] > 0) [rows] rows; null if the data isn't fax data. */
    fun decode(data: ByteArray, k: Int, columns: Int, rows: Int, blackIs1: Boolean, byteAlign: Boolean): ByteArray? {
        if (columns <= 0 || columns > 1 shl 16) return null
        val rowBytes = (columns + 7) / 8
        // Without a row count, as many as fit 16 MB (one bit of Group 4 data can code a whole row).
        val maxRows = if (rows > 0) rows else ((16L shl 20) / rowBytes).coerceAtMost(data.size.toLong() * 8).coerceAtMost(1L shl 16).toInt()
        val out = java.io.ByteArrayOutputStream()
        val bits = Bits(data)
        // Changing elements: where a run of the other color starts. The reference line starts all white.
        var ref = intArrayOf(columns, columns, columns)
        val cur = IntArray(columns + 2)
        var decoded = 0
        while (decoded < maxRows && !bits.done) {
            if (byteAlign && k < 0) bits.align()
            // An end of line (after any fill), and for mixed coding the tag that says how this line is coded.
            if (k >= 0) {
                bits.skipEol(byteAlign)
                if (bits.done) break
            }
            val twoD = when {
                k < 0 -> true
                k > 0 -> bits.bit() == 0
                else -> false
            }
            val n = (if (twoD) line2d(bits, ref, cur, columns) else line1d(bits, cur, columns)) ?: break
            val row = ByteArray(rowBytes)
            // Black runs: from cur[0] to cur[1], cur[2] to cur[3], ...
            var i = 0
            while (i < n) {
                val from = cur[i].coerceIn(0, columns); val to = (if (i + 1 < n) cur[i + 1] else columns).coerceIn(0, columns)
                for (x in from until to) row[x shr 3] = (row[x shr 3].toInt() or (0x80 ushr (x and 7))).toByte()
                i += 2
            }
            // As stored: 1 is white unless BlackIs1.
            if (!blackIs1) for (b in row.indices) row[b] = row[b].toInt().inv().toByte()
            out.write(row)
            decoded++
            ref = cur.copyOf(n + 3).also { it[n] = columns; it[n + 1] = columns; it[n + 2] = columns }
        }
        if (decoded == 0) return null
        // Rows the data ran out before: white.
        if (rows > 0) repeat(rows - decoded) { out.write(ByteArray(rowBytes) { if (blackIs1) 0 else -1 }) }
        return out.toByteArray()
    }

    /** One line of runs, white first; the number of changing elements put in [cur], or null at the end of the data. */
    private fun line1d(bits: Bits, cur: IntArray, columns: Int): Int? {
        var x = 0; var n = 0; var white = true
        while (x < columns) {
            val run = run(bits, white) ?: return if (x == 0) null else n
            x += run
            if (x < columns) { if (n >= cur.size) return null; cur[n++] = x }
            white = !white
        }
        return n
    }

    /**
     * One line coded against [ref] (its changing elements, then [columns] at least twice):
     * elements at even indexes start a black run, at odd ones a white run.
     */
    private fun line2d(bits: Bits, ref: IntArray, cur: IntArray, columns: Int): Int? {
        var a0 = -1; var white = true; var n = 0; var r = 0
        while (a0 < columns) {
            val mode = mode(bits) ?: return if (n == 0 && a0 < 0) null else n
            // b1: the first changing element right of a0 that starts a run of the other color; b2, the next.
            while (r > 0 && ref[r - 1] > a0) r--
            while (r < ref.size - 1 && (ref[r] <= a0 || (r and 1) != (if (white) 0 else 1))) r++
            val b1 = ref[r]; val b2 = ref[minOf(r + 1, ref.size - 1)]
            when (mode) {
                PASS -> a0 = b2
                HORIZONTAL -> {
                    val r1 = run(bits, white) ?: return null
                    val r2 = run(bits, !white) ?: return null
                    val a1 = minOf(maxOf(a0, 0) + r1, columns); val a2 = minOf(a1 + r2, columns)
                    if (n + 2 > cur.size) return null
                    cur[n++] = a1; cur[n++] = a2
                    a0 = a2
                }
                else -> {
                    val a1 = (b1 + mode - V0).coerceIn(0, columns)
                    if (n + 1 > cur.size) return null
                    cur[n++] = a1
                    a0 = a1; white = !white
                }
            }
        }
        while (n > 0 && cur[n - 1] >= columns) n--
        return n
    }

    private const val PASS = 100
    private const val HORIZONTAL = 101
    private const val V0 = 10 // V0 + d for a vertical offset d in -3..3

    private val MODES: Map<Int, Int> = buildMap {
        fun put(code: String, v: Int) = put(key(code), v)
        put("0001", PASS); put("001", HORIZONTAL)
        put("1", V0); put("011", V0 + 1); put("000011", V0 + 2); put("0000011", V0 + 3)
        put("010", V0 - 1); put("000010", V0 - 2); put("0000010", V0 - 3)
    }

    private fun mode(bits: Bits): Int? {
        var code = 0
        for (len in 1..7) {
            val b = bits.bit(); if (b < 0) return null
            code = (code shl 1) or b
            MODES[(len shl 16) or code]?.let { return it }
        }
        return null
    }

    /** A run of [white] or black: makeup codes then a terminating one. */
    private fun run(bits: Bits, white: Boolean): Int? {
        var total = 0
        while (true) {
            val v = code(bits, if (white) WHITE else BLACK) ?: return null
            total += v
            if (v < 64) return total
            if (total > 1 shl 16) return null
        }
    }

    private fun code(bits: Bits, table: Map<Int, Int>): Int? {
        var code = 0
        for (len in 1..13) {
            val b = bits.bit(); if (b < 0) return null
            code = (code shl 1) or b
            table[(len shl 16) or code]?.let { return it }
        }
        return null
    }

    private fun key(code: String) = (code.length shl 16) or code.toInt(2)

    private fun table(terminating: String, makeup: String): Map<Int, Int> = buildMap {
        terminating.trim().split(Regex("\\s+")).forEachIndexed { i, c -> put(key(c), i) }
        makeup.trim().split(Regex("\\s+")).forEachIndexed { i, c -> put(key(c), 64 * (i + 1)) }
        EXTENDED.trim().split(Regex("\\s+")).forEachIndexed { i, c -> put(key(c), 1792 + 64 * i) }
    }

    private const val EXTENDED = "00000001000 00000001100 00000001101 000000010010 000000010011 000000010100 000000010101 " +
        "000000010110 000000010111 000000011100 000000011101 000000011110 000000011111"

    private val WHITE = table(
        "00110101 000111 0111 1000 1011 1100 1110 1111 10011 10100 00111 01000 001000 000011 110100 110101 " +
            "101010 101011 0100111 0001100 0001000 0010111 0000011 0000100 0101000 0101011 0010011 0100100 0011000 00000010 00000011 00011010 " +
            "00011011 00010010 00010011 00010100 00010101 00010110 00010111 00101000 00101001 00101010 00101011 00101100 00101101 00000100 00000101 00001010 " +
            "00001011 01010010 01010011 01010100 01010101 00100100 00100101 01011000 01011001 01011010 01011011 01001010 01001011 00110010 00110011 00110100",
        "11011 10010 010111 0110111 00110110 00110111 01100100 01100101 01101000 01100111 011001100 011001101 011010010 011010011 011010100 011010101 " +
            "011010110 011010111 011011000 011011001 011011010 011011011 010011000 010011001 010011010 011000 010011011"
    )

    private val BLACK = table(
        "0000110111 010 11 10 011 0011 0010 00011 000101 000100 0000100 0000101 0000111 00000100 00000111 000011000 " +
            "0000010111 0000011000 0000001000 00001100111 00001101000 00001101100 00000110111 00000101000 00000010111 00000011000 000011001010 000011001011 000011001100 000011001101 000001101000 000001101001 " +
            "000001101010 000001101011 000011010010 000011010011 000011010100 000011010101 000011010110 000011010111 000001101100 000001101101 000011011010 000011011011 000001010100 000001010101 000001010110 000001010111 " +
            "000001100100 000001100101 000001010010 000001010011 000000100100 000000110111 000000111000 000000100111 000000101000 000001011000 000001011001 000000101011 000000101100 000001011010 000001100110 000001100111",
        "0000001111 000011001000 000011001001 000001011011 000000110011 000000110100 000000110101 0000001101100 0000001101101 0000001001010 0000001001011 0000001001100 0000001001101 0000001110010 0000001110011 0000001110100 " +
            "0000001110101 0000001110110 0000001110111 0000001010010 0000001010011 0000001010100 0000001010101 0000001011010 0000001011011 0000001100100 0000001100101"
    )

    private class Bits(private val data: ByteArray) {
        private var pos = 0L
        val done get() = pos >= data.size * 8L

        fun bit(): Int {
            if (pos >= data.size * 8L) return -1
            val b = (data[(pos shr 3).toInt()].toInt() shr (7 - (pos and 7).toInt())) and 1
            pos++
            return b
        }

        fun align() { pos = (pos + 7) and 7L.inv() }

        /** Skips an end of line (eleven or more zeros, then a one) if one is next; else, with [byteAlign], to the next byte. */
        fun skipEol(byteAlign: Boolean) {
            var zeros = 0
            while (zeros < 64 && pos + zeros < data.size * 8L && bitAt(pos + zeros) == 0) zeros++
            if (zeros >= 11 && pos + zeros < data.size * 8L) pos += zeros + 1
            else if (zeros >= 11) pos = data.size * 8L
            else if (byteAlign) align()
        }

        private fun bitAt(p: Long) = (data[(p shr 3).toInt()].toInt() shr (7 - (p and 7).toInt())) and 1
    }
}
