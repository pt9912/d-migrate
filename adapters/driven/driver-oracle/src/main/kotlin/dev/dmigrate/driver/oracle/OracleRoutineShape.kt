package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming

/**
 * Das Urteil, ob eine Routine oder ein Trigger sich in Oracle darstellen
 * laesst — geteilt von Generate- und Diff-Pfad.
 *
 * Zwei getrennte Urteile ueber dieselbe Frage waeren der Fehler: der
 * Diff-Pfad rendert sonst etwas anderes als der Generate-Pfad, ohne dass es
 * auffaellt. Die Formen hinter den Urteilen stehen in [OracleRoutineDdl].
 */
internal object OracleRoutineShape {

    /** Was Oracle nicht ausdruecken kann, mit dem Grund fuer die E053-Meldung. */
    data class Unrenderable(val reason: String, val hint: String)

    /**
     * Der Grund, warum eine Funktion in Oracle nicht darstellbar ist — oder
     * null.
     *
     * `CREATE FUNCTION` verlangt eine `RETURN`-Klausel; ohne Rueckgabetyp im
     * Modell gaebe es nichts, was dort stehen koennte.
     */
    fun unsupportedFunctionShape(name: String, fn: FunctionDefinition): Unrenderable? = when {
        fn.returns == null -> Unrenderable(
            "Function '$name' carries no return type; PL/SQL requires a RETURN clause.",
            "Declare the function's return type in the schema definition.",
        )
        else -> unsupportedTypes(name, fn.parameters, fn.returns?.type)
    }

    fun unsupportedProcedureShape(name: String, proc: ProcedureDefinition): Unrenderable? =
        unsupportedTypes(name, proc.parameters, null)

    /**
     * Neutrale Typnamen, fuer die es in einer PL/SQL-Signatur keine
     * Entsprechung gibt.
     *
     * Der Fallback in [OracleRoutineDdl.paramTypeSql] reicht einen
     * unbekannten Namen unveraendert durch — richtig fuer einen
     * benutzerdefinierten Oracle-Typ, den der Reverse namentlich gelesen hat.
     * Ein **neutraler** Name ohne Abbildung ergaebe dagegen einen
     * Parametertyp, den der Server nicht kennt; deshalb faengt diese Liste
     * ihn vorher ab.
     */
    private fun unsupportedTypes(
        name: String,
        parameters: List<ParameterDefinition>,
        returnType: String?,
    ): Unrenderable? {
        val offending = (parameters.map { it.type } + listOfNotNull(returnType))
            .filter { it.lowercase() in UNRENDERABLE_NEUTRAL_TYPES }
            .distinct()
        if (offending.isEmpty()) return null
        return Unrenderable(
            "Routine '$name' uses the neutral type(s) ${offending.joinToString(", ")}, which Oracle " +
                "has no unconstrained parameter type for.",
            "Express the parameter as a concrete type (e.g. text, decimal, binary).",
        )
    }

    /**
     * `array` degradiert an einer Spalte zu `JSON`; als Parameter waere das
     * eine stille Umdeutung statt einer Speicherform. `geometry` und
     * `fulltext` sind fuer Oracle nicht gescoped, `time` hat keinen eigenen
     * Typ — die Spaltenform `VARCHAR2(n)` traegt eine Laenge, die eine
     * Signatur nicht tragen darf.
     */
    private val UNRENDERABLE_NEUTRAL_TYPES = setOf("array", "geometry", "fulltext", "time")

    /**
     * Der Grund, warum ein Trigger in Oracle nicht darstellbar ist — oder
     * null. [tables] entscheidet, ob das Ziel eine Tabelle oder eine Sicht
     * ist; fehlt es, wird die Tabellenannahme nicht geprueft.
     */
    fun unsupportedTriggerShape(
        name: String,
        trigger: TriggerDefinition,
        tables: Map<String, TableDefinition>?,
    ): Unrenderable? = triggerConditionProblem(name, trigger) ?: triggerTargetProblem(name, trigger, tables)

    /**
     * Eine `WHEN`-Bedingung gibt es nur am zeilenweisen `BEFORE`/`AFTER`-
     * Trigger: am Anweisungs-Trigger lehnt Oracle sie mit ORA-04077 ab, am
     * `INSTEAD OF`-Trigger mit ORA-25004.
     */
    private fun triggerConditionProblem(name: String, trigger: TriggerDefinition): Unrenderable? {
        if (trigger.condition == null) return null
        return when {
            trigger.timing == TriggerTiming.INSTEAD_OF -> Unrenderable(
                "Trigger '$name' is an INSTEAD OF trigger with a WHEN condition; Oracle rejects that " +
                    "combination (ORA-25004).",
                "Move the condition into the trigger body as an IF.",
            )
            trigger.forEach != TriggerForEach.ROW -> Unrenderable(
                "Trigger '$name' fires per statement and carries a WHEN condition; Oracle allows WHEN " +
                    "only on row-level triggers (ORA-04077).",
                "Make it a row-level trigger or move the condition into the body.",
            )
            else -> null
        }
    }

