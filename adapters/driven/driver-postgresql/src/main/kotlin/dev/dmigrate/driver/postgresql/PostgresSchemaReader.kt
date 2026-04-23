package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import java.sql.Connection

/**
 * PostgreSQL [SchemaReader] implementation.
 *
 * Uses `information_schema` for portable base data and `pg_catalog`
 * for PostgreSQL-specific metadata (sequences, enum types, backing
 * index detection).
 */
class PostgresSchemaReader(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : SchemaReader {

    override fun read(pool: ConnectionPool, options: SchemaReadOptions): SchemaReadResult {
        val notes = mutableListOf<SchemaReadNote>()
        val skipped = mutableListOf<SkippedObject>()

        pool.borrow().use { conn ->
            val session = jdbcFactory(conn)
            val schema = currentSchema(conn)
            val database = conn.catalog ?: "unknown"

            val tables = readPostgresTables(session, schema, notes)
            val sequences = readPostgresSequences(session, schema)
            val customTypes = readPostgresCustomTypes(session, schema)

            readPostgresExtensionNotes(session, notes)
            val views = if (options.includeViews) readPostgresViews(session, schema) else emptyMap()
            val functions = if (options.includeFunctions) readPostgresFunctions(session, schema) else emptyMap()
            val procedures = if (options.includeProcedures) readPostgresProcedures(session, schema) else emptyMap()
            val triggers = if (options.includeTriggers) readPostgresTriggers(session, schema) else emptyMap()

            val schemaDef = SchemaDefinition(
                name = ReverseScopeCodec.postgresName(database, schema),
                version = ReverseScopeCodec.REVERSE_VERSION,
                tables = tables,
                sequences = sequences,
                customTypes = customTypes,
                views = views,
                functions = functions,
                procedures = procedures,
                triggers = triggers,
            )

            return SchemaReadResult(schema = schemaDef, notes = notes, skippedObjects = skipped)
        }
    }
}

private fun readPostgresExtensionNotes(
    session: JdbcOperations,
    notes: MutableList<SchemaReadNote>,
) {
    val extensions = PostgresMetadataQueries.listInstalledExtensions(session)
    for (extension in extensions) {
        notes += SchemaReadNote(
            severity = SchemaReadSeverity.INFO,
            code = "R400",
            objectName = extension,
            message = "PostgreSQL extension '$extension' is installed",
            hint = "Extension-dependent objects may require this extension in the target database",
        )
    }
}
