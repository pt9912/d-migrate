package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.MssqlHashPartitionMode
import dev.dmigrate.driver.TransformationNote
import dev.dmigrate.driver.mssqlContext
import dev.dmigrate.core.model.inOrdinalOrder
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Renderer fuer Tabellen-, Spalten- und Primaerschluessel-Operationen
 * Zustandslos: nimmt den [MssqlDiffRenderContext] und schreibt
 * Statements und Diagnosen dort hinein.
 *
 * Drei T-SQL-Eigenheiten praegen fast jede Methode hier:
 *
 * 1. **Defaults sind benannte Constraint-Objekte.** `ALTER COLUMN` und
 *    `DROP COLUMN` scheitern, solange einer an der Spalte haengt — beide
 *    brauchen den Dreischritt bzw. das vorgeschaltete `DROP CONSTRAINT`.
 *    Geloest wird ueber einen Katalog-Nachschlag, nicht ueber die
 *    Namenskonvention: ein fremdes Schema traegt SQL Servers Auto-Namen.
 * 2. **`ALTER COLUMN` ist eine Voll-Neudeklaration.** Fehlt die Nullability,
 *    wird die Spalte still nullable. Die fehlende Haelfte kommt deshalb aus
 *    dem Schema ([MssqlDiffRenderContext.columnFor]) — und wenn sie dort nicht
 *    steht, wird geblockt statt geraten.
 * 3. **Umbenannt wird ueber `sp_rename`**, nicht ueber `ALTER TABLE`.
 */
internal object MssqlDiffTableOps {