    /**
     * `INSTEAD OF` gibt es nur auf einer Sicht, `BEFORE`/`AFTER` nur auf einer
     * Tabelle — ein `BEFORE`-Trigger auf einer Sicht scheitert mit ORA-25001.
     */
    private fun triggerTargetProblem(
        name: String,
        trigger: TriggerDefinition,
        tables: Map<String, TableDefinition>?,
    ): Unrenderable? {
        if (tables == null) return null
        val targetIsTable = trigger.table in tables
        return when {
            trigger.timing == TriggerTiming.INSTEAD_OF && targetIsTable -> Unrenderable(
                "Trigger '$name' is an INSTEAD OF trigger on table '${trigger.table}'; Oracle allows them " +
                    "only on views.",
                "Rewrite it as a BEFORE or AFTER trigger.",
            )
            trigger.timing != TriggerTiming.INSTEAD_OF && !targetIsTable -> Unrenderable(
                "Trigger '$name' fires ${trigger.timing} on '${trigger.table}', which is not a table; " +
                    "Oracle allows only INSTEAD OF triggers on views (ORA-25001).",
                "Rewrite it as an INSTEAD OF trigger.",
            )
            else -> null
        }
    }

    /**
     * Was einen Rumpf unrenderbar macht — oder null.
     *
     * Ein fremder Dialekt bleibt liegen: d-migrate uebersetzt keine
     * prozeduralen Koerper. Ein PL/SQL-Rumpf dagegen wird gerendert, weil die
     * Signatur im Modell neben ihm steht.
     */
    fun bodyProblem(
        kind: String,
        name: String,
        body: String?,
        sourceDialect: String?,
    ): Unrenderable? {
        val kindLabel = kind.replaceFirstChar { it.uppercase() }
        return when {
            body == null -> Unrenderable(
                "$kindLabel '$name' has no body and must be manually implemented.",
                "Provide a $kind body in the schema definition.",
            )
            sourceDialect != null && sourceDialect != "oracle" -> Unrenderable(
                "$kindLabel '$name' was written for '$sourceDialect' and must be manually rewritten " +
                    "for Oracle.",
                "Rewrite the $kind body using PL/SQL syntax.",
            )
            else -> null
        }
    }

    /**
     * Keys, die auf denselben emittierten Namen fallen, nach diesem Namen.
     *
     * Das neutrale Modell unterscheidet Routinen ueber die Signatur
     * (`calc(in:integer)` neben `calc(in:text)`) und Trigger ueber die
     * Tabelle (`orders::touch` neben `items::touch`). Oracle kann beides
     * nicht: freistehende Routinen lassen sich nicht ueberladen (nur
     * Package-Routinen), und ein Triggername gilt schemaweit — ein zweiter
     * gleichnamiger Trigger auf einer anderen Tabelle wird mit ORA-04095
     * abgelehnt.
     */
    fun collidingNames(keys: Set<String>, nameOf: (String) -> String): Map<String, List<String>> =
        keys.groupBy(nameOf).filterValues { it.size > 1 }

    fun nameCollision(
        kind: String,
        name: String,
        colliding: Map<String, List<String>>,
    ): Unrenderable? {
        val keys = colliding[name] ?: return null
        val reason = if (kind == "trigger") {
            "Oracle trigger names are schema-global (ORA-04095)"
        } else {
            "Oracle has no overloading for standalone routines"
        }
        return Unrenderable(
            "The name '$name' is used by ${keys.size} ${kind}s (${keys.joinToString(", ")}); $reason.",
            "Rename them so each carries a schema-wide unique name.",
        )
    }

    /**
     * Oracle kennt kein Umbenennen freistehender Routinen: `RENAME f TO g`
     * antwortet mit ORA-03001, `ALTER FUNCTION f RENAME TO g` mit ORA-00922.
     * Nur `ALTER TRIGGER … RENAME TO` gibt es.
     */
    fun renameUnsupported(kind: String, from: String, to: String): Unrenderable = Unrenderable(
        "Oracle cannot rename a $kind ('$from' to '$to'); there is no RENAME for standalone routines.",
        "Drop the $kind and create it under the new name.",
    )
}
