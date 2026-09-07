package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ParameterDirection
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.RoutineSecurity
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.core.model.toSqlEventClause

/**
 * PL/SQL-Huelle um einen Routinen-Rumpf: `CREATE OR REPLACE FUNCTION`,
 * `… PROCEDURE` und `… TRIGGER`.
 *
 * Das neutrale Modell traegt den Rumpf ohne Huelle (`body`) und die Signatur
 * daneben (`parameters`, `returns`, `table`, `events`, `timing`) — aus diesen
 * Feldern setzt sich die Anweisung hier wieder zusammen. Ob sie sich ueberhaupt
 * setzen laesst, entscheidet [OracleRoutineShape]. Render-Regeln:
 * `spec/ddl-generation-rules.md` (Abschnitte Oracle).
 *
 * Kein abschliessendes `/`: das ist ein SQL*Plus-Zeilenkommando, kein Teil der
 * Anweisung. Ueber JDBC gesendet wuerde es die Uebersetzung scheitern lassen
 * und die Routine `INVALID` zuruecklassen.
 */
internal object OracleRoutineDdl {

    fun functionSql(
        name: String,
        fn: FunctionDefinition,
        body: String,
        quote: (String) -> String,
    ): String = buildString {
        append("CREATE OR REPLACE FUNCTION ${quote(name)}${parameterClause(fn.parameters, quote)}\n")
        append("RETURN ${paramTypeSql(checkNotNull(fn.returns).type)}")
        if (fn.deterministic == true) append(" DETERMINISTIC")
        append(authidClause(fn.security))
        append(" IS\n")
        append(body.trim())
    }

    fun procedureSql(
        name: String,
        proc: ProcedureDefinition,
        body: String,
        quote: (String) -> String,
    ): String = buildString {
        append("CREATE OR REPLACE PROCEDURE ${quote(name)}${parameterClause(proc.parameters, quote)}")
        append(authidClause(proc.security))
        append(" IS\n")
        append(body.trim())
    }

    fun triggerSql(
        name: String,
        trigger: TriggerDefinition,
        body: String,
        quote: (String) -> String,
    ): String = buildString {
        append("CREATE OR REPLACE TRIGGER ${quote(name)}\n")
        append("${timingSql(trigger.timing)} ${trigger.events.toSqlEventClause()} ON ${quote(trigger.table)}\n")
        // `INSTEAD OF` feuert in Oracle immer zeilenweise; die Klausel ist dort
        // zulaessig, aber entbehrlich.
        if (trigger.forEach == TriggerForEach.ROW && trigger.timing != TriggerTiming.INSTEAD_OF) {
            append("FOR EACH ROW\n")
        }
        // Der Katalog liefert die Bedingung ohne die aeusseren Klammern, die
        // die Syntax verlangt -- innere bleiben aber stehen. Sie hier
        // abzuziehen zerbraeche `(a) AND (b)`: das faengt mit `(` an und hoert
        // mit `)` auf, ohne von einem Klammerpaar umschlossen zu sein.
        trigger.condition?.let { append("WHEN (${it.trim()})\n") }
        append(body.trim())
    }

    private fun timingSql(timing: TriggerTiming): String = when (timing) {
        TriggerTiming.BEFORE -> "BEFORE"
        TriggerTiming.AFTER -> "AFTER"
        TriggerTiming.INSTEAD_OF -> "INSTEAD OF"
    }

    /**
     * `AUTHID CURRENT_USER` nur bei `INVOKER`: `DEFINER` ist Oracles
     * Voreinstellung, und sie auszuschreiben wuerde einen Unterschied
     * behaupten, wo keiner ist.
     */
    private fun authidClause(security: RoutineSecurity?): String =
        if (security == RoutineSecurity.INVOKER) " AUTHID CURRENT_USER" else ""

    /**
     * Die Parameterliste samt Klammern — oder nichts.
     *
     * Eine leere Klammer ist in PL/SQL ein Uebersetzungsfehler: sowohl
     * `PROCEDURE p()` als auch `FUNCTION f()` entstehen als `INVALID`.
     */
    private fun parameterClause(parameters: List<ParameterDefinition>, quote: (String) -> String): String =
        if (parameters.isEmpty()) "" else "(${parameterList(parameters, quote)})"

    private fun parameterList(parameters: List<ParameterDefinition>, quote: (String) -> String): String =
        parameters.joinToString(", ") { p ->
            "${quote(p.name)} ${directionSql(p.direction)} ${paramTypeSql(p.type)}"
        }

    private fun directionSql(direction: ParameterDirection): String = when (direction) {
        ParameterDirection.IN -> "IN"
        ParameterDirection.OUT -> "OUT"
        ParameterDirection.INOUT -> "IN OUT"
    }

    /**
     * Neutraler Typname auf einen PL/SQL-Signaturtyp.
     *
     * **Ohne Laenge und ohne Praezision.** PL/SQL laesst einen beschraenkten
     * Parameter- oder Rueckgabetyp nicht zu: `IN VARCHAR2(10)` und
     * `RETURN NUMBER(10)` erzeugen die Routine als `INVALID`. Deshalb rendert
     * dieser Zweig andere Namen als [OracleTypeMapper], der fuer Spalten
     * zustaendig ist und dort Laengen setzen muss.
     *
     * Alles Weitere ist ein nativer Oracle-Typname, den der Reverse so gelesen
     * hat (ein benutzerdefinierter Typ etwa). Neutrale Namen ohne Abbildung
     * faengt [OracleRoutineShape] vorher ab.
     */
    fun paramTypeSql(neutral: String): String = when (neutral.lowercase()) {
        // `identifier` ist der Surrogatschluessel des neutralen Modells; als
        // Parameter bleibt davon der Zahlentyp uebrig.
        "integer", "identifier", "smallint", "biginteger", "decimal" -> "NUMBER"
        // Anders als eine Spalte darf ein PL/SQL-Parameter `BOOLEAN` sein.
        // `NUMBER` daraus zu machen aenderte den Aufrufvertrag.
        "boolean" -> "BOOLEAN"
        "float" -> "BINARY_DOUBLE"
        "text", "char", "email", "enum" -> "VARCHAR2"
        "uuid" -> "VARCHAR2"
        "binary" -> "BLOB"
        "date", "datetime" -> "TIMESTAMP"
        "json" -> "JSON"
        "xml" -> "XMLTYPE"
        else -> neutral.uppercase()
    }

}
