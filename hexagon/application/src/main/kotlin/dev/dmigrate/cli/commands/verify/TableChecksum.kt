package dev.dmigrate.cli.commands.verify

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest

/**
 * LN-009 / ADR 0030: reihenfolge-unabhängige Tabellen-Prüfsumme.
 *
 * Pro Zeile werden die (bereits kanonisierten) Spaltenwerte **längen-gerahmt** in
 * ein SHA-256 gehasht; NULL trennt das Framing strukturell vom Leerstring. Die
 * Tabellen-Prüfsumme ist die **additive Summe der 256-bit-Zeilendigests mod
 * 2²⁵⁶** — dadurch reihenfolge-unabhängig (kein `ORDER BY` nötig) und korrekt für
 * Duplikate/Multiset (anders als XOR, wo identische Zeilenpaare sich auslöschen).
 *
 * Vertrag: Schutz gegen **versehentliche** Korruption/Datenverlust im Transfer,
 * nicht gegen adversariell konstruierte Kollisionen.
 */
class TableChecksum {
    private var accumulator: BigInteger = BigInteger.ZERO
    private var rows: Long = 0

    /**
     * Fügt eine Zeile hinzu. `null`-Einträge sind SQL-NULL-Spalten; Nicht-Null-
     * Einträge sind die kanonischen Bytes der Spalte (siehe `ValueCanonicalizer`).
     * Die Reihenfolge der Spalten MUSS auf Quell- und Zielseite identisch sein
     * (der Aufrufer ordnet nach Spaltenname).
     */
    fun addRow(canonicalColumns: List<ByteArray?>) {
        val framed = frame(canonicalColumns)
        val rowDigest = sha256(framed)
        accumulator = accumulator.add(BigInteger(1, rowDigest)).mod(MODULUS)
        rows++
    }

    /** Anzahl bisher aufgenommener Zeilen (für den Row-Count-Vorabcheck). */
    fun rowCount(): Long = rows

    /** 64-stelliger, links-null-aufgefüllter Hex-Digest der Tabellen-Prüfsumme. */
    fun digestHex(): String {
        val hex = accumulator.toString(16)
        return hex.padStart(HEX_LENGTH, '0')
    }

    private fun frame(columns: List<ByteArray?>): ByteArray {
        val out = ByteArrayOutputStream()
        putVarInt(out, columns.size.toLong())
        for (column in columns) {
            if (column == null) {
                out.write(0)
            } else {
                out.write(1)
                putVarInt(out, column.size.toLong())
                out.write(column)
            }
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun putVarInt(out: ByteArrayOutputStream, valueIn: Long) {
        var value = valueIn
        while (true) {
            val b = (value and 0x7F).toInt()
            value = value ushr 7
            if (value == 0L) {
                out.write(b)
                return
            }
            out.write(b or 0x80)
        }
    }

    companion object {
        /** 2²⁵⁶ — der Ringmodulus des additiven Kombinators. */
        val MODULUS: BigInteger = BigInteger.ONE.shiftLeft(256)
        private const val HEX_LENGTH = 64
    }
}
