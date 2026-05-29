package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.sqlite.parser.SqliteTriggerSqlParser

/**
 * 0.9.7 SQLite-Sequence Phase D: reverse-engineering support for
 * the helper-table emulation that Phase B.3 emits. Lives parallel to
 * `MysqlSequenceSupport` and follows the same three-step protocol:
 *
 * 1. [scanSequenceSupport] inspects `sqlite_master` for the helper
 *    table and the canonical `_bi`/`_ai` trigger pairs, returning a
 *    [SqliteSequenceSupportSnapshot] that captures every observation
 *    the schema-reader needs (rows, trigger metadata, diagnostics).
 * 2. [materializeSequences] turns the helper-table rows into
 *    `SequenceDefinition`s. [materializeSequenceDefaults] enriches
 *    user-table columns with `DefaultValue.SequenceNextVal(...)`
 *    based on the resolved trigger pairs.
 * 3. [filterSupportTable] and [filterSupportTriggers] strip the
 *    helper-table and the canonical support triggers out of the
 *    "regular" tables/triggers maps so they don't leak into the
 *    neutral schema as user objects.
 *
 * The diagnostics that fall out of the scan:
 * - `W116` — degraded reverse (canonical secondary-match, or only
 *   one half of the trigger pair present);
 * - `W120` — primary marker matched but the body integrity check
 *   failed (user modified the trigger body);
 * - `W124` — user-defined BEFORE INSERT trigger on the host table
 *   was created **before** the canonical `_bi`-trigger, so it may
 *   mask the sequence assignment (Plan §5.1 lines 1397–1402).
 */
internal class SqliteSequenceReverseSupport {

    fun scanSequenceSupport(session: JdbcMetadataSession): SqliteSequenceSupportSnapshot {
        val exists = SqliteMetadataQueries.checkDmgSequencesTableExists(session)
        if (exists == null) {
            return SqliteSequenceSupportSnapshot.absent(SupportTableState.NOT_ACCESSIBLE)
        }
        if (exists == false) return SqliteSequenceSupportSnapshot.absent(SupportTableState.NOT_FOUND)
        val shapeOk = runCatching { SqliteMetadataQueries.checkDmgSequencesShape(session) }
            .getOrDefault(false)
        if (!shapeOk) {
            return SqliteSequenceSupportSnapshot.absent(SupportTableState.INVALID_SHAPE)
        }
        val rows = runCatching { SqliteMetadataQueries.listDmgSequencesRows(session) }
            .getOrDefault(emptyList())
        val triggerRows = runCatching { SqliteMetadataQueries.listTriggersWithRowid(session) }
            .getOrDefault(emptyList())
        val triggerObservations = classifyTriggers(triggerRows)
        val pairings = pairTriggers(triggerObservations)
        return SqliteSequenceSupportSnapshot(
            supportTableState = SupportTableState.AVAILABLE,
            rows = rows,
            triggers = triggerObservations,
            pairings = pairings,
        )
    }

    /**
     * Step D.2 — materialise `dmg_sequences` rows back into
     * [SequenceDefinition]s. Plan §3.2 cache_size is metadata-only;
     * we surface it through [SequenceDefinition.cache] for round-
     * trip lossless metadata even though the renderer ignores the
     * value at runtime.
     */
    fun materializeSequences(snapshot: SqliteSequenceSupportSnapshot): Map<String, SequenceDefinition> {
        if (snapshot.supportTableState != SupportTableState.AVAILABLE) return emptyMap()
        val out = LinkedHashMap<String, SequenceDefinition>()
        for (row in snapshot.rows) {
            val name = row["name"] as? String ?: continue
            val start = (row["next_value"] as? Number)?.toLong() ?: continue
            val increment = (row["increment_by"] as? Number)?.toLong() ?: 1L
            val minValue = (row["min_value"] as? Number)?.toLong()
            val maxValue = (row["max_value"] as? Number)?.toLong()
            val cycle = (row["cycle_enabled"] as? Number)?.toInt() == 1
            val cache = (row["cache_size"] as? Number)?.toInt()
            out[name] = SequenceDefinition(
                start = start,
                increment = increment,
                minValue = minValue,
                maxValue = maxValue,
                cycle = cycle,
                cache = cache,
            )
        }
        return out
    }

