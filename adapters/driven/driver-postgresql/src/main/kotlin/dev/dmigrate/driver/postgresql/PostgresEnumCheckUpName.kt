package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.metadata.EnumValueCheck
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.postgresContext

/**
 * Der Up einer Aenderung am Wertevorrat einer Inline-Enum-Spalte, wenn der Name des alten
 * Wertevorrat-CHECKs unbekannt ist (Issue #81).
 *
 * Der Up loest den alten CHECK unter seinem Katalognamen. Den kennt er nur aus der Ist-Seite:
 * als benannten CHECK (aus einer Datenbank gelesen) oder ueber einen Katalog. Ist die Ist-Seite
 * eine Datei, fuehrt sie den Wertevorrat inline am Spaltentyp, ohne CHECK und ohne Namen.
 *
 * Frueher liess der Up das `DROP` dann still weg und legte nur den neuen CHECK an: die Spalte trug
 * danach zwei CHECKs, und der alte wies neue Werte weiter ab — bei Exit 0. Ein `DROP` mit dem
 * Standardnamen `<tabelle>_<spalte>_check` waere geraten: Er stimmt nur, wenn die Datenbank aus
 * genau dieser Datei durch d-migrate entstand und PostgreSQL nicht ausweichen musste, und mit
 * `IF EXISTS` loeste er bei falschem Namen still nichts. Der Up blockiert deshalb, genau wie der
 * Down in derselben Lage ([PostgresEnumCheckRollback]).
 */
internal object PostgresEnumCheckUpName {

    const val CODE = "POSTGRES_ENUM_CHECK_NAME_UNKNOWN"

    /**
     * Ob der Up von [op] einen alten Wertevorrat-CHECK loesen muesste, dessen Namen er nicht kennt.
     * Dann ist die Operation blockiert und `true` kommt zurueck.
     */
    fun blocks(
        op: DiffOperation.AlterColumnType,
        ctx: PostgresDiffRenderContext,
        table: String,
        column: String,
        sourceType: NeutralType,
    ): Boolean {
        if (ctx.direction != PostgresRenderDirection.UP) return false
        // Nur eine Inline-Enum der Ist-Seite rendert einen Wertevorrat-CHECK; `ref_type` ist ein
        // eigener Typ, eine Textspalte hat keinen.
        if (EnumValueCheck.inlineValues(sourceType) == null) return false
        if (ctx.enumValueCheckNames(table, column).isNotEmpty()) return false
        // `values_constraint` (ADR 0069): der Name steht in der Datei, der Up loest den alten CHECK unter ihm.
        if (EnumValueCheck.explicitName(sourceType) != null) return false
        if (ctx.options.postgresContext?.catalogRead == true) return false
        ctx.skip(
            op,
            "Operation ${op.id} changes the value list of '$table.$column', but the current side is a file: " +
                "it carries the values inline, without the name of the CHECK the database holds. The up " +
                "direction cannot drop the old CHECK, and adding the new one alone would leave the old one in " +
                "place (it keeps rejecting the new values). Run schema migrate against the database " +
                "(--target db:...), where the name of the existing CHECK is read from the catalog, or name the CHECK " +
                "in the schema (values_constraint) so that it is known here.",
            code = CODE,
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
        return true
    }
}