    fun renderCreateTable(op: DiffOperation.CreateTable, ctx: MssqlDiffRenderContext) {
        val table = op.objectRef.rootName
        val partitioning = op.table.partitioning
        // Die HASH-Emulation greift vor allem anderen, weil sie die
        // eindeutigen Schluessel veraendert — SQL Server verlangt die
        // Partitionsspalte in jedem davon. Sie steht auch VOR dem DOWN-Zweig:
        // der muss wissen, ob im Vorwaertslauf Function und Scheme entstanden
        // sind, und fuer HASH sagt `isRenderable` das nicht.
        val hashOutcome = resolveHashPartitionPlan(
            table, op.table,
            ctx.options.mssqlContext?.hashPartitionMode ?: MssqlHashPartitionMode.ACTION_REQUIRED,
            ctx.sql::quote, ctx.schemaForDirection(),
        )
        if (ctx.direction == MssqlRenderDirection.DOWN) {
            ctx.emit(op, "DROP TABLE ${ctx.sql.quote(table)};")
            // Function und Scheme sind eigenstaendige Datenbankobjekte, keine
            // Tabellen-Eigenschaft: `DROP TABLE` laesst sie stehen. Ohne diesen
            // Rueckbau scheiterte ein erneuter Vorwaertslauf am schon
            // vorhandenen Namen. Reihenfolge ist erzwungen — das Scheme haengt
            // an der Function, die Tabelle am Scheme.
            val created = hashOutcome is MssqlHashPartitionOutcome.Planned ||
                (partitioning != null && MssqlPartitionDdl.isRenderable(partitioning))
            if (created) {
                ctx.emit(op, "DROP PARTITION SCHEME ${ctx.sql.quote(MssqlPartitionDdl.schemeName(table))};")
                ctx.emit(op, "DROP PARTITION FUNCTION ${ctx.sql.quote(MssqlPartitionDdl.functionName(table))};")
            }
            return
        }
        if (hashOutcome is MssqlHashPartitionOutcome.Refused) {
            return blockDeferred(op, ctx, "table '$table': ${hashOutcome.reason}")
        }
        val hashPlan = (hashOutcome as? MssqlHashPartitionOutcome.Planned)?.plan
        // LIST/HASH ohne Emulation, mehrspaltiger Schluessel oder keine Kinder:
        // SQL Server kann das nicht ausdruecken. Flach anzulegen waere kein
        // Teilerfolg, sondern ein stiller Verlust — Linie wie `E055`.
        if (hashPlan == null && partitioning != null && !MssqlPartitionDdl.isRenderable(partitioning)) {
            return blockDeferred(op, ctx, "table '$table' uses partitioning SQL Server cannot express")
        }
        val schema = ctx.schemaForDirection()
            ?: return blockMissingSchema(op, ctx, "rendering the columns of '$table'")
        val effective = hashPlan?.table ?: op.table
        val notes = mutableListOf<TransformationNote>()
        val lines = mutableListOf<String>()
        for ((colName, col) in effective.columns.inOrdinalOrder()) {
            lines += "    " + ctx.sql.columnDeclaration(table, colName, col, effective, schema, notes)
        }
        // Die berechnete Spalte kennt das neutrale Modell nicht; ihre Zeile
        // kommt direkt aus der Emulation.
        hashPlan?.let { lines += "    " + it.bucketLine }
        if (effective.primaryKey.isNotEmpty()) {
            val cols = effective.primaryKey.joinToString(", ") { ctx.sql.quote(it) }
            val pkClause = MssqlClusteredStorage.primaryKeyClause(effective)
            lines += "    CONSTRAINT ${ctx.sql.quote(MssqlConstraintNames.primaryKey(table))} $pkClause ($cols)"
        }
        // Spaltenstaendige `references` sind dieselben Fremdschluessel, nur in
        // der anderen Modellform. Der Generate-Pfad rendert beide; ohne diese
        // Zeile verloere eine per `migrate` angelegte Tabelle die Beziehung
        // still. Gebaut werden sie mit derselben Funktion, die auch die
        // Abhaengigkeits-Buchhaltung fragt — sonst laufen Rendern und
        // Buchfuehren auseinander.
        val columnForeignKeys = effective.columns.inOrdinalOrder().mapNotNull { (colName, col) ->
            col.references?.let { MssqlDiffObjectOps.columnForeignKey(table, colName, it) }
        }
        // Traegt das Modell denselben Fremdschluessel in beiden Formen unter
        // demselben Namen, darf er nur einmal entstehen (Msg 2714).
        val declaredNames = effective.constraints.mapTo(mutableSetOf()) { it.name }
        val allConstraints = effective.constraints + columnForeignKeys.filterNot { it.name in declaredNames }
        for (constraint in allConstraints.sortedBy { it.name }) {
            val line = ctx.sql.constraintLine(table, constraint, ctx.cascadeGuard())
                ?: return blockUnrenderableConstraint(op, ctx, table, constraint)
            lines += "    $line"
        }
        // Die Indizes VOR dem CREATE TABLE aufloesen: ein nicht renderbarer
        // Index (z. B. Volltext) muss blocken, bevor irgendetwas emittiert ist —
        // sonst laege die Operation in `rendered` UND `skipped`.
        val indexSqls = effective.indices.map {
            MssqlDiffObjectOps.resolveIndexSql(op, ctx, table, it, effective) ?: return
        }
        // Function und Scheme muessen VOR der Tabelle stehen, die sich an sie
        // haengt. Der Schluesseltyp kommt aus derselben Spaltenwiedergabe wie
        // die Spaltenzeile darueber — geraten wird er nicht.
        // `null` heisst: geblockt, es wurde nichts emittiert.
        val onClause = MssqlDiffPartitionOps.emit(op, ctx, table, effective, schema, hashPlan, partitioning)
            ?: return

        ctx.emit(op, "CREATE TABLE ${ctx.sql.quote(table)} (\n" + lines.joinToString(",\n") + "\n)$onClause;")
        ctx.carryOverNotes(op, notes)
        indexSqls.forEach { ctx.emit(op, it) }
    }

    fun renderDropTable(op: DiffOperation.DropTable, ctx: MssqlDiffRenderContext) {
        val table = op.objectRef.rootName
        val text = if (ctx.direction == MssqlRenderDirection.DOWN) {
            "-- DropTable is NOT_REVERSIBLE; refusing to render an inverse."
        } else {
            "DROP TABLE ${ctx.sql.quote(table)};"
        }
        ctx.emit(op, text)
    }

