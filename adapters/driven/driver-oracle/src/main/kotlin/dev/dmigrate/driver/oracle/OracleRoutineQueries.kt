package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.metadata.JdbcOperations

/**
 * Katalog-Abfragen hinter dem Routinen-Reverse: Quelltexte, Signaturen,
 * Routinen-Eigenschaften und die Trigger-Angaben.
 *
 * Getrennt von [OracleMetadataQueries], weil sie eine eigene Frage
 * beantworten und dort die Groessengrenze rissen.
 *
 * Alle Quelltexte kommen aus `ALL_SOURCE` — auch die der Trigger, obwohl
 * `ALL_TRIGGERS` mit `TRIGGER_BODY` eine eigene Spalte dafuer fuehrt. Die ist
 * vom Typ `LONG` und in einer Abfrage weder verkettbar noch mit anderen
 * Ausdruecken kombinierbar; `ALL_SOURCE` liefert denselben Text zeilenweise
 * als `VARCHAR2` und deckt damit alle drei Objektarten mit einem Lesepfad ab.
 */
internal object OracleRoutineQueries {

    /** Eine Objektart mit ihrem vollstaendigen Quelltext aus `ALL_SOURCE`. */
    data class RoutineSourceRow(
        /** `FUNCTION`, `PROCEDURE` oder `TRIGGER`. */
        val type: String,
        val name: String,
        val source: String,
    )

    /**
     * Quelltexte der Funktionen, Prozeduren und Trigger eines Schemas.
     *
     * `ALL_SOURCE` fuehrt eine Zeile je Quellzeile, jeweils mit dem
     * Zeilenumbruch am Ende; aneinandergehaengt ergeben sie den Text, wie er
     * gesendet wurde. Das einleitende `CREATE OR REPLACE` steht dort nicht —
     * Zeile 1 beginnt mit `FUNCTION`, `PROCEDURE` bzw. `TRIGGER`.
     *
     * Packages bleiben aussen vor: ihre Bestandteile tragen keinen eigenen
     * Namen im Objektkatalog, und das neutrale Modell hat keine Gruppierung
     * fuer sie ([OracleSchemaReader] meldet sie als ungelesen).
     */
    fun listSources(session: JdbcOperations, schema: String): List<RoutineSourceRow> = session.queryList(
        """
        SELECT type, name, line, text
        FROM all_source
        WHERE owner = ? AND type IN ('FUNCTION', 'PROCEDURE', 'TRIGGER')
        ORDER BY type, name, line
        """.trimIndent(),
        schema,
    ).groupBy { it.string("type") to it.string("name") }
        .map { (key, lines) ->
            val (type, name) = key
            RoutineSourceRow(
                type = type,
                name = name,
                source = lines.joinToString("") { it.stringOrNull("text").orEmpty() },
            )
        }

    /** Ein Parameter oder Rueckgabewert aus `ALL_ARGUMENTS`. */
    data class RoutineArgumentRow(
        val routine: String,
        /** `0` ist der Rueckgabewert einer Funktion, ab `1` die Parameter. */
        val position: Int,
        /** Beim Rueckgabewert leer. */
        val name: String?,
        /**
         * Bei eingebauten Typen der Typname (`NUMBER`, `VARCHAR2`), bei
         * benutzerdefinierten aber nur die **Kategorie**: `OBJECT`, `VARRAY`,
         * `TABLE`, `REF CURSOR`, `PL/SQL RECORD`. Der Name steht dann in
         * [typeName].
         */
        val dataType: String,
        /** Der Typname zu einer Kategorie in [dataType]; sonst leer. */
        val typeName: String?,
        /** `IN`, `OUT` oder `IN/OUT`. */
        val inOut: String,
        val defaulted: Boolean,
    )

