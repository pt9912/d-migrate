package dev.dmigrate.format.verify

import dev.dmigrate.verify.ValueCanonicalizationException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * LN-009 / ADR 0030: dialekt-neutrale Geometrie-Kanonik über WKB.
 *
 * Normalisiert PostGIS-EWKB (Hex, via `PGobject.getValue()`), MySQL-Geometrie
 * (4-Byte-LE-SRID-Präfix + WKB) und rohes WKB auf eine kanonische Form:
 * `SRID(4 LE) || WKB(Byte-Order=LE, ISO-Typkodierung, ohne eingebettete SRID)`.
 * Der Parser interpretiert sowohl EWKB-High-Bit-Flags (`0x20000000` SRID,
 * `0x40000000` M, `0x80000000` Z) als auch die ISO-1000er-Offset-Kodierung und
 * schreibt beide auf dieselbe ISO-Form zurück — so kollidieren PostGIS- und
 * MySQL-Repräsentationen derselben Geometrie.
 *
 * **Grenze (Phase C):** SpatiaLite-BLOBs (GAIA-Format, kein Standard-WKB) werden
 * mit [ValueCanonicalizationException] abgelehnt → der Verifier schließt die
 * Spalte mit einem W-Code aus (kein stiller Pass).
 */
class CanonicalGeometry {

    fun canonicalize(value: Any): ByteArray = when (value) {
        is ByteArray -> normalize(value)
        is String -> normalize(hexToBytes(value))
        else -> {
            val pg = if (value.javaClass.name == "org.postgresql.util.PGobject") {
                runCatching { value.javaClass.getMethod("getValue").invoke(value) as? String }.getOrNull()
            } else {
                null
            }
            if (pg != null) normalize(hexToBytes(pg)) else throw cannot(value)
        }
    }

    private fun normalize(raw: ByteArray): ByteArray {
        // Zwei Formen: rohes WKB/EWKB (ab Offset 0) vs. MySQL (4-Byte-LE-SRID-Präfix
        // + WKB, ab Offset 4). Beide sind byte-mehrdeutig (MySQL-SRID=0 beginnt mit
        // 0x00 wie eine BE-Byte-Order); zuverlässig disambiguiert über **exakten
        // Byte-Verbrauch** — die korrekte Deutung konsumiert das Array vollständig.
        tryNormalize(raw, wkbOffset = 0, prefixSrid = null)?.let { return it }
        if (raw.size >= 9) {
            tryNormalize(raw, wkbOffset = 4, prefixSrid = le32(raw, 0))?.let { return it }
        }
        throw ValueCanonicalizationException("Unerkanntes Geometrie-Format (kein Standard-WKB/EWKB; SpatiaLite-BLOB?)")
    }

    /** Parst ab [wkbOffset]; liefert nur bei vollständigem, exaktem Byte-Verbrauch ein Ergebnis. */
    private fun tryNormalize(raw: ByteArray, wkbOffset: Int, prefixSrid: Int?): ByteArray? {
        if (wkbOffset >= raw.size || !isByteOrder(raw[wkbOffset])) return null
        val cursor = Cursor(raw, wkbOffset)
        val out = ByteArrayOutputStream()
        val embeddedSrid = try {
            writeGeometry(cursor, out)
        } catch (_: RuntimeException) {
            return null
        }
        if (!cursor.atEnd()) return null
        val effectiveSrid = embeddedSrid ?: prefixSrid ?: 0
        val result = ByteArrayOutputStream()
        result.write(int32Le(effectiveSrid))
        result.write(out.toByteArray())
        return result.toByteArray()
    }