    /**
     * `sp_rename` benennt die Tabelle um, aber **nicht** ihre Constraints und
     * Indizes. Deren Namen folgen danach nicht mehr der Konvention
     * ([MssqlConstraintNames]) — der naechste Diff auf dieselbe Tabelle
     * findet `df_<alterName>_<spalte>` statt `df_<neuerName>_<spalte>`.
     * Das ist eine Aussage wert, kein stiller Nebeneffekt.
     */
    fun renderRenameTable(op: DiffOperation.RenameTable, ctx: MssqlDiffRenderContext) {
        val (from, to) = if (ctx.direction == MssqlRenderDirection.UP) {
            op.fromName to op.toName
        } else {
            op.toName to op.fromName
        }
        ctx.emit(op, ctx.sql.renameSql(from, to), MssqlDiffRenderContext.MSSQL_RENAME_HINTS)
        ctx.addInfoDiagnostic(
            code = "MSSQL_RENAME_KEEPS_CONSTRAINT_NAMES",
            operationId = op.id,
            message = "sp_rename renames table '$from' to '$to' but leaves the names of its " +
                "constraints and indices untouched; they keep referring to '$from' and no longer " +
                "match the naming convention a later migration looks them up by.",
        )
    }

    fun renderAddColumn(op: DiffOperation.AddColumn, ctx: MssqlDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        if (ctx.direction == MssqlRenderDirection.DOWN) {
            dropColumnStatements(op, ctx, table, column)
            return
        }
        val schema = ctx.schemaForDirection()
            ?: return blockMissingSchema(op, ctx, "rendering column '$table.$column'")
        val tableDef = schema.tables[table]
            ?: return blockMissingSchema(op, ctx, "rendering column '$table.$column'")
        val notes = mutableListOf<TransformationNote>()
        // T-SQL: `ADD`, nicht `ADD COLUMN`.
        val declaration = ctx.sql.columnDeclaration(table, column, op.column, tableDef, schema, notes)
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD $declaration;")
        ctx.carryOverNotes(op, notes)
        emitColumnForeignKey(op, ctx, table, column, op.column, tableDef)
    }

    fun renderDropColumn(op: DiffOperation.DropColumn, ctx: MssqlDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        if (ctx.direction == MssqlRenderDirection.DOWN) {
            // DropColumn ist NOT_REVERSIBLE — der Dispatcher filtert das vorher;
            // der Platzhalter haelt den emit-Pfad total.
            ctx.emit(op, "-- DropColumn is NOT_REVERSIBLE; refusing to render an inverse.")
            return
        }
        dropColumnStatements(op, ctx, table, column)
    }

    /**
     * IDENTITY-Spalten kommen hier nicht an: der Dispatcher schickt sie in den
     * Tabellen-Neubau ([MssqlRebuildPlanner.requiresRebuild]), weil `ALTER
     * COLUMN` sie weder setzen noch entfernen noch neu deklarieren kann.
     */
    fun renderAlterColumnType(op: DiffOperation.AlterColumnType, ctx: MssqlDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        val targetType = if (ctx.direction == MssqlRenderDirection.UP) op.after else op.before
        val target = ctx.columnFor(table, column)
            ?: return blockMissingColumn(op, ctx, table, column, "its nullability")
        alterColumnWithDefaultDance(op, ctx, table, column, targetType, target)
    }

    fun renderAlterColumnNullability(op: DiffOperation.AlterColumnNullability, ctx: MssqlDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        val targetRequired = if (ctx.direction == MssqlRenderDirection.UP) op.after else op.before
        val target = ctx.columnFor(table, column)
            ?: return blockMissingColumn(op, ctx, table, column, "its column type")
        // Ohne diese Wache entstuende `ALTER COLUMN [id] INT IDENTITY(1,1) NOT NULL`
        // — ungueltiges T-SQL (Msg 156). IDENTITY gehoert in die Spalten-Anlage,
        // nicht in eine Neudeklaration.
        if (blockIdentityColumn(op, ctx, table, column, target)) return
        alterColumnWithDefaultDance(
            op, ctx, table, column, target.type, target.copy(required = targetRequired),
        )
    }

