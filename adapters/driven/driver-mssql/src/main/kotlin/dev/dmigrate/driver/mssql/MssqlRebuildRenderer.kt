package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.inOrdinalOrder
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.TransformationNote
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Der Tabellen-Neubau: anlegen, kopieren, alte loeschen, umbenennen, Oberflaeche
 * nachziehen. Er laeuft nur fuer die eine Aenderung, die SQL Server nicht per
 * `ALTER` kann — IDENTITY setzen oder entfernen ([MssqlRebuildPlanner]).
 *
 * **Warum die Zwischentabelle nackt ist.** SQL Server fuehrt Constraints
 * schema-global. Solange die alte Tabelle lebt, sind `pk_<t>`, `df_<t>_<c>`,
 * `uq_<t>_<c>`, `ck_<t>_<c>` und die Namen aus dem Modell vergeben; die
 * Zwischentabelle scheiterte an Msg 2714. Sie bekommt deshalb nur Spalten —
 * Typ, `IDENTITY`, `NULL`/`NOT NULL` — und die gesamte benannte Oberflaeche
 * entsteht erst nach dem Umbenennen, unter den endgueltigen Namen. Damit
 * bleibt keine Zwischenform zurueck: die Tabelle sieht danach aus, als haette
 * `schema generate` sie geschrieben.
 *
 * **Warum die Zaehler ueberleben.** Die Kopie laeuft mit
 * `SET IDENTITY_INSERT … ON`, schreibt also die bestehenden Schluesselwerte
 * statt neue zu vergeben. SQL Server zieht den Zaehler dabei auf den hoechsten
 * eingefuegten Wert nach, der naechste `INSERT` setzt die Reihe also fort.
 * `SET` und `INSERT` stehen in **einem** Statement: der Schalter ist
 * sitzungsweit, und ein abgebrochener Lauf duerfte ihn nicht offen lassen.
 */
internal object MssqlRebuildRenderer {

    /**
     * Was der Neubau braucht, bevor er ein einziges Statement emittiert. Der
     * Renderer loest erst alles auf, was scheitern kann — eine Operation, die
     * nach dem ersten `emit` blockt, laege in `rendered` UND `skipped`, und
     * `MigrationDdlResult` erzwingt Disjunktheit mit `require()`.
     */
    private data class Resolved(
        val statements: List<String>,
        val notes: List<TransformationNote>,
    )

    fun render(rebuild: MssqlRebuildPlanner.Rebuild, ctx: MssqlDiffRenderContext) {
        val (table, trigger, bucket) = rebuild
        val targetSchema = ctx.schemaForDirection()
        val sourceSchema = ctx.schemaOppositeOfDirection()
        val target = targetSchema?.tables?.get(table)
        val source = sourceSchema?.tables?.get(table)
        if (targetSchema == null || target == null || source == null) {
            return blockBucket(
                bucket, ctx,
                "Rebuilding table '$table' needs both the current and the desired definition of the table, " +
                    "but the DiffResult carries only one of them for this direction.",
                code = "MSSQL_COLUMN_NOT_IN_SCHEMA",
                reason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
            )
        }
        // Partitionierung rendert dieser Pfad nicht — eine partitionierte Tabelle als
        // gewoehnliche neu aufzubauen waere ein stiller Verlust, kein Teilerfolg.
        if (target.partitioning != null || source.partitioning != null) {
            return blockBucket(
                bucket, ctx,
                "Table '$table' is partitioned, and rebuilding it would drop the partitioning: the neutral " +
                    "model carries no partition function or scheme for SQL Server.",
                code = "DIALECT_UNSUPPORTED_OPERATION",
                reason = MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION,
            )
        }
        val resolved = resolve(rebuild, ctx, targetSchema, sourceSchema, target, source)
            ?: return blockBucketRemainder(bucket, ctx, table)
        if (ctx.options.strictGapOperations) {
            return blockBucket(
                bucket, ctx,
                "Rebuilding table '$table' drops and re-creates it, which leaves a window in which the table " +
                    "is absent (`hasGap = true`), and `--strict-gap-operations` is set.",
                code = "OPERATION_HAS_GAP_STRICT_BLOCKED",
                reason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
            )
        }
        resolved.statements.forEach { ctx.emitRebuild(bucket, trigger, it) }
        ctx.carryOverNotes(trigger, resolved.notes)
        ctx.addInfoDiagnostic(
            code = "MSSQL_TABLE_REBUILT_FOR_IDENTITY",
            operationId = trigger.id,
            message = "Table '$table' is rebuilt (create, copy, drop, rename) because SQL Server cannot add or " +
                "remove IDENTITY with ALTER COLUMN. The copy runs with SET IDENTITY_INSERT ON, so existing key " +
                "values and the identity counter are preserved.",
        )
    }

