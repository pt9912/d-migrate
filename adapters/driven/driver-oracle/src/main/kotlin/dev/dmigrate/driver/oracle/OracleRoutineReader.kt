package dev.dmigrate.driver.oracle

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.model.DependencyInfo
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ParameterDirection
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.core.model.RoutineSecurity
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.driver.SkippedObject
import dev.dmigrate.driver.metadata.JdbcOperations

/**
 * Baut Funktionen, Prozeduren und Trigger aus den Katalogzeilen von
 * [OracleRoutineQueries] ins neutrale Modell.
 *
 * Der Rumpf ist das, was hinter dem einleitenden `IS`/`AS` steht; Signatur und
 * Trigger-Kontext stehen im Modell als eigene Felder daneben. `sourceDialect`
 * haelt fest, woher der Rumpf stammt: auf einem anderen Ziel ist PL/SQL nicht
 * gueltig.
 *
 * Was das Modell nicht tragen kann, wird **gemeldet statt geraten**. Der
 * teuerste Fehler waere eine Routine, die gelesen aussieht und beim
 * Zurueckschreiben etwas anderes tut als das Original.
 */
internal object OracleRoutineReader {

    data class Routines(
        val functions: Map<String, FunctionDefinition>,
        val procedures: Map<String, ProcedureDefinition>,
        val triggers: Map<String, TriggerDefinition>,
    )

    fun read(
        session: JdbcOperations,
        schema: String,
        options: SchemaReadOptions,
        notes: MutableList<SchemaReadNote>,
        skipped: MutableList<SkippedObject>,
    ): Routines {
        val collector = Collector(notes, skipped)
        val sources = OracleRoutineQueries.listSources(session, schema).associateBy { it.type to it.name }
        val arguments = OracleRoutineQueries.listArguments(session, schema).groupBy { it.routine }
        val properties = OracleRoutineQueries.listProperties(session, schema).associateBy { it.name }
        val dependencies = OracleMetadataQueries.listRoutineDependencies(session, schema)

        if (options.includeFunctions || options.includeProcedures) {
            sources.values
                .filter { it.type != "TRIGGER" }
                .filter { if (it.type == "FUNCTION") options.includeFunctions else options.includeProcedures }
                .forEach { source ->
                    collector.addRoutine(
                        source = source,
                        arguments = arguments[source.name].orEmpty(),
                        properties = properties[source.name],
                        dependencies = dependencyInfo(dependencies[source.type to source.name]),
                    )
                }
        }
        if (options.includeTriggers) {
            val updateOfColumns = OracleRoutineQueries.listUpdateOfColumns(session, schema)
            val ordered = OracleRoutineQueries.listTriggerOrdering(session, schema)
            OracleRoutineQueries.listTriggers(session, schema).forEach { row ->
                collector.addTrigger(
                    row = row,
                    source = sources["TRIGGER" to row.name],
                    updateOfColumns = updateOfColumns[row.name].orEmpty(),
                    ordered = row.name in ordered,
                    ownSchema = schema,
                    dependencies = dependencyInfo(dependencies["TRIGGER" to row.name]),
                )
            }
        }
        return collector.result()
    }

    /**
     * Fehlt das Objekt im Abhaengigkeitsergebnis, ist das kein Beleg fuer
     * „hat keine" — eine Routine ohne jede Kante gibt es, aber sie ist von
     * fehlenden Leserechten nicht zu unterscheiden. `null` bleibt deshalb
     * `null`, statt eine leere, verifizierte Projektion zu behaupten.
     */
    private fun dependencyInfo(row: OracleMetadataQueries.ViewDependencyRow?): DependencyInfo? =
        row?.let {
            DependencyInfo(
                tables = it.tables,
                views = it.views,
                projectionSources = listOf(DEPENDENCY_SOURCE),
            )
        }

    private const val DEPENDENCY_SOURCE = "ALL_DEPENDENCIES"
    private const val DIALECT = "oracle"
    private const val LANGUAGE = "plsql"

    /** Die Korrelationsnamen, die Oracle ohne eigene `REFERENCING`-Klausel fuehrt. */
    private const val DEFAULT_REFERENCING = "REFERENCING NEW AS NEW OLD AS OLD"