    fun renderAlterColumnDefault(op: DiffOperation.AlterColumnDefault, ctx: MssqlDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        val target = if (ctx.direction == MssqlRenderDirection.UP) op.after else op.before
        // Erst aufloesen, DANN emittieren: ein Blocker nach dem ersten emit()
        // legte die Operation in `rendered` UND `skipped`, und die beiden
        // Mengen muessen disjunkt sein — MigrationDdlResult prueft das mit
        // require() und der Renderer flaege mit IllegalArgumentException.
        val type = if (target == null) {
            null
        } else {
            // Der Spaltentyp entscheidet ueber die Literal-Form (z. B. tz-Defaults);
            // ohne ihn waere `N'…'` vs. Zahl geraten.
            ctx.columnFor(table, column)?.type
                ?: return blockMissingColumn(op, ctx, table, column, "its column type")
        }
        ctx.emit(op, ctx.sql.dropDefaultConstraintSql(table, column))
        if (target != null && type != null) {
            ctx.emit(op, ctx.sql.addDefaultConstraintSql(table, column, target, type))
        }
    }

    fun renderAddPrimaryKey(op: DiffOperation.AddPrimaryKey, ctx: MssqlDiffRenderContext) {
        val table = op.objectRef.rootName
        if (ctx.direction == MssqlRenderDirection.DOWN) {
            ctx.emit(op, ctx.sql.dropPrimaryKeySql(table))
            return
        }
        ctx.emit(op, ctx.sql.addPrimaryKeySql(table, op.columns, indicesOf(table, ctx)))
    }

    /**
     * Die Indizes der Tabelle in der gerenderten Richtung. Der Primaerschluessel
     * braucht sie, um zu wissen, ob ihm die Ablage zusteht; fehlt das Schema,
     * bleibt es beim Default clustered -- die Richtung, in die SQL Server ohne
     * Angabe ohnehin geht.
     */
    private fun indicesOf(table: String, ctx: MssqlDiffRenderContext): List<IndexDefinition> =
        ctx.schemaForDirection()?.tables?.get(table)?.indices.orEmpty()

    fun renderDropPrimaryKey(op: DiffOperation.DropPrimaryKey, ctx: MssqlDiffRenderContext) {
        val table = op.objectRef.rootName
        if (ctx.direction == MssqlRenderDirection.DOWN) {
            ctx.emit(op, ctx.sql.addPrimaryKeySql(table, op.columns, indicesOf(table, ctx)))
            // Aufwaerts wird der echte Name im Katalog nachgeschlagen, abwaerts
            // kann er nicht rekonstruiert werden — das neutrale Modell traegt
            // ihn nicht. Der Rollback stellt den Schluessel also her, aber
            // unter d-migrates Namen.
            ctx.addInfoDiagnostic(
                code = "MSSQL_PK_NAME_NOT_RESTORED",
                operationId = op.id,
                message = "The rollback re-creates the primary key of '$table' as " +
                    "'${MssqlConstraintNames.primaryKey(table)}'. If the original key had a different name, " +
                    "the restored schema matches structurally but not by constraint name.",
            )
            return
        }
        ctx.emit(op, ctx.sql.dropPrimaryKeySql(table))
    }

    /** `objectRef.path` ist `[tabelle, neuerName]`; `path[0]` ist rename-stabil. */
    fun renderRenameColumn(op: DiffOperation.RenameColumn, ctx: MssqlDiffRenderContext) {
        val table = op.objectRef.path[0]
        val (from, to) = if (ctx.direction == MssqlRenderDirection.UP) {
            op.fromName to op.toName
        } else {
            op.toName to op.fromName
        }
        ctx.emit(
            op,
            ctx.sql.renameSql("$table.$from", to, objectType = "COLUMN"),
            MssqlDiffRenderContext.MSSQL_RENAME_HINTS,
        )
    }

