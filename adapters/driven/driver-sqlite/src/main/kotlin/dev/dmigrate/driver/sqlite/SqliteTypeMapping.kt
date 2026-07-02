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
        // Neutral `identifier` is the deliberate 32-bit auto-increment contract
        // (neutral-model-spec: PG SERIAL, MySQL INT AUTO_INCREMENT), but SQLite's
        // AUTOINCREMENT rowid is 64-bit — a cross-dialect transfer narrows the
        // range. R202 keeps that narrowing loud; the spec'd 64-bit path is
        // biginteger + ColumnGeneration.Identity.
        if (isAutoIncrement) return MappingResult(
            NeutralType.Identifier(autoIncrement = true),
            SchemaReadNote(
                severity = SchemaReadSeverity.INFO, code = "R202",
                objectName = "$tableName.$colName",
                message = "SQLite AUTOINCREMENT primary key is 64-bit; neutral 'identifier' is the " +
                    "32-bit auto-increment contract (PostgreSQL SERIAL, MySQL INT AUTO_INCREMENT) — " +
                    "a cross-dialect transfer narrows the value range",
                hint = "Model the column as biginteger plus generation: identity " +
                    "when the 64-bit range is required",
            ),
        )

        val raw = rawType.uppercase().trim()
        val maxLen = extractMaxLength(raw)

        return mapIntegerType(raw)
            ?: mapNumericType(raw)
            ?: mapStringType(raw, maxLen)
            ?: mapTemporalType(raw)
            ?: mapSpecialType(raw)
            ?: mapGeometryType(raw, tableName, colName)
            ?: mapFallback(raw, rawType, tableName, colName)
    }

    private fun mapIntegerType(raw: String): MappingResult? = when (raw) {
        "INTEGER", "INT" -> MappingResult(NeutralType.Integer)
        "BIGINT" -> MappingResult(NeutralType.BigInteger)
        "SMALLINT" -> MappingResult(NeutralType.SmallInt)
        else -> null
    }

    private fun mapNumericType(raw: String): MappingResult? = when {
        raw == "REAL" || raw == "DOUBLE" || raw == "FLOAT" -> MappingResult(NeutralType.Float())
        raw == "BOOLEAN" || raw == "TINYINT(1)" -> MappingResult(NeutralType.BooleanType)
        raw.startsWith("DECIMAL") || raw.startsWith("NUMERIC") -> {
            val (p, s) = extractPrecisionScale(raw)
            if (p != null && s != null) MappingResult(NeutralType.Decimal(p, s))
            else MappingResult(NeutralType.Float())
        }
        else -> null
    }

    private fun mapStringType(raw: String, maxLen: Int?): MappingResult? = when {
        raw == "TEXT" -> MappingResult(NeutralType.Text())
        raw.startsWith("VARCHAR") || raw.startsWith("CHARACTER VARYING") ->
            MappingResult(NeutralType.Text(maxLength = maxLen))
        raw.startsWith("CHAR(") -> MappingResult(NeutralType.Char(length = maxLen ?: 1))
        else -> null
    }

    private fun mapTemporalType(raw: String): MappingResult? = when (raw) {
        "DATE" -> MappingResult(NeutralType.Date)
        "TIME" -> MappingResult(NeutralType.Time)
        "DATETIME", "TIMESTAMP" -> MappingResult(NeutralType.DateTime())
        else -> null
    }

    private fun mapSpecialType(raw: String): MappingResult? = when {
        raw == "BLOB" -> MappingResult(NeutralType.Binary)
        raw == "UUID" -> MappingResult(NeutralType.Uuid)
        raw == "JSON" || raw == "JSONB" -> MappingResult(NeutralType.Json)
        else -> null
    }

    private fun mapGeometryType(raw: String, tableName: String, colName: String): MappingResult? {
        val isGeometry = raw == "GEOMETRY" || raw.startsWith("GEOMETRY(") ||
            raw == "POINT" || raw == "LINESTRING" || raw == "POLYGON" ||
            raw == "MULTIPOINT" || raw == "MULTILINESTRING" || raw == "MULTIPOLYGON"
        if (!isGeometry) return null
        return MappingResult(
            NeutralType.Geometry(geometryType = GeometryType.of(raw.substringBefore("(").lowercase())),
            SchemaReadNote(
                severity = SchemaReadSeverity.INFO, code = "R220",
                objectName = "$tableName.$colName",
                message = "Geometry column '$raw' — SpatiaLite-specific handling may be needed",
                hint = "Verify spatial profile compatibility",
            ),
        )
    }

    private fun mapFallback(raw: String, rawType: String, tableName: String, colName: String): MappingResult {
        if (raw == "") return MappingResult(
            NeutralType.Text(),
            SchemaReadNote(severity = SchemaReadSeverity.INFO, code = "R200",
                objectName = "$tableName.$colName", message = "Untyped column mapped to text"),
        )
        return MappingResult(
            NeutralType.Text(),
            SchemaReadNote(severity = SchemaReadSeverity.WARNING, code = "R201",
                objectName = "$tableName.$colName",
                message = "Unknown SQLite type '$rawType' mapped to text",
                hint = "Review the column type manually"),
        )
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
            trimmed.equals("CURRENT_DATE", ignoreCase = true) -> DefaultValue.FunctionCall("current_date")
            trimmed.equals("CURRENT_TIME", ignoreCase = true) -> DefaultValue.FunctionCall("current_time")
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

    // VA4/5d Befund 3a: vollständige, EXAKTE Liste der von `InitSpatialMetaData()`
    // angelegten SpatiaLite-Metatabellen (Stand mod_spatialite 5.x, live verifiziert).
    // Bewusst KEIN Präfix-Matching (`startsWith`) — das würde echte User-Tabellen wie
    // `geometry_columns_backup` oder `spatial_ref_sys_history` fälschlich als Metatabelle
    // verwerfen (stiller Datenverlust). Die sicherere Fehlerrichtung ist Unter-Match: eine
    // versions-neue Metatabelle leckt sichtbar in den Reverse-Output, statt User-Daten
    // still zu schlucken. `SpatialIndex`/`ElementaryGeometries`/`KNN`/`KNN2` sind VIRTUAL
    // TABLEs → bereits über [isVirtualTable] (S100) ausgeschlossen; die R*Tree-Index-
    // Schattentabellen `idx_<t>_<col>_{node,parent,rowid}` filtert der SchemaReader aus
    // `geometry_columns.spatial_index_enabled`.
    // Quell-validiert gegen SpatiaLite `src/spatialite/dbobj_scopes.c`
    // (`scope_is_internal_table`/`scope_is_geometry_trigger`). Diese Liste deckt die
    // von **plain** `InitSpatialMetaData()` angelegten Metadaten-Objekte ab (das, was
    // der Harness + ein typischer Spatial-Reverse erzeugen). SpatiaLites Voll-Taxonomie
    // umfasst zusätzlich Advanced-Feature-Tabellen (raster_coverages*, topologies/
    // networks, vector_coverages*, wms_*, stored_procedures/variables) — die entstehen
    // nur über separate Init-Funktionen; bei Bedarf hier ergänzen.
    private val SPATIALITE_META_TABLES = setOf(
        "geometry_columns",
        "geometry_columns_auth",
        "geometry_columns_field_infos",
        "geometry_columns_statistics",
        "geometry_columns_time",
        "views_geometry_columns",
        "views_geometry_columns_auth",
        "views_geometry_columns_field_infos",
        "views_geometry_columns_statistics",
        "virts_geometry_columns",
        "virts_geometry_columns_auth",
        "virts_geometry_columns_field_infos",
        "virts_geometry_columns_statistics",
        "spatial_ref_sys",
        "spatial_ref_sys_aux",
        "spatialite_history",
        "sql_statements_log",
        "data_licenses",
        // SpatiaLite-System-VIEWS (InitSpatialMetaData) — gleiches Namens-Filter-Set
        // (sqlite_master teilt den Namensraum für Tabellen + Views).
        "geom_cols_ref_sys",
        "spatial_ref_sys_all",
        "vector_layers",
        "vector_layers_auth",
        "vector_layers_field_infos",
        "vector_layers_statistics",
    )

    /** SpatiaLite-internes Metadaten-Objekt (Tabelle ODER View) — name-basiert. */
    fun isSpatiaLiteMetaTable(name: String): Boolean =
        name.lowercase() in SPATIALITE_META_TABLES

    // VA4/5d Befund 3 (Trigger): `AddGeometryColumn`/`CreateSpatialIndex` legen auf der
    // USER-Tabelle Integritäts-/Sync-Trigger an: `gg[iud]_` (Geometrie-Constraint),
    // `gi[iud]_` (R*Tree-Spatial-Index-Sync), `tm[iud]_` (geometry_columns_time-Pflege).
    // Sie sind SpatiaLite-intern und dürfen nicht als User-Trigger reverse-engineered
    // werden (sonst False-Drift im migrate-Post-Compare). Die Trigger AUF den
    // Metatabellen fängt bereits [isSpatiaLiteMetaTable].
    private val SPATIALITE_TRIGGER_PREFIXES = listOf(
        "ggi_", "ggu_", "ggd_", "gii_", "giu_", "gid_", "tmi_", "tmu_", "tmd_",
    )
    private val SPATIALITE_TRIGGER_BODY_MARKERS =
        Regex("GeometryConstraints|RTreeAlign|DisableSpatialIndex", RegexOption.IGNORE_CASE)

    /** True für SpatiaLite-generierte Geometrie-/Spatial-Index-Trigger auf User-Tabellen.
     *  Namens-Präfix deckt alle (auch `gid_`, das nur die R*Tree-Tabelle referenziert);
     *  der Body-Marker fängt zusätzlich abweichend benannte SpatiaLite-Trigger ab. */
    fun isSpatiaLiteGeometryTrigger(name: String, triggerSql: String): Boolean {
        val n = name.lowercase()
        return SPATIALITE_TRIGGER_PREFIXES.any { n.startsWith(it) } ||
            SPATIALITE_TRIGGER_BODY_MARKERS.containsMatchIn(triggerSql)
    }

    fun extractViewQuery(createSql: String): String? {
        val idx = createSql.indexOf(" AS ", ignoreCase = true)
        return if (idx >= 0) createSql.substring(idx + 4).trim() else null
    }
}
