package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Was eine Routinen-Operation am Rendern hindert, und wie der emittierte
 * Bezeichner aus dem kanonischen Key entsteht.
 *
 * Getrennt von [OracleDiffRoutineOps], das die Anweisungen erzeugt: dort geht
 * es um die Form, hier um das Urteil davor. Das Urteil selbst faellt
 * [OracleRoutineShape] — geteilt mit dem Generate-Pfad.
 */
internal object OracleDiffRoutineGuards {

    /**
     * Oracle kennt kein Umbenennen freistehender Routinen — weder
     * `RENAME f TO g` (ORA-03001) noch `ALTER FUNCTION f RENAME TO g`
     * (ORA-00922). Ein Drop-und-Create waere kein Rename, sondern ein anderes
     * Objekt: abhaengige Objekte wuerden invalidiert und Rechte fielen weg.
     */
    fun blockRename(
        op: DiffOperation,
        ctx: OracleDiffRenderContext,
        kind: String,
        fromName: String,
        toName: String,
    ) {
        // `fromName`/`toName` tragen den blanken Namen, nicht den
        // kanonischen Key -- der Planer baut den Key erst daraus.
        val (from, to) = directedNames(ctx, fromName, toName)
        val problem = OracleRoutineShape.renameUnsupported(kind, from, to)
        ctx.skip(op, "${problem.reason} ${problem.hint}", code = "E053")
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
    }

    /**
     * Ein fehlender Rumpf blockt anders als eine Form, die Oracle nicht kennt:
     * beim Rueckbau ist er `ROLLBACK_NOT_POSSIBLE` (die alte Fassung ist
     * schlicht unbekannt), sonst eine Handarbeit.
     */
    fun blocked(
        op: DiffOperation,
        ctx: OracleDiffRenderContext,
        problem: OracleRoutineShape.Unrenderable?,
        body: String?,
    ): Boolean {
        if (problem == null) return false
        val bodyUnknown = body.isNullOrBlank()
        val isDown = ctx.direction == OracleRenderDirection.DOWN
        val code = when {
            !bodyUnknown -> "E053"
            isDown -> "ROUTINE_DOWN_BODY_UNKNOWN"
            else -> "ROUTINE_REPLACE_UP_BODY_UNKNOWN"
        }
        val reason = if (bodyUnknown && isDown) {
            MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
        } else {
            MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        }
        ctx.skip(op, "${problem.reason} ${problem.hint}", code = code)
        ctx.addBlocker(reason, setOf(op.id))
        return true
    }

    /**
     * Zwei Objekte, die auf denselben Oracle-Namen fallen — Ueberladungen oder
     * gleichnamige Trigger auf verschiedenen Tabellen. Der Generate-Pfad
     * meldet das; hier zaehlt dieselbe Frage gegen das Zielschema, denn ein
     * zweites `CREATE OR REPLACE` ersetzte sonst still das erste.
     */
    fun collision(
        ctx: OracleDiffRenderContext,
        name: String,
        kind: String,
    ): OracleRoutineShape.Unrenderable? {
        val schema = ctx.schemaForDirection() ?: return null
        return OracleRoutineShape.nameCollision(
            kind,
            name,
            OracleRoutineShape.collidingNames(keysOf(schema, kind)) { key ->
                if (kind == "trigger") triggerName(key) else routineName(key)
            },
        )
    }

    private fun keysOf(schema: SchemaDefinition, kind: String): Set<String> = when (kind) {
        "function" -> schema.functions.keys
        "procedure" -> schema.procedures.keys
        else -> schema.triggers.keys
    }

    /** Der emittierte Bezeichner: aus dem kanonischen Key, nicht der Key selbst. */
    fun nameOf(op: DiffOperation, kind: String): String {
        val key = op.objectRef.rootName
        return if (kind == "TRIGGER") triggerName(key) else routineName(key)
    }

    fun routineName(key: String): String = ObjectKeyCodec.routineName(key)

    fun triggerName(key: String): String = ObjectKeyCodec.triggerName(key)

    /** Beim Rueckbau tauschen Quelle und Ziel des Umbenennens die Rollen. */
    fun directedNames(
        ctx: OracleDiffRenderContext,
        fromName: String,
        toName: String,
    ): Pair<String, String> =
        if (ctx.direction == OracleRenderDirection.UP) fromName to toName else toName to fromName
}