    /**
     * Der Generate-Pfad macht aus einem `references` an der Spalte
     * `fk_<tabelle>_<spalte>`; der Diff-Pfad kann das weder beim Anlegen der
     * Tabelle noch beim Hinzufuegen der Spalte (offener Punkt
     * `mssql-column-level-foreign-keys.md`). Bis dahin wenigstens laut: eine
     * Beziehung, die nach der Migration fehlt, darf kein Ergebnis sein, das
     * wie voller Erfolg aussieht.
     *
     * Traegt das Modell dieselbe Beziehung ZUSAETZLICH in der
     * Constraint-Liste, entsteht sie ueber deren eigene Operation — dann fehlt
     * nichts und die Warnung waere ein Fehlalarm.
     */
    /**
     * Der Fremdschluessel einer spaltenstaendigen `references`, als eigenes
     * `ALTER TABLE`.
     *
     * Nur wenn die Constraint-Liste dieselbe Beziehung nicht schon fuehrt: dann
     * entsteht sie ueber ihre eigene Operation, und ein zweites Anlegen
     * scheiterte am belegten Namen (Msg 2714).
     */
    private fun emitColumnForeignKey(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        table: String,
        column: String,
        col: ColumnDefinition,
        tableDef: TableDefinition,
    ) {
        val ref = col.references ?: return
        val constraint = MssqlDiffObjectOps.columnForeignKey(table, column, ref)
        if (tableDef.constraints.any { it.name == constraint.name }) return
        val alsoDeclared = tableDef.constraints.any {
            it.type == ConstraintType.FOREIGN_KEY &&
                column in (it.columns ?: emptyList()) &&
                it.references?.table == ref.table
        }
        if (alsoDeclared) return
        val line = ctx.sql.constraintLine(table, constraint, ctx.cascadeGuard()) ?: return
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD $line;")
    }

    // ── Gemeinsame Bausteine ─────────────────────

    /**
     * Der Dreischritt: Default loesen, Spalte neu deklarieren, Default zurueck.
     * Ohne den ersten Schritt scheitert `ALTER COLUMN` mit Msg 5074 („The
     * object 'df_…' is dependent on column '…'").
     */
    internal fun alterColumnWithDefaultDance(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        table: String,
        column: String,
        type: NeutralType,
        target: ColumnDefinition,
    ) {
        columnChangeStatements(op, ctx, table, column, type, target)?.forEach { ctx.emit(op, it) }
    }

    /**
     * Der Tanz als **Liste**, nicht als Emission: Abhaengigkeiten abraeumen,
     * Spalte neu deklarieren, alles zurueck. `null`, wenn etwas davon nicht
     * renderbar ist — dann ist die Operation bereits als Blocker vermerkt.
     *
     * Warum getrennt von [alterColumnWithDefaultDance]: `AlterCustomType`
     * faechert auf MEHRERE Spalten auf. Emittiert der Tanz selbst, ist die
     * Operation nach der ersten Spalte `rendered` — blockt dann die zweite,
     * liegt sie in `rendered` UND `skipped`, und `MigrationDdlResult` bricht
     * das mit einer Exception ab. Die Regel „erst aufloesen, dann emittieren"
     * gilt fuer die ganze OPERATION, nicht je Aufruf.
     */
    internal fun columnChangeStatements(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        table: String,
        column: String,
        type: NeutralType,
        target: ColumnDefinition,
    ): List<String>? {
        val deps = dependenciesFor(ctx, table, column, forDrop = false)
        val recreates = MssqlDiffColumnDependencies.recreateStatements(op, ctx, deps) ?: return null
        val checks = generatedChecks(ctx, table, column, target, ctx.schemaForDirection())
        val bearingChecks = generatedChecks(
            ctx, table, column, ctx.schemaBeforeChange()?.tables?.get(table)?.columns?.get(column),
            ctx.schemaBeforeChange(),
        )
        val out = mutableListOf<String>()
        if (MssqlDiffColumnDependencies.carriesFullText(deps)) return null
        out += MssqlDiffColumnDependencies.dropStatements(ctx, deps)
        if (bearingChecks.isNotEmpty() || checks.isNotEmpty()) {
            out += ctx.sql.dropConstraintSql(table, MssqlConstraintNames.check(table, column))
        }
        out += ctx.sql.dropDefaultConstraintSql(table, column)
        // Nicht `toSql(type)`: der Mapper kennt nur den neutralen Typ und macht
        // aus einem Enum `NVARCHAR(MAX)`. Der Spalten-Helfer kennt die SPALTE
        // und liefert `NVARCHAR(<laengster Wert>)` — dieselbe Breite, die
        // `schema generate` schreibt, und anders als MAX schluesselfaehig.
        val sqlType = columnSqlType(ctx, table, column, target.copy(type = type)) ?: ctx.sql.toSql(type)
        out += ctx.sql.alterColumnSql(table, column, sqlType, target.required)
        target.default?.let { out += ctx.sql.addDefaultConstraintSql(table, column, it, type) }
        out += checks
        out += recreates
        return out
    }