    /**
     * Step D.3 — enrich user-table columns with
     * `DefaultValue.SequenceNextVal(...)` based on the resolved
     * trigger pairings.
     */
    fun materializeSequenceDefaults(
        snapshot: SqliteSequenceSupportSnapshot,
        tables: Map<String, TableDefinition>,
    ): Map<String, TableDefinition> {
        if (snapshot.supportTableState != SupportTableState.AVAILABLE) return tables
        if (snapshot.pairings.isEmpty()) return tables
        val out = LinkedHashMap<String, TableDefinition>(tables)
        for (pairing in snapshot.pairings) {
            val tableName = pairing.tableName ?: continue
            val columnName = pairing.columnName ?: continue
            val sequenceName = pairing.sequenceName ?: continue
            val original = out[tableName] ?: continue
            val column = original.columns[columnName] ?: continue
            if (column.default is DefaultValue.SequenceNextVal) continue
            val newColumns = LinkedHashMap(original.columns)
            newColumns[columnName] = column.copy(
                default = DefaultValue.SequenceNextVal(sequenceName),
            )
            out[tableName] = original.copy(columns = newColumns)
        }
        return out
    }

    fun filterSupportTable(
        tables: Map<String, TableDefinition>,
        snapshot: SqliteSequenceSupportSnapshot,
    ): Map<String, TableDefinition> {
        if (snapshot.supportTableState != SupportTableState.AVAILABLE) return tables
        return tables.filterKeys { it != SqliteSequenceNaming.SUPPORT_TABLE }
    }

    fun filterSupportTriggers(
        triggers: Map<String, TriggerDefinition>,
        snapshot: SqliteSequenceSupportSnapshot,
    ): Map<String, TriggerDefinition> {
        if (snapshot.triggers.isEmpty()) return triggers
        val confirmedNames = snapshot.triggers
            .filter { it.classification.isManagedSupport }
            .map { it.name }
            .toSet()
        if (confirmedNames.isEmpty()) return triggers
        return triggers.filterKeys { key ->
            val (_, triggerName) = ObjectKeyCodec.parseTriggerKey(key)
            triggerName !in confirmedNames
        }
    }

    fun aggregateNotes(snapshot: SqliteSequenceSupportSnapshot): List<SchemaReadNote> {
        if (snapshot.supportTableState == SupportTableState.NOT_FOUND) return emptyList()
        val notes = mutableListOf<SchemaReadNote>()
        if (snapshot.supportTableState == SupportTableState.INVALID_SHAPE) {
            notes += SchemaReadNote(
                severity = SchemaReadSeverity.WARNING,
                code = "W116",
                objectName = SqliteSequenceNaming.SUPPORT_TABLE,
                message = "Support table '${SqliteSequenceNaming.SUPPORT_TABLE}' exists but does not " +
                    "carry the canonical column shape; treating it as user-defined.",
            )
            return notes
        }
        if (snapshot.supportTableState == SupportTableState.NOT_ACCESSIBLE) {
            notes += SchemaReadNote(
                severity = SchemaReadSeverity.WARNING,
                code = "W116",
                objectName = SqliteSequenceNaming.SUPPORT_TABLE,
                message = "Support table '${SqliteSequenceNaming.SUPPORT_TABLE}' is not accessible; " +
                    "sequence reverse path skipped.",
            )
            return notes
        }
        for (pairing in snapshot.pairings) {
            when (pairing.diagnostic) {
                PairingDiagnostic.SECONDARY_MATCH_DEGRADED -> notes += SchemaReadNote(
                    severity = SchemaReadSeverity.WARNING,
                    code = "W116",
                    objectName = "${pairing.tableName}.${pairing.columnName}",
                    message = "Sequence-support trigger pair reconstructed via secondary matching; " +
                        "marker comment is missing or unparseable.",
                )
                PairingDiagnostic.BODY_MODIFIED -> notes += SchemaReadNote(
                    severity = SchemaReadSeverity.WARNING,
                    code = "W120",
                    objectName = pairing.affectedTriggerNames.joinToString(","),
                    message = "Sequence-support trigger has a valid marker but the body was modified; " +
                        "the emulation may no longer work as designed.",
                )
                PairingDiagnostic.HALF_PAIR -> notes += SchemaReadNote(
                    severity = SchemaReadSeverity.WARNING,
                    code = "W116",
                    objectName = pairing.affectedTriggerNames.joinToString(","),
                    message = "Only half of the sequence-support trigger pair was found; sequence " +
                        "metadata stays reconstructed but the column binding is incomplete.",
                )
                PairingDiagnostic.MASKED_BY_USER_TRIGGER -> notes += SchemaReadNote(
                    severity = SchemaReadSeverity.WARNING,
                    code = "W124",
                    objectName = "${pairing.tableName}.${pairing.columnName}",
                    message = "User-defined BEFORE INSERT trigger on '${pairing.tableName}' was " +
                        "created before the sequence-support trigger; SQLite's trigger-by-creation-" +
                        "order rule may mask the sequence assignment.",
                )
                PairingDiagnostic.NONE -> Unit
            }
        }
        return notes
    }