    /**
     * Sammelt die gelesenen Objekte und die Gruende fuer die uebergangenen.
     *
     * Eigener Typ, damit die Pruefungen je Objektart kurze Funktionen bleiben
     * und nicht jede eine wachsende Parameterliste durchreichen muss.
     */
    private class Collector(
        private val notes: MutableList<SchemaReadNote>,
        private val skipped: MutableList<SkippedObject>,
    ) {
        private val functions = mutableMapOf<String, FunctionDefinition>()
        private val procedures = mutableMapOf<String, ProcedureDefinition>()
        private val triggers = mutableMapOf<String, TriggerDefinition>()

        fun result() = Routines(functions, procedures, triggers)

        fun addRoutine(
            source: OracleRoutineQueries.RoutineSourceRow,
            arguments: List<OracleRoutineQueries.RoutineArgumentRow>,
            properties: OracleRoutineQueries.RoutinePropertyRow?,
            dependencies: DependencyInfo?,
        ) {
            val kind = source.type.lowercase()
            routineProblem(source, arguments, properties)?.let { (code, reason) ->
                skip(kind, source.name, code, reason)
                return
            }
            val body = checkNotNull(OracleRoutineBody.splitRoutine(source.source)).body
            noteDroppedHints(kind, source.name, properties)
            val parameters = parametersOf(arguments)
            val key = ObjectKeyCodec.routineKey(source.name, parameters)
            if (source.type == "FUNCTION") {
                functions[key] = FunctionDefinition(
                    parameters = parameters,
                    returns = returnTypeOf(arguments),
                    language = LANGUAGE,
                    // `NO` ist Oracles Voreinstellung und heisst im Modell
                    // „Dialekt-Default gilt" -- als `false` abgelegt plante
                    // jeder Lauf erneut ein `ReplaceFunction`, weil eine
                    // Schemadatei ohne die Angabe `null` traegt.
                    deterministic = properties?.deterministic?.takeIf { it },
                    body = body,
                    dependencies = dependencies,
                    sourceDialect = DIALECT,
                    security = securityOf(properties),
                )
            } else {
                procedures[key] = ProcedureDefinition(
                    parameters = parameters,
                    language = LANGUAGE,
                    body = body,
                    dependencies = dependencies,
                    sourceDialect = DIALECT,
                    security = securityOf(properties),
                )
            }
        }

        /** Der Grund, eine Routine zu uebergehen — Code und Text, oder null. */
        private fun routineProblem(
            source: OracleRoutineQueries.RoutineSourceRow,
            arguments: List<OracleRoutineQueries.RoutineArgumentRow>,
            properties: OracleRoutineQueries.RoutinePropertyRow?,
        ): Pair<String, String>? = when {
            // Beide aendern die Aufrufform, nicht nur eine Eigenschaft: eine
            // pipelined Funktion wird ueber `TABLE(...)` aufgerufen, eine
            // Aggregatfunktion hat statt eines Rumpfes einen Implementierungstyp.
            properties?.pipelined == true -> "R359" to
                "The function is PIPELINED; the neutral model has no field for it and regenerating " +
                "would produce a function that is called differently."
            properties?.aggregate == true -> "R359" to
                "The function is a user-defined aggregate (AGGREGATE USING); its behaviour lives in an " +
                "implementation type, not in a body."
            properties?.sqlMacro == true -> "R359" to
                "The function is a SQL macro; its body returns text that Oracle splices into the calling " +
                "statement rather than executing."
            properties?.polymorphic == true -> "R359" to
                "The function is a polymorphic table function; its signature is determined at run time."
            arguments.any { it.defaulted } -> "R360" to
                "A parameter carries a DEFAULT; the neutral model has no field for it, so callers that " +
                "rely on omitting the argument would break."
            arguments.any { it.dataType.isEmpty() } -> "R360" to
                "A parameter has no readable type name in ALL_ARGUMENTS."
            // `DATA_TYPE` traegt bei benutzerdefinierten Typen nur die
            // Kategorie. Ohne `TYPE_NAME` daneben gaebe es nichts, was in
            // einer Signatur stehen koennte -- `IN REF CURSOR` etwa ist kein
            // gueltiges PL/SQL.
            arguments.any { unnameableType(it) } -> "R360" to
                "A parameter or return value has the type category " +
                "'${arguments.first { unnameableType(it) }.dataType}', which ALL_ARGUMENTS lists without a " +
                "usable type name; the neutral model has no form for it."
            OracleRoutineBody.splitRoutine(source.source) == null -> "R358" to
                "The stored source carries no top-level IS or AS, so its body cannot be separated from " +
                "the signature."
            else -> null
        }

        /**
         * `PARALLEL_ENABLE` und `RESULT_CACHE` aendern nur, wie der Server die
         * Routine ausfuehrt, nicht was sie tut — sie werden gemeldet, aber
         * halten das Lesen nicht auf.
         */
        private fun noteDroppedHints(
            kind: String,
            name: String,
            properties: OracleRoutineQueries.RoutinePropertyRow?,
        ) {
            val dropped = buildList {
                if (properties?.parallelEnabled == true) add("PARALLEL_ENABLE")
                if (properties?.resultCached == true) add("RESULT_CACHE")
            }
            if (dropped.isEmpty()) return
            notes += SchemaReadNote(
                severity = SchemaReadSeverity.WARNING,
                code = "R363",
                objectName = name,
                message = "The $kind '$name' carries ${dropped.joinToString(" and ")}; the neutral model " +
                    "has no field for it, so a regenerated $kind runs without it.",
                hint = "Re-apply the clause manually if the execution behaviour matters.",
            )
        }

        fun addTrigger(
            row: OracleRoutineQueries.TriggerRow,
            source: OracleRoutineQueries.RoutineSourceRow?,
            updateOfColumns: List<String>,
            ordered: Boolean,
            ownSchema: String,
            dependencies: DependencyInfo?,
        ) {
            triggerProblem(row, source, ordered, ownSchema)?.let { (code, reason) ->
                skip("trigger", row.name, code, reason)
                return
            }
            val body = checkNotNull(OracleRoutineBody.splitTrigger(checkNotNull(source).source)).body
            noteDroppedUpdateOf(row.name, updateOfColumns)
            triggers[ObjectKeyCodec.triggerKey(row.tableName, row.name)] = TriggerDefinition(
                table = row.tableName,
                events = checkNotNull(triggerEvents(row.triggeringEvent)),
                timing = checkNotNull(triggerTiming(row.triggerType)),
                forEach = triggerForEach(row.triggerType),
                condition = row.whenClause,
                body = body,
                dependencies = dependencies,
                sourceDialect = DIALECT,
            )
        }

        /** Der Grund, einen Trigger zu uebergehen — Code und Text, oder null. */
        private fun triggerProblem(
            row: OracleRoutineQueries.TriggerRow,
            source: OracleRoutineQueries.RoutineSourceRow?,
            ordered: Boolean,
            ownSchema: String,
        ): Pair<String, String>? = when {
            // Ein Trigger auf einer Tabelle eines anderen Schemas: das Modell
            // fuehrt nur den blanken Tabellennamen, und der zeigt hier auf
            // nichts -- die Struktur-Validierung des Generate-Pfads meldete
            // ihn spaeter als E018 „references non-existent table".
            row.tableOwner != null && row.tableOwner != ownSchema -> "R361" to
                "The trigger fires on '${row.tableOwner}.${row.tableName}', a table outside the read " +
                "schema; the neutral model carries the bare table name only."
            // `FOLLOWS`/`PRECEDES` steht im Kopf und faellt beim Schnitt weg.
            // Ohne die Klausel feuerte der wiedererzeugte Trigger in einer
            // anderen Reihenfolge als das Original.
            ordered -> "R361" to
                "The trigger declares a FOLLOWS or PRECEDES ordering; the neutral model has no field " +
                "for it, so a regenerated trigger would fire in a different order."
            triggerTiming(row.triggerType) == null -> "R361" to
                "The trigger fires as '${row.triggerType}', which is not one of BEFORE, AFTER or " +
                "INSTEAD OF; compound and system triggers have no neutral representation."
            triggerEvents(row.triggeringEvent) == null -> "R361" to
                "The trigger fires on '${row.triggeringEvent}', which is not a DML event; the neutral " +
                "model carries INSERT, UPDATE and DELETE only."
            row.actionType != null && row.actionType != "PL/SQL" -> "R361" to
                "The trigger's action is '${row.actionType}' rather than a PL/SQL block."
            row.status == "DISABLED" -> "R361" to
                "The trigger is DISABLED; the neutral model has no field for it, so a regenerated " +
                "trigger would start firing."
            row.crossEdition != null && row.crossEdition != "NO" -> "R361" to
                "The trigger is a crossedition trigger; edition-based redefinition has no neutral " +
                "representation."
            row.referencingNames != null && row.referencingNames != DEFAULT_REFERENCING -> "R361" to
                "The trigger declares custom correlation names ('${row.referencingNames}'); the body " +
                "uses them and would not compile without the clause."
            source == null -> "R358" to
                "ALL_TRIGGERS lists the trigger but ALL_SOURCE holds no text for it."
            OracleRoutineBody.splitTrigger(source.source) == null -> "R358" to
                "The stored source carries no top-level DECLARE or BEGIN, so its body cannot be " +
                "separated from the header."
            else -> null
        }

        /**
         * `UPDATE OF a, b` schraenkt ein, bei welchen Spalten der Trigger
         * feuert. Das neutrale Modell fuehrt die Einschraenkung nicht — der
         * wiedererzeugte Trigger feuert bei jeder Aenderung, also oefter als
         * das Original.
         */
        private fun noteDroppedUpdateOf(name: String, columns: List<String>) {
            if (columns.isEmpty()) return
            notes += SchemaReadNote(
                severity = SchemaReadSeverity.WARNING,
                code = "R362",
                objectName = name,
                message = "Trigger '$name' fires only for UPDATE OF ${columns.joinToString(", ")}; the " +
                    "neutral model has no field for that restriction, so a regenerated trigger fires " +
                    "on every update and two triggers differing only in the column list compare as equal.",
                hint = "Re-add the UPDATE OF column list manually after regenerating.",
            )
        }

        /** Eine Kategorie, zu der kein in einer Signatur nennbarer Name kommt. */
        private fun unnameableType(arg: OracleRoutineQueries.RoutineArgumentRow): Boolean =
            OracleTypeMapping.isUserDefinedCategory(arg.dataType) &&
                OracleTypeMapping.mapUserDefinedParamType(arg.dataType, arg.typeName) == null

        private fun skip(kind: String, name: String, code: String, reason: String) {
            skipped += SkippedObject(type = kind, name = name, reason = reason, code = code)
            notes += SchemaReadNote(
                severity = SchemaReadSeverity.WARNING,
                code = code,
                objectName = name,
                message = "The $kind '$name' is not read for oracle: $reason",
            )
        }
    }