    /**
     * Der CHECK, den der Generate-Pfad an eine Enum- oder Domain-Spalte
     * haengt (`ck_<tabelle>_<spalte>`).
     *
     * Er steht in KEINER Modell-Liste — er entsteht erst beim Rendern aus dem
     * Spaltentyp. Der Abhaengigkeits-Tanz konnte ihn deshalb nicht sehen und
     * liess ihn stehen; `ALTER COLUMN` scheiterte dann an Msg 5074, genau wie
     * bei einem Default. Abgeraeumt wird er ueber die Konvention (mit
     * `IF EXISTS`, er muss nicht existieren), wiederhergestellt wird der des
     * ZIELzustands — die Werte koennen sich geaendert haben.
     */
    /**
     * Der SQL-Typ, in dem diese Spalte landet — so, wie der Generate-Pfad ihn
     * schreibt.
     *
     * Massgeblich ist der Typ der OPERATION, nicht der der Schema-Spalte: die
     * Operation sagt, wohin geaendert wird. Die Spalte liefert den Kontext, den
     * der neutrale Typ nicht hat — die Enum-Werte hinter einem `refType`, die
     * Basis einer Domain.
     */
    private fun columnSqlType(
        ctx: MssqlDiffRenderContext,
        table: String,
        column: String,
        col: ColumnDefinition,
    ): String? {
        val schema = ctx.schemaForDirection() ?: return null
        val tableDef = schema.tables[table] ?: return null
        return ctx.sql.renderColumn(table, column, col, tableDef, schema, mutableListOf()).sqlType
    }

    private fun generatedChecks(
        ctx: MssqlDiffRenderContext,
        table: String,
        column: String,
        col: ColumnDefinition?,
        schema: SchemaDefinition?,
    ): List<String> {
        if (col == null || schema == null) return emptyList()
        val tableDef = schema.tables[table] ?: return emptyList()
        val rendering = ctx.sql.renderColumn(table, column, col, tableDef, schema, mutableListOf())
        return rendering.objects
            .filter { it.kind == MssqlColumnObject.Kind.CHECK }
            .map { ctx.sql.columnObjectStatement(table, column, it) }
    }

    private fun dropColumnStatements(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        table: String,
        column: String,
    ) {
        // Nur abraeumen, nicht wieder anlegen: die Spalte, auf der die Objekte
        // hingen, gibt es danach nicht mehr.
        val deps = dependenciesFor(ctx, table, column, forDrop = true)
        if (MssqlDiffColumnDependencies.carriesFullText(deps)) {
            MssqlDiffObjectOps.blockFullTextInTransaction(op, ctx, deps.table, "drop")
            return
        }
        MssqlDiffColumnDependencies.dropStatements(ctx, deps).forEach { ctx.emit(op, it) }
        // Wie beim ALTER COLUMN: der generierte CHECK haengt an der Spalte und
        // steht in keiner Modell-Liste. `DROP COLUMN` scheitert an ihm ebenso
        // (Msg 5074) — wiederhergestellt wird hier nichts.
        val bearing = ctx.schemaOppositeOfDirection()
        if (generatedChecks(ctx, table, column, bearing?.tables?.get(table)?.columns?.get(column), bearing)
                .isNotEmpty()
        ) {
            ctx.emit(op, ctx.sql.dropConstraintSql(table, MssqlConstraintNames.check(table, column)))
        }
        ctx.emit(op, ctx.sql.dropDefaultConstraintSql(table, column))
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP COLUMN ${ctx.sql.quote(column)};")
    }

