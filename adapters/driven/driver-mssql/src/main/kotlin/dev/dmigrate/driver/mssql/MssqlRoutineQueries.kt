package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.metadata.JdbcOperations

/**
 * Katalog-Abfragen hinter dem Routinen-Reverse: Ruempfe, Signaturen und die
 * Kanten, die der Rumpf einer Routine auf andere Objekte zieht.
 *
 * Getrennt von [MssqlMetadataQueries], weil sie eine eigene Frage beantworten
 * und dort die Groessengrenze rissen.
 */
internal object MssqlRoutineQueries {

    /** Ein Routinen-Parameter aus `sys.parameters`. */
    data class RoutineParamRow(
        val routine: String,
        val name: String,
        val typeName: String,
        val isOutput: Boolean,
        val isReadonly: Boolean = false,
        val isReturnValue: Boolean,
        val precision: Int? = null,
        val scale: Int? = null,
    )

    /**
     * Parameter und Rueckgabetyp der Routinen eines Schemas.
     *
     * Aus dem Katalog statt aus dem Definitionstext: `sys.parameters` traegt
     * Name, Typ und Richtung strukturiert, und der Rueckgabewert einer Funktion
     * steht dort als Parameter mit `parameter_id = 0`.
     */
    fun listRoutineParameters(session: JdbcOperations, schema: String): List<RoutineParamRow> = session.queryList(
        """
        SELECT o.name AS routine_name, p.name AS param_name, t.name AS type_name,
               p.is_output, p.is_readonly, p.parameter_id, p.precision, p.scale
        FROM sys.objects o
        JOIN sys.parameters p ON p.object_id = o.object_id
        JOIN sys.types t ON t.user_type_id = p.user_type_id
        WHERE o.schema_id = SCHEMA_ID(?) AND o.is_ms_shipped = 0
          AND o.type IN ('P', 'FN', 'IF', 'TF')
        ORDER BY o.name, p.parameter_id
        """.trimIndent(),
        schema,
    ).map { row ->
        val id = row.int("parameter_id") ?: 0
        RoutineParamRow(
            routine = row.string("routine_name"),
            // Der Rueckgabewert traegt einen leeren Namen.
            name = (row["param_name"]?.toString() ?: "").removePrefix("@"),
            typeName = row.string("type_name"),
            isOutput = row.bool("is_output") == true,
            // READONLY gibt es in T-SQL nur fuer tabellenwertige Parameter.
            isReadonly = row.bool("is_readonly") == true,
            isReturnValue = id == 0,
            precision = row.int("precision"),
            scale = row.int("scale"),
        )
    }

    /**
     * Welche Objekte der Rumpf einer Routine oder eines Triggers anspricht.
     *
     * Aus `sys.sql_expression_dependencies` statt aus dem Rumpftext: der
     * Katalog loest den Verweis auf ein Objekt auf, ein regulaerer Ausdruck
     * raet ihn. `referenced_schema_name` bleibt leer, wenn der Rumpf den Namen
     * unqualifiziert nennt — der Name kommt deshalb aus dem aufgeloesten
     * Objekt, sofern es eins gibt.
     *
     * SQL Server loest Funktionsaufrufe beim `CREATE` auf. Ohne diese Kanten
     * ordnet der Migrations-Plan zwei voneinander abhaengige Funktionen nach
     * Namen, und der Server weist die erste ab.
     */
    fun listRoutineDependencies(session: JdbcOperations, schema: String): List<RoutineDependencyRow> =
        session.queryList(
            """
            SELECT o.name AS referencing_name,
                   COALESCE(ro.name, d.referenced_entity_name) AS referenced_name,
                   ro.type AS referenced_type
            FROM sys.sql_expression_dependencies d
            JOIN sys.objects o ON o.object_id = d.referencing_id
            JOIN sys.schemas s ON s.schema_id = o.schema_id
            LEFT JOIN sys.objects ro ON ro.object_id = d.referenced_id
            WHERE s.name = ? AND o.is_ms_shipped = 0
              AND o.type IN ('P', 'FN', 'IF', 'TF', 'TR')
            ORDER BY o.name, referenced_name
            """.trimIndent(),
            schema,
        ).map { row ->
            RoutineDependencyRow(
                referencing = row.string("referencing_name"),
                referenced = row.string("referenced_name"),
                referencedType = row["referenced_type"]?.toString()?.trim(),
            )
        }

    /** Eine Kante aus dem Rumpf einer Routine auf ein anderes Objekt. */
    data class RoutineDependencyRow(
        val referencing: String,
        val referenced: String,
        /** `U` Tabelle, `V` Sicht, `FN`/`IF`/`TF` Funktion, `P` Prozedur; null, wenn nicht aufloesbar. */
        val referencedType: String?,
    )

    /**
     * Eine gelesene Routine: Rumpf aus `sys.sql_modules`, plus die
     * Trigger-Angaben, die nur fuer `TR` gefuellt sind.
     */
    data class RoutineRow(
        val type: String,
        val name: String,
        val definition: String,
        val table: String?,
        val isInsert: Boolean,
        val isUpdate: Boolean,
        val isDelete: Boolean,
        val isInsteadOf: Boolean,
    )

    /**
     * Funktionen, Prozeduren und Trigger mit T-SQL-Rumpf.
     *
     * Der `JOIN` auf `sys.sql_modules` schliesst CLR-Routinen aus: ihr Code
     * liegt in einer Assembly und steht dort nicht. Genau diese bleiben
     * ungelesen und werden weiterhin gemeldet.
     */
    fun listRoutines(session: JdbcOperations, schema: String): List<RoutineRow> = session.queryList(
        """
        SELECT o.type AS object_type, o.name AS object_name, m.definition,
               OBJECT_NAME(o.parent_object_id) AS parent_name,
               OBJECTPROPERTY(o.object_id, 'ExecIsInsertTrigger') AS is_insert,
               OBJECTPROPERTY(o.object_id, 'ExecIsUpdateTrigger') AS is_update,
               OBJECTPROPERTY(o.object_id, 'ExecIsDeleteTrigger') AS is_delete,
               OBJECTPROPERTY(o.object_id, 'ExecIsInsteadOfTrigger') AS is_instead_of
        FROM sys.objects o
        JOIN sys.sql_modules m ON m.object_id = o.object_id
        WHERE o.schema_id = SCHEMA_ID(?) AND o.is_ms_shipped = 0
          AND o.type IN ('P', 'FN', 'IF', 'TF', 'TR')
        ORDER BY o.type, o.name
        """.trimIndent(),
        schema,
    ).mapNotNull { row ->
        val definition = row["definition"]?.toString() ?: return@mapNotNull null
        RoutineRow(
            type = row.string("object_type").trim(),
            name = row.string("object_name"),
            definition = definition,
            table = row["parent_name"]?.toString(),
            isInsert = row.int("is_insert") == 1,
            isUpdate = row.int("is_update") == 1,
            isDelete = row.int("is_delete") == 1,
            isInsteadOf = row.int("is_instead_of") == 1,
        )
    }
}
