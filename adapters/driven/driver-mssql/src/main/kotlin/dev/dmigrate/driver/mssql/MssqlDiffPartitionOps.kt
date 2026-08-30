package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.migration.MigrationBlockedReason

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

    /**
     * Der Bestand an Partitionen aendert sich. SQL Server kennt dafuer keine
     * Kind-Anweisung: Partitionen sind keine Objekte, sondern die Abschnitte
     * zwischen den Grenzwerten der Partition Function. Das Kind-Delta wird
     * deshalb in ein **Grenz-Delta** zurueckgerechnet — eine Grenze mehr ist
     * ein `SPLIT`, eine weniger ein `MERGE`.
     *
     * Genau deshalb ist der Kind-Weg der falsche: eine eingefuegte Grenze
     * erscheint im neutralen Modell als **ein entferntes und zwei
     * hinzugekommene** Kinder, weil die MSSQL-Lesung die Zahlenachse
     * lueckenlos abdeckt. Wer das als „drop, dann zweimal add" ausfuehrte,
     * verloere die Zeilen der aufgeteilten Partition; der `SPLIT` behaelt sie,
     * und `MERGE` schiebt sie in die Nachbarpartition.
     *
     * `ALTER PARTITION SCHEME … NEXT USED` steht vor jedem `SPLIT`: SQL Server
     * verlangt fuer die neu entstehende Partition eine Filegroup, und ohne die
     * Angabe scheitert der `SPLIT` an einer Servermeldung.
     */
    fun renderAlterTablePartitions(op: DiffOperation.AlterTablePartitions, ctx: MssqlDiffRenderContext) {
        val table = op.objectRef.rootName
        val down = ctx.direction == MssqlRenderDirection.DOWN
        val before = if (down) op.after else op.before
        val after = if (down) op.before else op.after

        if (blockUnrenderable(op, ctx, table, before, after)) return

        val beforeBoundaries = MssqlPartitionDdl.boundaryLiterals(before)
        val afterBoundaries = MssqlPartitionDdl.boundaryLiterals(after)
        val added = afterBoundaries.filterNot { it in beforeBoundaries }
        val removed = beforeBoundaries.filterNot { it in afterBoundaries }

        val function = ctx.sql.quote(MssqlPartitionDdl.functionName(table))
        val scheme = ctx.sql.quote(MssqlPartitionDdl.schemeName(table))
        for (boundary in removed) {
            ctx.emit(op, "ALTER PARTITION FUNCTION $function() MERGE RANGE ($boundary);")
        }
        for (boundary in added) {
            ctx.emit(op, "ALTER PARTITION SCHEME $scheme NEXT USED ${ctx.sql.quote(ctx.options.partitionStorage)};")
            ctx.emit(op, "ALTER PARTITION FUNCTION $function() SPLIT RANGE ($boundary);")
        }
    }

    /**
     * Ein Grenz-Delta gibt es nur, wo beide Seiten als RANGE ueber einer
     * Spalte lesbar sind. LIST und HASH haben in SQL Server keine Grenzen,
     * und eine Partitionierung ohne Kinder hat keine, die sich verschieben
     * koennte.
     */
    private fun blockUnrenderable(
        op: DiffOperation.AlterTablePartitions,
        ctx: MssqlDiffRenderContext,
        table: String,
        before: PartitionConfig,
        after: PartitionConfig,
    ): Boolean {
        if (MssqlPartitionDdl.isRenderable(before) && MssqlPartitionDdl.isRenderable(after)) return false
        ctx.skip(
            op,
            "Operation ${op.id} changes the partitions of '$table', but SQL Server expresses " +
                "partitioning only as RANGE boundaries over a single column. This partitioning is not " +
                "in that shape, so there is no boundary delta to apply.",
            code = "E055",
        )
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, setOf(op.id))
        return true
    }

    /** Blockt und liefert `null` — fuer Pfade, die einen Ausdruck brauchen. */
    private fun blockDeferredNull(op: DiffOperation, ctx: MssqlDiffRenderContext, what: String): String? {
        MssqlDiffTableOps.blockDeferred(op, ctx, what)
        return null
    }
}
