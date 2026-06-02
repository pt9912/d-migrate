package dev.dmigrate.core.util

import java.security.MessageDigest

/**
 * Lookup-Table-basierte Hex-Konvertierung. Schneller als
 * `joinToString { "%02x".format(it) }` (kein Formatter-Allokat pro Byte)
 * und identisch in der Ausgabe (lowercase, ohne Trennzeichen).
 *
 * Vor diesem Helper gab es im Repo elf Fundstellen mit vier
 * verschiedenen Schreibweisen. Konsolidiert via Code-Review zu AP 6.3
 * (0.9.6 Phase A). Details: `docs/planning/done/refactoring-sha256Hex.md`.
 */
private val HEX_CHARS = "0123456789abcdef".toCharArray()

/** Lowercase Hex-Repraesentation des [ByteArray]. Keine Trennzeichen. */
fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        sb.append(HEX_CHARS[(b.toInt() ushr 4) and 0xF])
        sb.append(HEX_CHARS[b.toInt() and 0xF])
    }
    return sb.toString()
}

/** SHA-256-Digest des [ByteArray] als Lowercase-Hex. */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

/**
 * SHA-256-Digest des [String] (UTF-8-kodiert) als Lowercase-Hex.
 * Convenience fuer die haeufigen `digest(text.toByteArray())`-Aufrufe.
 */
fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))