    private fun classifyTriggers(rows: List<Map<String, Any?>>): List<TriggerObservation> {
        val out = mutableListOf<TriggerObservation>()
        for (row in rows) {
            val name = row["name"] as? String ?: continue
            val table = row["tbl_name"] as? String ?: continue
            val sql = row["sql"] as? String ?: continue
            val rowid = (row["rowid"] as? Number)?.toLong() ?: continue

            val marker = SqliteSequenceMarkerParser.parse(sql)
            if (marker != null) {
                val bodyOk = verifyBodyIntegrity(sql, marker.objectType, table)
                val classification = if (bodyOk) TriggerClassification.PRIMARY_MATCH
                else TriggerClassification.PRIMARY_MATCH_BODY_MODIFIED
                out += TriggerObservation(name, table, rowid, sql, marker, classification)
                continue
            }
            // No marker → enforce Plan §6.1 lines 1716-1726: only
            // promote to SECONDARY_CANDIDATE if (a) the name matches
            // the canonical schema, (b) event/timing align with the
            // expected `_bi`/`_ai` semantics, (c) the WHEN-clause is
            // `NEW.<column> IS NULL` for the column the canonical
            // name decoder recovered, and (d) the body carries the
            // required identifier tokens. Otherwise the trigger
            // stays USER_DEFINED so the user object survives
            // round-trip.
            if (matchesSecondaryCriteria(name, table, sql)) {
                out += TriggerObservation(name, table, rowid, sql, null, TriggerClassification.SECONDARY_CANDIDATE)
            } else {
                out += TriggerObservation(name, table, rowid, sql, null, TriggerClassification.USER_DEFINED)
            }
        }
        return out
    }

    /**
     * Implements four of the five Plan §6.1 secondary-match criteria
     * that are decidable on a single trigger observation. The fifth
     * criterion ("both `_bi` and `_ai` of the pair exist") is checked
     * during pairing in [buildPairing].
     */
    private fun matchesSecondaryCriteria(name: String, table: String, sql: String): Boolean {
        if (!SqliteSequenceNaming.isCanonicalSupportTriggerName(name)) return false
        val parts = decomposeCanonicalName(name) ?: return false
        val parsed = runCatching { SqliteTriggerSqlParser.parse(sql, name) }.getOrNull() ?: return false
        if (parsed.event != TriggerEvent.INSERT) return false
        val expectedTiming = when (parts.suffix) {
            SqliteSequenceMarkerParser.ObjectType.BEFORE_INSERT -> TriggerTiming.BEFORE
            SqliteSequenceMarkerParser.ObjectType.AFTER_INSERT -> TriggerTiming.AFTER
        }
        if (parsed.timing != expectedTiming) return false
        if (!whenClauseMatchesColumn(parsed.condition, parts.column)) return false
        return verifyBodyIntegrity(sql, parts.suffix, table)
    }

