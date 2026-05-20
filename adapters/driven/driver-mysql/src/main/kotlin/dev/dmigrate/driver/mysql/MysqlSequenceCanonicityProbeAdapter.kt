package dev.dmigrate.driver.mysql

import dev.dmigrate.core.util.sha256Hex
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.MysqlSequenceCanonicityDeclaration
import dev.dmigrate.driver.MysqlSequenceCanonicityKind
import dev.dmigrate.driver.MysqlSequenceCanonicityProbe
import dev.dmigrate.driver.MysqlSequenceCanonicityStatus
import java.sql.Connection
import java.sql.SQLException

/**
 * E.3 MySQL Sequence Drift-Check Sub-Slice A (2026-05-20): probe
 * adapter for the helper-table emulation's canonical objects.
 * Mirrors [MysqlCheckPreflightProbe]'s shape: stateless, takes a
 * JDBC [Connection], returns a [MysqlSequenceCanonicityDeclaration]
 * per probe call.
 *
 * The adapter inspects the live `INFORMATION_SCHEMA` /
 * `SHOW CREATE` outputs and compares them against the canonical
 * contract that `MysqlSequenceEmulationTemplates` emits. Drift is
 * surfaced field-by-field so the operator can see exactly what
 * differs.
 *
 * Out-of-scope (separate slices): batch / multiple probes per
 * call, schema-qualified probing (only the connection's default
 * schema is inspected today), drift-repair / auto-fix.
 */
