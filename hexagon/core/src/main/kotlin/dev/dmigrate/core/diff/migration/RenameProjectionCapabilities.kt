package dev.dmigrate.core.diff.migration

import java.util.Locale

/**
 * Core-local discriminator for [RenameDependencyPolicy] decisions.
 * `hexagon:core` cannot depend on `hexagon:ports-read`'s
 * `DatabaseDialect`, so the application layer maps its dialect to
 * this enum at the `DiffPlanner.plan(...)` call site.
 */
enum class RenameProjectionDialect {
    POSTGRESQL,
    MYSQL,
    SQLITE,
}

/**
 * Where the engine-capability information was sourced from. Policies
 * use this to decide whether they may rely on runtime-dependent
 * `AUTOMATIC_BY_ENGINE` classifications (e.g. SQLite
 * `legacy_alter_table` or MySQL server-family).
 *
 * - [FILE_ONLY]: file-to-file migration or DB-target without an
 *   explicit capability probe. Policies must classify version-/
 *   PRAGMA-dependent decisions conservatively.
 * - [LIVE_TARGET]: the runner probed the live target before
 *   `plan(...)` and the resulting capability values reflect the
 *   actual engine state.
 * - [TEST_PINNED]: deterministic test input. Should never appear in
 *   production traffic; tests use it to drive matrix coverage
 *   without an actual server.
 */
enum class RenameCapabilitySource {
    FILE_ONLY,
    LIVE_TARGET,
    TEST_PINNED,
}

/**
 * Runtime engine capabilities consumed by [RenameDependencyPolicy].
 * Plan-time only: the application layer fills this once before
 * `DiffPlanner.plan(...)`; the planner never re-derives values
 * after the fact (Plan-2 §F.4 dependency-projection §3.3a).
 *
 * String fields are the transport contract at the application/core
 * boundary. Policies parse them via [RenameProjectionVersionParser]
 * into a small structured type before comparing — lexicographic
 * comparison on raw strings would mis-order `3.9` vs `3.26`.
 */
data class RenameProjectionCapabilities(
    /**
     * Dialect this capability bundle targets. The policy registry uses
     * [dialect] to select the matching [RenameDependencyPolicy]; an
     * incomplete capability bundle (e.g. missing
     * [sqliteLegacyAlterTable]) still resolves to the right dialect
     * policy, which then classifies the runtime-gated paths as
     * unknown / blocked.
     */
    val dialect: RenameProjectionDialect,
    val source: RenameCapabilitySource = RenameCapabilitySource.FILE_ONLY,
    val sqliteVersion: String? = null,
    val sqliteLegacyAlterTable: Boolean? = null,
    val mysqlServerFamily: String? = null, // "mysql", "mariadb", or unknown
    val mysqlVersion: String? = null,
) {
    companion object {
        /**
         * Conservative default for a dialect-less call site (currently
         * tests). PostgreSQL is the most permissive policy in the F.4
         * matrix; production paths set [dialect] explicitly via the
         * application-layer factory.
         */
        val FILE_ONLY: RenameProjectionCapabilities =
            RenameProjectionCapabilities(
                dialect = RenameProjectionDialect.POSTGRESQL,
                source = RenameCapabilitySource.FILE_ONLY,
            )

        /**
         * Builder for tests and the application factory: starts from
         * FILE_ONLY and pins the dialect. Avoids spreading
         * `RenameProjectionCapabilities(dialect = …)` constructor
         * calls throughout the codebase.
         */
        fun fileOnly(dialect: RenameProjectionDialect): RenameProjectionCapabilities =
            RenameProjectionCapabilities(dialect = dialect, source = RenameCapabilitySource.FILE_ONLY)
    }
}

/**
 * Parsed major/minor/patch tuple plus an optional pre-release suffix
 * (e.g. MariaDB's `-MariaDB`). Returned by
 * [RenameProjectionVersionParser]. Implements [Comparable] so
 * policies can write `parsed >= MIN_SQLITE_3_26` without doing
 * string comparisons.
 *
 * `null` parts are treated as `0` for ordering; the suffix is
 * compared lexicographically only when the numeric prefix is equal.
 */
data class ParsedRenameVersion(
    val major: Int,
    val minor: Int = 0,
    val patch: Int = 0,
    val suffix: String = "",
) : Comparable<ParsedRenameVersion> {

    override fun compareTo(other: ParsedRenameVersion): Int {
        val byMajor = major.compareTo(other.major)
        if (byMajor != 0) return byMajor
        val byMinor = minor.compareTo(other.minor)
        if (byMinor != 0) return byMinor
        val byPatch = patch.compareTo(other.patch)
        if (byPatch != 0) return byPatch
        return suffix.compareTo(other.suffix)
    }
}

/**
 * Conservative version parser for SQLite and MySQL/MariaDB version
 * strings. Returns `null` for any input that does not start with at
 * least one numeric component — policies must treat that as an
 * unknown capability and block runtime-dependent paths.
 *
 * Accepted shapes (examples):
 *
 * - `3.9` → `3.9.0` (missing components default to 0)
 * - `3.26.0`
 * - `8.0.30` (MySQL)
 * - `10.11.5-MariaDB` → suffix `mariadb` (preserved, lowercased)
 * - `8.4.0+build.123` → suffix `build.123` (build metadata after `+`)
 *
 * Suffix separator is restricted to `-` or `+` — exactly the
 * separators the target engines (SQLite / MySQL / MariaDB) use in
 * their `SELECT version()` / `PRAGMA compile_options` output. A `.`
 * after the patch component is treated as a malformed input
 * (returns `null`) so we do not accidentally accept four-segment
 * version strings (`3.9.0.0`) or trailing-dot inputs (`3.`).
 *
 * Returns `null` for: empty string, blank string, `abc`, `v1`,
 * `1.x`, `3.9.0.0`, `3.`.
 */
object RenameProjectionVersionParser {

    private val NUMERIC_VERSION = Regex("""^(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:[-+](.*))?$""")

    fun parse(value: String?): ParsedRenameVersion? {
        if (value.isNullOrBlank()) return null
        val match = NUMERIC_VERSION.matchEntire(value.trim()) ?: return null
        val groups = match.groupValues
        val major = groups[1].toIntOrNull() ?: return null
        val minor = groups[2].ifBlank { "0" }.toIntOrNull() ?: return null
        val patch = groups[3].ifBlank { "0" }.toIntOrNull() ?: return null
        val suffix = groups[4].lowercase(Locale.ROOT)
        return ParsedRenameVersion(major = major, minor = minor, patch = patch, suffix = suffix)
    }
}