    private fun blockMissingColumn(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        table: String,
        column: String,
        missing: String,
    ) {
        ctx.skip(
            op,
            "Operation ${op.id} needs $missing of column '$table.$column', but the column is not in " +
                "the schema for this rendering direction. ALTER COLUMN in T-SQL re-declares the whole " +
                "column, so rendering without that value would silently change it.",
            code = "MSSQL_COLUMN_NOT_IN_SCHEMA",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
    }

    /**
     * Die Abhaengigkeiten der Spalte. `bearing` ist das Schema, das die Spalte
     * noch BESCHREIBT — bei einem Drop also das Gegenstueck zur Renderrichtung,
     * weil die Zielrichtung sie gerade nicht mehr enthaelt.
     */
    private fun dependenciesFor(
        ctx: MssqlDiffRenderContext,
        table: String,
        column: String,
        forDrop: Boolean,
    ): MssqlDiffColumnDependencies.ColumnDependencies = MssqlDiffColumnDependencies.of(
        table = table,
        column = column,
        bearing = if (forDrop) ctx.schemaOppositeOfDirection() else ctx.schemaBeforeChange(),
        surviving = if (forDrop) null else ctx.schemaForDirection(),
        // Kein Schema fuehrt sie an dieser Stelle, aber sie stehen da: ein
        // Fremdschluessel, den eine schon gerenderte Operation angelegt hat.
        // Uebersieht ihn der Tanz, scheitert `ALTER COLUMN` an Msg 5074.
        alsoPresent = ctx.inboundForeignKeysCreatedSoFar(table, column),
    )

    /**
     * Die Nullability einer IDENTITY-Spalte zu aendern hat kein Ziel, das SQL
     * Server kennt: eine IDENTITY-Spalte ist immer `NOT NULL`. Die Aenderung
     * kann also nur nach nullable zeigen — ein Zustand, den weder `ALTER
     * COLUMN` (Msg 156) noch der Tabellen-Neubau herstellen kann, weil auch
     * dessen `CREATE TABLE` die Spalte als `IDENTITY(1,1) NOT NULL` schreiben
     * muesste. Ein Neubau wuerde die Abweichung also still verschlucken statt
     * sie zu erfuellen; deshalb bleibt es hier bei einem Blocker.
     */
    private fun blockIdentityColumn(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        table: String,
        column: String,
        col: ColumnDefinition,
    ): Boolean {
        // Die Spalte, nicht der Typ: IDENTITY kommt ebenso aus `generation`,
        // und ein `ALTER COLUMN` darauf ist genauso unzulaessig.
        if (!MssqlColumnConstraintHelper.isIdentity(col.type, col)) return false
        ctx.skip(
            op,
            "Column '$table.$column' is an IDENTITY column, and SQL Server requires those to be NOT NULL. " +
                "The requested nullability cannot be reached — neither by ALTER COLUMN (Msg 156) nor by " +
                "rebuilding the table, whose CREATE TABLE would declare the column NOT NULL again.",
            code = "MSSQL_IDENTITY_COLUMN_NOT_NULLABLE",
        )
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, setOf(op.id))
        return true
    }

    internal fun blockDeferred(op: DiffOperation, ctx: MssqlDiffRenderContext, what: String) {
        ctx.skip(
            op,
            "Operation ${op.id} is not rendered because $what, and the migrate path does not render that for " +
                "SQL Server. Creating the table without it would be a silent loss, not a partial success.",
            code = "DIALECT_UNSUPPORTED_OPERATION",
        )
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, setOf(op.id))
    }

    private fun blockUnrenderableConstraint(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        table: String,
        constraint: ConstraintDefinition,
    ) {
        ctx.skip(
            op,
            "Constraint '${constraint.name}' (${constraint.type}) on '$table' cannot be rendered in T-SQL — " +
                "either the dialect has no equivalent (EXCLUDE) or the definition is incomplete. Emitting the " +
                "table without it would drop the guarantee silently.",
            code = "DIALECT_UNSUPPORTED_OPERATION",
        )
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, setOf(op.id))
    }

    private fun blockMissingSchema(op: DiffOperation, ctx: MssqlDiffRenderContext, what: String) {
        ctx.skip(
            op,
            "Operation ${op.id} needs the schema for $what, but the DiffResult carries none for this direction.",
            code = "MSSQL_COLUMN_NOT_IN_SCHEMA",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
    }
}
