package dev.dmigrate.driver

/**
 * Guards partition-bound literals before they are concatenated into DDL.
 *
 * ADR 0019 keeps RANGE/LIST partition-bound literals as strings (the reverse
 * reader strips type casts but keeps the quoted value). Both the PostgreSQL and
 * MySQL partition generators emit that literal verbatim into a `FOR VALUES` /
 * `VALUES LESS THAN` / `VALUES IN` clause, so a literal that smuggled in a
 * statement terminator or comment opener could break out of its clause. This
 * rejects those characters on every generate path, identically for both
 * dialects (no per-dialect drift).
 *
 * The denylist is intentionally dialect-neutral: it lists only tokens that break
 * out on **both** dialects. It deliberately does **not** list the backslash — `\`
 * is an escape character only for MySQL, so doubling it belongs on the MySQL render
 * layer (`MysqlPartitionBoundRenderer`), not here. Rejecting `\` here would wrongly
 * reject a legitimate PostgreSQL bound, where `standard_conforming_strings` keeps
 * the backslash literal.
 */
object PartitionLiteralGuard {

    private val UNSAFE = listOf(";", "--", "/*")

    /** Returns [literal] unchanged, or throws if it contains an unsafe token. */
    fun ensureSafe(literal: String, partitionName: String): String {
        require(UNSAFE.none { literal.contains(it) }) {
            "Partition '$partitionName' bound contains unsafe characters: $literal"
        }
        return literal
    }
}
