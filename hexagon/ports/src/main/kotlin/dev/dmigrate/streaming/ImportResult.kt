package dev.dmigrate.streaming

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.data.TriggerMode

/**
 * Chunk-granularer Fehler für `--on-error log`.
 */
data class ChunkFailure(
    val table: String,
    val chunkIndex: Long,
    val rowsLost: Long,
    val reason: String,
)

/**
 * Strukturierte Form eines `finishTable()`-Partial-Failure-Pfads.
 */
data class FailedFinishInfo(
    val adjustments: List<SequenceAdjustment>,
    val causeMessage: String,
    val causeClass: String,
    val causeStack: String? = null,
    val closeCauseMessage: String? = null,
    val closeCauseClass: String? = null,
    val closeCauseStack: String? = null,
)

/**
 * Pro-Tabelle-Zusammenfassung eines Imports.
 */
data class TableImportSummary(
    val table: String,
    val rowsInserted: Long,
    val rowsUpdated: Long,
    val rowsSkipped: Long,
    val rowsUnknown: Long,
    val rowsFailed: Long,
    val chunkFailures: List<ChunkFailure>,
    val sequenceAdjustments: List<SequenceAdjustment>,
    val targetColumns: List<ColumnDescriptor>,
    val triggerMode: TriggerMode,
    val failedFinish: FailedFinishInfo? = null,
    val error: String? = null,
    val durationMs: Long,
)

/**
 * Statistik-Aggregat eines Streaming-Imports.
 */
data class ImportResult(
    val tables: List<TableImportSummary>,
    val totalRowsInserted: Long,
    val totalRowsUpdated: Long,
    val totalRowsSkipped: Long,
    val totalRowsUnknown: Long,
    val totalRowsFailed: Long,
    val durationMs: Long,
) {
    val success: Boolean get() = tables.all { it.error == null && it.failedFinish == null }
}
