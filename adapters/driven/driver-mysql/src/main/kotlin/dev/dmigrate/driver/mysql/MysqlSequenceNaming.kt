package dev.dmigrate.driver.mysql

import java.security.MessageDigest
import java.util.Locale

/**
 * Canonical naming for MySQL sequence emulation support objects (0.9.3).
 *
 * Trigger name format: `dmg_seq_<table16>_<column16>_<hash10>_bi`
 * - table16/column16: first 16 chars of normalized name
 * - hash10: first 10 lowercase hex chars of SHA-256 over
 *   `<tableNorm>\u0000<columnNorm>` (full normalized names, not truncated)
 *
 * This utility is intentionally a standalone object so it can be
 * reused by Reverse-Engineering in 0.9.4.
 */
object MysqlSequenceNaming {

    const val SUPPORT_TABLE = "dmg_sequences"
    const val NEXTVAL_ROUTINE = "dmg_nextval"
    const val SETVAL_ROUTINE = "dmg_setval"
    private const val TRIGGER_PREFIX = "dmg_seq_"
    private const val TRIGGER_SUFFIX = "_bi"
    private const val NAME_SEGMENT_LENGTH = 16
    private const val HASH_LENGTH = 10

    /**
     * Normalizes a SQL identifier for canonical naming:
     * ASCII-lowercase, non-alphanumeric characters except `_` removed.
     */
    fun normalize(name: String): String =
        name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '_' }

    /**
     * Computes the first 10 lowercase hex characters of SHA-256
     * over `<tableNorm>\u0000<columnNorm>`.
     */
    fun hash10(tableNorm: String, columnNorm: String): String {
        val input = "$tableNorm\u0000$columnNorm"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_LENGTH)
    }

    /**
     * Builds the canonical BEFORE INSERT trigger name for a
     * sequence-backed column.
     *
     * Result is always <= 55 characters (well within MySQL's 64-char limit):
     * `dmg_seq_` (8) + table16 (16) + `_` (1) + column16 (16) + `_` (1) +
     * hash10 (10) + `_bi` (3) = 55
     */
    fun triggerName(tableName: String, columnName: String): String {
        val tableNorm = normalize(tableName)
        val colNorm = normalize(columnName)
        val table16 = tableNorm.take(NAME_SEGMENT_LENGTH)
        val col16 = colNorm.take(NAME_SEGMENT_LENGTH)
        val h = hash10(tableNorm, colNorm)
        return "${TRIGGER_PREFIX}${table16}_${col16}_${h}${TRIGGER_SUFFIX}"
    }

    /** All reserved support object names (for collision detection). */
    val reservedNames: Set<String> = setOf(SUPPORT_TABLE, NEXTVAL_ROUTINE, SETVAL_ROUTINE)

    /** Checks if a trigger name matches the canonical support trigger pattern. */
    fun isSupportTriggerName(name: String): Boolean =
        name.startsWith(TRIGGER_PREFIX) && name.endsWith(TRIGGER_SUFFIX)
}