    /** Die ganze Sequenz als Text — oder `null`, wenn etwas davon nicht renderbar ist. */
    @Suppress("LongParameterList", "ReturnCount")
    private fun resolve(
        rebuild: MssqlRebuildPlanner.Rebuild,
        ctx: MssqlDiffRenderContext,
        targetSchema: SchemaDefinition,
        sourceSchema: SchemaDefinition?,
        target: TableDefinition,
        source: TableDefinition,
    ): Resolved? {
        val table = rebuild.table
        val trigger = rebuild.trigger
        val bucket = rebuild.ops
        val temp = MssqlRebuildPlanner.tempTableName(table, bucket)
        val notes = mutableListOf<TransformationNote>()
        val sources = MssqlRebuildPlanner.columnSources(source, target, bucket, ctx.direction)

        val declarations = mutableListOf<String>()
        val objectStatements = mutableListOf<String>()
        for ((name, col) in target.columns.inOrdinalOrder()) {
            val rendering = ctx.sql.renderColumn(table, name, col, target, targetSchema, notes)
            declarations += rendering.declaration
            objectStatements += rendering.objects.map { ctx.sql.columnObjectStatement(table, name, it) }
        }
        val copy = copyStatement(table, temp, target, sources, ctx)
            ?: return blockUnfillable(bucket, ctx, table, target, sources)

        val statements = mutableListOf<String>()
        val createdSoFar = ctx.inboundForeignKeysCreatedSoFar(table)
        MssqlRebuildPlanner.inboundForeignKeysPresent(sourceSchema, table, createdSoFar).forEach {
            statements += ctx.sql.dropConstraintIfTableExistsSql(it.childTable, it.constraint.name)
        }
        statements += createTempTableSql(temp, declarations, ctx)
        statements += copy
        statements += "DROP TABLE ${ctx.sql.quote(table)};"
        statements += ctx.sql.renameSql(temp, table)
        if (target.primaryKey.isNotEmpty()) {
            statements += ctx.sql.addPrimaryKeySql(table, target.primaryKey, target.indices)
        }
        statements += objectStatements
        // Auch hier kann dasselbe Objekt in beiden Modellformen stehen —
        // Spiegelfall der eingehenden Seite, zweimal angelegt waere Msg 2714.
        // Deklariert vor synthetisiert: die Modell-Definition kann mehrspaltig
        // sein oder Kaskaden tragen, die ein `references` an der Spalte nicht
        // ausdrueckt. Bei gleichem Namen gewinnt deshalb sie.
        val outbound = (target.constraints + columnLevelForeignKeys(table, target)).distinctBy { it.name }
        for (constraint in outbound) {
            statements += MssqlDiffObjectOps.resolveConstraintSql(trigger, ctx, table, constraint) ?: return null
        }
        for (index in target.indices) {
            statements += MssqlDiffObjectOps.resolveIndexSql(trigger, ctx, table, index, target) ?: return null
        }
        val restorable = MssqlRebuildPlanner.inboundForeignKeysToRestore(
            sourceSchema, targetSchema, table, createdSoFar, bucket,
        )
        for (inbound in restorable) {
            statements += MssqlDiffObjectOps
                .resolveConstraintSql(trigger, ctx, inbound.childTable, inbound.constraint) ?: return null
        }
        return Resolved(statements, notes)
    }

    private fun createTempTableSql(
        temp: String,
        declarations: List<String>,
        ctx: MssqlDiffRenderContext,
    ): String = "CREATE TABLE ${ctx.sql.quote(temp)} (\n" +
        declarations.joinToString(",\n") { "    $it" } + "\n);"

