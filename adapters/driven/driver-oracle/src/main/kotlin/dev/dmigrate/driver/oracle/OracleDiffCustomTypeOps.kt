package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Per-operation renderers for custom types (Sub-Slice 5c).
 *
 * **Oracle fuehrt fuer ENUM und DOMAIN gar kein Datenbankobjekt.** Der
 * Generate-Pfad (`OracleDdlGenerator.generateCustomTypes`) liefert fuer
 * beide `null`: eine ENUM lebt als `VARCHAR2(<breite>)` + benannter CHECK an
 * jeder nutzenden Spalte, eine DOMAIN als CLOB. Nur `COMPOSITE` erzeugt eine
 * E054-Notiz. Strukturell steht MSSQL vor derselben Lage (auch dort gibt es
 * kein Typ-Objekt), PostgreSQL dagegen nicht — es hat mit `CREATE TYPE` ein
 * echtes Katalogobjekt.
 *
 * Folge fuer den Diff-Pfad:
 * - `CreateCustomType`/`DropCustomType` erzeugen **keine Anweisung**, buchen
 *   die Operation aber als erledigt ([OracleDiffRenderContext.markRendered])
 *   und legen die Begruendung als INFO-Diagnose ab. Der Vertrag erlaubt das
 *   ausdruecklich: [dev.dmigrate.driver.migration.MigrationDdlResult] verlangt
 *   nur, dass jede ANWEISUNG eine gerenderte Operation hat, nicht umgekehrt.
 *   Ein SQL-Kommentar als Pseudo-Anweisung waere die falsche Ablage -- er
 *   landete im ausgegebenen Migrationsskript, obwohl `statements`
 *   auszufuehrendes SQL traegt und `diagnostics` die Erklaerungen.
 * - `AlterCustomType` faechert auf die nutzenden Spalten auf — das ist der
 *   einzige Ort, an dem eine Enum-Aenderung in Oracle ueberhaupt sichtbar
 *   wird.
 */
internal object OracleDiffCustomTypeOps {

    private val typeMapper = OracleTypeMapper()
    private fun quoteIdentifier(name: String): String = SqlIdentifiers.quoteIdentifier(name, DatabaseDialect.ORACLE)
    private val columnHelper = OracleColumnConstraintHelper(quoteIdentifier = ::quoteIdentifier, typeMapper = typeMapper)

    fun renderCreateCustomType(op: DiffOperation.CreateCustomType, ctx: OracleDiffRenderContext) {
        if (blockComposite(op, ctx, op.objectRef.rootName, op.customType)) return
        noteResolvedAtColumn(op, ctx, op.objectRef.rootName, op.customType.kind, created = ctx.direction == OracleRenderDirection.UP)
    }

    fun renderDropCustomType(op: DiffOperation.DropCustomType, ctx: OracleDiffRenderContext) {
        if (blockComposite(op, ctx, op.objectRef.rootName, op.customType)) return
        noteResolvedAtColumn(op, ctx, op.objectRef.rootName, op.customType.kind, created = ctx.direction == OracleRenderDirection.DOWN)
    }

    /**
     * Eine geaenderte ENUM wird in Oracle an jeder nutzenden Spalte sichtbar:
     * die Spaltenbreite folgt dem laengsten Wert, der Wertevorrat steckt im
     * benannten CHECK. Pro Spalte entstehen deshalb drei Anweisungen, und
     * ihre Reihenfolge ist nicht beliebig: **zuerst den alten CHECK loesen**
     * (er verbietet sonst die neuen Werte), dann die Breite anpassen, dann
     * den neuen CHECK setzen.
     *
     * Der Rueckwaertsgang existiert nicht: `AlterCustomType` ist
     * `MANUAL_REQUIRED` mit `risks.down = null`. Der Dispatcher faengt das
     * ueber seinen Down-Risiko-Waechter ab und liefert `ROLLBACK_NOT_POSSIBLE`
     * -- ohne ihn liefe der Renderer hier in eine Exception statt in einen
     * Blocker.
     */
    fun renderAlterCustomType(op: DiffOperation.AlterCustomType, ctx: OracleDiffRenderContext) {
        val name = op.objectRef.rootName
        // Beide Seiten: ein Wechsel COMPOSITE→ENUM ist sonst ungeblockt und
        // fiele in den Fan-out.
        if (blockComposite(op, ctx, name, op.before)) return
        if (blockComposite(op, ctx, name, op.after)) return
        val schema = ctx.schemaForDirection()
            ?: return blockMissingSchema(op, ctx, name)
        val values = op.after.values
        if (op.after.kind != CustomTypeKind.ENUM || values.isNullOrEmpty()) {
            // Eine DOMAIN faellt in Oracle immer auf CLOB, eine wertelose ENUM
            // auf ein ungebundenes VARCHAR2(4000) -- in beiden Faellen aendert
            // eine neue Typdefinition an der Spalte nichts Renderbares.
            ctx.markRendered(op)
            ctx.addInfoDiagnostic(
                code = "ORACLE_CUSTOM_TYPE_AT_COLUMN",
                operationId = op.id,
                message = "Type '$name' changed, but its Oracle column shape " +
                    (if (op.after.kind == CustomTypeKind.DOMAIN) "(CLOB)" else "(unbounded VARCHAR2)") +
                    " carries no values to re-render; nothing to do.",
            )
            return
        }
        // Erst alle Spalten aufloesen, dann emittieren: ein Block nach dem
        // ersten emit() legte die Operation in `rendered` UND `skipped`, und
        // MigrationDdlResult prueft die Disjunktheit mit require().
        val users = usingColumns(schema, name, ctx)
        if (users.isEmpty()) {
            ctx.markRendered(op)
            ctx.addInfoDiagnostic(
                code = "ORACLE_CUSTOM_TYPE_NO_USERS",
                operationId = op.id,
                message = "Enum type '$name' changed, but no column in this schema uses it; nothing to render.",
            )
            return
        }
        // Einen CHECK gibt es an der Spalte nur, wenn die VORHERIGE Definition
        // eine wertebasierte ENUM war: eine DOMAIN steht als CLOB da, eine
        // wertelose ENUM als ungebundenes VARCHAR2(4000) -- beide ohne CHECK.
        // Ein `DROP CONSTRAINT` liefe dort in ORA-02443.
        val hadCheck = op.before.kind == CustomTypeKind.ENUM && !op.before.values.isNullOrEmpty()
        for ((table, column) in users) {
            val quotedTable = ctx.sql.quote(table)
            val quotedColumn = ctx.sql.quote(column)
            if (hadCheck) {
                val checkName = ctx.sql.quote(OracleColumnConstraintHelper.enumCheckName(table, column))
                ctx.emit(op, "ALTER TABLE $quotedTable DROP CONSTRAINT $checkName;")
            }
            ctx.emit(op, "ALTER TABLE $quotedTable MODIFY $quotedColumn VARCHAR2(${OracleTypeMapper.enumWidth(values)});")
            ctx.emit(op, "ALTER TABLE $quotedTable ADD ${columnHelper.enumCheckClause(table, column, values)};")
        }
        ctx.addInfoDiagnostic(
            code = "ORACLE_CUSTOM_TYPE_FANNED_OUT",
            operationId = op.id,
            message = "Enum type '$name' has no object in Oracle; the change was fanned out to " +
                users.joinToString(", ") { "${it.first}.${it.second}" } + ".",
        )
    }