    /**
     * Plan §6.1 line 1722: secondary-match accepts a trigger whose
     * `WHEN`-clause checks `NEW.<column> IS NULL` for the column the
     * canonical name encoded — across the four SQLite identifier-
     * quoting forms.
     */
    private fun whenClauseMatchesColumn(condition: String?, column: String): Boolean {
        if (condition.isNullOrBlank()) return false
        val collapsed = condition.replace(Regex("\\s+"), " ").trim().lowercase()
        val col = column.lowercase()
        val colRefs = listOf(
            col,
            "\"${col.replace("\"", "\"\"")}\"",
            "`${col.replace("`", "``")}`",
            "[$col]",
        )
        return colRefs.any { ref -> collapsed == "new.$ref is null" }
    }

    private fun verifyBodyIntegrity(
        sql: String,
        objectType: SqliteSequenceMarkerParser.ObjectType,
        tableName: String,
    ): Boolean = when (objectType) {
        SqliteSequenceMarkerParser.ObjectType.BEFORE_INSERT ->
            SqliteIdentifierTokenScanner.containsIdentifier(sql, SqliteSequenceNaming.SUPPORT_TABLE)
        SqliteSequenceMarkerParser.ObjectType.AFTER_INSERT ->
            SqliteIdentifierTokenScanner.containsIdentifier(sql, tableName) &&
                SqliteIdentifierTokenScanner.containsIdentifier(sql, "ROWID")
    }

    private fun pairTriggers(observations: List<TriggerObservation>): List<TriggerPairing> {
        val groups = groupPairCandidates(observations)
        return groups.mapNotNull { (key, pair) ->
            buildPairing(key, pair, observations)
        }
    }

    private fun groupPairCandidates(
        observations: List<TriggerObservation>,
    ): Map<Triple<String, String, String?>, MutablePair> {
        val groups: MutableMap<Triple<String, String, String?>, MutablePair> = LinkedHashMap()
        for (obs in observations) {
            if (obs.classification == TriggerClassification.USER_DEFINED) continue
            val key = pairingKey(obs) ?: continue
            val pair = groups.getOrPut(key) { MutablePair() }
            when (obs.markerObjectType ?: deriveObjectTypeFromName(obs.name)) {
                SqliteSequenceMarkerParser.ObjectType.BEFORE_INSERT -> pair.bi = obs
                SqliteSequenceMarkerParser.ObjectType.AFTER_INSERT -> pair.ai = obs
                null -> Unit
            }
        }
        return groups
    }

    private fun buildPairing(
        key: Triple<String, String, String?>,
        pair: MutablePair,
        observations: List<TriggerObservation>,
    ): TriggerPairing? {
        val (table, column, seqOrNull) = key
        val bi = pair.bi
        val ai = pair.ai
        if (bi == null || ai == null) {
            return TriggerPairing(
                tableName = table,
                columnName = column,
                sequenceName = seqOrNull,
                affectedTriggerNames = listOfNotNull(bi?.name, ai?.name),
                diagnostic = PairingDiagnostic.HALF_PAIR,
            )
        }
        val sequenceName = bi.markerSequenceName ?: ai.markerSequenceName ?: seqOrNull ?: return null
        val baseDiagnostic = classifyPair(bi, ai)
        val maskedByUser = userTriggerCreatedBefore(observations, table, bi.rowid)
        val diagnostic = if (maskedByUser) PairingDiagnostic.MASKED_BY_USER_TRIGGER else baseDiagnostic
        return TriggerPairing(
            tableName = table,
            columnName = column,
            sequenceName = sequenceName,
            affectedTriggerNames = listOf(bi.name, ai.name),
            diagnostic = diagnostic,
        )
    }

    private fun classifyPair(bi: TriggerObservation, ai: TriggerObservation): PairingDiagnostic {
        val anyBodyModified = bi.classification == TriggerClassification.PRIMARY_MATCH_BODY_MODIFIED ||
            ai.classification == TriggerClassification.PRIMARY_MATCH_BODY_MODIFIED
        if (anyBodyModified) return PairingDiagnostic.BODY_MODIFIED
        val anyPrimary = bi.classification.isPrimary || ai.classification.isPrimary
        val anySecondary = bi.classification == TriggerClassification.SECONDARY_CANDIDATE ||
            ai.classification == TriggerClassification.SECONDARY_CANDIDATE
        return if (anySecondary && !anyPrimary) PairingDiagnostic.SECONDARY_MATCH_DEGRADED
        else PairingDiagnostic.NONE
    }

    private fun userTriggerCreatedBefore(
        observations: List<TriggerObservation>,
        table: String,
        biRowid: Long,
    ): Boolean = observations.any { other ->
        other.classification == TriggerClassification.USER_DEFINED &&
            other.table == table &&
            other.rowid < biRowid &&
            triggerIsBeforeInsert(other.sql)
    }

    private fun pairingKey(obs: TriggerObservation): Triple<String, String, String?>? {
        val marker = obs.marker
        if (marker != null) {
            return Triple(marker.tableName, marker.columnName, marker.sequenceName)
        }
        // Secondary candidates use the canonical name decomposition.
        val parts = decomposeCanonicalName(obs.name) ?: return null
        return Triple(obs.table, parts.column, null)
    }

    private data class CanonicalNameParts(val column: String, val suffix: SqliteSequenceMarkerParser.ObjectType)

    /**
     * `dmg_seq_<table16>_<column16>_<hash10>_{bi,ai}` — the table
     * segment is rarely the full original (truncated to 16 chars +
     * normalised), so we cannot recover the column or sequence with
     * 100% fidelity from the name alone. The reverse path falls back
     * to the marker for sequence-name recovery; the canonical column
     * segment is best-effort.
     */
    private fun decomposeCanonicalName(name: String): CanonicalNameParts? {
        if (!SqliteSequenceNaming.isCanonicalSupportTriggerName(name)) return null
        val withoutPrefix = name.removePrefix("dmg_seq_")
        val type = when {
            withoutPrefix.endsWith("_bi") -> SqliteSequenceMarkerParser.ObjectType.BEFORE_INSERT
            withoutPrefix.endsWith("_ai") -> SqliteSequenceMarkerParser.ObjectType.AFTER_INSERT
            else -> return null
        }
        // Strip the `_bi` / `_ai` suffix and the trailing 10-hex
        // hash + underscore (11 chars total). The remainder is
        // `<table16>_<column16>` — split on the last underscore.
        val core = withoutPrefix.dropLast(3)
        if (core.length <= 11) return null
        val withoutHash = core.dropLast(11)
        val splitIdx = withoutHash.lastIndexOf('_')
        if (splitIdx < 0) return null
        val column = withoutHash.substring(splitIdx + 1)
        return CanonicalNameParts(column, type)
    }

    private fun deriveObjectTypeFromName(name: String): SqliteSequenceMarkerParser.ObjectType? =
        decomposeCanonicalName(name)?.suffix

    private fun triggerIsBeforeInsert(sql: String): Boolean =
        Regex("(?is)\\bBEFORE\\s+INSERT\\b").containsMatchIn(sql)

    private class MutablePair {
        var bi: TriggerObservation? = null
        var ai: TriggerObservation? = null
    }
}