    /**
     * Die Kopie. Eine Zielspalte ohne Quelle wird aus ihrem Default gefuellt —
     * der Default-Constraint existiert zu diesem Zeitpunkt noch nicht, die
     * Zwischentabelle ist ja nackt, also muss der Wert im `SELECT` stehen.
     * Eine neue IDENTITY-Spalte bleibt aussen vor: die vergibt SQL Server.
     *
     * `null`, wenn eine Zielspalte weder Quelle noch Default hat und NOT NULL
     * ist — daraus laesst sich kein Wert erfinden.
     */
    private fun copyStatement(
        table: String,
        temp: String,
        target: TableDefinition,
        sources: List<Pair<String, MssqlRebuildPlanner.ColumnSource>>,
        ctx: MssqlDiffRenderContext,
    ): String? {
        val targets = mutableListOf<String>()
        val expressions = mutableListOf<String>()
        var identityInsert = false
        for ((name, from) in sources) {
            val col = target.columns[name] ?: continue
            val isIdentity = MssqlRebuildPlanner.isIdentity(col)
            when (from) {
                is MssqlRebuildPlanner.ColumnSource.From -> {
                    targets += ctx.sql.quote(name)
                    expressions += ctx.sql.quote(from.column)
                    if (isIdentity) identityInsert = true
                }
                // Eine neue IDENTITY-Spalte fuellt SQL Server selbst.
                MssqlRebuildPlanner.ColumnSource.Fill -> if (!isIdentity) {
                    val literal = fillExpression(col, ctx) ?: return null
                    targets += ctx.sql.quote(name)
                    expressions += literal
                }
            }
        }
        val insert = "INSERT INTO ${ctx.sql.quote(temp)} (${targets.joinToString(", ")})\n" +
            "    SELECT ${expressions.joinToString(", ")} FROM ${ctx.sql.quote(table)};"
        if (!identityInsert) return insert
        return "SET IDENTITY_INSERT ${ctx.sql.quote(temp)} ON;\n" +
            insert + "\n" +
            "SET IDENTITY_INSERT ${ctx.sql.quote(temp)} OFF;"
    }

    private fun fillExpression(col: ColumnDefinition, ctx: MssqlDiffRenderContext): String? {
        val default = col.default
        return when {
            default != null -> ctx.sql.toDefaultSql(default, col.type)
            !col.required -> "NULL"
            else -> null
        }
    }

    /** Die `references` der Spalten als Constraints — sonst faellt die Beziehung beim Umbau weg. */
    private fun columnLevelForeignKeys(table: String, target: TableDefinition): List<ConstraintDefinition> =
        target.columns.mapNotNull { (name, col) ->
            col.references?.let { MssqlDiffObjectOps.columnForeignKey(table, name, it) }
        }

    private fun blockUnfillable(
        bucket: List<DiffOperation>,
        ctx: MssqlDiffRenderContext,
        table: String,
        target: TableDefinition,
        sources: List<Pair<String, MssqlRebuildPlanner.ColumnSource>>,
    ): Resolved? {
        val unfillable = sources
            .filter { it.second is MssqlRebuildPlanner.ColumnSource.Fill }
            .map { it.first }
            .filter { name -> target.columns[name]?.let { it.required && it.default == null } == true }
        blockBucket(
            bucket, ctx,
            "Rebuilding table '$table' cannot fill column(s) ${unfillable.joinToString(", ") { "'$it'" }}: " +
                "they are new, NOT NULL and have no default, so the copy into the rebuilt table has no value " +
                "to write.",
            code = "MSSQL_REBUILD_COLUMN_NOT_FILLABLE",
            reason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
        )
        return null
    }

    /**
     * Ein Teil der Sequenz liess sich nicht rendern. Der Aufloeser hat dafuer
     * bereits die ausloesende Operation als Blocker vermerkt — der REST des
     * Eimers darf jetzt aber nicht aus der Buchhaltung fallen: er waere weder
     * `rendered` noch `skipped`, und ein Konsument, der beide Mengen liest,
     * saehe die Operationen spurlos verschwinden.
     */
    private fun blockBucketRemainder(
        bucket: List<DiffOperation>,
        ctx: MssqlDiffRenderContext,
        table: String,
    ) {
        val rest = bucket.filterNot { ctx.isSkipped(it) }
        if (rest.isEmpty()) return
        rest.forEach {
            ctx.skip(
                it,
                "Operation ${it.id} belongs to the rebuild of table '$table', which could not be rendered in " +
                    "full; the reason is reported on the operation that triggered the rebuild.",
                code = "MSSQL_REBUILD_NOT_RENDERABLE",
            )
        }
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, rest.map { it.id }.toSet())
    }

    private fun blockBucket(
        bucket: List<DiffOperation>,
        ctx: MssqlDiffRenderContext,
        message: String,
        code: String,
        reason: MigrationBlockedReason,
    ) {
        bucket.forEach { ctx.skip(it, message, code = code) }
        ctx.addBlocker(reason, bucket.map { it.id }.toSet())
    }
}
