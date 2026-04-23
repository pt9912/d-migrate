package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.metadata.*

/**
 * Shared JDBC metadata queries for MySQL.
 *
 * Operates on an already-borrowed connection via [JdbcOperations].
 * Uses `information_schema` with `lower_case_table_names`-aware lookups.
 */
object MysqlMetadataQueries {

    fun listTableRefs(session: JdbcOperations, schemaName: String): List<TableRef> {
        return session.queryList(
            """
            SELECT table_name, table_schema, table_type
            FROM information_schema.tables
            WHERE table_schema = ?
              AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """.trimIndent(), schemaName,
        ).map { row ->
            TableRef(
                name = row["table_name"] as String,
                schema = row["table_schema"] as? String,
                type = row["table_type"] as? String ?: "BASE TABLE",
            )
        }
    }

    fun listTableEngine(session: JdbcOperations, schemaName: String, table: String): String? {
        val row = session.querySingle(
            "SELECT engine FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
            schemaName, table,
        )
        return row?.get("engine") as? String
    }

    fun listColumns(session: JdbcOperations, schemaName: String, table: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT column_name, data_type, column_type, is_nullable,
                   column_default, ordinal_position, extra,
                   character_maximum_length, numeric_precision, numeric_scale
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ?
            ORDER BY ordinal_position
            """.trimIndent(), schemaName, table,
        )
    }

    fun listPrimaryKeyColumns(session: JdbcOperations, schemaName: String, table: String): List<String> {
        return session.queryList(
            """
            SELECT column_name
            FROM information_schema.key_column_usage
            WHERE table_schema = ? AND table_name = ?
              AND constraint_name = 'PRIMARY'
            ORDER BY ordinal_position
            """.trimIndent(), schemaName, table,
        ).map { it["column_name"] as String }
    }

    fun listForeignKeys(session: JdbcOperations, schemaName: String, table: String): List<ForeignKeyProjection> {
        val rows = session.queryList(
            """
            SELECT kcu.constraint_name, kcu.column_name,
                   kcu.referenced_table_name, kcu.referenced_column_name,
                   rc.delete_rule, rc.update_rule
            FROM information_schema.key_column_usage kcu
            JOIN information_schema.referential_constraints rc
              ON kcu.constraint_name = rc.constraint_name
              AND kcu.table_schema = rc.constraint_schema
            WHERE kcu.table_schema = ? AND kcu.table_name = ?
              AND kcu.referenced_table_name IS NOT NULL
            ORDER BY kcu.constraint_name, kcu.ordinal_position
            """.trimIndent(), schemaName, table,
        )
        return rows.groupBy { it["constraint_name"] as String }.map { (name, fkRows) ->
            val first = fkRows.first()
            ForeignKeyProjection(
                name = name,
                columns = fkRows.map { it["column_name"] as String },
                referencedTable = first["referenced_table_name"] as String,
                referencedColumns = fkRows.map { it["referenced_column_name"] as String },
                onDelete = (first["delete_rule"] as? String)?.takeIf { it != "NO ACTION" && it != "RESTRICT" },
                onUpdate = (first["update_rule"] as? String)?.takeIf { it != "NO ACTION" && it != "RESTRICT" },
            )
        }
    }

    fun listCheckConstraints(session: JdbcOperations, schemaName: String, table: String): List<ConstraintProjection> {
        return session.queryList(
            """
            SELECT tc.constraint_name, cc.check_clause
            FROM information_schema.table_constraints tc
            JOIN information_schema.check_constraints cc
              ON tc.constraint_name = cc.constraint_name
              AND tc.constraint_schema = cc.constraint_schema
            WHERE tc.constraint_type = 'CHECK'
              AND tc.table_schema = ? AND tc.table_name = ?
            ORDER BY tc.constraint_name
            """.trimIndent(), schemaName, table,
        ).map { row ->
            ConstraintProjection(
                name = row["constraint_name"] as String,
                type = "CHECK",
                expression = row["check_clause"] as? String,
            )
        }
    }

    fun listIndices(session: JdbcOperations, schemaName: String, table: String): List<IndexProjection> {
        val rows = session.queryList(
            """
            SELECT index_name, column_name, non_unique, seq_in_index, index_type
            FROM information_schema.statistics
            WHERE table_schema = ? AND table_name = ?
              AND index_name != 'PRIMARY'
            ORDER BY index_name, seq_in_index
            """.trimIndent(), schemaName, table,
        )
        return rows.groupBy { it["index_name"] as String }.map { (name, idxRows) ->
            IndexProjection(
                name = name,
                columns = idxRows.sortedBy { (it["seq_in_index"] as Number).toInt() }
                    .map { it["column_name"] as String },
                isUnique = (idxRows.first()["non_unique"] as Number).toInt() == 0,
                type = idxRows.first()["index_type"] as? String,
            )
        }
    }

    fun listViews(session: JdbcOperations, schemaName: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT table_name, view_definition
            FROM information_schema.views
            WHERE table_schema = ?
            ORDER BY table_name
            """.trimIndent(), schemaName,
        )
    }

    fun listViewRoutineUsage(session: JdbcOperations, schemaName: String): Map<String, List<String>> {
        return try {
            val rows = session.queryList(
                """
                SELECT TABLE_NAME AS view_name, SPECIFIC_NAME AS routine_name
                FROM INFORMATION_SCHEMA.VIEW_ROUTINE_USAGE
                WHERE TABLE_SCHEMA = ?
                ORDER BY view_name, routine_name
                """.trimIndent(), schemaName,
            )
            rows.groupBy(
                { it["view_name"] as String },
                { it["routine_name"] as String },
            )
        } catch (_: Exception) {
            // VIEW_ROUTINE_USAGE may not exist on older MySQL versions
            emptyMap()
        }
    }

    fun listFunctions(session: JdbcOperations, schemaName: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT routine_name, routine_type, data_type,
                   dtd_identifier, routine_definition,
                   is_deterministic, routine_body
            FROM information_schema.routines
            WHERE routine_schema = ? AND routine_type = 'FUNCTION'
            ORDER BY routine_name
            """.trimIndent(), schemaName,
        )
    }

    fun listProcedures(session: JdbcOperations, schemaName: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT routine_name, routine_type,
                   routine_definition, routine_body
            FROM information_schema.routines
            WHERE routine_schema = ? AND routine_type = 'PROCEDURE'
            ORDER BY routine_name
            """.trimIndent(), schemaName,
        )
    }

    fun listRoutineParameters(
        session: JdbcOperations,
        schemaName: String,
        routineName: String,
        routineType: String,
    ): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT parameter_name, data_type, dtd_identifier,
                   parameter_mode, ordinal_position
            FROM information_schema.parameters
            WHERE specific_schema = ? AND specific_name = ?
              AND routine_type = ?
              AND ordinal_position > 0
            ORDER BY ordinal_position
            """.trimIndent(), schemaName, routineName, routineType,
        )
    }

    fun listTriggers(session: JdbcOperations, schemaName: String): List<Map<String, Any?>> {
        return session.queryList(
            """
            SELECT trigger_name, event_object_table,
                   action_timing, event_manipulation,
                   action_orientation, action_statement
            FROM information_schema.triggers
            WHERE trigger_schema = ?
            ORDER BY event_object_table, trigger_name
            """.trimIndent(), schemaName,
        )
    }

    // ── Sequence-Support queries (0.9.4 AP 6.1) ──────────────

    /**
     * Two-step existence check for dmg_sequences.
     * Returns `true` (exists), `false` (does not exist), or `null`
     * (technically undecidable — e.g. permission error on information_schema).
     */
    fun checkSupportTableExists(session: JdbcOperations, schemaName: String): Boolean? {
        return try {
            val row = session.querySingle(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = ? AND table_name = ?
                  AND table_type = 'BASE TABLE'
                """.trimIndent(), schemaName, MysqlSequenceNaming.SUPPORT_TABLE,
            )
            row != null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Verifies the canonical column shape of dmg_sequences.
     * Returns `true` if all required columns are present.
     */
    /** Required columns with their acceptable type classes for D2. */
    private val REQUIRED_COLUMN_TYPES = mapOf(
        "managed_by" to setOf("char", "varchar", "text", "tinytext", "mediumtext", "longtext"),
        "format_version" to setOf("char", "varchar", "text", "tinytext", "mediumtext", "longtext"),
        "name" to setOf("char", "varchar", "text", "tinytext", "mediumtext", "longtext"),
        "next_value" to setOf("tinyint", "smallint", "mediumint", "int", "bigint"),
        "increment_by" to setOf("tinyint", "smallint", "mediumint", "int", "bigint"),
        "min_value" to setOf("tinyint", "smallint", "mediumint", "int", "bigint"),
        "max_value" to setOf("tinyint", "smallint", "mediumint", "int", "bigint"),
        "cycle_enabled" to setOf("tinyint", "bit"),
        "cache_size" to setOf("tinyint", "smallint", "mediumint", "int", "bigint"),
    )

    /**
     * Verifies the canonical column shape of dmg_sequences including
     * type semantics (D2 requirement).
     *
     * Validates DATA_TYPE against allowed type classes per §5.2.
     * COLUMN_TYPE is read alongside for potential future disambiguation.
     * `bit` is accepted for cycle_enabled since MySQL JDBC robustly
     * reads bit(1) as Boolean/Integer.
     *
     * Limitation: this checks the declared type, not actual JDBC read
     * behavior. Driver-specific edge cases (e.g. unsigned bigint read
     * as BigInteger) are caught downstream by safeLong()/safeInt()
     * during row validation, not here.
     */
    fun checkSupportTableShape(session: JdbcOperations, schemaName: String): Boolean {
        val actualColumns = session.queryList(
            """
            SELECT column_name, data_type, column_type
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ?
            """.trimIndent(), schemaName, MysqlSequenceNaming.SUPPORT_TABLE,
        ).associate {
            (it["column_name"] as String).lowercase() to Pair(
                (it["data_type"] as? String)?.lowercase(),
                (it["column_type"] as? String)?.lowercase(),
            )
        }
        return REQUIRED_COLUMN_TYPES.all { (col, allowedTypes) ->
            val (dataType, _) = actualColumns[col] ?: return false
            dataType in allowedTypes
        }
    }

    /**
     * Reads all rows from dmg_sequences, returning raw row maps.
     * Caller is responsible for validation and conflict detection.
     */
    fun listSupportSequenceRows(session: JdbcOperations, schemaName: String): List<Map<String, Any?>> {
        val safeDb = schemaName.replace("`", "``")
        return session.queryList(
            """
            SELECT managed_by, format_version, name,
                   next_value, increment_by, min_value, max_value,
                   cycle_enabled, cache_size
            FROM `$safeDb`.`${MysqlSequenceNaming.SUPPORT_TABLE}`
            """.trimIndent(),
        )
    }

    /**
     * Targeted routine lookup for a specific support routine.
     * Checks existence, marker comment in definition, and routine type.
     */
    /** Extracts column name from SET NEW.`column` in trigger body. */
    private val TRIGGER_COLUMN_PATTERN = Regex("""SET\s+NEW\.`?(\w+)`?\s*=""", RegexOption.IGNORE_CASE)

    /** Extracts sequence name from dmg_nextval('seqname') in trigger body. */
    private val TRIGGER_SEQUENCE_PATTERN = Regex("""`?dmg_nextval`?\s*\(\s*'([^']+)'\s*\)""", RegexOption.IGNORE_CASE)

    /** Verifies guard pattern: NEW.<column> IS NULL */
    private val TRIGGER_GUARD_PATTERN = Regex("""NEW\.`?(\w+)`?\s+IS\s+NULL""", RegexOption.IGNORE_CASE)

    /** Strip backtick quoting from identifiers. */
    private fun String.stripBackticks(): String =
        if (startsWith("`") && endsWith("`")) substring(1, length - 1).replace("``", "`")
        else this

    /**
     * Resolve a potentially schema-qualified identifier against the active scope.
     * Handles backtick-quoted segments: `mydb`.`seq` or mydb.seq or `mydb.seq`.
     * Returns the unqualified name if it's in scope, or null if it points elsewhere.
     */
    private fun unqualify(name: String, activeSchema: String): String? {
        // Split on unquoted dot first, so segmented identifiers like
        // `mydb`.`seq` stay intact during scope resolution.
        val dotIdx = findUnquotedDot(name)
        if (dotIdx < 0) return name.stripBackticks() // unqualified → in scope
        val schema = name.substring(0, dotIdx).stripBackticks()
        val local = name.substring(dotIdx + 1).stripBackticks()
        return if (schema.equals(activeSchema, ignoreCase = true)) local else null
    }

    /** Find the first '.' that is not inside backtick-quoted segments. */
    private fun findUnquotedDot(s: String): Int {
        var inBacktick = false
        for (i in s.indices) {
            when (s[i]) {
                '`' -> inBacktick = !inBacktick
                '.' -> if (!inBacktick) return i
            }
        }
        return -1
    }

    /** Extracts marker fields: sequence=X table=Y column=Z */
    private val MARKER_SEQUENCE_PATTERN = Regex("""sequence=(\S+)""")
    private val MARKER_TABLE_PATTERN = Regex("""table=(\S+)""")
    private val MARKER_COLUMN_PATTERN = Regex("""column=(\S+)""")

    /** Expected signatures for support routines (return type + parameter form). */
    private val expectedRoutineSignatures = mapOf(
        MysqlSequenceNaming.NEXTVAL_ROUTINE to RoutineSignature(
            returnType = "bigint", paramCount = 1, marker = "object=nextval",
        ),
        MysqlSequenceNaming.SETVAL_ROUTINE to RoutineSignature(
            returnType = "bigint", paramCount = 2, marker = "object=setval",
        ),
    )

    private data class RoutineSignature(
        val returnType: String,
        val paramCount: Int,
        val marker: String,
    )

    fun lookupSupportRoutine(
        session: JdbcOperations,
        schemaName: String,
        routineName: String,
    ): SupportRoutineState {
        return try {
            val row = session.querySingle(
                """
                SELECT routine_name, routine_definition, data_type,
                       dtd_identifier
                FROM information_schema.routines
                WHERE routine_schema = ? AND routine_name = ?
                  AND routine_type = 'FUNCTION'
                """.trimIndent(), schemaName, routineName,
            ) ?: return SupportRoutineState.MISSING

            // MySQL's information_schema.routines.routine_definition often strips
            // comments (including d-migrate markers). SHOW CREATE FUNCTION preserves
            // the original source text. Try SHOW CREATE first, fall back to
            // routine_definition.
            val safeDb = schemaName.replace("`", "``")
            val safeName = routineName.replace("`", "``")
            val showRow = try {
                session.querySingle("SHOW CREATE FUNCTION `$safeDb`.`$safeName`")
            } catch (_: Exception) { null }
            // SHOW CREATE returns column "Create Function" with full body
            val showBody = showRow?.values?.filterIsInstance<String>()
                ?.firstOrNull { "FUNCTION" in it.uppercase() || "BEGIN" in it.uppercase() }
                ?: ""
            val definition = showBody.ifEmpty { row["routine_definition"] as? String ?: "" }
            val dataType = (row["data_type"] as? String)?.lowercase() ?: ""

            // Check marker — routine exists but no d-migrate marker → user object
            if ("d-migrate:mysql-sequence-v1" !in definition) {
                return SupportRoutineState.NON_CANONICAL
            }

            // Check signature: return type and specific marker
            val expected = expectedRoutineSignatures[routineName]
            if (expected != null) {
                if (dataType != expected.returnType) return SupportRoutineState.NON_CANONICAL
                if (expected.marker !in definition) return SupportRoutineState.NON_CANONICAL
            }

            // Check parameter count
            if (expected != null) {
                val paramRows = session.queryList(
                    """
                    SELECT ordinal_position
                    FROM information_schema.parameters
                    WHERE specific_schema = ? AND specific_name = ?
                      AND routine_type = 'FUNCTION'
                      AND ordinal_position > 0
                    """.trimIndent(), schemaName, routineName,
                )
                if (paramRows.size != expected.paramCount) return SupportRoutineState.NON_CANONICAL
            }

            SupportRoutineState.CONFIRMED
        } catch (_: Exception) {
            SupportRoutineState.NOT_ACCESSIBLE
        }
    }

    /**
     * Lists potential support triggers matching the canonical name pattern.
     * Returns trigger name → state after marker/body inspection.
     */
    /**
     * Result of a support trigger scan.
     * [accessible] is false when the query itself failed (permission error).
     */
    /** Individual trigger assessment with table/column context for diagnostic keys. */
    data class SupportTriggerAssessment(
        val triggerName: String,
        val state: SupportTriggerState,
        val tableName: String,
        val columnName: String?,
        val sequenceName: String?,
    )

    data class SupportTriggerScanResult(
        val triggers: List<SupportTriggerAssessment>,
        val accessible: Boolean,
    )

    private data class SupportTriggerInput(
        val name: String,
        val timing: String,
        val event: String,
        val body: String,
        val table: String,
        val column: String?,
        val rawSequence: String?,
        val guardColumn: String?,
        val seqUnqualified: String?,
        val markerSeq: String?,
        val markerTable: String?,
        val markerColumn: String?,
    )

    fun listPotentialSupportTriggers(
        session: JdbcOperations,
        schemaName: String,
    ): SupportTriggerScanResult {
        val rows = try {
            session.queryList(
                """
                SELECT trigger_name, action_timing, event_manipulation,
                       action_statement, event_object_table
                FROM information_schema.triggers
                WHERE trigger_schema = ?
                  AND trigger_name LIKE 'dmg_seq_%'
                  AND trigger_name LIKE '%_bi'
                ORDER BY trigger_name
                """.trimIndent(), schemaName,
            )
        } catch (_: Exception) {
            return SupportTriggerScanResult(emptyList(), accessible = false)
        }

        val safeDb = schemaName.replace("`", "``")

        val result = rows.map { row ->
            val name = row["trigger_name"] as String
            val timing = row["action_timing"] as? String ?: ""
            val event = row["event_manipulation"] as? String ?: ""
            var body = row["action_statement"] as? String ?: ""
            val table = row["event_object_table"] as? String ?: ""

            // MySQL's information_schema may strip comments from action_statement.
            // Fall back to SHOW CREATE TRIGGER which preserves the original source.
            if ("d-migrate:mysql-sequence-v1" !in body) {
                val safeTrig = name.replace("`", "``")
                val showRow = try {
                    session.querySingle("SHOW CREATE TRIGGER `$safeDb`.`$safeTrig`")
                } catch (_: Exception) { null }
                val showBody = showRow?.values?.filterIsInstance<String>()
                    ?.firstOrNull { "BEGIN" in it.uppercase() || "SET" in it.uppercase() }
                if (showBody != null && showBody.isNotEmpty()) body = showBody
            }

            // Extract column, sequence, and guard from body for validation
            val column = TRIGGER_COLUMN_PATTERN.find(body)?.groupValues?.get(1)?.stripBackticks()
            val rawSequence = TRIGGER_SEQUENCE_PATTERN.find(body)?.groupValues?.get(1)
            val guardColumn = TRIGGER_GUARD_PATTERN.find(body)?.groupValues?.get(1)?.stripBackticks()

            // Extract marker fields for cross-validation
            val markerSeq = MARKER_SEQUENCE_PATTERN.find(body)?.groupValues?.get(1)
            val markerTable = MARKER_TABLE_PATTERN.find(body)?.groupValues?.get(1)
            val markerColumn = MARKER_COLUMN_PATTERN.find(body)?.groupValues?.get(1)

            // Scope check: reject schema-qualified sequence names pointing elsewhere
            val seqUnqualified = rawSequence?.let { unqualify(it, schemaName) }

            val state = assessTriggerState(
                SupportTriggerInput(
                    name = name,
                    timing = timing,
                    event = event,
                    body = body,
                    table = table,
                    column = column,
                    rawSequence = rawSequence,
                    guardColumn = guardColumn,
                    seqUnqualified = seqUnqualified,
                    markerSeq = markerSeq,
                    markerTable = markerTable,
                    markerColumn = markerColumn,
                )
            )

            SupportTriggerAssessment(
                name,
                state,
                table,
                column,
                seqUnqualified ?: rawSequence?.stripBackticks(),
            )
        }
        return SupportTriggerScanResult(result, accessible = true)
    }

    private fun assessTriggerState(input: SupportTriggerInput): SupportTriggerState = when {
        !MysqlSequenceNaming.isSupportTriggerName(input.name) -> SupportTriggerState.USER_OBJECT
        !input.timing.equals("BEFORE", ignoreCase = true) ||
            !input.event.equals("INSERT", ignoreCase = true) -> SupportTriggerState.NON_CANONICAL
        "d-migrate:mysql-sequence-v1" !in input.body -> SupportTriggerState.MISSING_MARKER
        "object=sequence-trigger" !in input.body -> SupportTriggerState.NON_CANONICAL
        "dmg_nextval" !in input.body -> SupportTriggerState.NON_CANONICAL
        input.column == null || input.rawSequence == null -> SupportTriggerState.NON_CANONICAL
        input.guardColumn == null -> SupportTriggerState.NON_CANONICAL
        !input.guardColumn.equals(input.column, ignoreCase = true) -> SupportTriggerState.NON_CANONICAL
        input.seqUnqualified == null -> SupportTriggerState.NON_CANONICAL
        input.markerSeq != null &&
            !input.markerSeq.equals(input.seqUnqualified, ignoreCase = true) -> SupportTriggerState.NON_CANONICAL
        input.markerTable != null &&
            !input.markerTable.equals(input.table, ignoreCase = true) -> SupportTriggerState.NON_CANONICAL
        input.markerColumn != null &&
            !input.markerColumn.equals(input.column, ignoreCase = true) -> SupportTriggerState.NON_CANONICAL
        else -> SupportTriggerState.CONFIRMED
    }
}
