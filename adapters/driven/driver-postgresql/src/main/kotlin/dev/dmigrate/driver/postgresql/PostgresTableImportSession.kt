package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.data.AbstractTableImportSession
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.WriteResult
import org.postgresql.PGConnection
import org.postgresql.util.PGobject
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.Types

internal class PostgresTableImportSession(
    conn: Connection,
    savedAutoCommit: Boolean,
    table: String,
    private val qualifiedTable: QualifiedTableName,
    targetColumns: List<TargetColumn>,
    private val generatedAlwaysColumns: Set<String>,
    primaryKeyColumns: List<String>,
    options: ImportOptions,
    private val schemaSync: PostgresSchemaSync,
    private var triggersDisabled: Boolean,
) : AbstractTableImportSession(conn, savedAutoCommit, table, targetColumns, primaryKeyColumns, options) {

    private var triggersReenabled: Boolean = false

    /**
     * I-04: Namen der PostgreSQL-Enum-Typen im Ziel. Werte für solche Spalten
     * werden als [PGobject] mit dem Enum-Typ gebunden — sonst lehnt PostgreSQL
     * den `varchar`-Parameter ab (`column is of type X but expression is of type
     * character varying`). Per Default lazy aus der Verbindung (pg_enum); für
     * Tests über [enumTypeNamesOverride] setzbar (Seam, keine Live-DB nötig).
     */
    internal var enumTypeNamesOverride: Set<String>? = null
    private val resolvedEnumTypeNames: Set<String> by lazy { loadEnumTypeNames() }
    private val enumTypeNames: Set<String> get() = enumTypeNamesOverride ?: resolvedEnumTypeNames

    private fun loadEnumTypeNames(): Set<String> =
        conn.prepareStatement(
            "SELECT DISTINCT t.typname FROM pg_type t JOIN pg_enum e ON e.enumtypid = t.oid",
        ).use { ps ->
            ps.executeQuery().use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1)) }
            }
        }

    // VA1c: PostGIS-Geometriespalten beim INSERT aus WKB konstruieren.
    // ST_GeomFromWKB akzeptiert plain WKB (von ST_AsBinary, VA1b) — auch das von
    // einer MySQL-Quelle gelesene WKB; SRID 0 (SRID-Erhalt via VA2).
    override val geometryBindConstructor: String? = "ST_GeomFromWKB"

    // Nur PostGIS-`geometry` (NICHT die nativen PG-Typen point/polygon/…).
    override fun isGeometryTypeName(typeNameLower: String): Boolean = typeNameLower == "geometry"

    override fun buildInsertSql(importedTargetColumns: List<TargetColumn>): String {
        val overridingSystemValue = if (importedTargetColumns.any { it.name in generatedAlwaysColumns }) {
            " OVERRIDING SYSTEM VALUE"
        } else {
            ""
        }

        return if (importedTargetColumns.isEmpty()) {
            buildDefaultValuesInsert(overridingSystemValue, importedTargetColumns)
        } else {
            buildColumnInsert(importedTargetColumns, overridingSystemValue)
        }
    }

    override fun executeChunk(
        importedTargetColumns: List<TargetColumn>,
        rows: List<Array<Any?>>,
    ): WriteResult = when (options.onConflict) {
        OnConflict.UPDATE -> executeUpsertChunk(importedTargetColumns, rows)
        // ABORT: COPY-Bulk-Fast-Path, wenn sicher (echte PGConnection für copyAPI + alle harten
        // Sperren via PostgresCopyFastPath.isEligible) — sonst der Batch-INSERT-Pfad.
        OnConflict.ABORT ->
            if (conn.isWrapperFor(PGConnection::class.java) &&
                PostgresCopyFastPath.isEligible(
                    importedTargetColumns, generatedAlwaysColumns,
                    isGeometry = { isGeometryColumn(it) }, isEnum = { isEnumColumn(it) },
                )
            ) {
                PostgresCopyFastPath.execute(conn, qualifiedTable.quotedPath(), importedTargetColumns, rows)
            } else {
                executeInsertChunk(importedTargetColumns, rows)
            }
        // SKIP = ON CONFLICT DO NOTHING — COPY kennt kein ON CONFLICT → Batch-INSERT.
        OnConflict.SKIP -> executeInsertChunk(importedTargetColumns, rows)
    }

    override fun bindRow(
        stmt: PreparedStatement,
        importedTargetColumns: List<TargetColumn>,
        row: Array<Any?>,
    ) {
        importedTargetColumns.forEachIndexed { index, targetColumn ->
            bindValue(stmt, index + 1, targetColumn, row[index])
        }
    }

    override fun reseedSequences(): List<SequenceAdjustment> =
        schemaSync.reseedGenerators(JdbcDatabaseConnection(conn), table, importedColumns.orEmpty())

    override fun finishDialectCleanup(): Throwable? =
        if (triggersDisabled && !triggersReenabled) {
            runCatching {
                schemaSync.enableTriggers(JdbcDatabaseConnection(conn), table)
                triggersReenabled = true
            }.exceptionOrNull()
        } else {
            null
        }

    override fun closePreFinally() {
        if (triggersDisabled && !triggersReenabled) {
            runCatching {
                schemaSync.enableTriggers(JdbcDatabaseConnection(conn), table)
                triggersReenabled = true
            }.onFailure(::recordCleanupFailure)
        }
    }

    override fun closeFinally() {
        runCatching { conn.autoCommit = savedAutoCommit }.onFailure(::recordCleanupFailure)
    }

    /**
     * PostgreSQL does not need the PK-columns-in-import check because the
     * ON CONFLICT clause references PK columns from table metadata, not
     * from the imported data.
     */
    override fun validateUpsertColumns(resolvedTargetColumns: List<TargetColumn>) {}

    private fun buildDefaultValuesInsert(
        overridingSystemValue: String,
        importedTargetColumns: List<TargetColumn>,
    ): String {
        val baseInsert =
            "INSERT INTO ${qualifiedTable.quotedPath()}$overridingSystemValue DEFAULT VALUES"
        return when (options.onConflict) {
            OnConflict.ABORT -> baseInsert
            OnConflict.SKIP -> "$baseInsert ON CONFLICT DO NOTHING"
            OnConflict.UPDATE -> buildReturningInsert(baseInsert, buildUpsertClause(importedTargetColumns))
        }
    }

    private fun buildColumnInsert(
        importedTargetColumns: List<TargetColumn>,
        overridingSystemValue: String,
    ): String {
        val columnList = importedTargetColumns.joinToString(", ") { quotePostgresIdentifier(it.name) }
        val placeholders = importedTargetColumns.joinToString(", ") { valuePlaceholder(it) }
        val baseInsert =
            "INSERT INTO ${qualifiedTable.quotedPath()} ($columnList)$overridingSystemValue VALUES ($placeholders)"
        return when (options.onConflict) {
            OnConflict.UPDATE -> buildReturningInsert(baseInsert, buildUpsertClause(importedTargetColumns))
            OnConflict.ABORT -> baseInsert
            OnConflict.SKIP -> "$baseInsert ON CONFLICT DO NOTHING"
        }
    }

    private fun buildReturningInsert(
        baseInsert: String,
        upsertClause: String = "",
    ): String =
        "$baseInsert$upsertClause RETURNING (xmax = 0) AS inserted"

    private fun buildUpsertClause(importedTargetColumns: List<TargetColumn>): String {
        val pkSet = primaryKeyColumns.toSet()
        val updateColumns = importedTargetColumns.filterNot { it.name in pkSet }
        if (primaryKeyColumns.isEmpty()) {
            error("ON CONFLICT UPDATE requires primaryKeyColumns to be loaded")
        }
        val conflictTarget = primaryKeyColumns.joinToString(", ") { quotePostgresIdentifier(it) }
        if (updateColumns.isEmpty()) {
            val pk = quotePostgresIdentifier(primaryKeyColumns.first())
            return " ON CONFLICT ($conflictTarget) DO UPDATE SET $pk = EXCLUDED.$pk"
        }
        val assignments = updateColumns.joinToString(", ") {
            "${quotePostgresIdentifier(it.name)} = EXCLUDED.${quotePostgresIdentifier(it.name)}"
        }
        return " ON CONFLICT ($conflictTarget) DO UPDATE SET $assignments"
    }

    private fun executeInsertChunk(
        importedTargetColumns: List<TargetColumn>,
        rows: List<Array<Any?>>,
    ): WriteResult {
        val stmt = preparedStatement!!
        for (row in rows) {
            bindRow(stmt, importedTargetColumns, row)
            stmt.addBatch()
        }
        return toWriteResult(stmt.executeBatch(), options.onConflict)
    }

    private fun executeUpsertChunk(
        importedTargetColumns: List<TargetColumn>,
        rows: List<Array<Any?>>,
    ): WriteResult {
        if (rows.isEmpty()) return WriteResult(rowsInserted = 0, rowsUpdated = 0, rowsSkipped = 0)
        if (importedTargetColumns.isEmpty()) {
            return executeUpsertChunkRowByRow(rows)
        }

        val sql = buildMultiRowUpsertSql(importedTargetColumns, rows.size)
        conn.prepareStatement(sql).use { batchStmt ->
            var paramIdx = 1
            for (row in rows) {
                for ((colIdx, targetColumn) in importedTargetColumns.withIndex()) {
                    bindValue(batchStmt, paramIdx++, targetColumn, row[colIdx])
                }
            }
            var inserted = 0L
            var updated = 0L
            batchStmt.executeQuery().use { rs ->
                while (rs.next()) {
                    if (rs.getBoolean(1)) inserted++ else updated++
                }
            }
            return WriteResult(rowsInserted = inserted, rowsUpdated = updated, rowsSkipped = 0)
        }
    }

    private fun executeUpsertChunkRowByRow(rows: List<Array<Any?>>): WriteResult {
        val stmt = preparedStatement!!
        var inserted = 0L
        var updated = 0L
        for (row in rows) {
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "UPSERT RETURNING returned no row for table '$table'" }
                if (rs.getBoolean(1)) inserted++ else updated++
            }
        }
        return WriteResult(rowsInserted = inserted, rowsUpdated = updated, rowsSkipped = 0)
    }

    private fun buildMultiRowUpsertSql(importedTargetColumns: List<TargetColumn>, rowCount: Int): String {
        val overridingSystemValue = if (importedTargetColumns.any { it.name in generatedAlwaysColumns }) {
            " OVERRIDING SYSTEM VALUE"
        } else {
            ""
        }
        val columnList = importedTargetColumns.joinToString(", ") { quotePostgresIdentifier(it.name) }
        val singleRow = "(${importedTargetColumns.joinToString(", ") { valuePlaceholder(it) }})"
        val allRows = (1..rowCount).joinToString(", ") { singleRow }
        val baseInsert =
            "INSERT INTO ${qualifiedTable.quotedPath()} " +
                "($columnList)$overridingSystemValue VALUES $allRows"
        return buildReturningInsert(baseInsert, buildUpsertClause(importedTargetColumns))
    }

    /** PostgreSQLs `boolean`/`bool` -- unabhaengig davon, welchen JDBC-Code der Treiber dafuer meldet. */
    private fun isBooleanColumn(targetColumn: TargetColumn): Boolean =
        targetColumn.sqlTypeName.equals("bool", ignoreCase = true) ||
            targetColumn.sqlTypeName.equals("boolean", ignoreCase = true)

    private fun bindValue(
        stmt: PreparedStatement,
        parameterIndex: Int,
        targetColumn: TargetColumn,
        value: Any?,
    ) {
        if (value == null) {
            stmt.setNull(parameterIndex, targetColumn.jdbcType)
            return
        }

        when {
            // Ein Dialekt ohne BOOLEAN-Spaltentyp liefert seine Wahrheitswerte
            // als Zahl (Oracle: NUMBER(1)). Der Reverse rekonstruiert daraus
            // korrekt `boolean`, das Ziel entsteht also als `boolean` -- und
            // PostgreSQL nimmt in eine solche Spalte KEINE Zahl an
            // ("column is of type boolean but expression is of type numeric").
            // Die Umsetzung gehoert an diese Grenze: hier ist der Zieltyp
            // bekannt, und 0/nicht-0 ist eindeutig.
            // Auf den Typnamen abgestellt, nicht auf den JDBC-Code: pgjdbc
            // meldet `boolean` je nach Version als BOOLEAN ODER als BIT, und
            // BIT allein waere zu breit (echte Bitfelder).
            isBooleanColumn(targetColumn) && value is Number ->
                stmt.setBoolean(parameterIndex, value.toDouble() != 0.0)

            // VA1c/W1: WKB-Geometriespalte. Explizit als bytea binden (setBytes),
            // nicht via setObject-Default — `ST_GeomFromWKB(?)` erwartet bytea, und
            // setBytes ist pgjdbc-versionsunabhängig. MUSS vor dem Enum-Zweig
            // stehen (geometry ist OTHER + nicht-well-known → würde sonst dort als
            // Enum-pgObject(value.toString()) landen).
            isGeometryColumn(targetColumn) && value is ByteArray ->
                stmt.setBytes(parameterIndex, value)

            targetColumn.jdbcType == Types.OTHER &&
                targetColumn.sqlTypeName.equals("json", ignoreCase = true) ->
                stmt.setObject(parameterIndex, pgObject("json", value.toString()))

            targetColumn.jdbcType == Types.OTHER &&
                targetColumn.sqlTypeName.equals("jsonb", ignoreCase = true) ->
                stmt.setObject(parameterIndex, pgObject("jsonb", value.toString()))

            targetColumn.jdbcType == Types.OTHER &&
                targetColumn.sqlTypeName.equals("interval", ignoreCase = true) ->
                stmt.setObject(parameterIndex, pgObject("interval", value.toString()))

            targetColumn.jdbcType == Types.OTHER &&
                targetColumn.sqlTypeName.equals("xml", ignoreCase = true) ->
                stmt.setObject(parameterIndex, pgObject("xml", value.toString()))

            // I-04: benannte PG-Enum-Spalte. pgjdbc meldet sie als `VARCHAR` (nicht
            // `OTHER`), unterschieden wird allein über den Typnamen. Built-ins werden
            // vorab ausgeschlossen, damit für sie nicht der pg_enum-Katalog abgefragt
            // wird; nur echte Nicht-Built-in-Namen konsultieren [enumTypeNames].
            isEnumColumn(targetColumn) ->
                stmt.setObject(parameterIndex, pgObject(targetColumn.sqlTypeName!!, value.toString()))

            targetColumn.jdbcType == Types.ARRAY && value is List<*> ->
                stmt.setArray(
                    parameterIndex,
                    stmt.connection.createArrayOf(arrayElementType(targetColumn.sqlTypeName), value.toTypedArray())
                )

            else -> stmt.setObject(parameterIndex, value)
        }
    }

    private fun arrayElementType(sqlTypeName: String?): String {
        if (sqlTypeName == null) return "text"
        return when {
            sqlTypeName.endsWith("[]") -> sqlTypeName.removeSuffix("[]")
            sqlTypeName.startsWith("_") -> sqlTypeName.removePrefix("_")
            else -> sqlTypeName
        }
    }

    private fun pgObject(type: String, value: String): PGobject =
        PGobject().apply {
            this.type = type
            this.value = value
        }

    private fun isEnumColumn(targetColumn: TargetColumn): Boolean {
        val typeName = targetColumn.sqlTypeName ?: return false
        if (typeName.lowercase() in NON_ENUM_TYPE_NAMES) return false
        return typeName in enumTypeNames
    }

    private fun toWriteResult(counts: IntArray, onConflict: OnConflict): WriteResult {
        var inserted = 0L
        var skipped = 0L
        var unknown = 0L

        for (count in counts) {
            when {
                // `reWriteBatchedInserts=true` (PostgresJdbcUrlBuilder) lässt pgjdbc
                // Mehrzeilen-Batches serverseitig zu einem Multi-Row-INSERT zusammenfassen und
                // meldet dann SUCCESS_NO_INFO je Batch-Element statt einer Zeilenzahl. Unter
                // ABORT fügt jede Zeile ein (ein Konflikt würde werfen, nicht stumm überspringen)
                // → als eingefügt zählen. Unter SKIP (ON CONFLICT DO NOTHING) ist Einfügen vs.
                // Überspringen aus SUCCESS_NO_INFO nicht rekonstruierbar → ehrlich als unknown.
                count == Statement.SUCCESS_NO_INFO ->
                    if (onConflict == OnConflict.ABORT) inserted++ else unknown++
                count == 0 -> skipped++
                else -> inserted += count.toLong()
            }
        }

        return WriteResult(
            rowsInserted = inserted,
            rowsUpdated = 0,
            rowsSkipped = skipped,
            rowsUnknown = unknown,
        )
    }

    private companion object {
        /**
         * I-04: pgjdbc meldet echte Skalar-Spalten und Enum-Spalten beide mit
         * `Types.VARCHAR`; unterschieden wird über den Typnamen. Bekannte Built-in-
         * Typnamen werden vorab ausgeschlossen, damit für sie nicht der pg_enum-
         * Katalog abgefragt wird (und Unit-Tests mit Mock-Verbindungen keine
         * Katalogabfrage auslösen).
         */
        val NON_ENUM_TYPE_NAMES = setOf(
            "varchar", "text", "bpchar", "char", "name", "citext",
            "int2", "int4", "int8", "serial", "bigserial", "smallserial",
            "float4", "float8", "numeric", "money",
            "bool", "boolean", "date", "timestamp", "timestamptz",
            "time", "timetz", "interval",
            "uuid", "bytea", "json", "jsonb", "xml",
            "inet", "cidr", "macaddr", "macaddr8",
            "bit", "varbit",
            "point", "line", "lseg", "box", "path", "polygon", "circle",
        )
    }
}
