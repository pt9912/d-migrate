package dev.dmigrate.driver

/**
 * E.1 Routine-Migration Slice C.1.a: structural MySQL server version.
 *
 * Used by [RoutineKindCapability.minServerVersion] to compare a
 * declared floor against the live server reported by
 * `MysqlMetadataQueries.readServerVersion()` (driver-mysql).
 *
 * The vendor suffix captured by [parse] is informational only —
 * comparison ignores it. Both MySQL (`8.0.36-log`) and MariaDB
 * (`10.11.6-MariaDB`) advertise a leading `major.minor.patch` triple
 * that comparison uses; anything past the first dash falls into
 * [vendor].
 */
data class MysqlServerVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val vendor: String? = null,
) : Comparable<MysqlServerVersion> {

    val isMariaDb: Boolean
        get() = vendor?.contains("MariaDB", ignoreCase = true) == true

    override fun compareTo(other: MysqlServerVersion): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        return patch.compareTo(other.patch)
    }

    companion object {

        // major.minor.patch + optional `-vendorSuffix`. Matches the
        // strings `SELECT VERSION()` returns on both MySQL and MariaDB.
        private val VERSION_REGEX =
            Regex("""^(\d+)\.(\d+)\.(\d+)(?:[-+](.*))?$""")

        /**
         * Parses `8.0.36-log`, `5.7.44`, `10.11.6-MariaDB`, `8.4.0`,
         * etc. Returns `null` for any string that does not match the
         * canonical `major.minor.patch[-vendor]` shape — including
         * vendor identifiers like `"unknown"`.
         */
        fun parse(raw: String): MysqlServerVersion? {
            val match = VERSION_REGEX.matchEntire(raw.trim()) ?: return null
            val groups = match.groupValues
            // groups[0] is the full match; [1..3] are major/minor/patch,
            // [4] is the optional vendor suffix. Avoid destructuring
            // because Detekt's DestructuringDeclarationWithTooManyEntries
            // caps at 3 entries.
            val major = groups[1].toIntOrNull() ?: return null
            val minor = groups[2].toIntOrNull() ?: return null
            val patch = groups[3].toIntOrNull() ?: return null
            val vendor = groups.getOrNull(4)?.takeIf { it.isNotEmpty() }
            return MysqlServerVersion(major, minor, patch, vendor)
        }
    }
}