    private fun parametersOf(
        arguments: List<OracleRoutineQueries.RoutineArgumentRow>,
    ): List<ParameterDefinition> = arguments
        // Position 0 ist der Rueckgabewert einer Funktion, kein Parameter.
        .filter { it.position > 0 }
        .map { arg ->
            ParameterDefinition(
                name = arg.name.orEmpty(),
                type = neutralTypeOf(arg),
                direction = directionOf(arg.inOut),
            )
        }

    /**
     * Kein `precision`/`scale`: PL/SQL laesst einen beschraenkten
     * Rueckgabetyp nicht zu, und was `ALL_ARGUMENTS` dort fuehrt, ist die
     * Speichergroesse — sie abzulegen behauptete eine Einschraenkung, die die
     * Routine nicht traegt.
     */
    private fun returnTypeOf(
        arguments: List<OracleRoutineQueries.RoutineArgumentRow>,
    ): ReturnType? = arguments.firstOrNull { it.position == 0 }
        ?.let { ReturnType(type = neutralTypeOf(it)) }

    /**
     * Der neutrale Typname eines Arguments. Bei einem benutzerdefinierten Typ
     * traegt `DATA_TYPE` nur die Kategorie; der Name kommt dann aus
     * `TYPE_NAME`.
     */
    private fun neutralTypeOf(arg: OracleRoutineQueries.RoutineArgumentRow): String =
        OracleTypeMapping.mapUserDefinedParamType(arg.dataType, arg.typeName)
            ?: OracleTypeMapping.mapParamType(arg.dataType)