    /**
     * Parameter und Rueckgabetypen der freistehenden Routinen eines Schemas.
     *
     * `data_level = 0` ist Pflicht: die Felder eines RECORD- oder
     * `%ROWTYPE`-Arguments stehen als eigene Zeilen mit eigenen Positionen ab
     * 1 darunter und wuerden sonst zu zusaetzlichen Parametern.
     * `package_name IS NULL` grenzt auf freistehende Routinen ein.
     */
    fun listArguments(session: JdbcOperations, schema: String): List<RoutineArgumentRow> = session.queryList(
        """
        SELECT object_name, position, argument_name, data_type, type_name, in_out, defaulted
        FROM all_arguments
        WHERE owner = ? AND package_name IS NULL AND data_level = 0
        ORDER BY object_name, sequence
        """.trimIndent(),
        schema,
    ).map { row ->
        RoutineArgumentRow(
            routine = row.string("object_name"),
            position = row.int("position") ?: 0,
            name = row.stringOrNull("argument_name"),
            // Ein Argument ohne Typnamen gibt es nicht; ein leerer Name faellt
            // beim Aufrufer als nicht abbildbar auf.
            dataType = row.stringOrNull("data_type").orEmpty(),
            typeName = row.stringOrNull("type_name"),
            inOut = row.stringOrNull("in_out").orEmpty(),
            defaulted = row.stringOrNull("defaulted") == "Y",
        )
    }

    /** Eigenschaften, die im Kopf der Routine stehen und nicht im Rumpf. */
    data class RoutinePropertyRow(
        val name: String,
        val deterministic: Boolean,
        /** `DEFINER` oder `CURRENT_USER`. */
        val authid: String?,
        /** Aendert die Aufrufform: `TABLE(f(...))` statt `f(...)`. */
        val pipelined: Boolean,
        /** Aggregatfunktion — das Verhalten steckt in einem Typ, nicht im Rumpf. */
        val aggregate: Boolean,
        /** SQL-Makro — der Rumpf liefert Text, der in die Abfrage eingesetzt wird. */
        val sqlMacro: Boolean,
        /** Polymorphe Tabellenfunktion — Signatur entsteht zur Laufzeit. */
        val polymorphic: Boolean,
        val parallelEnabled: Boolean,
        val resultCached: Boolean,
    )

    /**
     * Die Kopf-Eigenschaften je freistehender Routine.
     *
     * Sie stehen im Quelltext **vor** dem `IS`/`AS` und fielen beim Schnitt in
     * Kopf und Rumpf weg. Zwei davon traegt das neutrale Modell
     * (`deterministic`, `security`); die uebrigen nicht — der Aufrufer
     * entscheidet je Eigenschaft, ob sie das Lesen aufhaelt oder nur eine
     * Meldung wert ist.
     */
    fun listProperties(session: JdbcOperations, schema: String): List<RoutinePropertyRow> = session.queryList(
        """
        SELECT object_name, deterministic, authid, pipelined, aggregate,
               sql_macro, polymorphic, parallel, result_cache
        FROM all_procedures
        WHERE owner = ? AND procedure_name IS NULL
          AND object_type IN ('FUNCTION', 'PROCEDURE')
        """.trimIndent(),
        schema,
    ).map { row ->
        RoutinePropertyRow(
            name = row.string("object_name"),
            deterministic = row.stringOrNull("deterministic") == "YES",
            authid = row.stringOrNull("authid"),
            pipelined = row.stringOrNull("pipelined") == "YES",
            aggregate = row.stringOrNull("aggregate") == "YES",
            // `SQL_MACRO` und `POLYMORPHIC` tragen die Art als Text (`SCALAR`,
            // `TABLE`). Wo keine vorliegt, steht dort die **Zeichenkette**
            // `'NULL'`, nicht SQL-NULL — ein Test auf „nicht leer" hielte
            // deshalb jede gewoehnliche Routine fuer ein Makro.
            sqlMacro = row.declaredKind("sql_macro") != null,
            polymorphic = row.declaredKind("polymorphic") != null,
            parallelEnabled = row.stringOrNull("parallel") == "YES",
            resultCached = row.stringOrNull("result_cache") == "YES",
        )
    }

    /** Die strukturierten Trigger-Angaben aus `ALL_TRIGGERS`. */
    data class TriggerRow(
        val name: String,
        /** `BEFORE EACH ROW`, `AFTER STATEMENT`, `INSTEAD OF`, … */
        val triggerType: String,
        /** `INSERT OR UPDATE`, `DELETE`, … — ohne die `UPDATE OF`-Spaltenliste. */
        val triggeringEvent: String,
        val tableName: String,
        val tableOwner: String?,
        /** `TABLE` oder `VIEW`. */
        val baseObjectType: String?,
        /** Ohne die umgebenden Klammern und ohne `:`-Praefix vor `NEW`/`OLD`. */
        val whenClause: String?,
        /** `ENABLED` oder `DISABLED`. */
        val status: String?,
        /** `PL/SQL` oder `CALL`. */
        val actionType: String?,
        val crossEdition: String?,
        val referencingNames: String?,
    )

