package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.inOrdinalOrder
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.ManualActionRequired
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.TransformationNote
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Per-operation renderers for table / column / primary-key DDL (Sub-Slice
 * 5a). Stateless: takes an [OracleDiffRenderContext] and writes statements /
 * diagnostics back into it.
 *
 * `CreateTable` reuses [OracleColumnConstraintHelper] and
 * [OracleIndexDdlBuilder] directly -- the same code the Generate path
 * (`OracleDdlGenerator.generateTable`) uses -- so a table created via
 * `schema migrate` renders byte-identical column/constraint/index DDL to one
 * created via `schema generate`. No `deferredFks`/`deferredConstraints`:
 * those exist for the full-schema generate path's circular-FK handling
 * across MULTIPLE tables in one apply; a single `CreateTable` Diff op has no
 * analogous multi-table concern (the planner orders operations, not this
 * renderer).
 *
 * Everything here builds on facts verified live against
 * `gvenzl/oracle-free:23-slim-faststart` (2026-09-06, Sub-Slice 5a probes):
 * constraint AND index names survive `RENAME TO` unchanged; `DROP COLUMN`
 * needs no `CASCADE CONSTRAINTS` even when UNIQUE/CHECK/FK constraints
 * reference the column; `DROP PRIMARY KEY` needs no constraint name;
 * identity can be dropped in place but never added to an existing column
 * (`ORA-30673`, see [renderAlterColumnType]).
 */
internal object OracleDiffTableOps {

    private val typeMapper = OracleTypeMapper()
    private fun quoteIdentifier(name: String): String = SqlIdentifiers.quoteIdentifier(name, DatabaseDialect.ORACLE)
    private val columnHelper = OracleColumnConstraintHelper(quoteIdentifier = ::quoteIdentifier, typeMapper = typeMapper)
    private val indexBuilder = OracleIndexDdlBuilder(quoteIdentifier = ::quoteIdentifier)

    fun renderCreateTable(op: DiffOperation.CreateTable, ctx: OracleDiffRenderContext) {
        val tableName = op.objectRef.rootName
        if (ctx.direction == OracleRenderDirection.DOWN) {
            ctx.emit(op, "DROP TABLE ${ctx.sql.quote(tableName)};")
            return
        }
        val schema = ctx.schemaForDirection()
            ?: return blockMissingSchema(op, ctx, "rendering the columns of '$tableName'")
        val table = op.table
        geometryColumns(table.columns)
            .takeIf { it.isNotEmpty() }
            ?.let { return blockSpatial(op, ctx, tableName, it) }
        val notes = mutableListOf<TransformationNote>()
        val unkeyableColumns = table.columns.filterValues { typeMapper.isUnkeyable(it.type) }.keys
        val lines = mutableListOf<String>()

        for ((colName, col) in table.columns.inOrdinalOrder()) {
            lines += columnHelper.generateColumnSql(tableName, colName, col, schema, notes)
        }
        for ((colName, col) in table.columns.inOrdinalOrder()) {
            val ref = col.references ?: continue
            lines += columnHelper.buildForeignKeyClause(
                "fk_${tableName}_$colName", listOf(colName), ref.table, listOf(ref.column), ref.onDelete, notes,
            )
        }
        for (constraint in table.constraints.sortedBy { it.name }) {
            columnHelper.generateConstraintClause(tableName, constraint, unkeyableColumns, notes)?.let { lines += it }
        }
        if (table.primaryKey.isNotEmpty()) {
            val lobKeys = table.primaryKey.filter { it in unkeyableColumns }
            if (lobKeys.isNotEmpty()) {
                notes += columnHelper.unkeyableKeyNote(tableName, "pk_$tableName", "PRIMARY KEY", lobKeys)
            } else {
                val pkCols = table.primaryKey.joinToString(", ") { ctx.sql.quote(it) }
                lines += "CONSTRAINT ${ctx.sql.quote("pk_$tableName")} PRIMARY KEY ($pkCols)"
            }
        }
        // Der Generate-Pfad legt die Tabelle hier flach an und meldet E055 --
        // dort bekommt der Anwender ein Skript, das er vor dem Ausfuehren
        // liest. Der Migrate-Pfad fuehrt aus, und eine grosse Tabelle still
        // unpartitioniert anzulegen ist keine Notiz wert, sondern ein
        // Blocker. PostgreSQLs Diff-Pfad blockt an derselben Stelle, und das
        // Anwenderhandbuch sagt genau das zu.
        table.partitioning?.let {
            return blockPartitioning(op, ctx, tableName, it.type.name)
        }
        val sql = buildString {
            append("CREATE TABLE ${ctx.sql.quote(tableName)} (\n")
            append(lines.joinToString(",\n") { "    $it" })
            append("\n);")
        }
        ctx.emit(op, sql)
        ctx.carryOverNotes(op, notes)
        for (index in table.indices) {
            val stmt = indexBuilder.render(tableName, table, index, unkeyableColumns)
            if (stmt.sql.isNotBlank()) ctx.emit(op, stmt.sql)
            ctx.carryOverNotes(op, stmt.notes)
        }
    }

