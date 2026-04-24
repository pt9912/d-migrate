package dev.dmigrate.cli.migration

import dev.dmigrate.migration.MigrationTool

/**
 * Validates migration versions for tool-specific constraints.
 *
 * Each tool has its own version format requirements:
 * - **Flyway**: numeric dotted (e.g., `1`, `1.0`, `1.0.0`)
 * - **Liquibase**: any non-blank string
 * - **Django**: 4+ digit prefix with optional `_slug` (e.g., `0001`, `0001_initial`)
 * - **Knex**: numeric prefix with optional `_slug` (e.g., `20260414120000`, `20260414120000_create_users`)
 */
object MigrationVersionValidator {

    private val FLYWAY_VERSION = Regex("^\\d+(\\.\\d+)*$")
    private val DJANGO_VERSION = Regex("^\\d{4,}(_[a-z][a-z0-9_]*)?$")
    private val KNEX_VERSION = Regex("^\\d+(_[a-z][a-z0-9_]*)?$")

    data class ValidationResult(
        val valid: Boolean,
        val error: String? = null,
    )

    fun validate(tool: MigrationTool, version: String): ValidationResult {
        if (version.isBlank()) {
            return ValidationResult(false, "Version must not be blank")
        }
        return when (tool) {
            MigrationTool.FLYWAY -> {
                if (FLYWAY_VERSION.matches(version)) ValidationResult(true)
                else ValidationResult(false,
                    "Flyway version must be numeric (e.g., '1', '1.0', '1.0.0'), got: '$version'")
            }
            MigrationTool.LIQUIBASE -> ValidationResult(true)
            MigrationTool.DJANGO -> {
                if (DJANGO_VERSION.matches(version)) ValidationResult(true)
                else ValidationResult(false,
                    "Django version must be 4+ digits with optional _slug (e.g., '0001', '0001_initial'), got: '$version'")
            }
            MigrationTool.KNEX -> {
                if (KNEX_VERSION.matches(version)) ValidationResult(true)
                else ValidationResult(
                    false,
                    "Knex version must be numeric with optional _slug " +
                        "(e.g., '20260414120000', '20260414120000_create_users'), got: '$version'",
                )
            }
        }
    }

    /**
     * Checks whether a `schema.version` value can serve as a Flyway/Liquibase
     * fallback. Returns null if the version is not tool-suitable.
     */
    fun normalizeFallback(tool: MigrationTool, schemaVersion: String): String? {
        if (schemaVersion.isBlank()) return null
        return when (tool) {
            MigrationTool.FLYWAY -> {
                // Try to use schema version as Flyway version (must be numeric)
                val candidate = schemaVersion.removePrefix("v").removePrefix("V")
                if (FLYWAY_VERSION.matches(candidate)) candidate else null
            }
            MigrationTool.LIQUIBASE -> schemaVersion
            MigrationTool.DJANGO, MigrationTool.KNEX -> null // never fallback
        }
    }
}