    /**
     * Trigger-Angaben ohne Quelltext.
     *
     * `DESCRIPTION` und `TRIGGER_BODY` bleiben aussen vor: beide sind `LONG`.
     * Was sie tragen, kommt aus [listSources] und den strukturierten Spalten
     * hier — mit einer Ausnahme, die keine strukturierte Quelle hat und
     * deshalb ueber [listUpdateOfColumns] laeuft.
     *
     * Der `base_object_type`-Filter fehlt absichtlich: `= 'TABLE'` schloesse
     * INSTEAD-OF-Trigger auf Sichten aus, die das neutrale Modell fuehrt.
     */
    fun listTriggers(session: JdbcOperations, schema: String): List<TriggerRow> = session.queryList(
        """
        SELECT trigger_name, trigger_type, triggering_event, table_name, table_owner,
               base_object_type, when_clause, status, action_type, crossedition, referencing_names
        FROM all_triggers
        WHERE owner = ?
        ORDER BY trigger_name
        """.trimIndent(),
        schema,
    ).map { row ->
        TriggerRow(
            name = row.string("trigger_name"),
            triggerType = row.stringOrNull("trigger_type").orEmpty().trim(),
            triggeringEvent = row.stringOrNull("triggering_event").orEmpty().trim(),
            tableName = row.string("table_name"),
            tableOwner = row.stringOrNull("table_owner"),
            baseObjectType = row.stringOrNull("base_object_type")?.trim(),
            whenClause = row.stringOrNull("when_clause")?.trim()?.ifEmpty { null },
            status = row.stringOrNull("status")?.trim(),
            actionType = row.stringOrNull("action_type")?.trim(),
            crossEdition = row.stringOrNull("crossedition")?.trim(),
            referencingNames = row.stringOrNull("referencing_names")?.trim(),
        )
    }

    /**
     * Die Spalten einer `UPDATE OF a, b`-Einschraenkung, je Trigger.
     *
     * Sie stehen **nicht** in `ALL_TRIGGERS`: `COLUMN_NAME` ist dort leer und
     * `TRIGGERING_EVENT` nennt nur `UPDATE`. `ALL_TRIGGER_COLS` fuehrt je
     * angesprochener Spalte eine Zeile und markiert mit `COLUMN_LIST = 'YES'`
     * genau die, die zur Einschraenkung gehoeren — die uebrigen Zeilen sind
     * blosse Rumpf-Verweise.
     */
    /**
     * Die `FOLLOWS`/`PRECEDES`-Beziehungen der Trigger eines Schemas.
     *
     * Die Klausel steht im Kopf und faellt beim Schnitt in Kopf und Rumpf weg;
     * `ALL_TRIGGERS` fuehrt sie nicht. Ohne sie feuerte ein wiedererzeugter
     * Trigger in einer anderen Reihenfolge als das Original.
     */
    fun listTriggerOrdering(session: JdbcOperations, schema: String): Set<String> = session.queryList(
        """
        SELECT trigger_name
        FROM all_trigger_ordering
        WHERE trigger_owner = ?
        """.trimIndent(),
        schema,
    ).map { it.string("trigger_name") }.toSet()

    fun listUpdateOfColumns(session: JdbcOperations, schema: String): Map<String, List<String>> = session.queryList(
        """
        SELECT trigger_name, column_name
        FROM all_trigger_cols
        WHERE trigger_owner = ? AND column_list = 'YES'
        ORDER BY trigger_name, column_name
        """.trimIndent(),
        schema,
    ).groupBy({ it.string("trigger_name") }, { it.string("column_name") })

    /**
     * Der Wert einer Spalte, die ihre Abwesenheit als Zeichenkette `'NULL'`
     * schreibt statt als SQL-NULL — oder null, wenn nichts deklariert ist.
     */
    private fun Map<String, Any?>.declaredKind(key: String): String? =
        stringOrNull(key)?.trim()?.takeUnless { it.isEmpty() || it.equals("NULL", ignoreCase = true) }

}
