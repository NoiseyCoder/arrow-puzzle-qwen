package com.arrowescape.game

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.ceil

/**
 * Binary level-pack codec (the app ships ONLY the decoder path in production; [encode] exists
 * so the offline builder tool and unit tests can share exactly one format definition).
 *
 * ### Container layout (all integers big-endian, gzip-compressed as a whole)
 * ```
 * payload := header || levelRecords*
 * header   := magic(4B "ARWP") | version(u8=1) | firstLevel(u16) | count(u16)
 * per level: width(u8) height(u8) arrowCount(u16)
 *            per arrow: startX(u8) startY(u8) length(u16>=2) steps(2 bits each, MSB-first, padded)
 * file     := header32 || gzip(payload) || crc32(u32)
 * header32 := magic(4B) | version(u8) | reserved(3B) | payloadCrc32(u32)
 * ```
 * The trailing CRC32 covers the gzip payload bytes (guards against file corruption / bad
 * downloads). It is deliberately distinct from the golden *content* hash (see
 * `GoldenChecksumsTest`), which hashes the decoded arrow list and detects generator-output
 * changes. A failing CRC means "corrupt file"; a failing content hash means "builder output
 * changed" — two separate diagnostics.
 *
 * ### Step encoding
 * Each step after the start cell is a [Direction] ordinal packed into 2 bits, written
 * most-significant-bit first into successive bytes; the last byte is zero-padded. This makes
 * long snaking arrows extremely compact (~0.25 bytes per cell).
 */
object LevelPack {

    const val MAGIC = 0x41525750 // "ARWP"
    const val VERSION = 1
    const val LEVELS_PER_PACK = 500

    class PackHeader(val version: Int, val firstLevel: Int, val count: Int)

    /** Thrown for corrupt data (CRC mismatch, truncated stream, illegal arrow geometry). */
    class PackCorruptException(message: String) : IllegalStateException(message)

    // ---------------------------------------------------------------- decoding

    /** Decodes a complete pack file (outer header + gzip payload + CRC32 trailer). */
    fun decodeFile(bytes: ByteArray): List<Level> {
        if (bytes.size < 9) throw PackCorruptException("file too short")
        var p = 0
        fun u8(): Int = bytes[p++].toInt() and 0xFF
        requireMagic(peekInt(bytes, p))
        p += 4
        val version = u8()
        if (version != VERSION) throw PackCorruptException("unsupported pack version $version")
        p += 3 // reserved
        val storedCrc = peekInt(bytes, p); p += 4
        val gz = bytes.copyOfRange(p, bytes.size - 4)
        val actualCrc = crc32(gz)
        if (actualCrc != storedCrc) {
            throw PackCorruptException("CRC32 mismatch: file corrupted (stored=${hex(storedCrc)} actual=${hex(actualCrc)})")
        }
        return decodePayload(gunzip(gz))
    }

    /** Decodes the uncompressed payload (header + records). Used by tests and the builder's self-check. */
    fun decodePayload(payload: ByteArray): List<Level> {
        val r = BitReader(payload)
        requireMagic(r.readInt())
        val version = r.readUnsignedByte()
        if (version != VERSION) throw PackCorruptException("unsupported payload version $version")
        val firstLevel = r.readUnsignedShort()
        val count = r.readUnsignedShort()
        if (count !in 1..LEVELS_PER_PACK) throw PackCorruptException("bad count $count")
        val levels = ArrayList<Level>(count)
        for (i in 0 until count) {
            levels += decodeLevel(r, firstLevel + i)
        }
        if (!r.atEnd()) throw PackCorruptException("trailing garbage in payload")
        return levels
    }

    private fun decodeLevel(r: BitReader, number: Int): Level {
        val w = r.readUnsignedByte()
        val h = r.readUnsignedByte()
        if (w !in 2..64 || h !in 2..64) throw PackCorruptException("level $number: bad size ${w}x$h")
        val arrowCount = r.readUnsignedShort()
        if (arrowCount == 0 || arrowCount > w * h) throw PackCorruptException("level $number: bad arrow count $arrowCount")
        val occupied = BooleanArray(w * h)
        val arrows = ArrayList<Arrow>(arrowCount)
        for (id in 0 until arrowCount) {
            val sx = r.readUnsignedByte()
            val sy = r.readUnsignedByte()
            val len = r.readUnsignedShort()
            // length >= 2 is a hard format rule: the head direction is derived from the LAST
            // step, so a single-cell arrow could never have a defined direction. Encoders must
            // reject such arrows at generation time; seeing len < 2 here means corruption.
            if (len < 2 || len > w * h) throw PackCorruptException("level $number arrow $id: illegal length $len")
            if (sx !in 0 until w || sy !in 0 until h) throw PackCorruptException("level $number arrow $id: start off-grid")
            val cells = ArrayList<Point>(len)
            cells.add(Point(sx, sy))
            if (occupied[sy * w + sx]) throw PackCorruptException("level $number arrow $id: overlap at start")
            occupied[sy * w + sx] = true
            var x = sx; var y = sy
            for (s in 0 until len - 1) {
                val d = Direction.fromOrdinal(r.readBits(2))
                x += d.dx; y += d.dy
                if (x !in 0 until w || y !in 0 until h) throw PackCorruptException("level $number arrow $id: step leaves grid")
                val idx = y * w + x
                if (occupied[idx]) throw PackCorruptException("level $number arrow $id: overlap mid-path")
                occupied[idx] = true
                cells.add(Point(x, y))
            }
            arrows.add(Arrow(id, cells))
        }
        return Level(number, w, h, arrows)
    }