    private fun directionOf(inOut: String): ParameterDirection = when (inOut.uppercase()) {
        "OUT" -> ParameterDirection.OUT
        "IN/OUT" -> ParameterDirection.INOUT
        else -> ParameterDirection.IN
    }

    private fun securityOf(properties: OracleRoutineQueries.RoutinePropertyRow?): RoutineSecurity? =
        when (properties?.authid) {
            "CURRENT_USER" -> RoutineSecurity.INVOKER
            "DEFINER" -> RoutineSecurity.DEFINER
            else -> null
        }

    /**
     * `TRIGGER_TYPE` traegt Zeitpunkt und Granularitaet in einem Wert:
     * `BEFORE EACH ROW`, `AFTER STATEMENT`, `INSTEAD OF`. Was keinem der drei
     * Zeitpunkte entspricht (`COMPOUND`, die Ereignis-Trigger auf Schema und
     * Datenbank), liefert null.
     */
    private fun triggerTiming(triggerType: String): TriggerTiming? = when {
        triggerType.startsWith("INSTEAD OF") -> TriggerTiming.INSTEAD_OF
        triggerType.startsWith("BEFORE ") -> TriggerTiming.BEFORE
        triggerType.startsWith("AFTER ") -> TriggerTiming.AFTER
        else -> null
    }

    /** `INSTEAD OF`-Trigger feuern in Oracle immer zeilenweise. */
    private fun triggerForEach(triggerType: String): TriggerForEach =
        if (triggerType.contains("EACH ROW") || triggerType.startsWith("INSTEAD OF")) {
            TriggerForEach.ROW
        } else {
            TriggerForEach.STATEMENT
        }

    /**
     * `TRIGGERING_EVENT` nennt die Ereignisse mit `OR` verbunden. Die
     * `UPDATE OF`-Spaltenliste steht dort **nicht** — sie kommt aus
     * `ALL_TRIGGER_COLS`.
     */
    private fun triggerEvents(triggeringEvent: String): Set<TriggerEvent>? {
        val events = triggeringEvent.split(" OR ").map { it.trim() }.filter { it.isNotEmpty() }
        if (events.isEmpty()) return null
        val mapped = events.map { event ->
            when (event.uppercase()) {
                "INSERT" -> TriggerEvent.INSERT
                "UPDATE" -> TriggerEvent.UPDATE
                "DELETE" -> TriggerEvent.DELETE
                else -> return null
            }
        }
        return mapped.toSet()
    }
}
