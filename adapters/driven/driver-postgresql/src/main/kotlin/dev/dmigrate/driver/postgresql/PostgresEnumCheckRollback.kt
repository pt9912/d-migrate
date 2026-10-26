package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.EnumCheckProjection
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.metadata.EnumValueCheck
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.postgresContext

/**
 * Der Rueckweg einer Aenderung am Wertevorrat einer Inline-Enum-Spalte.
 *
 * Der Up-Pfad loest den alten Wertevorrat-CHECK unter seinem Katalognamen und
 * legt den neuen **unbenannt** an. Der Rueckweg muss dasselbe in Gegenrichtung
 * tun: den vom Up angelegten CHECK loesen und den, den der Up geloest hat, mit
 * seinen alten Werten wieder anlegen. `op.before` traegt die Werte nicht
 * (der Reader liefert `TEXT` plus CHECK), der alte CHECK steht deshalb nur in
 * der Ist-Seite des Plans.
 *
 * Der Servername des vom Up angelegten CHECKs ist beim Rendern nicht bekannt:
 * PostgreSQL vergibt `<tabelle>_<spalte>_check`, haengt bei Kollision eine Ziffer
 * an und kuerzt lange Namen. Der Name wird deshalb nur dort abgeleitet, wo er
 * sicher ist; sonst blockiert der Rueckweg ([blockUnknownName]), statt einen
 * `DROP` mit erratenem Namen zu rendern, der bei falschem Namen nichts loeste
 * und den erweiterten CHECK stehen liesse.
 */
internal object PostgresEnumCheckRollback {

    /** Ein Wertevorrat-CHECK der Ist-Seite. */
    data class State(val name: String, val values: List<String>)

    /** Laenge, auf die PostgreSQL Bezeichner kuerzt. */
    private const val MAX_IDENTIFIER_BYTES = 63

    /**
     * Rendert den Rueckweg von [op] fuer die CHECK-Seite.
     *
     * @return `false`, wenn [op] weder einen Wertevorrat anlegt noch loest —
     *   dann bleibt es beim bisherigen Pfad.
     */
    fun render(
        op: DiffOperation.AlterColumnType,
        ctx: PostgresDiffRenderContext,
        table: String,
        column: String,
        typeStatement: String?,
    ): Boolean {
        // Mehrere Wertevorrat-CHECKs an der Spalte: der Up hat sie ueber eigene DropConstraint-Operationen
        // geloest und den neuen CHECK unter einem Namen angelegt, den der Renderer nicht kennt (der
        // Standardname war beim Anlegen noch belegt, wenn er unter den alten stand). Die inversen
        // DropConstraints legen die alten ohnehin wieder an, `findState` sah nur den ersten.
        if (ctx.currentSchema?.tables?.get(table)?.constraints.orEmpty().count { isValueCheck(it, column) } > 1) {
            blockUnknownName(op, ctx, "$table.$column")
            return true
        }
        val createdByUp = EnumValueCheck.inlineValues(op.after)
        val droppedByUp = findState(ctx.currentSchema, table, column)
        val restore = droppedByUp?.values ?: EnumValueCheck.inlineValues(op.before)
        if (createdByUp == null && restore == null) return false

        // Ohne Katalogseite kennt der Renderer die Namen des Ziels nicht (eine Datei fuehrt den
        // Wertevorrat inline, ohne Namen; und der Up hat dort nichts loesen koennen). Ein alter CHECK
        // mit Namen in der Ist-Seite ist der Beleg; sonst muss die Ist-Seite aus einer Datenbank gelesen
        // sein. `--target-version` setzt die Serverversion auch bei Datei gegen Datei und taugt dafuer nicht.
        val catalog = ctx.options.postgresContext?.catalogRead == true
        val dropName = createdByUp?.let { createdName(ctx.currentSchema, table, column, droppedByUp) }
        val nameUnknown = (droppedByUp == null && !catalog) || (createdByUp != null && dropName == null)
        if (nameUnknown) {
            blockUnknownName(op, ctx, "$table.$column")
            return true
        }
        dropName?.let { ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP CONSTRAINT ${ctx.sql.quote(it)};") }
        typeStatement?.let { ctx.emit(op, it) }
        restore?.let { values ->
            val clause = EnumValueCheck.clause(column, values, ctx.sql::quote)
            val named = droppedByUp?.let { "CONSTRAINT ${ctx.sql.quote(it.name)} " }.orEmpty()
            ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD $named$clause;")
        }
        return true
    }

    private fun isValueCheck(constraint: ConstraintDefinition, column: String): Boolean =
        constraint.type == ConstraintType.CHECK && EnumCheckProjection.valuesOf(constraint.expression, column) != null

    /** Der Wertevorrat-CHECK von [column] in [schema], falls es einen gibt. */
    fun findState(schema: SchemaDefinition?, table: String, column: String): State? =
        schema?.tables?.get(table)?.constraints?.firstNotNullOfOrNull { constraint ->
            if (constraint.type != ConstraintType.CHECK) return@firstNotNullOfOrNull null
            EnumCheckProjection.valuesOf(constraint.expression, column)?.let { State(constraint.name, it) }
        }

    /**
     * Der Name, unter dem der Up den neuen CHECK anlegt — oder `null`, wo er
     * nicht sicher ist.
     *
     * Der Standardname `<tabelle>_<spalte>_check` ist sicher, wenn er in der
     * Ist-Seite von keinem **anderen** Constraint belegt ist (PostgreSQL prueft
     * schemaweit) und ohne Kuerzung passt. Der alte CHECK selbst zaehlt nicht:
     * der Up loest ihn vor dem Anlegen, sein Name wird frei.
     */
    fun createdName(
        schema: SchemaDefinition?,
        table: String,
        column: String,
        dropped: State?,
    ): String? {
        val default = "${table}_${column}_check"
        if (default.toByteArray(Charsets.UTF_8).size > MAX_IDENTIFIER_BYTES) return null
        val taken = schema?.tables.orEmpty().values.flatMap { it.constraints }.map { it.name }
        val others = if (dropped != null) taken - dropped.name else taken
        return default.takeIf { it !in others }
    }

    private fun blockUnknownName(op: DiffOperation.AlterColumnType, ctx: PostgresDiffRenderContext, target: String) {
        ctx.skip(
            op,
            "Operation ${op.id} created the value CHECK of '$target' under a server-chosen name that the " +
                "renderer cannot determine safely (name taken, truncated, or no catalog available). " +
                "The down direction cannot drop it, so it would leave the widened or stale CHECK in place.",
            code = "POSTGRES_ENUM_CHECK_ROLLBACK_NAME_UNKNOWN",
        )
        ctx.addBlocker(MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE, operationIds = setOf(op.id))
    }
}