    private fun requireMagic(v: Int) {
        if (v != MAGIC) throw PackCorruptException("bad magic ${hex(v)}, expected ARWP pack")
    }

    /** Big-endian 32-bit read at [offset] without advancing any cursor. */
    private fun peekInt(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 24) or
            ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or
            (b[offset + 3].toInt() and 0xFF)

    // ---------------------------------------------------------------- encoding

    /** Encodes levels into the gzip container format understood by [decodeFile]. */
    fun encodeFile(firstLevel: Int, levels: List<Level>): ByteArray {
        val payload = encodePayload(firstLevel, levels)
        val gz = gzip(payload)
        val out = ByteArrayOutputStream(payload.size + 32)
        out.write(intBytes(MAGIC))
        out.write(VERSION)
        out.write(ByteArray(3))
        out.write(intBytes(crc32(gz)))
        out.write(gz)
        return out.toByteArray()
    }

    fun encodePayload(firstLevel: Int, levels: List<Level>): ByteArray {
        val bitLen = 4 + 1 + 2 + levels.sumOf { 3 + ceil(it.arrows.sumOf { a -> a.cells.size - 1 } * 2.0 / 8.0) + it.totalArrows * 5 }
        val w = BitWriter(bitLen + 64)
        w.writeInt(MAGIC)
        w.writeByte(VERSION)
        w.writeUnsignedShort(firstLevel)
        w.writeUnsignedShort(levels.size)
        for (lv in levels) {
            w.writeByte(lv.width)
            w.writeByte(lv.height)
            w.writeUnsignedShort(lv.totalArrows)
            for (a in lv.arrows) {
                require(a.cells.size >= 2) { "length-1 arrows are a generation-time reject, never encoded" }
                w.writeByte(a.tail.x)
                w.writeByte(a.tail.y)
                w.writeUnsignedShort(a.cells.size)
                for (i in 1 until a.cells.size) {
                    val step = a.cells[i] - a.cells[i - 1]
                    val d = when {
                        step.x == 1 -> Direction.RIGHT
                        step.x == -1 -> Direction.LEFT
                        step.y == 1 -> Direction.DOWN
                        step.y == -1 -> Direction.UP
                        else -> throw IllegalArgumentException("non-adjacent cells in arrow ${a.id}")
                    }
                    w.writeBits(d.ordinal, 2)
                }
            }
        }
        return w.toByteArray()
    }

    // ---------------------------------------------------------------- bit IO + checksum

    private class BitReader(private val b: ByteArray) {
        private var pos = 0 // bit position
        fun atEnd(): Boolean = pos >= b.size * 8
        fun readBits(n: Int): Int {
            if (pos + n > b.size * 8) throw PackCorruptException("unexpected end of stream")
            var v = 0
            for (i in 0 until n) {
                val byte = b[pos ushr 3]
                val bit = (byte shr (7 - (pos and 7))) and 1
                v = (v shl 1) or bit
                pos++
            }
            return v
        }
        fun readUnsignedByte(): Int = readBits(8)
        fun readUnsignedShort(): Int = readBits(16)
        fun readInt(): Int {
            var v = 0
            for (i in 0 until 4) v = (v shl 8) or readUnsignedByte()
            return v
        }
    }

    private class BitWriter(capacityBits: Int) {
        private val b = ByteArray(ceil(capacityBits / 8.0).toInt())
        private var pos = 0
        fun writeBits(value: Int, n: Int) {
            for (i in n - 1 downTo 0) {
                val bit = (value shr i) and 1
                if (bit == 1) b[pos ushr 3] = (b[pos ushr 3] or (1 shl (7 - (pos and 7)))).toByte()
                pos++
            }
        }
        fun writeByte(v: Int) = writeBits(v and 0xFF, 8)
        fun writeUnsignedShort(v: Int) = writeBits(v and 0xFFFF, 16)
        fun writeInt(v: Int) { for (i in 3 downTo 0) writeByte((v ushr (i * 8)) and 0xFF) }
        fun toByteArray(): ByteArray = b.copyOf(ceil(pos / 8.0).toInt())
    }

    /** CRC32 (IEEE) over bytes, returned as a signed Int matching java.util.zip.CRC32.value(). */
    fun crc32(data: ByteArray): Int {
        val c = java.util.zip.CRC32()
        c.update(data)
        return c.value.toInt()
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 2 + 32)
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray {
        ByteArrayInputStream(data).use { bin ->
            GZIPInputStream(bin).use { gz ->
                val out = ByteArrayOutputStream(data.size * 2)
                val buf = ByteArray(8192)
                while (true) {
                    val n = gz.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            }
        }
    }

    private fun intBytes(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    private fun hex(v: Int) = String.format("%08X", v)

    /** Golden content hash: CRC32 over the DECODED level content (size + ordered arrow paths).
     *  Distinct from the pack CRC32 — see class KDoc. */
    fun contentHash(level: Level): String {
        val data = ByteArrayOutputStream()
        data.write(level.width and 0xFF)
        data.write(level.height and 0xFF)
        for (a in level.arrows) {
            data.write(a.cells.size and 0xFF)
            for (p in a.cells) {
                data.write(p.x and 0xFF)
                data.write(p.y and 0xFF)
            }
        }
        return hex(crc32(data.toByteArray()))
    }
}
