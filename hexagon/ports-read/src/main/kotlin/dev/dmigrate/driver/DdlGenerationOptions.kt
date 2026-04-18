package dev.dmigrate.driver

/**
 * Typed generator options for DDL generation (0.5.5 Phase D).
 * Lives in hexagon:ports so both application and driver adapters can use it.
 */
data class DdlGenerationOptions(
    val spatialProfile: SpatialProfile = SpatialProfile.NONE,
)

/**
 * Spatial profile controlling how geometry columns are mapped to DDL.
 * The profile is resolved from the CLI flag and dialect defaults
 * before any generator is invoked.
 */
enum class SpatialProfile(val cliName: String) {
    POSTGIS("postgis"),
    NATIVE("native"),
    SPATIALITE("spatialite"),
    NONE("none");

    companion object {
        private val BY_CLI_NAME = entries.associateBy { it.cliName }

        fun fromCliName(name: String): SpatialProfile? = BY_CLI_NAME[name.lowercase()]
    }
}

/**
 * Central policy for spatial profile defaults and allowed combinations.
 * Single source of truth — CLI, Runner, and tests all use this.
 */
object SpatialProfilePolicy {

    fun defaultFor(dialect: DatabaseDialect): SpatialProfile = when (dialect) {
        DatabaseDialect.POSTGRESQL -> SpatialProfile.POSTGIS
        DatabaseDialect.MYSQL -> SpatialProfile.NATIVE
        DatabaseDialect.SQLITE -> SpatialProfile.NONE
    }

    fun allowedFor(dialect: DatabaseDialect): Set<SpatialProfile> = when (dialect) {
        DatabaseDialect.POSTGRESQL -> setOf(SpatialProfile.POSTGIS, SpatialProfile.NONE)
        DatabaseDialect.MYSQL -> setOf(SpatialProfile.NATIVE, SpatialProfile.NONE)
        DatabaseDialect.SQLITE -> setOf(SpatialProfile.SPATIALITE, SpatialProfile.NONE)
    }

    /**
     * Resolves the effective spatial profile from a raw CLI string and dialect.
     * Returns null if the raw profile name is unknown or not allowed for the dialect.
     */
    fun resolve(dialect: DatabaseDialect, rawProfile: String?): Result {
        if (rawProfile == null) {
            return Result.Resolved(defaultFor(dialect))
        }
        val profile = SpatialProfile.fromCliName(rawProfile)
            ?: return Result.UnknownProfile(rawProfile)
        if (profile !in allowedFor(dialect)) {
            return Result.NotAllowedForDialect(profile, dialect)
        }
        return Result.Resolved(profile)
    }

    sealed interface Result {
        data class Resolved(val profile: SpatialProfile) : Result
        data class UnknownProfile(val raw: String) : Result
        data class NotAllowedForDialect(val profile: SpatialProfile, val dialect: DatabaseDialect) : Result
    }
}
