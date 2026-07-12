package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionPool

/**
 * Reads the **structural** schema (tables/columns/constraints/partitioning) of a pool,
 * with views/procedures/functions/triggers switched off. Shared by the export and import
 * wirings' parallel-planning paths (LN-007/LN-008, ADR 0032) so the schema-read boilerplate
 * lives in one place.
 */
internal fun readStructuralSchema(pool: ConnectionPool): SchemaDefinition =
    DatabaseDriverRegistry.get(pool.dialect).schemaReader().read(
        pool,
        SchemaReadOptions(
            includeViews = false, includeProcedures = false,
            includeFunctions = false, includeTriggers = false,
        ),
    ).schema