    /**
     * Liest eine (ggf. verschachtelte) WKB-Geometrie und schreibt sie kanonisch
     * (LE, ISO-Typ). Gibt eine eingebettete EWKB-SRID zurück (oder null).
     */
    private fun writeGeometry(c: Cursor, out: ByteArrayOutputStream): Int? {
        val order = if (c.byte().toInt() == 0) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val t = readType(c, order)
        val dims = 2 + (if (t.hasZ) 1 else 0) + (if (t.hasM) 1 else 0)
        // Kanonisch: LE, ISO-Typ (base + 1000*Z + 2000*M), keine eingebettete SRID.
        out.write(1)
        out.write(int32Le(t.base + (if (t.hasZ) 1000 else 0) + (if (t.hasM) 2000 else 0)))

        when (t.base) {
            1 -> copyPoints(c, out, order, 1, dims)                       // Point
            2 -> { val n = c.uint32(order); out.write(int32Le(n)); copyPoints(c, out, order, n, dims) } // LineString
            3 -> {                                                         // Polygon
                val rings = c.uint32(order); out.write(int32Le(rings))
                repeat(rings) { val n = c.uint32(order); out.write(int32Le(n)); copyPoints(c, out, order, n, dims) }
            }
            4, 5, 6, 7 -> {                                               // Multi* / GeometryCollection
                val n = c.uint32(order); out.write(int32Le(n))
                repeat(n) { writeGeometry(c, out) }
            }
            else -> throw ValueCanonicalizationException("Unbekannter WKB-Geometrietyp: ${t.base}")
        }
        return t.srid
    }

    /** Geparster WKB/EWKB-Typcode. */
    private data class WkbType(val base: Int, val hasZ: Boolean, val hasM: Boolean, val srid: Int?)

    /**
     * Dekodiert den Typcode: EWKB-High-Bit-Flags (`0x80000000` Z, `0x40000000` M,
     * `0x20000000` SRID) ODER ISO-1000er-Offset (`base + 1000*Z + 2000*M`). Liest
     * eine eingebettete EWKB-SRID direkt nach dem Typ (vor dem Geometriekörper).
     */
    private fun readType(c: Cursor, order: ByteOrder): WkbType {
        val raw = c.uint32(order)
        val ewkbFlags = raw and 0xE0000000.toInt()
        if (ewkbFlags != 0) {
            val srid = if (raw and 0x20000000.toInt() != 0) c.uint32(order) else null
            return WkbType(
                base = raw and 0xFF,
                hasZ = raw and 0x80000000.toInt() != 0,
                hasM = raw and 0x40000000.toInt() != 0,
                srid = srid,
            )
        }
        val dim = raw / 1000
        return WkbType(base = raw % 1000, hasZ = dim == 1 || dim == 3, hasM = dim == 2 || dim == 3, srid = null)
    }

    private fun copyPoints(c: Cursor, out: ByteArrayOutputStream, order: ByteOrder, count: Int, dims: Int) {
        repeat(count * dims) { out.write(doubleLe(c.double(order))) }
    }

    private fun isByteOrder(b: Byte): Boolean = b.toInt() == 0 || b.toInt() == 1

    private fun le32(b: ByteArray, off: Int): Int =
        ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun int32Le(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun doubleLe(v: Double): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(v).array()

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        if (clean.length % 2 != 0) throw ValueCanonicalizationException("Ungültiger Geometrie-Hex-String")
        return ByteArray(clean.length / 2) {
            ((hexDigit(clean[it * 2]) shl 4) or hexDigit(clean[it * 2 + 1])).toByte()
        }
    }

    private fun hexDigit(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> throw ValueCanonicalizationException("Ungültiges Hex-Zeichen: $ch")
    }

    private fun cannot(value: Any): ValueCanonicalizationException =
        ValueCanonicalizationException("Wert der Klasse ${value.javaClass.name} nicht als Geometrie kanonisierbar")

    /** Lese-Cursor über ein WKB-Byte-Array. */
    private class Cursor(private val bytes: ByteArray, start: Int) {
        private var pos = start

        fun atEnd(): Boolean = pos == bytes.size

        fun byte(): Byte {
            require(pos < bytes.size) { "WKB zu kurz" }
            return bytes[pos++]
        }

        fun uint32(order: ByteOrder): Int {
            check(pos + 4 <= bytes.size) { "WKB zu kurz (uint32)" }
            val v = ByteBuffer.wrap(bytes, pos, 4).order(order).int
            pos += 4
            return v
        }

        fun double(order: ByteOrder): Double {
            check(pos + 8 <= bytes.size) { "WKB zu kurz (double)" }
            val v = ByteBuffer.wrap(bytes, pos, 8).order(order).double
            pos += 8
            return v
        }
    }
}
