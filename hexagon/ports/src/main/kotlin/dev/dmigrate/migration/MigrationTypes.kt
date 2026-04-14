package dev.dmigrate.migration

import dev.dmigrate.driver.DatabaseDialect

/**
 * Supported external migration tools for `d-migrate export`.
 */
enum class MigrationTool {
    FLYWAY, LIQUIBASE, DJANGO, KNEX;

    /**
     * Whether this tool requires an explicit `--version` CLI flag.
     * Flyway/Liquibase can fall back to `schema.version`;
     * Django/Knex always require explicit `--version`.
     */
    val requiresExplicitVersion: Boolean get() = this == DJANGO || this == KNEX
}

/**
 * Tracks how the migration version was resolved.
 */
enum class MigrationVersionSource {
    /** Explicitly provided via `--version` CLI flag. */
    CLI,
    /** Derived from `schema.version` (Flyway/Liquibase fallback). */
    SCHEMA,
}
