package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ParameterDirection
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.core.model.canonicalOrder

/**
 * T-SQL-Huelle um einen Routinen-Rumpf: `CREATE OR ALTER FUNCTION`,
 * `… PROCEDURE` und `… TRIGGER`.
 *
 * Das neutrale Modell traegt den Rumpf ohne Huelle (`body`) und die Signatur
 * daneben (`parameters`, `returns`, `table`, `events`, `timing`) — aus diesen
 * Feldern setzt sich die Anweisung hier wieder zusammen. Was T-SQL nicht kennt,
 * wird gemeldet statt umgedeutet: `BEFORE`-Trigger, zeilenweise Trigger und
 * eine `WHEN`-Bedingung gibt es dort nicht. Render-Regeln:
 * `spec/ddl-generation-rules.md` (Abschnitte 10 und 11).
 */
internal object MssqlRoutineDdl {

    /** Was T-SQL nicht ausdruecken kann, mit dem Grund fuer die E053-Meldung. */
    data class Unrenderable(val reason: String, val hint: String)

    /**
     * Der Grund, warum eine Funktion in T-SQL nicht darstellbar ist — oder null.
     *
     * `CREATE FUNCTION` verlangt eine `RETURNS`-Klausel; ohne Rueckgabetyp im
     * Modell gaebe es nichts, was dort stehen koennte.
     */
    fun unsupportedFunctionShape(name: String, fn: FunctionDefinition): Unrenderable? = when {
        fn.returns == null -> Unrenderable(
            "Function '$name' carries no return type; T-SQL requires a RETURNS clause.",
            "Declare the function's return type in the schema definition.",
        )
        else -> unsupportedTypes(name, fn.parameters, fn.returns?.type)
    }

    fun unsupportedProcedureShape(name: String, proc: ProcedureDefinition): Unrenderable? =
        unsupportedTypes(name, proc.parameters, null)

    /**
     * Neutrale Typnamen, fuer die es in T-SQL keine Entsprechung gibt.
     *
     * Der Fallback in [paramTypeSql] reicht einen unbekannten Namen unveraendert
     * durch — das ist fuer einen benutzerdefinierten T-SQL-Typ richtig, den der
     * Reverse namentlich gelesen hat. Ein **neutraler** Name ohne Abbildung
     * dagegen ergaebe `@x ARRAY`, was der Server mit „Cannot find data type"
     * ablehnt. Deshalb faengt diese Liste sie vorher ab.
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
            "Routine '$name' uses the neutral type(s) ${offending.joinToString(", ")}, which SQL Server " +
                "has no parameter type for.",
            "Express the parameter as a concrete type (e.g. text, integer, binary).",
        )
    }

    private val UNRENDERABLE_NEUTRAL_TYPES = setOf("array", "fulltext")

    /** Setzt [unsupportedFunctionShape] `== null` voraus. */
    fun functionSql(
        name: String,
        fn: FunctionDefinition,
        body: String,
        quote: (String) -> String,
    ): String = buildString {
        append("CREATE OR ALTER FUNCTION ${quote(name)}(${parameterList(fn.parameters)})\n")
        append("RETURNS ${returnTypeSql(checkNotNull(fn.returns))} AS\n")
        append(body.trim())
        append("\n;")
    }

    fun procedureSql(
        name: String,
        proc: ProcedureDefinition,
        body: String,
        quote: (String) -> String,
    ): String = buildString {
        // Eine leere Parameterliste schreibt T-SQL bei Prozeduren ohne Klammern:
        // `CREATE PROCEDURE p () AS` scheitert an „Incorrect syntax near ')'".
        // Funktionen verlangen die Klammern umgekehrt immer.
        val params = if (proc.parameters.isEmpty()) "" else "(${parameterList(proc.parameters)})"
        append("CREATE OR ALTER PROCEDURE ${quote(name)}$params AS\n")
        append(body.trim())
        append("\n;")
    }

