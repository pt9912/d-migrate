package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.MysqlSequenceSupportNaming

/**
 * Canonical naming for MySQL sequence emulation support objects (0.9.3).
 *
 * Trigger name format: `dmg_seq_<table16>_<column16>_<hash10>_bi`
 * - table16/column16: first 16 chars of normalized name
 * - hash10: first 10 lowercase hex chars of SHA-256 over
 *   `<tableNorm><NUL><columnNorm>` (full normalized names, not truncated)
 *
 * Driver-side facade over [MysqlSequenceSupportNaming] in
 * `hexagon:ports-read` (E.3 Sub-Slice F follow-up, 2026-05-20).
 * Kept as a public driver object so existing callers
 * (`MysqlDdlGenerator`, `MysqlSequenceReverseSupport`, integration
 * tests) keep their imports stable while the drift-check stage in
 * `hexagon:application` reuses the same naming logic without
 * crossing a driver-internal module boundary.
 */
object MysqlSequenceNaming {

    const val SUPPORT_TABLE: String = MysqlSequenceSupportNaming.SUPPORT_TABLE
    const val NEXTVAL_ROUTINE: String = MysqlSequenceSupportNaming.NEXTVAL_ROUTINE
    const val SETVAL_ROUTINE: String = MysqlSequenceSupportNaming.SETVAL_ROUTINE

    /**
     * Normalizes a SQL identifier for canonical naming:
     * ASCII-lowercase, non-alphanumeric characters except `_` removed.
     */
    fun normalize(name: String): String = MysqlSequenceSupportNaming.normalize(name)

    /**
     * Computes the first 10 lowercase hex characters of SHA-256
     * over `<tableNorm><NUL><columnNorm>`.
     */
    fun hash10(tableNorm: String, columnNorm: String): String =
        MysqlSequenceSupportNaming.hash10(tableNorm, columnNorm)

    /**
     * Builds the canonical BEFORE INSERT trigger name for a
     * sequence-backed column.
     *
     * Result is always <= 55 characters (well within MySQL's 64-char limit):
     * `dmg_seq_` (8) + table16 (16) + `_` (1) + column16 (16) + `_` (1) +
     * hash10 (10) + `_bi` (3) = 55
     */
    fun triggerName(tableName: String, columnName: String): String =
        MysqlSequenceSupportNaming.triggerName(tableName, columnName)

    /** All reserved support object names (for collision detection). */
    val reservedNames: Set<String> = setOf(SUPPORT_TABLE, NEXTVAL_ROUTINE, SETVAL_ROUTINE)

    /**
     * Checks if a trigger name matches the canonical support trigger pattern.
     * Format: `dmg_seq_<table16>_<column16>_<hex10>_bi`
     * The hash segment must be exactly 10 lowercase hex characters.
     */
    private val CANONICAL_PATTERN = Regex(
        "^dmg_seq_[a-z0-9_]{1,16}_[a-z0-9_]{1,16}_[0-9a-f]{10}_bi$"
    )

    fun isSupportTriggerName(name: String): Boolean =
        CANONICAL_PATTERN.matches(name)
}
