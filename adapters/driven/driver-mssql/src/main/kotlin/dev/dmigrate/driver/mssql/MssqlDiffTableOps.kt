package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.TransformationNote
import dev.dmigrate.core.model.inOrdinalOrder
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Renderer fuer Tabellen-, Spalten- und Primaerschluessel-Operationen
 * (Sub-Slice 5a). Zustandslos: nimmt den [MssqlDiffRenderContext] und schreibt
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
        if (ctx.direction == MssqlRenderDirection.DOWN) {
            ctx.emit(op, "DROP TABLE ${ctx.sql.quote(table)};")
            return
        }
        // Indizes und Partitionierung gehoeren Sub-Slice 5b bzw. Slice 7. Eine
        // Tabelle OHNE ihre Indizes anzulegen waere kein Teilerfolg, sondern
        // ein stiller Verlust — der naechste Postcompare meldete Drift.
        if (op.table.indices.isNotEmpty()) {
            return blockDeferred(op, ctx, "table '$table' carries ${op.table.indices.size} index/indices", "5b")
        }
        if (op.table.partitioning != null) {
            return blockDeferred(op, ctx, "table '$table' is partitioned", "slice 7")
        }
        val schema = ctx.schemaForDirection()
            ?: return blockMissingSchema(op, ctx, "rendering the columns of '$table'")
        val notes = mutableListOf<TransformationNote>()
        val lines = mutableListOf<String>()
        for ((colName, col) in op.table.columns.inOrdinalOrder()) {
            lines += "    " + ctx.sql.columnDeclaration(table, colName, col, op.table, schema, notes)
        }
        if (op.table.primaryKey.isNotEmpty()) {
            val cols = op.table.primaryKey.joinToString(", ") { ctx.sql.quote(it) }
            lines += "    CONSTRAINT ${ctx.sql.quote(MssqlConstraintNames.primaryKey(table))} PRIMARY KEY ($cols)"
        }
        for (constraint in op.table.constraints.sortedBy { it.name }) {
            val line = ctx.sql.constraintLine(table, constraint)
                ?: return blockUnrenderableConstraint(op, ctx, table, constraint)
            lines += "    $line"
        }
        ctx.emit(op, "CREATE TABLE ${ctx.sql.quote(table)} (\n" + lines.joinToString(",\n") + "\n);")
        ctx.carryOverNotes(op, notes)
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

    fun renderAlterColumnType(op: DiffOperation.AlterColumnType, ctx: MssqlDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        val targetType = if (ctx.direction == MssqlRenderDirection.UP) op.after else op.before
        if (blockIdentityChange(op, ctx, table, column, op.before, op.after)) return
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
        if (blockIdentityColumn(op, ctx, table, column, target.type)) return
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
        ctx.emit(op, ctx.sql.addPrimaryKeySql(table, op.columns))
    }

    fun renderDropPrimaryKey(op: DiffOperation.DropPrimaryKey, ctx: MssqlDiffRenderContext) {
        val table = op.objectRef.rootName
        if (ctx.direction == MssqlRenderDirection.DOWN) {
            ctx.emit(op, ctx.sql.addPrimaryKeySql(table, op.columns))
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

    // ── Gemeinsame Bausteine ─────────────────────

    /**
     * Der Dreischritt: Default loesen, Spalte neu deklarieren, Default zurueck.
     * Ohne den ersten Schritt scheitert `ALTER COLUMN` mit Msg 5074 („The
     * object 'df_…' is dependent on column '…'").
     */
    private fun alterColumnWithDefaultDance(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        table: String,
        column: String,
        type: NeutralType,
        target: ColumnDefinition,
    ) {
        if (blockDependentObjects(op, ctx, table, column, "altered")) return
        ctx.emit(op, ctx.sql.dropDefaultConstraintSql(table, column))
        ctx.emit(op, ctx.sql.alterColumnSql(table, column, type, target.required))
        target.default?.let { ctx.emit(op, ctx.sql.addDefaultConstraintSql(table, column, it, type)) }
    }

    private fun dropColumnStatements(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        table: String,
        column: String,
    ) {
        if (blockDependentObjects(op, ctx, table, column, "dropped")) return
        ctx.emit(op, ctx.sql.dropDefaultConstraintSql(table, column))
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP COLUMN ${ctx.sql.quote(column)};")
    }

    /**
     * IDENTITY laesst sich per `ALTER COLUMN` weder setzen noch entfernen; das
     * verlangt einen Tabellen-Neubau. Der ist ein eigener Renderer (Muster:
     * die SQLite-Rebuild-Sequenz) und gehoert nicht in den Skelett-Sub-Slice —
     * bis dahin wird laut geblockt statt ein `ALTER COLUMN` zu schicken, das
     * die Identity kommentarlos verlieren wuerde.
     */
    private fun blockIdentityChange(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        table: String,
        column: String,
        before: NeutralType,
        after: NeutralType,
    ): Boolean {
        val wasIdentity = (before as? NeutralType.Identifier)?.autoIncrement == true
        val willBeIdentity = (after as? NeutralType.Identifier)?.autoIncrement == true
        if (wasIdentity == willBeIdentity) return false
        ctx.skip(
            op,
            "Operation ${op.id} adds or removes IDENTITY on column '$table.$column'. SQL Server " +
                "cannot do that with ALTER COLUMN — it requires rebuilding the table (create, copy, " +
                "drop, rename). Rendering a plain ALTER COLUMN would silently drop the identity.",
            code = "MSSQL_IDENTITY_CHANGE_NEEDS_REBUILD",
        )
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, setOf(op.id))
        return true
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
     * SQL Server verweigert `ALTER COLUMN` und `DROP COLUMN` mit Msg 5074 nicht
     * nur wegen eines Defaults, sondern ebenso wegen eines abhaengigen CHECK,
     * eines UNIQUE-Constraints oder eines Index auf der Spalte — und d-migrates
     * eigener Generate-Pfad haengt an jede Enum-Spalte ein `ck_…` und an jede
     * `unique: true`-Spalte ein `uq_…`.
     *
     * Die haben Sub-Slice 5b als Eigentuemer. Bis dahin wird geblockt: sie hier
     * mit dynamischem SQL wegzuraeumen hiesse, Objekte zu loeschen, die der
     * Plan gar nicht anfasst.
     */
    private fun blockDependentObjects(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        table: String,
        column: String,
        verb: String,
    ): Boolean {
        val tableDef = ctx.schemaForDirection()?.tables?.get(table) ?: return false
        val dependents = mutableListOf<String>()
        if (tableDef.columns[column]?.unique == true) dependents += "a UNIQUE constraint"
        if (tableDef.constraints.any { column in (it.columns ?: emptyList()) }) dependents += "a table constraint"
        if (tableDef.indices.any { idx -> idx.columns.any { it.name == column } }) dependents += "an index"
        if (dependents.isEmpty()) return false
        ctx.skip(
            op,
            "Column '$table.$column' cannot be $verb while ${dependents.joinToString(" and ")} depends on it; " +
                "SQL Server rejects that with Msg 5074. Dropping and recreating those objects around the change " +
                "is the subject of sub-slice 5b.",
            code = "MSSQL_COLUMN_HAS_DEPENDENT_OBJECTS",
        )
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, setOf(op.id))
        return true
    }

    /** Eine IDENTITY-Spalte laesst sich nicht neu deklarieren; `ALTER COLUMN` waere Msg 156. */
    private fun blockIdentityColumn(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        table: String,
        column: String,
        type: NeutralType,
    ): Boolean {
        if ((type as? NeutralType.Identifier)?.autoIncrement != true) return false
        ctx.skip(
            op,
            "Column '$table.$column' is an IDENTITY column; SQL Server cannot re-declare it with ALTER COLUMN " +
                "(Msg 156). Changing such a column requires rebuilding the table.",
            code = "MSSQL_IDENTITY_CHANGE_NEEDS_REBUILD",
        )
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, setOf(op.id))
        return true
    }

    private fun blockDeferred(op: DiffOperation, ctx: MssqlDiffRenderContext, what: String, owner: String) {
        ctx.skip(
            op,
            "Operation ${op.id} is not rendered because $what, and that is the subject of $owner. Creating the " +
                "table without it would be a silent loss, not a partial success.",
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
