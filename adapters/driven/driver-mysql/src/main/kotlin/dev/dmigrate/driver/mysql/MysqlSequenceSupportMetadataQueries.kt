package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.metadata.JdbcOperations
import java.io.ByteArrayOutputStream

internal object MysqlSequenceSupportMetadataQueries {
    private val requiredColumnTypes = mapOf(
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

    private val triggerColumnPattern =
        Regex("""SET\s+NEW\.(?:`((?:``|[^`])+)`|(\w+))\s*=""", RegexOption.IGNORE_CASE)
    private val triggerSequencePattern =
        Regex("""`?dmg_nextval`?\s*\(\s*'((?:''|\\.|[^'])*)'\s*\)""", RegexOption.IGNORE_CASE)
    private val triggerGuardPattern =
        Regex("""NEW\.(?:`((?:``|[^`])+)`|(\w+))\s+IS\s+NULL""", RegexOption.IGNORE_CASE)
    private val markerSequencePattern = Regex("""sequence=(\S+)""")
    private val markerTablePattern = Regex("""table=(\S+)""")
    private val markerColumnPattern = Regex("""column=(\S+)""")

    private val expectedRoutineSignatures = mapOf(
        MysqlSequenceNaming.NEXTVAL_ROUTINE to RoutineSignature(
            returnType = "bigint",
            paramCount = 1,
            marker = "object=nextval",
        ),
        MysqlSequenceNaming.SETVAL_ROUTINE to RoutineSignature(
            returnType = "bigint",
            paramCount = 2,
            marker = "object=setval",
        ),
    )

    fun checkSupportTableExists(session: JdbcOperations, schemaName: String): Boolean? {
        return try {
            val row = session.querySingle(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = ? AND table_name = ?
                  AND table_type = 'BASE TABLE'
                """.trimIndent(),
                schemaName,
                MysqlSequenceNaming.SUPPORT_TABLE,
            )
            row != null
        } catch (_: Exception) {
            null
        }
    }

    fun checkSupportTableShape(session: JdbcOperations, schemaName: String): Boolean {
        val actualColumns = session.queryList(
            """
            SELECT column_name, data_type, column_type
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ?
            """.trimIndent(),
            schemaName,
            MysqlSequenceNaming.SUPPORT_TABLE,
        ).associate {
            (it["column_name"] as String).lowercase() to Pair(
                (it["data_type"] as? String)?.lowercase(),
                (it["column_type"] as? String)?.lowercase(),
            )
        }
        return requiredColumnTypes.all { (column, allowedTypes) ->
            val (dataType, _) = actualColumns[column] ?: return false
            dataType in allowedTypes
        }
    }

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
                """.trimIndent(),
                schemaName,
                routineName,
            ) ?: return SupportRoutineState.MISSING

            val safeDb = schemaName.replace("`", "``")
            val safeName = routineName.replace("`", "``")
            val showRow = try {
                session.querySingle("SHOW CREATE FUNCTION `$safeDb`.`$safeName`")
            } catch (_: Exception) {
                null
            }
            val showBody = showRow?.values?.filterIsInstance<String>()
                ?.firstOrNull { "FUNCTION" in it.uppercase() || "BEGIN" in it.uppercase() }
                ?: ""
            val definition = showBody.ifEmpty { row["routine_definition"] as? String ?: "" }
            val dataType = (row["data_type"] as? String)?.lowercase() ?: ""

            if ("d-migrate:mysql-sequence-v1" !in definition) {
                return SupportRoutineState.NON_CANONICAL
            }

            val expected = expectedRoutineSignatures[routineName]
            if (expected != null) {
                if (dataType != expected.returnType) return SupportRoutineState.NON_CANONICAL
                if (expected.marker !in definition) return SupportRoutineState.NON_CANONICAL
            }

            if (expected != null) {
                val paramRows = session.queryList(
                    """
                    SELECT ordinal_position
                    FROM information_schema.parameters
                    WHERE specific_schema = ? AND specific_name = ?
                      AND routine_type = 'FUNCTION'
                      AND ordinal_position > 0
                    """.trimIndent(),
                    schemaName,
                    routineName,
                )
                if (paramRows.size != expected.paramCount) return SupportRoutineState.NON_CANONICAL
            }

            SupportRoutineState.CONFIRMED
        } catch (_: Exception) {
            SupportRoutineState.NOT_ACCESSIBLE
        }
    }

    fun listPotentialSupportTriggers(
        session: JdbcOperations,
        schemaName: String,
    ): MysqlMetadataQueries.SupportTriggerScanResult {
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
                """.trimIndent(),
                schemaName,
            )
        } catch (_: Exception) {
            return MysqlMetadataQueries.SupportTriggerScanResult(emptyList(), accessible = false)
        }

        val safeDb = schemaName.replace("`", "``")
        val result = rows.map { row ->
            val name = row["trigger_name"] as String
            val timing = row["action_timing"] as? String ?: ""
            val event = row["event_manipulation"] as? String ?: ""
            var body = row["action_statement"] as? String ?: ""
            val table = row["event_object_table"] as? String ?: ""

            if ("d-migrate:mysql-sequence-v1" !in body) {
                val safeTrigger = name.replace("`", "``")
                val showRow = try {
                    session.querySingle("SHOW CREATE TRIGGER `$safeDb`.`$safeTrigger`")
                } catch (_: Exception) {
                    null
                }
                val showBody = showRow?.values?.filterIsInstance<String>()
                    ?.firstOrNull { "BEGIN" in it.uppercase() || "SET" in it.uppercase() }
                if (!showBody.isNullOrEmpty()) body = showBody
            }

            val column = triggerColumnPattern.find(body)?.mysqlIdentifier()
            val rawSequence = triggerSequencePattern.find(body)
                ?.groupValues
                ?.get(1)
                ?.let(::unescapeMysqlStringLiteral)
            val guardColumn = triggerGuardPattern.find(body)?.mysqlIdentifier()
            val markerSeq = markerSequencePattern.find(body)?.groupValues?.get(1)?.let(::decodeMarkerValue)
            val markerTable = markerTablePattern.find(body)?.groupValues?.get(1)?.let(::decodeMarkerValue)
            val markerColumn = markerColumnPattern.find(body)?.groupValues?.get(1)?.let(::decodeMarkerValue)
            val unqualifiedSequence = rawSequence?.let { unqualify(it, schemaName) }

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
                    seqUnqualified = unqualifiedSequence,
                    markerSeq = markerSeq,
                    markerTable = markerTable,
                    markerColumn = markerColumn,
                )
            )

            MysqlMetadataQueries.SupportTriggerAssessment(
                triggerName = name,
                state = state,
                tableName = table,
                columnName = column,
                sequenceName = unqualifiedSequence ?: rawSequence?.stripBackticks(),
            )
        }

        return MysqlMetadataQueries.SupportTriggerScanResult(result, accessible = true)
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

    private fun String.stripBackticks(): String {
        return if (startsWith("`") && endsWith("`")) {
            substring(1, length - 1).replace("``", "`")
        } else {
            this
        }
    }

    private fun MatchResult.mysqlIdentifier(): String {
        val quoted = groupValues[1]
        return if (quoted.isNotEmpty()) quoted.replace("``", "`") else groupValues[2]
    }

    private fun unescapeMysqlStringLiteral(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char == '\'' && index + 1 < value.length && value[index + 1] == '\'' -> {
                    append('\'')
                    index += 2
                }
                char == '\\' && index + 1 < value.length -> {
                    append(unescapeMysqlBackslash(value[index + 1]))
                    index += 2
                }
                else -> {
                    append(char)
                    index++
                }
            }
        }
    }

    private fun unescapeMysqlBackslash(value: Char): Char = when (value) {
        '0' -> '\u0000'
        '\'' -> '\''
        '"' -> '"'
        'b' -> '\b'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'Z' -> '\u001A'
        '\\' -> '\\'
        else -> value
    }

    private fun decodeMarkerValue(value: String): String {
        val output = StringBuilder()
        val bytes = ByteArrayOutputStream()

        fun flushBytes() {
            if (bytes.size() == 0) return
            output.append(bytes.toByteArray().toString(Charsets.UTF_8))
            bytes.reset()
        }

        var index = 0
        while (index < value.length) {
            if (value[index] == '%' && index + 2 < value.length) {
                val high = hexValue(value[index + 1])
                val low = hexValue(value[index + 2])
                if (high >= 0 && low >= 0) {
                    bytes.write((high shl 4) + low)
                    index += 3
                    continue
                }
            }
            flushBytes()
            output.append(value[index])
            index++
        }

        flushBytes()
        return output.toString()
    }

    private fun hexValue(value: Char): Int = when (value) {
        in '0'..'9' -> value - '0'
        in 'a'..'f' -> value - 'a' + 10
        in 'A'..'F' -> value - 'A' + 10
        else -> -1
    }

    private fun unqualify(name: String, activeSchema: String): String? {
        val dotIndex = findUnquotedDot(name)
        if (dotIndex < 0) return name.stripBackticks()
        val schema = name.substring(0, dotIndex).stripBackticks()
        val local = name.substring(dotIndex + 1).stripBackticks()
        return if (schema.equals(activeSchema, ignoreCase = true)) local else null
    }

    private fun findUnquotedDot(input: String): Int {
        var inBacktick = false
        for (index in input.indices) {
            when (input[index]) {
                '`' -> inBacktick = !inBacktick
                '.' -> if (!inBacktick) return index
            }
        }
        return -1
    }

    private data class RoutineSignature(
        val returnType: String,
        val paramCount: Int,
        val marker: String,
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
}