    /**
     * Keys, die auf denselben emittierten Namen fallen, nach diesem Namen.
     *
     * Das neutrale Modell unterscheidet Routinen ueber die Signatur
     * (`calc(in:integer)` neben `calc(in:text)`) und Trigger ueber die Tabelle
     * (`orders::touch` neben `items::touch`). T-SQL kann beides nicht: Routinen
     * lassen sich nicht ueberladen, und Triggernamen gelten schemaweit. Beide
     * `CREATE OR ALTER` traegen denselben Namen, der zweite ersetzte den ersten
     * stillschweigend.
     */
    fun collidingNames(keys: Set<String>, nameOf: (String) -> String): Map<String, List<String>> =
        keys.groupBy(nameOf).filterValues { it.size > 1 }

    fun nameCollision(
        kind: String,
        name: String,
        colliding: Map<String, List<String>>,
    ): Unrenderable? {
        val keys = colliding[name] ?: return null
        val reason = if (kind == "trigger") "SQL Server trigger names are schema-global" else "SQL Server has no routine overloading"
        return Unrenderable(
            "The name '$name' is used by ${keys.size} ${kind}s (${keys.joinToString(", ")}); $reason.",
            "Rename them so each carries a schema-wide unique name.",
        )
    }

    /**
     * Was einen Rumpf unrenderbar macht — oder null, wenn er sich rendern
     * laesst.
     *
     * Ein fremder Dialekt bleibt liegen: d-migrate uebersetzt keine
     * prozeduralen Koerper. Ein T-SQL-Rumpf dagegen wird gerendert, seit die
     * Signatur neben ihm im Modell steht.
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
            sourceDialect != null && sourceDialect != "mssql" -> Unrenderable(
                "$kindLabel '$name' was written for '$sourceDialect' and must be manually rewritten " +
                    "for SQL Server.",
                "Rewrite the $kind body using T-SQL syntax.",
            )
            else -> null
        }
    }

    /** Setzt [unsupportedTriggerShape] `== null` voraus. */
    fun triggerSql(
        name: String,
        trigger: TriggerDefinition,
        body: String,
        quote: (String) -> String,
    ): String {
        val timing = if (trigger.timing == TriggerTiming.INSTEAD_OF) "INSTEAD OF" else "AFTER"
        // T-SQL trennt die Ereignisse mit Komma; `OR` ist die PostgreSQL-Form.
        val events = trigger.events.canonicalOrder().joinToString(", ") { it.name }
        return buildString {
            append("CREATE OR ALTER TRIGGER ${quote(name)} ON ${quote(trigger.table)}\n")
            append("$timing $events AS\n")
            append(body.trim())
            append("\n;")
        }
    }

    /**
     * Der Grund, warum ein Trigger in T-SQL nicht darstellbar ist — oder null.
     *
     * SQL Server kennt nur `AFTER` und `INSTEAD OF`, feuert je Anweisung statt
     * je Zeile und hat keine `WHEN`-Bedingung. Ein Rumpf, der auf zeilenweises
     * Feuern gebaut ist, liefe dort still falsch; deshalb wird er nicht
     * gerendert.
     */
    fun unsupportedTriggerShape(name: String, trigger: TriggerDefinition): Unrenderable? = when {
        trigger.timing == TriggerTiming.BEFORE -> Unrenderable(
            "Trigger '$name' fires BEFORE; SQL Server only knows AFTER and INSTEAD OF triggers.",
            "Rewrite it as an INSTEAD OF trigger or move the logic into a constraint or the caller.",
        )
        trigger.forEach == TriggerForEach.ROW -> Unrenderable(
            "Trigger '$name' fires FOR EACH ROW; SQL Server triggers fire once per statement.",
            "Rewrite the body set-based over the `inserted` and `deleted` pseudo-tables.",
        )
        trigger.condition != null -> Unrenderable(
            "Trigger '$name' carries a WHEN condition; T-SQL trigger syntax has none.",
            "Move the condition into the trigger body as an IF over `inserted`/`deleted`.",
        )
        else -> null
    }

