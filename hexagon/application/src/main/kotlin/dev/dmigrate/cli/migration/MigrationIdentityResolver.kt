package dev.dmigrate.cli.migration

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.migration.MigrationIdentity
import dev.dmigrate.migration.MigrationTool
import dev.dmigrate.migration.MigrationVersionSource

/**
 * Resolves a [MigrationIdentity] from CLI flags and schema metadata.
 *
 * Applies the tool-specific version strategy from Phase A:
 * - Flyway/Liquibase: `--version` optional, falls back to `schema.version`
 * - Django/Knex: `--version` required, no fallback
 */
object MigrationIdentityResolver {

    class ResolutionException(message: String) : IllegalArgumentException(message)

    fun resolve(
        tool: MigrationTool,
        dialect: DatabaseDialect,
        cliVersion: String?,
        schemaVersion: String?,
        schemaName: String,
    ): MigrationIdentity {
        val (version, source) = resolveVersion(tool, cliVersion, schemaVersion)
        val validation = MigrationVersionValidator.validate(tool, version)
        if (!validation.valid) {
            throw ResolutionException(validation.error!!)
        }
        val slug = MigrationSlugNormalizer.normalize(schemaName)
        return MigrationIdentity(
            tool = tool,
            dialect = dialect,
            version = version,
            versionSource = source,
            slug = slug,
        )
    }

    private fun resolveVersion(
        tool: MigrationTool,
        cliVersion: String?,
        schemaVersion: String?,
    ): Pair<String, MigrationVersionSource> {
        // CLI version always takes precedence
        if (!cliVersion.isNullOrBlank()) {
            return cliVersion to MigrationVersionSource.CLI
        }
        // Tools requiring explicit version fail here
        if (tool.requiresExplicitVersion) {
            throw ResolutionException(
                "--version is required for ${tool.name.lowercase()} exports"
            )
        }
        // Flyway/Liquibase: try schema.version fallback
        val fallback = schemaVersion?.let {
            MigrationVersionValidator.normalizeFallback(tool, it)
        }
        if (fallback != null) {
            return fallback to MigrationVersionSource.SCHEMA
        }
        throw ResolutionException(
            "No --version provided and schema.version '${schemaVersion ?: ""}' " +
                "is not suitable for ${tool.name.lowercase()}"
        )
    }
}
