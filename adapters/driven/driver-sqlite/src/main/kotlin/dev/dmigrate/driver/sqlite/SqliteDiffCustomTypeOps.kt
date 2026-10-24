package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.RefTypeVocabulary
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Benannte Typen im Diff-Pfad von SQLite.
 *
 * SQLite hat kein Typ-Objekt: eine Aufzaehlung steht als `TEXT` mit `CHECK`
 * an jeder Spalte, die auf sie verweist (`generateEnumRefTypeColumn`). Der
 * Reverse liest deshalb nie einen benannten Typ zurueck, und der Planer
 * plante `CreateCustomType` bei jedem Lauf neu -- was dieser Renderer bisher
 * als `DIALECT_UNSUPPORTED_OPERATION` blockierte (Issue #48).
 *
 * - `CreateCustomType`/`DropCustomType` einer ENUM oder DOMAIN erzeugen **keine
 *   Anweisung**, buchen die Operation aber als erledigt und legen die
 *   Begruendung als INFO-Diagnose ab -- wie Oracle
 *   (`OracleDiffCustomTypeOps` im Oracle-Treiber).
 * - `AlterCustomType` einer ENUM oder DOMAIN aendert den `CHECK` (und bei einer
 *   Domain den Basistyp) an den nutzenden Spalten. SQLite kann ihn nicht ersetzen, der Weg ist der Tabellen-Neubau:
 *   [rebuildUsers] nennt die Tabellen, und [SqliteRebuildPlanner.classify]
 *   nimmt die Operation in deren Eimer auf. Hierher kommt sie nur, wenn keine
 *   Spalte den Typ nutzt.
 * - COMPOSITE bleibt blockiert, wie bei SQL Server und Oracle: die Generierung
 *   meldet E054 und ueberspringt den Typ; eine Abbildung (Fallback-Strategie)
 *   ist eine eigene Entscheidung.
 */
internal object SqliteDiffCustomTypeOps {

    fun renderCreateCustomType(op: DiffOperation.CreateCustomType, ctx: SqliteDiffRenderContext) {
        if (blockComposite(op, ctx, op.customType)) return
        noteAtColumn(op, ctx, op.objectRef.rootName, op.customType.kind, created = ctx.direction == SqliteRenderDirection.UP)
        if (ctx.direction == SqliteRenderDirection.UP) warnIfVocabularyUnverifiable(op, ctx)
    }

    /**
     * Gegen eine Datenbank liest der Reverse den CHECK einer **Domain**-Spalte
     * nur zurueck, wenn er eine Werteliste ist (`VALUE IN (…)`); jeder andere
     * (`VALUE >= 0`) steht unbenannt am Spaltentext und ist nicht zu sehen: `CreateCustomType` erscheint bei jedem Lauf, und eine
     * Aenderung des CHECK ist auf der Ist-Seite nicht zu sehen. Eine Spalte, die
     * es auf beiden Seiten gibt, bleibt dann ohne Operation. Still hinzunehmen
     * waere das ein "ok" ohne Wirkung -- gesagt wird es.
     *
     * Fuer eine ENUM entfaellt die Warnung: der Migrate-Reader gibt den
     * Wertevorrat zurueck, der Vergleich sieht die Aenderung und baut die
     * Tabelle neu auf.
     */
    private fun warnIfVocabularyUnverifiable(op: DiffOperation.CreateCustomType, ctx: SqliteDiffRenderContext) {
        if (op.customType.kind != CustomTypeKind.DOMAIN) return
        // Eine Werteliste liest der Migrate-Reader zurueck; der Vergleich sieht sie.
        if (RefTypeVocabulary.vocabularyOf(op.customType) != null) return
        val name = op.objectRef.rootName
        val current = ctx.currentSchema ?: return
        val desired = ctx.desiredSchema ?: return
        val existing = desired.tables.flatMap { (table, def) ->
            def.columns.keys.filter { column ->
                usesType(desired, table, column, name) && current.tables[table]?.columns?.containsKey(column) == true
            }.map { "$table.$it" }
        }.sorted()
        if (existing.isEmpty()) return
        ctx.warning(
            op,
            "Type '$name' is used by existing column(s) ${existing.joinToString(", ")}. SQLite reports " +
                "no CHECK for them that is a value list, so a changed definition of '$name' is not detected " +
                "against a database target and its CHECK is not rebuilt. Plan against a `file:` target of the " +
                "previous schema to rebuild the CHECK.",
            code = "SQLITE_CUSTOM_TYPE_VOCABULARY_UNVERIFIED",
        )
    }

    fun renderDropCustomType(op: DiffOperation.DropCustomType, ctx: SqliteDiffRenderContext) {
        if (blockComposite(op, ctx, op.customType)) return
        noteAtColumn(op, ctx, op.objectRef.rootName, op.customType.kind, created = ctx.direction == SqliteRenderDirection.DOWN)
    }

    /**
     * Eine ENUM ohne nutzende Spalte (oder deren Zusammensetzung hier nicht
     * bestimmbar ist): nichts zu bauen. Mit nutzenden Spalten laeuft die
     * Operation ueber den Neubau und kommt nicht hierher.
     */
    fun renderAlterCustomType(op: DiffOperation.AlterCustomType, ctx: SqliteDiffRenderContext) {
        if (!sameResolvableKind(op)) {
            unsupported(op, ctx)
            return
        }
        // Der Rueckwaertsgang existiert nicht: die Operation ist
        // `MANUAL_REQUIRED` und hat in DOWN kein Risiko. Ohne diese Sperre
        // liefe der Neubau mit dem alten Wertevorrat und taete, als sei das
        // Zuruecknehmen geprueft.
        if (ctx.direction == SqliteRenderDirection.DOWN) {
            ctx.skip(
                op,
                "Operation ${op.id} changes the value vocabulary of enum type '${op.objectRef.rootName}'; " +
                    "the change cannot be reverted automatically.",
                code = "SQLITE_CUSTOM_TYPE_NOT_REVERSIBLE",
            )
            ctx.addBlocker(MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE, operationIds = setOf(op.id))
            return
        }
        ctx.markRendered(op)
        ctx.info(
            op,
            "Enum type '${op.objectRef.rootName}' changed, but no column in this schema uses it; " +
                "nothing to rebuild.",
            code = "SQLITE_CUSTOM_TYPE_NO_USERS",
        )
    }

    /**
     * Pro `AlterCustomType` einer ENUM (beide Seiten) die Tabellen, die eine
     * Spalte mit `ref_type` auf diesen Typ haben -- auf BEIDEN Seiten als
     * Enum-Spalte desselben Typs. Eine Spalte, die es nur auf einer Seite
     * gibt, entsteht oder faellt mit `CreateTable`/`DropColumn` und baut den
     * Typ dabei ohnehin neu.
     *
     * Schluessel ist die Operations-Id; leer, wo kein Schema da ist.
     */
    fun rebuildUsers(
        ops: List<DiffOperation>,
        current: SchemaDefinition?,
        desired: SchemaDefinition?,
    ): Map<String, Set<String>> {
        if (current == null || desired == null) return emptyMap()
        return ops.filterIsInstance<DiffOperation.AlterCustomType>()
            .filter(::sameResolvableKind)
            .associate { op ->
                val name = op.objectRef.rootName
                op.id to desired.tables.filter { (table, def) ->
                    def.columns.any { (column, _) ->
                        usesType(current, table, column, name) && usesType(desired, table, column, name)
                    }
                }.keys.toSortedSet()
            }
            .filterValues { it.isNotEmpty() }
    }

    /** ENUM->ENUM oder DOMAIN->DOMAIN: ein Wechsel der Art ist eine eigene Entscheidung. */
    private fun sameResolvableKind(op: DiffOperation.AlterCustomType): Boolean =
        op.before.kind == op.after.kind && op.after.kind != CustomTypeKind.COMPOSITE

    private fun usesType(schema: SchemaDefinition, table: String, column: String, typeName: String): Boolean =
        (schema.tables[table]?.columns?.get(column)?.type as? NeutralType.Enum)?.refType == typeName

    private fun noteAtColumn(
        op: DiffOperation,
        ctx: SqliteDiffRenderContext,
        name: String,
        kind: CustomTypeKind,
        created: Boolean,
    ) {
        val verb = if (created) "created" else "dropped"
        ctx.markRendered(op)
        ctx.info(
            op,
            "The ${kind.name.lowercase()} type '$name' is $verb at its columns (${shape(kind)}); SQLite has no type object, " +
                "so this operation renders no statement.",
            code = "SQLITE_CUSTOM_TYPE_AT_COLUMN",
        )
    }

    private fun shape(kind: CustomTypeKind): String =
        if (kind == CustomTypeKind.DOMAIN) "base type + CHECK" else "TEXT + CHECK"

    private fun blockComposite(
        op: DiffOperation,
        ctx: SqliteDiffRenderContext,
        customType: CustomTypeDefinition,
    ): Boolean {
        if (customType.kind != CustomTypeKind.COMPOSITE) return false
        unsupported(op, ctx)
        return true
    }

    private fun unsupported(op: DiffOperation, ctx: SqliteDiffRenderContext) {
        ctx.skip(op, "Operation ${op::class.simpleName} is not in the first SQLite matrix.")
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
    }
}
