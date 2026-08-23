package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Custom Types im Diff-Pfad.
 *
 * T-SQL hat für Enum und Domain **kein eigenes Objekt**. Der Generate-Pfad
 * loest beide an der Spalte auf — ein Enum wird `NVARCHAR(<laengster Wert>)`
 * plus benannter CHECK, eine Domain ihr Basistyp plus CHECK. Anlegen und
 * Loeschen des Typs sind hier deshalb gegenstandslos: es gibt nichts, worauf
 * ein `CREATE TYPE` oder `DROP TYPE` zeigen koennte.
 *
 * Bezahlt wird das beim **Aendern**. Wo PostgreSQL `ALTER TYPE … ADD VALUE`
 * kennt — ein Katalog-Eintrag, eine Anweisung —, faechert SQL Server auf: jede
 * Spalte, die den Typ nutzt, traegt ihre eigene Kopie der Werte in ihrem CHECK
 * und ihre eigene Breite. Geaendert werden muss jede einzeln, und zwar mit
 * demselben Tanz wie bei einer gewoehnlichen Typaenderung — Abhaengigkeiten
 * abraeumen, `ALTER COLUMN`, alles zurueck.
 */
internal object MssqlDiffCustomTypeOps {

    fun renderCreateCustomType(op: DiffOperation.CreateCustomType, ctx: MssqlDiffRenderContext) {
        if (blockComposite(op, ctx, op.objectRef.rootName, op.customType)) return
        // Abwaerts ist die Umkehr eines Create ein Entfernen — der Kommentar
        // muss das sagen, sonst behauptet das Rollback-Skript das Gegenteil.
        val verb = if (ctx.direction == MssqlRenderDirection.UP) "created" else "dropped"
        noteResolvedAtColumn(op, ctx, op.objectRef.rootName, op.customType, verb)
    }

    fun renderDropCustomType(op: DiffOperation.DropCustomType, ctx: MssqlDiffRenderContext) {
        if (blockComposite(op, ctx, op.objectRef.rootName, op.customType)) return
        noteResolvedAtColumn(op, ctx, op.objectRef.rootName, op.customType, "dropped")
    }

    /**
     * Die Werte oder die Basis haben sich geaendert. Betroffen ist jede Spalte,
     * die den Typ nutzt — dort steckt die Information, nicht in einem
     * Katalogobjekt.
     */
    fun renderAlterCustomType(op: DiffOperation.AlterCustomType, ctx: MssqlDiffRenderContext) {
        val name = op.objectRef.rootName
        if (blockComposite(op, ctx, name, op.after) || blockComposite(op, ctx, name, op.before)) return
        val schema = ctx.schemaForDirection()
        if (schema == null) {
            ctx.skip(
                op,
                "Changing custom type '$name' needs the schema of this direction to find the columns that use " +
                    "it, but the DiffResult carries none.",
                code = "MSSQL_COLUMN_NOT_IN_SCHEMA",
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
            return
        }
        // Nur Spalten, die es zum Zeitpunkt dieser Operation schon GIBT.
        //
        // `AlterCustomType` liegt in Phase TYPES und laeuft damit als
        // allererstes — vor `TABLES` und `COLUMNS`. Eine Tabelle oder Spalte,
        // die derselbe Plan erst anlegt, existiert hier noch nicht; ein
        // `ALTER TABLE` darauf waere Msg 208. Noetig ist es ohnehin nicht:
        // `CreateTable` und `AddColumn` rendern die Spalte ueber denselben
        // Spalten-Helfer und damit bereits mit der neuen Breite und dem neuen
        // CHECK. Massgeblich ist deshalb, was BEIDE Schemata kennen.
        val existing = ctx.schemaOppositeOfDirection()
        val users = schema.tables.entries
            .sortedBy { it.key }
            .flatMap { (table, tableDef) ->
                tableDef.columns.entries
                    .filter { (column, col) ->
                        (col.type as? NeutralType.Enum)?.refType == name &&
                            existing?.tables?.get(table)?.columns?.containsKey(column) == true
                    }
                    .sortedBy { it.key }
                    .map { (column, col) -> Triple(table, column, col) }
            }
        if (users.isEmpty()) {
            ctx.emit(
                op,
                "-- Custom type '$name' changed, but no column uses it; T-SQL keeps no object for it.",
                MssqlDiffRenderContext.MSSQL_METADATA_DDL_HINTS,
            )
            return
        }
        // Erst ALLE Spalten aufloesen, dann emittieren. Blockt eine spaetere,
        // nachdem eine fruehere schon emittiert hat, laege die Operation in
        // `rendered` UND `skipped` — das bricht `MigrationDdlResult` mit einer
        // Exception ab, statt einen Blocker zu liefern.
        val statements = mutableListOf<String>()
        for ((table, column, col) in users) {
            statements += MssqlDiffTableOps
                .columnChangeStatements(op, ctx, table, column, col.type, col) ?: return
        }
        statements.forEach { ctx.emit(op, it) }
        ctx.addInfoDiagnostic(
            code = "MSSQL_CUSTOM_TYPE_FANNED_OUT",
            operationId = op.id,
            message = "Custom type '$name' has no object in SQL Server; the change was applied to the " +
                "${users.size} column(s) that use it (${users.joinToString(", ") { "'${it.first}.${it.second}'" }}). " +
                "Each carries its own width and CHECK constraint.",
        )
    }

    /**
     * Ein `CREATE TYPE`/`DROP TYPE` gaebe es in T-SQL nur fuer benutzerdefinierte
     * Typen, die d-migrate nicht erzeugt. Die Operation ist damit erledigt,
     * nicht uebersprungen — der Kommentar haelt im Skript fest, warum dort
     * nichts steht.
     */
    private fun noteResolvedAtColumn(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        name: String,
        type: CustomTypeDefinition,
        verb: String,
    ) {
        val kind = type.kind.name.lowercase()
        ctx.emit(
            op,
            "-- The $kind type '$name' is $verb at its columns, not as an object: SQL Server has no " +
                "equivalent, so the generate and migrate paths both render it inline.",
            MssqlDiffRenderContext.MSSQL_METADATA_DDL_HINTS,
        )
    }

    private fun blockComposite(
        op: DiffOperation,
        ctx: MssqlDiffRenderContext,
        name: String,
        type: CustomTypeDefinition,
    ): Boolean {
        if (type.kind != CustomTypeKind.COMPOSITE) return false
        ctx.skip(
            op,
            "Composite type '$name' has no equivalent in SQL Server; the generate path reports E054 and skips " +
                "it. Flatten the fields into columns or store the value as JSON text.",
            code = "E054",
        )
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, setOf(op.id))
        return true
    }
}