    /**
     * Spalten, die den Typ referenzieren — und zwar auf BEIDEN Seiten als
     * Enum-Spalte desselben Typs.
     *
     * `AlterCustomType` liegt in Phase `TYPES` und laeuft damit VOR
     * `TABLES`/`COLUMNS`. Eine Spalte, die es nur auf der gelesenen Seite
     * gibt, entsteht erst danach — `CreateTable` rendert sie ohnehin mit der
     * neuen Breite. Und eine Spalte, die auf der Gegenseite noch KEINE
     * Enum-Spalte ist (Typwechsel im selben Diff), traegt zu diesem Zeitpunkt
     * weder die Breite noch den CHECK, den der Fan-out anfassen wuerde: das
     * `AlterColumnType` dafuer laeuft erst in Phase `COLUMNS`.
     *
     * Sortiert, damit die Anweisungsfolge nicht von der Iterationsreihenfolge
     * einer Map abhaengt (sie geht in den Plan und in Artefakte ein).
     */
    private fun usingColumns(
        schema: SchemaDefinition,
        typeName: String,
        ctx: OracleDiffRenderContext,
    ): List<Pair<String, String>> {
        val opposite = ctx.schemaOppositeOfDirection()
        fun usesType(s: SchemaDefinition?, table: String, column: String): Boolean =
            (s?.tables?.get(table)?.columns?.get(column)?.type as? NeutralType.Enum)?.refType == typeName
        return schema.tables.toSortedMap().flatMap { (tableName, table) ->
            table.columns.toSortedMap().mapNotNull { (columnName, column) ->
                val isEnumHere = (column.type as? NeutralType.Enum)?.refType == typeName
                if (isEnumHere && usesType(opposite, tableName, columnName)) tableName to columnName else null
            }
        }
    }

    private fun noteResolvedAtColumn(
        op: DiffOperation,
        ctx: OracleDiffRenderContext,
        name: String,
        kind: CustomTypeKind,
        created: Boolean,
    ) {
        val verb = if (created) "created" else "dropped"
        val shape = if (kind == CustomTypeKind.DOMAIN) "CLOB" else "VARCHAR2 + CHECK"
        ctx.markRendered(op)
        ctx.addInfoDiagnostic(
            code = "ORACLE_CUSTOM_TYPE_AT_COLUMN",
            operationId = op.id,
            message = "The ${kind.name.lowercase()} type '$name' is $verb at its columns ($shape); Oracle has " +
                "no type object, so this operation renders no statement.",
        )
    }

    private fun blockComposite(
        op: DiffOperation,
        ctx: OracleDiffRenderContext,
        name: String,
        customType: CustomTypeDefinition,
    ): Boolean {
        if (customType.kind != CustomTypeKind.COMPOSITE) return false
        ctx.skip(
            op,
            "Operation ${op.id}: composite type '$name' is not supported in Oracle.",
            code = "E054",
        )
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, setOf(op.id))
        return true
    }

    private fun blockMissingSchema(op: DiffOperation, ctx: OracleDiffRenderContext, name: String) {
        ctx.skip(
            op,
            "Operation ${op.id} needs the schema of this rendering direction to find the columns using " +
                "type '$name', but the DiffResult carries none.",
            code = "ORACLE_TABLE_NOT_IN_SCHEMA",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
    }
}
