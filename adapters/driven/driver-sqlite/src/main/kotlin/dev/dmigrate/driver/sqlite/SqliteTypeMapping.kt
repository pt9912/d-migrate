package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity

/**
 * Pure functions for mapping SQLite type affinity to neutral types.
 * Extracted from [SqliteSchemaReader] for unit-testability.
 */
internal object SqliteTypeMapping {

    data class MappingResult(
        val type: NeutralType,
        val note: SchemaReadNote? = null,
    )

    fun mapColumn(
        rawType: String,
        isAutoIncrement: Boolean,
        tableName: String,
        colName: String,
    ): MappingResult {
        if (isAutoIncrement) return MappingResult(NeutralType.Identifier(autoIncrement = true))

        val raw = rawType.uppercase().trim()
        val maxLen = extractMaxLength(raw)

        return when {
            raw == "INTEGER" || raw == "INT" -> MappingResult(NeutralType.Integer)
            raw == "BIGINT" -> MappingResult(NeutralType.BigInteger)
            raw == "SMALLINT" -> MappingResult(NeutralType.SmallInt)
            raw == "TEXT" -> MappingResult(NeutralType.Text())
            raw == "BLOB" -> MappingResult(NeutralType.Binary)
            raw == "REAL" || raw == "DOUBLE" || raw == "FLOAT" -> MappingResult(NeutralType.Float())
            raw == "BOOLEAN" || raw == "TINYINT(1)" -> MappingResult(NeutralType.BooleanType)
            raw.startsWith("VARCHAR") || raw.startsWith("CHARACTER VARYING") ->
                MappingResult(NeutralType.Text(maxLength = maxLen))
            raw.startsWith("CHAR(") ->
                MappingResult(NeutralType.Char(length = maxLen ?: 1))
            raw.startsWith("DECIMAL") || raw.startsWith("NUMERIC") -> {
                val (p, s) = extractPrecisionScale(raw)
                if (p != null && s != null) MappingResult(NeutralType.Decimal(p, s))
                else MappingResult(NeutralType.Float())
            }
            raw == "DATE" -> MappingResult(NeutralType.Date)
            raw == "TIME" -> MappingResult(NeutralType.Time)
            raw == "DATETIME" || raw == "TIMESTAMP" -> MappingResult(NeutralType.DateTime())
            raw == "UUID" -> MappingResult(NeutralType.Uuid)
            raw == "JSON" || raw == "JSONB" -> MappingResult(NeutralType.Json)
            raw == "GEOMETRY" || raw.startsWith("GEOMETRY(") ||
                raw == "POINT" || raw == "LINESTRING" || raw == "POLYGON" ||
                raw == "MULTIPOINT" || raw == "MULTILINESTRING" || raw == "MULTIPOLYGON" ->
                MappingResult(
                    NeutralType.Geometry(geometryType = GeometryType.of(raw.substringBefore("(").lowercase())),
                    SchemaReadNote(
                        severity = SchemaReadSeverity.INFO,
                        code = "R220",
                        objectName = "$tableName.$colName",
                        message = "Geometry column '$raw' — SpatiaLite-specific handling may be needed",
                        hint = "Verify spatial profile compatibility",
                    ),
                )
            raw == "" -> MappingResult(
                NeutralType.Text(),
                SchemaReadNote(
                    severity = SchemaReadSeverity.INFO,
                    code = "R200",
                    objectName = "$tableName.$colName",
                    message = "Untyped column mapped to text",
                ),
            )
            else -> MappingResult(
                NeutralType.Text(),
                SchemaReadNote(
                    severity = SchemaReadSeverity.WARNING,
                    code = "R201",
                    objectName = "$tableName.$colName",
                    message = "Unknown SQLite type '$rawType' mapped to text",
                    hint = "Review the column type manually",
                ),
            )
        }
    }

