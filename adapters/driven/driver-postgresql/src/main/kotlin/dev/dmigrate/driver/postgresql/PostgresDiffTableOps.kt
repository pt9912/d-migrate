package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.inOrdinalOrder
import dev.dmigrate.core.model.isSpatialGeometryIndex
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.PostgresServerVersion
import dev.dmigrate.driver.metadata.EnumValueCheck
import dev.dmigrate.driver.migration.DialectExecutionHints
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.PlannerBlockerClassifier
import dev.dmigrate.driver.migration.TransactionScope
import dev.dmigrate.driver.postgresContext

/**
 * Per-operation renderers for table / column / primary-key DDL.
 * Stateless: takes a [PostgresDiffRenderContext] (which carries
 * direction + SQL builders) and writes statements / diagnostics
 * back into it.
 */
internal object PostgresDiffTableOps {

    /** Der Server kann den Ausdruck nicht in place setzen (unter 17, oder Version unbekannt). */
    /** Aus einer berechneten Spalte wieder eine gewoehnliche zu machen, geht nicht in place. */
    fun renderCreateTable(op: DiffOperation.CreateTable, ctx: PostgresDiffRenderContext) {
        val tableName = op.objectRef.rootName
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            ctx.emit(op, "DROP TABLE ${ctx.sql.quote(tableName)};")
            return
        }
        if (op.table.hasGeometryColumns() &&
            !ctx.requireExtension(op, POSTGIS_EXTENSION, "geometry columns on table `$tableName`")
        ) {
            return
        }
        // VA3: GiST/SP-GiST/BRIN (PG-eigen) UND SPATIAL (neutral) sind gültige
        // PostGIS-Spatial-Indizes; B-Tree/HASH/GIN auf Geometrie werden geblockt.
        val unsupportedSpatialIndex = op.table.indices.firstOrNull { idx ->
            idx.referencesGeometry(op.table) && !pgSupportsGeometryIndex(idx.type)
        }
        if (unsupportedSpatialIndex != null) {
            blockSpatialIndex(op, ctx, tableName, unsupportedSpatialIndex.type.name)
            return
        }
        if (blockUnsupportedExcludeOpClassInTable(op, ctx, tableName)) return
        // ADR 0025: resolve FULLTEXT backing tsvector columns BEFORE emitting the table — a
        // block must happen up-front (like the spatial precheck above), since skipping an op
        // that was already emitted is inconsistent. An unresolvable FULLTEXT blocks the whole op.
        val resolvedIndices = op.table.indices.map { idx ->
            ctx.resolveFullTextIndex(op, tableName, idx) ?: return
        }
        val version = ctx.options.postgresContext?.serverVersion
        val lines = mutableListOf<String>()
        for ((colName, col) in op.table.columns.inOrdinalOrder()) {
            lines += "    " + ctx.sql.columnLine(colName, col, version)
            PostgresDiffComputedColumnOps.noteDegradedVirtual(op, colName, col, version, ctx)
        }
        if (op.table.primaryKey.isNotEmpty()) {
            lines += "    PRIMARY KEY (" + op.table.primaryKey.joinToString(", ") { ctx.sql.quote(it) } + ")"
        }
        for (c in op.table.constraints.sortedBy { it.name }) {
            ctx.sql.constraintLine(c)?.let { lines += "    $it" }
        }
        // Partitionierung gehoert an die CREATE-TABLE-Klammer und ihre Kinder
        // hinterher. Ohne das legte eine Migration die Tabelle FLACH an — ohne
        // Blocker, ohne Diagnose, ohne dass irgendetwas fehlschluege. Der
        // Generate-Pfad konnte es laengst; nur dieser hier nicht.
        val partitioning = op.table.partitioning
        val emitPartitioning = PostgresPartitionClauses.isRenderable(partitioning)
        if (partitioning != null && !emitPartitioning) {
            // Ein `PARTITION BY` ohne Kinder nimmt in PostgreSQL keine Zeile an.
            // Der Generate-Pfad meldet das als E055; hier ist es ein Blocker,
            // weil eine Migration die Tabelle sonst als brauchbar hinterliesse.
            blockChildlessPartitioning(op, ctx, tableName, partitioning.type.name)
            return
        }
        val text = buildString {
            append("CREATE TABLE ").append(ctx.sql.quote(tableName)).append(" (\n")
            append(lines.joinToString(",\n"))
            append("\n)")
            if (emitPartitioning) {
                append(PostgresPartitionClauses.partitionByClause(partitioning!!, ctx.sql::quote))
            }
            append(";")
        }
        ctx.emit(op, text)
        if (emitPartitioning) {
            PostgresPartitionClauses
                .childStatements(tableName, partitioning!!, ctx.sql::quote)
                .forEach { ctx.emit(op, it) }
        }
        for ((colName, col) in op.table.columns.inOrdinalOrder()) warnIfDegradingEnum(op, ctx, colName, col)
        for (index in resolvedIndices) {
            emitIndexOrNote(op, ctx, tableName, index)
        }
    }

    /**
     * Ein FULLTEXT-Index ohne `tsvector`-Spalte hat nichts auszufuehren. Die
     * Operation gilt trotzdem als erledigt; die Begruendung steht in den
     * Diagnosen statt als Kommentar im Anweisungsstrom.
     */
    internal fun emitIndexOrNote(
        op: DiffOperation,
        ctx: PostgresDiffRenderContext,
        table: String,
        index: IndexDefinition,
        hints: DialectExecutionHints = PostgresDiffRenderContext.POSTGRES_TRANSACTIONAL_DDL_HINTS,
    ) {
        val concurrently = ctx.options.postgresContext?.concurrentIndexes == true
        val sql = ctx.sql.createIndexSql(table, index, concurrently)
        if (sql == null) {
            ctx.markRendered(op)
            ctx.addInfoDiagnostic(
                code = "POSTGRES_FULLTEXT_INDEX_WITHOUT_VECTOR",
                operationId = op.id,
                message = "FULLTEXT index '${ctx.sql.effectiveIndexName(table, index)}' has no backing " +
                    "tsvector column; nothing is created for it.",
            )
            return
        }
        if (!concurrently) {
            ctx.emit(op, sql, hints)
            return
        }
        // Ein abgebrochenes `CREATE INDEX CONCURRENTLY` hinterlaesst einen
        // ungueltigen Index unter demselben Namen; ohne das Aufraeumen
        // scheiterte der naechste Lauf daran. Auf einem nicht vorhandenen
        // Index kostet die Anweisung nichts.
        ctx.emit(
            op,
            ctx.sql.dropIndexSql(table, index, concurrently = true, ifExists = true),
            PostgresDiffRenderContext.POSTGRES_CONCURRENT_INDEX_HINTS,
            TransactionScope.NO_TRANSACTION,
        )
        ctx.emit(
            op,
            sql,
            PostgresDiffRenderContext.POSTGRES_CONCURRENT_INDEX_HINTS,
            TransactionScope.NO_TRANSACTION,
        )
        ctx.addInfoDiagnostic(
            code = "POSTGRES_INDEX_CONCURRENTLY",
            operationId = op.id,
            message = "Index '${ctx.sql.effectiveIndexName(table, index)}' is built with CONCURRENTLY, " +
                "outside any transaction. A failed run leaves an INVALID index of that name behind; " +
                "the next run drops it first, or you can drop it yourself with " +
                "DROP INDEX CONCURRENTLY IF EXISTS.",
        )
    }

    fun renderDropTable(op: DiffOperation.DropTable, ctx: PostgresDiffRenderContext) {
        val tableName = op.objectRef.rootName
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            // NOT_REVERSIBLE -- der Dispatcher filtert das vorher; hier bleibt
            // der Pfad total, ohne eine Anweisung zu erfinden.
            ctx.markRendered(op)
            ctx.addInfoDiagnostic(
                code = "POSTGRES_DROP_TABLE_NOT_REVERSIBLE",
                operationId = op.id,
                message = "DropTable is NOT_REVERSIBLE; no inverse statement is rendered.",
            )
            return
        }
        ctx.emit(op, "DROP TABLE ${ctx.sql.quote(tableName)};")
    }

    fun renderAddColumn(op: DiffOperation.AddColumn, ctx: PostgresDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP COLUMN ${ctx.sql.quote(column)};")
            return
        }
        if (op.column.type is NeutralType.Geometry &&
            !ctx.requireExtension(op, POSTGIS_EXTENSION, "geometry column `$table.$column`")
        ) {
            return
        }
        val version = ctx.options.postgresContext?.serverVersion
        PostgresDiffComputedColumnOps.noteDegradedVirtual(op, column, op.column, version, ctx)
        ctx.emit(
            op,
            "ALTER TABLE ${ctx.sql.quote(table)} ADD COLUMN ${ctx.sql.columnLine(column, op.column, version)};",
        )
        warnIfDegradingEnum(op, ctx, column, op.column)
    }

    fun renderDropColumn(op: DiffOperation.DropColumn, ctx: PostgresDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP COLUMN ${ctx.sql.quote(column)};")
    }

    fun renderAlterColumnType(op: DiffOperation.AlterColumnType, ctx: PostgresDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        val targetType = if (ctx.direction == PostgresRenderDirection.UP) op.after else op.before
        if (targetType is NeutralType.Geometry &&
            !ctx.requireExtension(op, POSTGIS_EXTENSION, "geometry type `$table.$column`")
        ) {
            return
        }
        val sourceType = if (ctx.direction == PostgresRenderDirection.UP) op.before else op.after
        // Ein `refType`-Enum verweist auf den nativen Typ; ein Inline-Enum wird
        // zur Textspalte, deren Wertevorrat ein eigener CHECK durchsetzt.
        val typeSql = declaredTypeSql(targetType, ctx)
        // Aendert sich nur der Wertevorrat, bleibt der deklarierte Typ derselbe.
        // Dann gibt es keinen Cast, fuer den eine `USING`-Angabe noetig waere —
        // und nichts, was eine `TYPE`-Anweisung zu tun haette.
        val typeUnchanged = typeSql == declaredTypeSql(sourceType, ctx)
        val usingClause = if (typeUnchanged) {
            ""
        } else {
            val expression = if (ctx.sql.isSafeImplicitCast(op.before, op.after)) {
                null
            } else {
                PostgresUsingOverlayResolver.resolve(op, ctx) ?: return
            }
            expression?.let { " USING $it" }.orEmpty()
        }
        // Der alte Wertevorrat zuerst: solange er steht, weist er Werte ab, die
        // der neue erlaubt — und die Spalte traegt danach zwei Aufzaehlungen,
        // von denen der Vergleich keine mehr eindeutig zuordnen kann.
        dropExistingEnumValueCheck(op, ctx, table, column)
        val newValues = EnumValueCheck.inlineValues(targetType)
        if (!typeUnchanged || newValues == null) {
            ctx.emit(
                op,
                "ALTER TABLE ${ctx.sql.quote(table)} ALTER COLUMN ${ctx.sql.quote(column)} " +
                    "TYPE $typeSql$usingClause;",
            )
        }
        newValues?.let { values ->
            ctx.emit(
                op,
                "ALTER TABLE ${ctx.sql.quote(table)} ADD ${EnumValueCheck.clause(column, values, ctx.sql::quote)};",
            )
        }
        warnIfUnenforceableEnum(op, ctx, "$table.$column", targetType)
    }

    /** Der Typ, wie er in der Spaltendeklaration steht. */
    private fun declaredTypeSql(type: NeutralType, ctx: PostgresDiffRenderContext): String =
        (type as? NeutralType.Enum)?.refType?.let { ctx.sql.quote(it) } ?: ctx.sql.toSql(type)

    /**
     * Loest den CHECK, der den Wertevorrat der Spalte bisher durchsetzt —
     * unter seinem echten Katalognamen ([PostgresDiffRenderContext.enumValueCheckName]).
     * Steht dort keiner, entfaellt die Anweisung.
     */
    private fun dropExistingEnumValueCheck(
        op: DiffOperation.AlterColumnType,
        ctx: PostgresDiffRenderContext,
        table: String,
        column: String,
    ) {
        val name = ctx.enumValueCheckName(table, column) ?: return
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP CONSTRAINT IF EXISTS ${ctx.sql.quote(name)};")
    }


    fun renderAlterColumnNullability(op: DiffOperation.AlterColumnNullability, ctx: PostgresDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        val targetRequired = if (ctx.direction == PostgresRenderDirection.UP) op.after else op.before
        val verb = if (targetRequired) "SET NOT NULL" else "DROP NOT NULL"
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ALTER COLUMN ${ctx.sql.quote(column)} $verb;")
    }

    fun renderAlterColumnDefault(op: DiffOperation.AlterColumnDefault, ctx: PostgresDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        val target = if (ctx.direction == PostgresRenderDirection.UP) op.after else op.before
        val text = if (target == null) {
            "ALTER TABLE ${ctx.sql.quote(table)} ALTER COLUMN ${ctx.sql.quote(column)} DROP DEFAULT;"
        } else {
            "ALTER TABLE ${ctx.sql.quote(table)} ALTER COLUMN ${ctx.sql.quote(column)} " +
                "SET DEFAULT ${ctx.sql.toDefaultSql(target, NeutralType.Text())};"
        }
        ctx.emit(op, text)
    }

    fun renderAddPrimaryKey(op: DiffOperation.AddPrimaryKey, ctx: PostgresDiffRenderContext) {
        val table = op.objectRef.rootName
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            emitDropPkAdvisory(op, ctx)
            ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP CONSTRAINT IF EXISTS ${ctx.sql.quote(table + "_pkey")};")
            return
        }
        val cols = op.columns.joinToString(", ") { ctx.sql.quote(it) }
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD PRIMARY KEY ($cols);")
    }

    /**
     * Plan-2 §F.4 second slice. Renders `ALTER TABLE … RENAME TO …`
     * in the configured direction. The mapper guarantees that a
     * `RenameTable` is only emitted when source and target tables are
     * structurally identical, so the rename is fully reversible.
     */
    fun renderRenameTable(op: DiffOperation.RenameTable, ctx: PostgresDiffRenderContext) {
        val (oldName, newName) = if (ctx.direction == PostgresRenderDirection.UP) {
            op.fromName to op.toName
        } else {
            op.toName to op.fromName
        }
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(oldName)} RENAME TO ${ctx.sql.quote(newName)};")
    }

    /**
     * Plan-2 §F.4 second slice. Renders
     * `ALTER TABLE … RENAME COLUMN … TO …` in the configured direction.
     * `objectRef.path` is `[tableName, toName]` so `path[0]` carries
     * the (rename-stable) table identifier.
     */
    fun renderRenameColumn(op: DiffOperation.RenameColumn, ctx: PostgresDiffRenderContext) {
        val table = op.objectRef.path[0]
        val (oldCol, newCol) = if (ctx.direction == PostgresRenderDirection.UP) {
            op.fromName to op.toName
        } else {
            op.toName to op.fromName
        }
        ctx.emit(
            op,
            "ALTER TABLE ${ctx.sql.quote(table)} RENAME COLUMN ${ctx.sql.quote(oldCol)} " +
                "TO ${ctx.sql.quote(newCol)};",
        )
    }

    fun renderDropPrimaryKey(op: DiffOperation.DropPrimaryKey, ctx: PostgresDiffRenderContext) {
        val table = op.objectRef.rootName
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            val cols = op.columns.joinToString(", ") { ctx.sql.quote(it) }
            ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD PRIMARY KEY ($cols);")
            return
        }
        emitDropPkAdvisory(op, ctx)
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP CONSTRAINT IF EXISTS ${ctx.sql.quote(table + "_pkey")};")
    }

    /**
     * The first-matrix DropPrimaryKey path assumes the auto-named PK
     * constraint follows PostgreSQL's `<table>_pkey` convention. If
     * the source schema explicitly named its PK (e.g. `CONSTRAINT
     * pk_users PRIMARY KEY (...)`), the `IF EXISTS` swallows the
     * mismatch and the PK survives; the next ADD will fail. Surface
     * an advisory diagnostic so operators can verify before running.
     *
     * TODO Phase F: enrich `DropPrimaryKey` with the explicit
     * constraint name (requires extending the schema model to track
     * PK constraint names; see Plan §11.2).
     */
    private fun emitDropPkAdvisory(op: DiffOperation, ctx: PostgresDiffRenderContext) {
        ctx.addInfoDiagnostic(
            code = "PG_PK_NAME_CONVENTION",
            operationId = op.id,
            message = "DropPrimaryKey for `${op.objectRef.rootName}` assumes the auto-named " +
                "constraint `${op.objectRef.rootName}_pkey`. Verify the source schema's actual " +
                "PK constraint name; a non-conventional name will let the IF EXISTS swallow the " +
                "mismatch and leave the PK in place.",
        )
    }

    /**
     * Enum-Degradations-Slice (AP3, W134). A PostgreSQL inline-`values` enum
     * (no `refType`) still renders as bare TEXT in the migrate/diff path — the
     * `refType` path ([PostgresDiffSqlBuilders.columnLine]) is the faithful one.
     * Make the value-enforcement loss loud instead of silent (DoD-Invariante).
     * UP only: both call sites return early for the DOWN direction.
     */
    private fun warnIfDegradingEnum(
        op: DiffOperation,
        ctx: PostgresDiffRenderContext,
        colName: String,
        col: ColumnDefinition,
    ) = warnIfUnenforceableEnum(op, ctx, colName, col.type)

    /**
     * Ein Enum, dessen Wertevorrat nirgends landet: es fuehrt weder eigene
     * Werte (dann setzt sie ein CHECK durch) noch einen `refType` (dann traegt
     * sie ein eigener Typ). Uebrig bleibt eine blanke Textspalte.
     */
    private fun warnIfUnenforceableEnum(
        op: DiffOperation,
        ctx: PostgresDiffRenderContext,
        objectName: String,
        type: NeutralType,
    ) {
        if (type !is NeutralType.Enum || type.refType != null) return
        if (EnumValueCheck.inlineValues(type) != null) return
        ctx.warning(
            op,
            "Enum column `$objectName` carries neither values nor a `ref_type`; it is migrated as bare " +
                "TEXT and nothing is enforced in the target (declare the values, or model the enum as a " +
                "custom type for a native CREATE TYPE).",
            code = "W134",
        )
    }

    private fun TableDefinition.hasGeometryColumns(): Boolean =
        columns.values.any { it.type is NeutralType.Geometry }

    private fun dev.dmigrate.core.model.IndexDefinition.referencesGeometry(table: TableDefinition): Boolean =
        // ADR 0025: shared predicate excludes FULLTEXT — otherwise a FULLTEXT index whose source
        // column is geometry-typed would be flagged unsupported-spatial and block the whole
        // CreateTable instead of reaching the FULLTEXT expansion loop. (This was the missed 6th
        // copy of the geometry/FULLTEXT guard.)
        isSpatialGeometryIndex { table.columns[it]?.type }

    /**
     * F.5 Sub-Slice F: inline `CREATE TABLE` constraint loop emits
     * EXCLUDE clauses verbatim via [PostgresDiffSqlBuilders.constraintLine].
     * Apply the same operator-class whitelist as the standalone
     * AddConstraint path so a `CREATE TABLE` with a custom opclass is
     * blocked instead of silently emitting non-round-trippable DDL.
     * Returns `true` when a block was emitted.
     */
    private fun blockUnsupportedExcludeOpClassInTable(
        op: DiffOperation.CreateTable,
        ctx: PostgresDiffRenderContext,
        tableName: String,
    ): Boolean {
        val offender = op.table.constraints
            .asSequence()
            .filter { it.type == ConstraintType.EXCLUDE }
            .mapNotNull { c ->
                when (val v = ExcludeOperatorClassGate.verdict(c.expression)) {
                    ExcludeOperatorClassGate.Verdict.Allowed -> null
                    is ExcludeOperatorClassGate.Verdict.Blocked -> c to v
                }
            }
            .firstOrNull()
            ?: return false
        val (constraint, verdict) = offender
        ctx.skip(
            op,
            "Operation ${op.id} would create table `$tableName` with EXCLUDE constraint " +
                "'${constraint.name}' using unsupported element '${verdict.offendingElement}': " +
                "${verdict.reason}.",
            code = PlannerBlockerClassifier.EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED_CODE,
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
        return true
    }

    /**
     * Eine Partitionierung ohne Kinder ist in PostgreSQL nicht renderbar: die
     * Tabelle naehme keine Zeile an. Der Generate-Pfad faellt dafuer auf eine
     * flache Tabelle zurueck und meldet `E055` — im Migrationspfad waere das
     * falsch, weil die flache Tabelle danach benutzbar aussieht und Daten
     * aufnimmt, die nie partitioniert werden.
     */
    private fun blockChildlessPartitioning(
        op: DiffOperation,
        ctx: PostgresDiffRenderContext,
        tableName: String,
        strategy: String,
    ) {
        ctx.skip(
            op,
            "Operation ${op.id} would create table `$tableName` with $strategy partitioning but no child " +
                "partitions; PostgreSQL would reject every insert into it. Define the partition boundaries " +
                "or remove the partitioning configuration.",
            code = "PARTITIONING_WITHOUT_CHILDREN",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
    }

    private fun blockSpatialIndex(
        op: DiffOperation,
        ctx: PostgresDiffRenderContext,
        tableName: String,
        indexType: String,
    ) {
        ctx.skip(
            op,
            "Operation ${op.id} would create table `$tableName` with a geometry-column index of type " +
                "$indexType. PostgreSQL spatial indexes require explicit GIST modelling; blocking to avoid " +
                "a partial spatial migration.",
            code = "SPATIAL_INDEX_UNSUPPORTED",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
    }

    private const val POSTGIS_EXTENSION = "postgis"
}