    /**
     * Die Down-Richtung blockt, statt einen SQL-Kommentar als Platzhalter zu
     * emittieren. Ueber den Planner ist sie ohnehin unerreichbar (`DropTable`
     * ist `NOT_REVERSIBLE`, der Dispatcher springt vorher ab) -- aber ein
     * handgebautes `DiffResult` koennte sie erreichen, und dann waere ein
     * Kommentar die falsche Antwort: `JdbcMigrationStatementExecutor` fuehrt
     * JEDE Anweisung aus, und Oracle lehnt eine reine Kommentar-Anweisung ab
     * (`ORA-00900`, gemessen). Siehe
     * `docs/planning/open/diff-comment-as-statement.md`.
     */
    fun renderDropTable(op: DiffOperation.DropTable, ctx: OracleDiffRenderContext) {
        if (ctx.direction == OracleRenderDirection.DOWN) {
            ctx.skip(op, "Operation ${op.id} drops a table; there is no inverse to render.")
            ctx.addBlocker(MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE, setOf(op.id))
            return
        }
        ctx.emit(op, "DROP TABLE ${ctx.sql.quote(op.objectRef.rootName)};")
    }

    /**
     * Live bestaetigt (2026-09-06): Constraint- UND Indexnamen bleiben unter
     * `RENAME TO` unveraendert (gemessen: `PK_T_OLD` bzw. `IDX_P6_OLD_TAG`
     * ueberleben die Umbenennung ihrer Tabelle). Es gibt hier also nichts
     * nachzuziehen -- anders als bei MSSQLs `sp_rename`.
     *
     * ACHTUNG fuer Sub-Slice 5b: genau weil die Namen stehen bleiben, driften
     * sie von der d-migrate-Namenskonvention ab, die den Tabellennamen
     * einbaut (`pk_`/`uq_`/`ck_`/`fk_`/`idx_<tabelle>_...`). Nach einem
     * Rename traegt das Objekt den ALTEN Tabellennamen, waehrend ein spaeter
     * gerenderter `DROP CONSTRAINT`/`DROP INDEX` den Namen aus dem AKTUELLEN
     * Tabellennamen bilden wuerde -- und ins Leere liefe. 5a ist davon nicht
     * betroffen: sein einziger namensabhaengiger Pfad (Primaerschluessel)
     * nutzt Oracles namenloses `DROP PRIMARY KEY`.
     */
    fun renderRenameTable(op: DiffOperation.RenameTable, ctx: OracleDiffRenderContext) {
        val (oldName, newName) = if (ctx.direction == OracleRenderDirection.UP) {
            op.fromName to op.toName
        } else {
            op.toName to op.fromName
        }
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(oldName)} RENAME TO ${ctx.sql.quote(newName)};")
    }

    fun renderAddColumn(op: DiffOperation.AddColumn, ctx: OracleDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        if (ctx.direction == OracleRenderDirection.DOWN) {
            ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP COLUMN ${ctx.sql.quote(column)};")
            return
        }
        if (op.column.type is NeutralType.Geometry) return blockSpatial(op, ctx, table, listOf(column))
        val schema = ctx.schemaForDirection()
            ?: return blockMissingSchema(op, ctx, "rendering column '$table.$column'")
        val notes = mutableListOf<TransformationNote>()
        val decl = columnHelper.generateColumnSql(table, column, op.column, schema, notes)
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD ($decl);")
        ctx.carryOverNotes(op, notes)
    }

    /** Live bestaetigt (2026-09-06): kein `CASCADE CONSTRAINTS` noetig, auch mit UNIQUE/CHECK/FK auf der Spalte. */
    fun renderDropColumn(op: DiffOperation.DropColumn, ctx: OracleDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP COLUMN ${ctx.sql.quote(column)};")
    }

    fun renderRenameColumn(op: DiffOperation.RenameColumn, ctx: OracleDiffRenderContext) {
        val table = op.objectRef.path[0]
        val (oldCol, newCol) = if (ctx.direction == OracleRenderDirection.UP) {
            op.fromName to op.toName
        } else {
            op.toName to op.fromName
        }
        ctx.emit(
            op,
            "ALTER TABLE ${ctx.sql.quote(table)} RENAME COLUMN ${ctx.sql.quote(oldCol)} TO ${ctx.sql.quote(newCol)};",
        )
    }

    /**
     * Live bestaetigt (2026-09-06, Sub-Slice-5a-Sonden): `ALTER TABLE ...
     * MODIFY <col> <typ>` aendert Praezision UND Generation-Modus einer
     * BESTEHENDEN Identity-Spalte in-place, ohne die Spalte voll neu zu
     * deklarieren -- anders als MSSQLs `ALTER COLUMN` braucht Oracle keinen
     * Schema-Lookup, um NOT NULL/Identity zu bewahren.
     *
     * Die beiden Identity-UEBERGAENGE sind davon zu trennen und verhalten
     * sich gegensaetzlich (beide live gemessen):
     * - **Identity entfernen** geht: `MODIFY <col> DROP IDENTITY` (danach
     *   nimmt die Spalte explizite Werte an, verifiziert).
     * - **Identity hinzufuegen** geht NICHT: `MODIFY <col> GENERATED ALWAYS
     *   AS IDENTITY` scheitert auf jeder nicht bereits identity-tragenden
     *   Spalte mit `ORA-30673` -- unabhaengig davon, ob die Tabelle leer,
     *   gefuellt oder die Spalte NULL-behaftet ist. Oracle kann eine
     *   gewoehnliche Spalte nicht nachtraeglich zur Identity-Spalte machen;
     *   dafuer braeuchte es einen Tabellen-Neubau (nicht in Slice 5,
     *   `open/oracle-add-identity-requires-rebuild.md`). Deshalb blockt
     *   dieser Fall mit benanntem Grund, statt DDL zu emittieren, die etwas
     *   anderes tut als die Operation behauptet.
     */
    fun renderAlterColumnType(op: DiffOperation.AlterColumnType, ctx: OracleDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        val up = ctx.direction == OracleRenderDirection.UP
        val sourceType = if (up) op.before else op.after
        val targetType = if (up) op.after else op.before
        if (targetType is NeutralType.Geometry || sourceType is NeutralType.Geometry) {
            return blockSpatial(op, ctx, table, listOf(column))
        }
        if (isIdentity(targetType) && !isIdentity(sourceType)) {
            return blockAddIdentity(op, ctx, table, column)
        }
        // Identity zuerst loesen: die anschliessende Typaenderung laeuft dann
        // auf einer gewoehnlichen Spalte. Zwei Anweisungen fuer eine
        // Operation sind vertraglich zulaessig (MigrationDdlStatement-KDoc).
        if (isIdentity(sourceType) && !isIdentity(targetType)) {
            ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} MODIFY ${ctx.sql.quote(column)} DROP IDENTITY;")
        }
        // Wie im PostgreSQL-Diff-Pfad: eine werte-basierte Enum-Spalte rendert
        // hier ungebunden (VARCHAR2(4000)), OHNE den CHECK, den ein frisches
        // CreateTable via OracleColumnConstraintHelper bekaeme -- der CHECK ist
        // eine eigene Operation (AddConstraint, Sub-Slice 5b), keine Typfrage.
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} MODIFY ${ctx.sql.quote(column)} ${ctx.sql.toSql(targetType)};")
        // Der Waechter fragt NICHT nach `values`: `OracleTypeMapper.toSql`
        // rendert JEDE Enum als ungebundenes VARCHAR2(4000) -- eine
        // `refType`-Enum degradiert genauso wie eine wertebasierte (Oracle hat
        // keinen nativen Enum-Typ, anders als PostgreSQL, wo nur der
        // inline-values-Fall verliert).
        if (ctx.direction == OracleRenderDirection.UP && targetType is NeutralType.Enum) {
            ctx.warning(
                op,
                "Column `$table.$column` is altered to an enum type but rendered as unbounded VARCHAR2(4000); " +
                    "the declared values are not enforced (a bounded, CHECK-constrained enum column is only " +
                    "produced by CreateTable — add the CHECK constraint separately if value enforcement is required).",
                code = "W134",
            )
        }
    }

    fun renderAlterColumnNullability(op: DiffOperation.AlterColumnNullability, ctx: OracleDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        val targetRequired = if (ctx.direction == OracleRenderDirection.UP) op.after else op.before
        val verb = if (targetRequired) "NOT NULL" else "NULL"
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} MODIFY ${ctx.sql.quote(column)} $verb;")
    }

    /** DEFAULT ist Spalteneigenschaft (wie PostgreSQL) -- kein Katalog-Lookup wie bei MSSQLs benanntem Constraint. */
    fun renderAlterColumnDefault(op: DiffOperation.AlterColumnDefault, ctx: OracleDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        val target = if (ctx.direction == OracleRenderDirection.UP) op.after else op.before
        if (target == null) {
            ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} MODIFY ${ctx.sql.quote(column)} DEFAULT NULL;")
            return
        }
        // Der Spaltentyp entscheidet ueber die Default-Form (z. B. SYSDATE vs.
        // SYSTIMESTAMP fuer current_timestamp) -- ohne ihn waere geraten.
        val type = ctx.columnFor(table, column)?.type
            ?: return blockMissingColumn(op, ctx, table, column, "its column type")
        ctx.emit(
            op,
            "ALTER TABLE ${ctx.sql.quote(table)} MODIFY ${ctx.sql.quote(column)} DEFAULT ${ctx.sql.toDefaultSql(target, type)};",
        )
    }

    fun renderAddPrimaryKey(op: DiffOperation.AddPrimaryKey, ctx: OracleDiffRenderContext) {
        val table = op.objectRef.rootName
        if (ctx.direction == OracleRenderDirection.DOWN) {
            ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP PRIMARY KEY;")
            return
        }
        val cols = op.columns.joinToString(", ") { ctx.sql.quote(it) }
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD CONSTRAINT ${ctx.sql.quote("pk_$table")} PRIMARY KEY ($cols);")
    }

    /** Live bestaetigt (2026-09-06): `DROP PRIMARY KEY` braucht keinen Constraint-Namen. */
    fun renderDropPrimaryKey(op: DiffOperation.DropPrimaryKey, ctx: OracleDiffRenderContext) {
        val table = op.objectRef.rootName
        if (ctx.direction == OracleRenderDirection.DOWN) {
            val cols = op.columns.joinToString(", ") { ctx.sql.quote(it) }
            ctx.emit(
                op,
                "ALTER TABLE ${ctx.sql.quote(table)} ADD CONSTRAINT ${ctx.sql.quote("pk_$table")} PRIMARY KEY ($cols);",
            )
            return
        }
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP PRIMARY KEY;")
    }

    /**
     * Identity, soweit der neutrale TYP sie traegt. `AlterColumnType` fuehrt
     * nur `before`/`after` als [NeutralType] -- ein `ColumnGeneration.Identity`
     * auf der [dev.dmigrate.core.model.ColumnDefinition] ist nicht Teil dieser
     * Operation und deshalb hier auch nicht sichtbar.
     */
    private fun isIdentity(type: NeutralType): Boolean = type is NeutralType.Identifier && type.autoIncrement

    /**
     * Oracle-Partitionierung ist nicht gescoped (Slice 7). Der Generate-Pfad
     * rendert die Tabelle deshalb flach und meldet `E055`; auf dem
     * Migrate-Pfad waere dasselbe eine stille Layout-Aenderung an einer
     * Tabelle, die der Anwender partitioniert haben wollte.
     */
    private fun blockPartitioning(
        op: DiffOperation,
        ctx: OracleDiffRenderContext,
        table: String,
        strategy: String,
    ) {
        ctx.skip(
            op,
            "Operation ${op.id} creates table '$table' with $strategy partitioning, which the Oracle " +
                "renderer cannot express (partition clauses are not carried in the neutral model). " +
                "Creating the table unpartitioned instead would silently change its physical layout, so " +
                "the operation is blocked; create the partitioned table manually and re-run.",
            code = "ORACLE_PARTITIONING_UNSUPPORTED",
        )
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, setOf(op.id))
    }

    /**
     * Spatial ist fuer Oracle nicht gescoped -- `OracleDdlGenerator
     * .canGenerateSpatial` liefert `false`. Diese Faehigkeit wertet aber nur
     * der Generate-Pfad aus (`AbstractDdlGenerator`); der Diff-Pfad fragt sie
     * nie und wuerde `SDO_GEOMETRY` aus der Typtabelle rendern. Solange das
     * Gate `schema migrate` fuer Oracle abwies, war das unerreichbar.
     */
    private fun blockSpatial(
        op: DiffOperation,
        ctx: OracleDiffRenderContext,
        table: String,
        columns: Collection<String>,
    ) {
        ctx.skip(
            op,
            "Operation ${op.id} touches geometry column(s) ${columns.sorted().joinToString(", ")} on " +
                "'$table'. Spatial support is not scoped for Oracle, so the renderer will not emit " +
                "SDO_GEOMETRY DDL.",
            code = "ORACLE_SPATIAL_UNSUPPORTED",
        )
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, setOf(op.id))
    }

    private fun geometryColumns(columns: Map<String, ColumnDefinition>): Set<String> =
        columns.filterValues { it.type is NeutralType.Geometry }.keys

    private fun blockAddIdentity(op: DiffOperation, ctx: OracleDiffRenderContext, table: String, column: String) {
        ctx.skip(
            op,
            "Operation ${op.id} would turn the existing column '$table.$column' into an identity column. " +
                "Oracle rejects that on any column that is not already an identity column (ORA-30673); the " +
                "change needs a table rebuild, which the migrate renderer does not perform.",
            code = "ORACLE_ADD_IDENTITY_UNSUPPORTED",
        )
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, setOf(op.id))
    }

    private fun blockMissingSchema(op: DiffOperation, ctx: OracleDiffRenderContext, what: String) {
        ctx.skip(
            op,
            "Operation ${op.id} needs the schema for $what, but the DiffResult carries none for this direction.",
            code = "ORACLE_SCHEMA_NOT_IN_DIFF_RESULT",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
    }

    private fun blockMissingColumn(
        op: DiffOperation,
        ctx: OracleDiffRenderContext,
        table: String,
        column: String,
        missing: String,
    ) {
        ctx.skip(
            op,
            "Operation ${op.id} needs $missing of column '$table.$column', but the column is not in the schema " +
                "for this rendering direction.",
            code = "ORACLE_COLUMN_NOT_IN_SCHEMA",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
    }
}