    fun parseDefault(raw: String?): DefaultValue? {
        if (raw == null) return null
        val trimmed = raw.trim()
        return when {
            trimmed.equals("NULL", ignoreCase = true) -> null
            trimmed.equals("TRUE", ignoreCase = true) -> DefaultValue.BooleanLiteral(true)
            trimmed.equals("FALSE", ignoreCase = true) -> DefaultValue.BooleanLiteral(false)
            trimmed.startsWith("'") && trimmed.endsWith("'") ->
                DefaultValue.StringLiteral(trimmed.substring(1, trimmed.length - 1).replace("''", "'"))
            trimmed.toLongOrNull() != null -> DefaultValue.NumberLiteral(trimmed.toLong())
            trimmed.toDoubleOrNull() != null -> DefaultValue.NumberLiteral(trimmed.toDouble())
            trimmed.contains("datetime(", ignoreCase = true) ||
                trimmed.equals("CURRENT_TIMESTAMP", ignoreCase = true) ->
                DefaultValue.FunctionCall("current_timestamp")
            else -> DefaultValue.StringLiteral(trimmed)
        }
    }

    fun extractMaxLength(raw: String): Int? {
        val match = Regex("\\((\\d+)\\)").find(raw)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    fun extractPrecisionScale(raw: String): Pair<Int?, Int?> {
        val match = Regex("\\((\\d+)\\s*,\\s*(\\d+)\\)").find(raw)
        return if (match != null) {
            match.groupValues[1].toIntOrNull() to match.groupValues[2].toIntOrNull()
        } else {
            null to null
        }
    }

    fun isVirtualTable(createSql: String): Boolean =
        createSql.trimStart().startsWith("CREATE VIRTUAL TABLE", ignoreCase = true)

    fun hasAutoincrement(createSql: String): Boolean =
        createSql.contains("AUTOINCREMENT", ignoreCase = true)

    fun hasWithoutRowid(createSql: String): Boolean =
        createSql.contains("WITHOUT ROWID", ignoreCase = true)

    private val SPATIALITE_META_TABLES = setOf(
        "geometry_columns", "spatial_ref_sys", "views_geometry_columns",
        "virts_geometry_columns", "geometry_columns_auth",
        "geometry_columns_field_infos", "geometry_columns_statistics",
        "geometry_columns_time", "spatial_ref_sys_aux",
        "spatialite_history", "sql_statements_log",
    )

    fun isSpatiaLiteMetaTable(name: String): Boolean =
        name.lowercase() in SPATIALITE_META_TABLES

    fun extractViewQuery(createSql: String): String? {
        val idx = createSql.indexOf(" AS ", ignoreCase = true)
        return if (idx >= 0) createSql.substring(idx + 4).trim() else null
    }

    data class TriggerParseResult(
        val timing: dev.dmigrate.core.model.TriggerTiming,
        val event: dev.dmigrate.core.model.TriggerEvent,
        val body: String?,
        val notes: List<SchemaReadNote> = emptyList(),
    )

    fun parseTriggerSql(sql: String, name: String): TriggerParseResult {
        val upper = sql.uppercase()
        val notes = mutableListOf<SchemaReadNote>()

        val timing = when {
            upper.contains("BEFORE") -> dev.dmigrate.core.model.TriggerTiming.BEFORE
            upper.contains("AFTER") -> dev.dmigrate.core.model.TriggerTiming.AFTER
            upper.contains("INSTEAD OF") -> dev.dmigrate.core.model.TriggerTiming.INSTEAD_OF
            else -> {
                notes += SchemaReadNote(SchemaReadSeverity.WARNING, "R210", name,
                    "Could not determine trigger timing")
                dev.dmigrate.core.model.TriggerTiming.BEFORE
            }
        }
        val event = when {
            upper.contains(" INSERT ") || upper.contains(" INSERT\n") -> dev.dmigrate.core.model.TriggerEvent.INSERT
            upper.contains(" UPDATE ") || upper.contains(" UPDATE\n") -> dev.dmigrate.core.model.TriggerEvent.UPDATE
            upper.contains(" DELETE ") || upper.contains(" DELETE\n") -> dev.dmigrate.core.model.TriggerEvent.DELETE
            else -> {
                notes += SchemaReadNote(SchemaReadSeverity.WARNING, "R211", name,
                    "Could not determine trigger event")
                dev.dmigrate.core.model.TriggerEvent.INSERT
            }
        }
        val bodyMatch = Regex("BEGIN\\s+(.*?)\\s+END", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(sql)
        val body = bodyMatch?.groupValues?.get(1)?.trim()

        return TriggerParseResult(timing, event, body, notes)
    }
}