internal data class SqliteSequenceSupportSnapshot(
    val supportTableState: SupportTableState,
    val rows: List<Map<String, Any?>> = emptyList(),
    val triggers: List<TriggerObservation> = emptyList(),
    val pairings: List<TriggerPairing> = emptyList(),
) {
    companion object {
        fun absent(state: SupportTableState): SqliteSequenceSupportSnapshot =
            SqliteSequenceSupportSnapshot(supportTableState = state)
    }
}

internal enum class SupportTableState {
    AVAILABLE,
    NOT_FOUND,
    NOT_ACCESSIBLE,
    INVALID_SHAPE,
}

internal data class TriggerObservation(
    val name: String,
    val table: String,
    val rowid: Long,
    val sql: String,
    val marker: SqliteSequenceMarkerParser.Marker?,
    val classification: TriggerClassification,
) {
    val markerObjectType: SqliteSequenceMarkerParser.ObjectType? get() = marker?.objectType
    val markerSequenceName: String? get() = marker?.sequenceName
}

internal enum class TriggerClassification(val isPrimary: Boolean, val isManagedSupport: Boolean) {
    PRIMARY_MATCH(true, true),
    PRIMARY_MATCH_BODY_MODIFIED(true, true),
    SECONDARY_CANDIDATE(false, true),
    USER_DEFINED(false, false),
}

internal data class TriggerPairing(
    val tableName: String?,
    val columnName: String?,
    val sequenceName: String?,
    val affectedTriggerNames: List<String>,
    val diagnostic: PairingDiagnostic,
)

internal enum class PairingDiagnostic {
    NONE,
    SECONDARY_MATCH_DEGRADED,
    BODY_MODIFIED,
    HALF_PAIR,
    MASKED_BY_USER_TRIGGER,
}
