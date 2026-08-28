package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition

/**
 * Die Partitionierungs-Seite des Migrationspfads fuer SQL Server.
 *
 * Gegenstueck zu [MssqlTablePartitioning] auf der Generate-Seite, und aus
 * demselben Grund eigenstaendig: Partition Function und Scheme sind Objekte
 * NEBEN der Tabelle, mit eigener Reihenfolge und eigenem Rueckbau.
 */
internal object MssqlDiffPartitionOps {

    /**
     * Die zwei Datenbankobjekte neben der Tabelle — Partition Function und
     * Scheme — und die Klausel, die die Tabelle an sie haengt.
     *
     * Eigene Methode, weil sie ein eigener Gegenstand ist: `renderCreateTable`
     * baut die Tabelle, das hier baut, woran sie haengt. Gibt `null` zurueck,
     * wenn geblockt wurde.
     */
    fun emit(
        op: DiffOperation.CreateTable,
        ctx: MssqlDiffRenderContext,
        table: String,
        effective: TableDefinition,
        schema: SchemaDefinition,
        hashPlan: MssqlHashPartitionPlan?,
        partitioning: PartitionConfig?,
    ): String? {
        var onClause = ""
        if (hashPlan != null) {
            MssqlPartitionDdl.createStatementsForBoundaries(
                table = table,
                boundaries = hashPlan.boundaries,
                columnType = MssqlHashPartitionEmulation.BUCKET_TYPE,
                storage = ctx.options.partitionStorage,
                quote = ctx.sql::quote,
            ).forEach { ctx.emit(op, it) }
            onClause = " ON ${ctx.sql.quote(MssqlPartitionDdl.schemeName(table))} " +
                "(${ctx.sql.quote(MssqlHashPartitionEmulation.BUCKET_COLUMN)})"
            ctx.warning(
                op,
                "HASH partitioning of table '$table' was emulated with a persisted computed column over " +
                    "${hashPlan.modulus} buckets. SQL Server hashes differently than the source dialect, so " +
                    "a row lands in a different bucket than it did there; the bucket column also joins every " +
                    "unique key, and a reverse reads the table back as RANGE.",
                code = "W145",
            )
        } else if (partitioning != null) {
            val keyColumn = partitioning.key.first()
            // Wegwerf-Senke fuer die Notizen: die Schluesselspalte wurde oben
            // schon deklariert und hat ihre Notizen dort abgegeben. Dieselbe
            // Liste ein zweites Mal zu fuellen, verdoppelte jede Meldung zu ihr.
            val keyType = effective.columns[keyColumn]
                ?.let { ctx.sql.renderColumn(table, keyColumn, it, effective, schema, mutableListOf()).sqlType }
                ?: return blockDeferredNull(op, ctx, "partition key column '$keyColumn' of '$table' has no SQL type")
            MssqlPartitionDdl.createStatements(
                table = table,
                config = partitioning,
                columnType = keyType,
                storage = ctx.options.partitionStorage,
                quote = ctx.sql::quote,
            ).forEach { ctx.emit(op, it) }
            onClause = MssqlPartitionDdl.onClause(table, partitioning, ctx.sql::quote)
            ctx.warning(
                op,
                "Partition function '${MssqlPartitionDdl.functionName(table)}' and scheme " +
                    "'${MssqlPartitionDdl.schemeName(table)}' were created for table '$table' alone. " +
                    "SQL Server shares these objects across tables; the neutral model carries " +
                    "partitioning per table, so a shared original becomes one pair per table.",
                code = "W144",
            )
        }
        return onClause
    }

    /** Blockt und liefert `null` — fuer Pfade, die einen Ausdruck brauchen. */
    private fun blockDeferredNull(op: DiffOperation, ctx: MssqlDiffRenderContext, what: String): String? {
        MssqlDiffTableOps.blockDeferred(op, ctx, what)
        return null
    }
}