class MysqlSequenceCanonicityProbeAdapter(
    private val connection: Connection,
) : MysqlSequenceCanonicityProbe {

    private val dialect: String = DatabaseDialect.MYSQL.name.lowercase()

    override fun probeSupportTable(operationId: String): MysqlSequenceCanonicityDeclaration {
        val columnsSql =
            "SELECT column_name, column_type, is_nullable FROM information_schema.columns " +
                "WHERE table_schema = DATABASE() AND table_name = " +
                MysqlSequenceSqlCodec.quoteStringLiteral(MysqlSequenceNaming.SUPPORT_TABLE) +
                " ORDER BY ordinal_position"
        // E.3 Sub-Slice F follow-up (2026-05-20): the canonical
        // contract for `dmg_sequences` includes `PRIMARY KEY (name)`;
        // without it duplicate-name guards collapse and dmg_nextval
        // returns indeterminate rows. Probe the PK columns from
        // information_schema.statistics (PRIMARY index name is
        // always "PRIMARY") so a table that drifted to "no PK" is
        // detected.
        val pkSql =
            "SELECT column_name FROM information_schema.statistics " +
                "WHERE table_schema = DATABASE() AND table_name = " +
                MysqlSequenceSqlCodec.quoteStringLiteral(MysqlSequenceNaming.SUPPORT_TABLE) +
                " AND index_name = 'PRIMARY' ORDER BY seq_in_index"
        val sqlHash = sha256Hex(columnsSql + "|" + pkSql).take(SQL_HASH_PREFIX_LEN)
        return runProbe(operationId, MysqlSequenceCanonicityKind.SUPPORT_TABLE,
            MysqlSequenceNaming.SUPPORT_TABLE, sqlHash) {
            val actualColumns = mutableListOf<Triple<String, String, Boolean>>()
            connection.createStatement().use { stmt ->
                stmt.executeQuery(columnsSql).use { rs ->
                    while (rs.next()) {
                        actualColumns += Triple(
                            rs.getString("column_name").lowercase(),
                            rs.getString("column_type").lowercase(),
                            rs.getString("is_nullable").equals("YES", ignoreCase = true),
                        )
                    }
                }
            }
            if (actualColumns.isEmpty()) {
                missing(operationId, MysqlSequenceCanonicityKind.SUPPORT_TABLE,
                    MysqlSequenceNaming.SUPPORT_TABLE, sqlHash)
            } else {
                val actualPk = mutableListOf<String>()
                connection.createStatement().use { stmt ->
                    stmt.executeQuery(pkSql).use { rs ->
                        while (rs.next()) {
                            actualPk += rs.getString("column_name").lowercase()
                        }
                    }
                }
                classifySupportTable(operationId, sqlHash, actualColumns, actualPk)
            }
        }
    }

    override fun probeRoutine(
        operationId: String,
        kind: MysqlSequenceCanonicityKind,
    ): MysqlSequenceCanonicityDeclaration {
        require(kind == MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE ||
            kind == MysqlSequenceCanonicityKind.SETVAL_ROUTINE) {
            "probeRoutine accepts NEXTVAL_ROUTINE or SETVAL_ROUTINE; got $kind"
        }
        val routineName = when (kind) {
            MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE -> MysqlSequenceNaming.NEXTVAL_ROUTINE
            MysqlSequenceCanonicityKind.SETVAL_ROUTINE -> MysqlSequenceNaming.SETVAL_ROUTINE
            else -> error("unreachable")
        }
        val markerKind = when (kind) {
            MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE -> "nextval"
            MysqlSequenceCanonicityKind.SETVAL_ROUTINE -> "setval"
            else -> error("unreachable")
        }
        val sql = "SHOW CREATE FUNCTION " + MysqlSequenceSqlCodec.quoteIdentifier(routineName)
        val sqlHash = sha256Hex(sql).take(SQL_HASH_PREFIX_LEN)
        return runProbe(operationId, kind, routineName, sqlHash) {
            val body = readShowCreateBody(sql, "Create Function")
            if (body == null) {
                missing(operationId, kind, routineName, sqlHash)
            } else {
                val expectedMarker = "/* d-migrate:mysql-sequence-v1 object=$markerKind */"
                if (body.contains(expectedMarker)) {
                    canonical(operationId, kind, routineName, sqlHash)
                } else {
                    drift(operationId, kind, routineName, sqlHash,
                        field = "body_marker",
                        expected = expectedMarker,
                        actual = body.take(MARKER_PREVIEW_LEN),
                    )
                }
            }
        }
    }

    override fun probeSequenceRow(
        operationId: String,
        sequenceName: String,
        expectedIncrement: Long,
        expectedMinValue: Long?,
        expectedMaxValue: Long?,
        expectedCycle: Boolean,
        expectedCache: Int?,
    ): MysqlSequenceCanonicityDeclaration {
        val nameLiteral = MysqlSequenceSqlCodec.quoteStringLiteral(sequenceName)
        val sql =
            "SELECT increment_by, min_value, max_value, cycle_enabled, cache_size " +
                "FROM " + MysqlSequenceSqlCodec.quoteIdentifier(MysqlSequenceNaming.SUPPORT_TABLE) +
                " WHERE name = $nameLiteral"
        val sqlHash = sha256Hex(sql).take(SQL_HASH_PREFIX_LEN)
        return runProbe(operationId, MysqlSequenceCanonicityKind.SEQUENCE_ROW,
            sequenceName, sqlHash) {
            connection.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    if (!rs.next()) {
                        missing(operationId, MysqlSequenceCanonicityKind.SEQUENCE_ROW,
                            sequenceName, sqlHash)
                    } else {
                        val actualIncrement = rs.getLong("increment_by")
                        val actualMin = rs.getLong("min_value").takeUnless { rs.wasNull() }
                        val actualMax = rs.getLong("max_value").takeUnless { rs.wasNull() }
                        val actualCycle = rs.getInt("cycle_enabled") != 0
                        val actualCache = rs.getInt("cache_size").takeUnless { rs.wasNull() }
                        classifySequenceRow(
                            operationId, sequenceName, sqlHash,
                            expectedIncrement, expectedMinValue, expectedMaxValue,
                            expectedCycle, expectedCache,
                            actualIncrement, actualMin, actualMax,
                            actualCycle, actualCache,
                        )
                    }
                }
            }
        }
    }

    override fun probeSupportTrigger(
        operationId: String,
        triggerName: String,
        expectedSequenceName: String,
    ): MysqlSequenceCanonicityDeclaration {
        val sql = "SHOW CREATE TRIGGER " + MysqlSequenceSqlCodec.quoteIdentifier(triggerName)
        val sqlHash = sha256Hex(sql).take(SQL_HASH_PREFIX_LEN)
        return runProbe(operationId, MysqlSequenceCanonicityKind.SUPPORT_TRIGGER,
            triggerName, sqlHash) {
            val body = readShowCreateBody(sql, "SQL Original Statement")
            if (body == null) {
                missing(operationId, MysqlSequenceCanonicityKind.SUPPORT_TRIGGER,
                    triggerName, sqlHash)
            } else {
                val expectedMarkerSubstring =
                    "object=sequence-trigger sequence=" +
                        MysqlSequenceSqlCodec.markerValue(expectedSequenceName)
                if (body.contains(expectedMarkerSubstring)) {
                    canonical(operationId, MysqlSequenceCanonicityKind.SUPPORT_TRIGGER,
                        triggerName, sqlHash)
                } else {
                    drift(operationId, MysqlSequenceCanonicityKind.SUPPORT_TRIGGER,
                        triggerName, sqlHash,
                        field = "body_marker",
                        expected = expectedMarkerSubstring,
                        actual = body.take(MARKER_PREVIEW_LEN),
                    )
                }
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────

    private fun runProbe(
        operationId: String,
        kind: MysqlSequenceCanonicityKind,
        objectName: String,
        sqlHash: String,
        block: () -> MysqlSequenceCanonicityDeclaration,
    ): MysqlSequenceCanonicityDeclaration = try {
        block()
    } catch (e: SQLException) {
        MysqlSequenceCanonicityDeclaration(
            operationId = operationId,
            dialect = dialect,
            kind = kind,
            objectName = objectName,
            status = MysqlSequenceCanonicityStatus.PROBE_RUNTIME_ERROR,
            sqlHash = sqlHash,
            problem = e.message ?: e::class.simpleName.orEmpty(),
        )
    }

    private fun readShowCreateBody(sql: String, columnLabel: String): String? =
        connection.createStatement().use { stmt ->
            try {
                stmt.executeQuery(sql).use { rs ->
                    if (rs.next()) rs.getString(columnLabel) else null
                }
            } catch (e: SQLException) {
                // MySQL throws SQLException with ErrorCode 1305 for
                // "FUNCTION/TRIGGER … does not exist". Treat as MISSING
                // for the routine/trigger probes; the runProbe wrapper
                // handles other SQLExceptions as PROBE_RUNTIME_ERROR.
                if (e.errorCode == MYSQL_ERR_SP_DOES_NOT_EXIST ||
                    e.errorCode == MYSQL_ERR_TRG_DOES_NOT_EXIST
                ) {
                    null
                } else {
                    throw e
                }
            }
        }

    private fun classifySupportTable(
        operationId: String,
        sqlHash: String,
        actual: List<Triple<String, String, Boolean>>,
        actualPk: List<String> = listOf("name"),
    ): MysqlSequenceCanonicityDeclaration {
        if (actual.isEmpty()) {
            return missing(operationId, MysqlSequenceCanonicityKind.SUPPORT_TABLE,
                MysqlSequenceNaming.SUPPORT_TABLE, sqlHash)
        }
        val expectedPk = listOf("name")
        if (actualPk != expectedPk) {
            return drift(operationId, MysqlSequenceCanonicityKind.SUPPORT_TABLE,
                MysqlSequenceNaming.SUPPORT_TABLE, sqlHash,
                field = "primary_key",
                expected = expectedPk.joinToString(","),
                actual = actualPk.joinToString(",").ifEmpty { "<none>" })
        }
        val expected = listOf(
            Triple("managed_by", "varchar(32)", false),
            Triple("format_version", "varchar(32)", false),
            Triple("name", "varchar(255)", false),
            Triple("next_value", "bigint", false),
            Triple("increment_by", "bigint", false),
            Triple("min_value", "bigint", true),
            Triple("max_value", "bigint", true),
            Triple("cycle_enabled", "tinyint(1)", false),
            Triple("cache_size", "int", true),
        )
        if (actual.size != expected.size) {
            return drift(operationId, MysqlSequenceCanonicityKind.SUPPORT_TABLE,
                MysqlSequenceNaming.SUPPORT_TABLE, sqlHash,
                field = "column_count",
                expected = expected.size.toString(),
                actual = actual.size.toString(),
            )
        }
        for (idx in expected.indices) {
            val (eName, eType, eNullable) = expected[idx]
            val (aName, aType, aNullable) = actual[idx]
            if (aName != eName) {
                return drift(operationId, MysqlSequenceCanonicityKind.SUPPORT_TABLE,
                    MysqlSequenceNaming.SUPPORT_TABLE, sqlHash,
                    field = "column_name[$idx]", expected = eName, actual = aName)
            }
            // MySQL reports `bigint` as `bigint` on 8.0+ but as
            // `bigint(20)` on older servers; treat the unsigned-aware
            // length suffix as equivalent.
            if (!typesEqual(eType, aType)) {
                return drift(operationId, MysqlSequenceCanonicityKind.SUPPORT_TABLE,
                    MysqlSequenceNaming.SUPPORT_TABLE, sqlHash,
                    field = "column_type[$aName]", expected = eType, actual = aType)
            }
            if (aNullable != eNullable) {
                return drift(operationId, MysqlSequenceCanonicityKind.SUPPORT_TABLE,
                    MysqlSequenceNaming.SUPPORT_TABLE, sqlHash,
                    field = "column_nullable[$aName]",
                    expected = eNullable.toString(), actual = aNullable.toString())
            }
        }
        return canonical(operationId, MysqlSequenceCanonicityKind.SUPPORT_TABLE,
            MysqlSequenceNaming.SUPPORT_TABLE, sqlHash)
    }

    /**
     * MySQL 5.7 reports `bigint(20)` / `int(11)`, MySQL 8.0+ reports
     * `bigint` / `int`. Strip the parenthesized display-width suffix
     * for integer types so the probe stays version-agnostic.
     */
    private fun typesEqual(expected: String, actual: String): Boolean {
        if (expected == actual) return true
        val normalisedActual = actual.replace(INTEGER_DISPLAY_WIDTH, "").trim()
        return expected == normalisedActual
    }

    @Suppress("LongParameterList")
    private fun classifySequenceRow(
        operationId: String,
        sequenceName: String,
        sqlHash: String,
        expectedIncrement: Long,
        expectedMinValue: Long?,
        expectedMaxValue: Long?,
        expectedCycle: Boolean,
        expectedCache: Int?,
        actualIncrement: Long,
        actualMin: Long?,
        actualMax: Long?,
        actualCycle: Boolean,
        actualCache: Int?,
    ): MysqlSequenceCanonicityDeclaration {
        if (actualIncrement != expectedIncrement) {
            return drift(operationId, MysqlSequenceCanonicityKind.SEQUENCE_ROW,
                sequenceName, sqlHash,
                field = "increment_by",
                expected = expectedIncrement.toString(),
                actual = actualIncrement.toString())
        }
        if (actualMin != expectedMinValue) {
            return drift(operationId, MysqlSequenceCanonicityKind.SEQUENCE_ROW,
                sequenceName, sqlHash,
                field = "min_value",
                expected = expectedMinValue?.toString() ?: "NULL",
                actual = actualMin?.toString() ?: "NULL")
        }
        if (actualMax != expectedMaxValue) {
            return drift(operationId, MysqlSequenceCanonicityKind.SEQUENCE_ROW,
                sequenceName, sqlHash,
                field = "max_value",
                expected = expectedMaxValue?.toString() ?: "NULL",
                actual = actualMax?.toString() ?: "NULL")
        }
        if (actualCycle != expectedCycle) {
            return drift(operationId, MysqlSequenceCanonicityKind.SEQUENCE_ROW,
                sequenceName, sqlHash,
                field = "cycle_enabled",
                expected = expectedCycle.toString(),
                actual = actualCycle.toString())
        }
        if (actualCache != expectedCache) {
            return drift(operationId, MysqlSequenceCanonicityKind.SEQUENCE_ROW,
                sequenceName, sqlHash,
                field = "cache_size",
                expected = expectedCache?.toString() ?: "NULL",
                actual = actualCache?.toString() ?: "NULL")
        }
        return canonical(operationId, MysqlSequenceCanonicityKind.SEQUENCE_ROW,
            sequenceName, sqlHash)
    }

    private fun canonical(
        operationId: String,
        kind: MysqlSequenceCanonicityKind,
        objectName: String,
        sqlHash: String,
    ) = MysqlSequenceCanonicityDeclaration(
        operationId = operationId,
        dialect = dialect,
        kind = kind,
        objectName = objectName,
        status = MysqlSequenceCanonicityStatus.CANONICAL,
        sqlHash = sqlHash,
    )

    private fun missing(
        operationId: String,
        kind: MysqlSequenceCanonicityKind,
        objectName: String,
        sqlHash: String,
    ) = MysqlSequenceCanonicityDeclaration(
        operationId = operationId,
        dialect = dialect,
        kind = kind,
        objectName = objectName,
        status = MysqlSequenceCanonicityStatus.MISSING,
        sqlHash = sqlHash,
    )

    @Suppress("LongParameterList")
    private fun drift(
        operationId: String,
        kind: MysqlSequenceCanonicityKind,
        objectName: String,
        sqlHash: String,
        field: String,
        expected: String,
        actual: String,
    ) = MysqlSequenceCanonicityDeclaration(
        operationId = operationId,
        dialect = dialect,
        kind = kind,
        objectName = objectName,
        status = MysqlSequenceCanonicityStatus.DRIFT,
        sqlHash = sqlHash,
        driftField = field,
        expected = expected,
        actual = actual,
    )

    companion object {
        private const val SQL_HASH_PREFIX_LEN: Int = 16
        private const val MARKER_PREVIEW_LEN: Int = 200
        private const val MYSQL_ERR_SP_DOES_NOT_EXIST: Int = 1305
        private const val MYSQL_ERR_TRG_DOES_NOT_EXIST: Int = 1360
        private val INTEGER_DISPLAY_WIDTH = Regex("\\(\\d+\\)")
    }
}