    /**
     * `DROP` zu einer der drei `CREATE OR ALTER`-Formen, oder null wenn [sql]
     * keine davon ist.
     */
    fun invert(sql: String, bracketedNameAfter: (String, String) -> String): String? {
        val kind = ROUTINE_KINDS.firstOrNull { sql.startsWith("CREATE OR ALTER $it", ignoreCase = true) }
            ?: return null
        return "DROP $kind IF EXISTS ${bracketedNameAfter(sql, "CREATE OR ALTER $kind")};"
    }

    private val ROUTINE_KINDS = listOf("FUNCTION", "PROCEDURE", "TRIGGER", "VIEW")

    /**
     * `@name TYPE` je Parameter, `OUTPUT` bei Rueckgaberichtung.
     *
     * Der Reverse legt Parameternamen ohne `@` ab (das Zeichen ist T-SQL-
     * Syntax, nicht Teil des Namens); handgeschriebene Schemata duerfen es
     * mitbringen. Beide Formen ergeben denselben Parameter.
     */
    private fun parameterList(parameters: List<ParameterDefinition>): String =
        parameters.joinToString(", ") { p ->
            val output = if (p.direction == ParameterDirection.IN) "" else " OUTPUT"
            "@${p.name.removePrefix("@")} ${paramTypeSql(p.type)}$output"
        }

    private fun returnTypeSql(returns: ReturnType): String {
        // Eine Inline-Tabellenfunktion gibt eine Tabelle zurueck; ihre Spalten
        // ergeben sich aus dem `RETURN (SELECT …)` im Rumpf.
        if (returns.type.equals("table", ignoreCase = true)) return "TABLE"
        val base = paramTypeSql(returns.type)
        if (returns.precision == null || !base.startsWith("DECIMAL")) return base
        val scale = returns.scale?.let { ",$it" } ?: ""
        return "DECIMAL(${returns.precision}$scale)"
    }

    /**
     * Neutraler Typname auf T-SQL.
     *
     * Das neutrale Modell fuehrt Parametertypen ohne Laenge (siehe
     * [MssqlTypeMapping.mapParamType]); die Textform wird deshalb als `MAX`
     * gerendert — ein enger deklarierter Quell-Parameter wird dabei weiter,
     * nie enger.
     */
    private fun paramTypeSql(neutral: String): String = when (neutral.lowercase()) {
        // `identifier` ist der Surrogatschluessel des neutralen Modells; als
        // Parameter ist davon der Zahlentyp uebrig, nicht die IDENTITY-Eigenschaft.
        "integer", "identifier" -> "INT"
        "biginteger" -> "BIGINT"
        "smallint" -> "SMALLINT"
        "boolean" -> "BIT"
        // Ohne Praezision ist `DECIMAL` in T-SQL `DECIMAL(18,0)` — ausgeschrieben
        // steht das in der DDL, statt als stille Voreinstellung zu wirken.
        "decimal" -> "DECIMAL(18,0)"
        "float" -> "FLOAT"
        "text", "char", "email", "json", "enum" -> "NVARCHAR(MAX)"
        "uuid" -> "UNIQUEIDENTIFIER"
        "binary" -> "VARBINARY(MAX)"
        "date" -> "DATE"
        "time" -> "TIME"
        "datetime" -> "DATETIME2"
        "xml" -> "XML"
        "geometry" -> "GEOMETRY"
        // Alles Weitere ist ein nativer Typname, den der Reverse so gelesen hat
        // (ein benutzerdefinierter Typ etwa). Neutrale Namen ohne Abbildung
        // faengt `unsupportedTypes` vorher ab.
        else -> neutral.uppercase()
    }

}
