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
 * 0.9.0 Phase D.2/D.3 (`docs/ImpPlan-0.9.0-D.md` §5.2 / §5.3):
 * Resume-Zustand fuer eine Tabelle bzw. einen Input-Slice, den der
 * [StreamingImporter] vom [DataImportRunner] per Map erhaelt. Ein
 * Eintrag signalisiert: die Tabelle wurde in einem frueheren Lauf
 * bereits teilweise oder ganz importiert.
 *
 * Der Importer liest dann aus dem Reader die ersten
 * [committedChunks] Chunks und **verwirft** sie (kein Write, kein
 * Commit, keine Zaehleranpassung). Erst ab dem darauffolgenden
 * Chunk wird wieder regulaer geschrieben. Das vermeidet
 * Doppel-Inserts fuer bereits bestaetigte Grenzen.
 *
 * Der zugehoerige Truncate-Guard (§4.4): fuer Tabellen mit
 * `committedChunks > 0` laesst der Importer `truncate` **nicht**
 * laufen — sonst wuerden bereits bestaetigte Zeilen wieder
 * verschwinden.
 */
data class ImportTableResumeState(
    /**
     * Anzahl der Chunks, die in einem frueheren Lauf bereits
     * erfolgreich `session.commitChunk()` durchlaufen haben. Der
     * Importer liest (und verwirft) genau so viele Chunks vom
     * Reader, bevor er wieder regulaer schreibt. Bei `0L` ist die
     * Tabelle fresh (aequivalent zu keinem Eintrag).
     */
    val committedChunks: Long,
) {
    init {
        require(committedChunks >= 0L) {
            "committedChunks must be >= 0, got $committedChunks"
        }
    }
}

/**
 * 0.9.0 Phase D.3 (`docs/ImpPlan-0.9.0-D.md` §5.3): chunk-granularer
 * Commit-Fortschritt. Der [StreamingImporter] emittiert diesen
 * Datensatz **nur** nachdem `session.commitChunk()` erfolgreich war;
 * Mid-Write- oder Mid-Commit-Fehler erzeugen **keinen** Commit-
 * Callback. Damit kann der [DataImportRunner] das Manifest so
 * fortschreiben, dass ein spaeterer Resume exakt am naechsten
 * offenen Chunk ansetzt.
 *
 * Im Unterschied zur `ImportChunkProcessed`-Progress-Event traegt
 * dieser Typ den aggregierten Zaehlerstand (inkl. Pre-Resume-
 * Offsets), damit er direkt ins Manifest geschrieben werden kann.
 */
data class ImportChunkCommit(
    val table: String,
    /** 0-basierter Reader-Chunk-Index des gerade bestaetigten Chunks. */
    val chunkIndex: Long,
    /**
     * Gesamtzahl der bis einschliesslich diesem Chunk bestaetigten
     * Chunks fuer diese Tabelle. Bei einem Fresh-Track-Lauf ist das
     * `chunkIndex + 1`; bei einem Resume-Lauf entsprechend
     * `pre-resume-offset + (neue committed Chunks)`.
     */
    val chunksCommitted: Long,
    val rowsInsertedTotal: Long,
    val rowsUpdatedTotal: Long,
    val rowsSkippedTotal: Long,
    val rowsUnknownTotal: Long,
    val rowsFailedTotal: Long,
) {
    init {
        require(table.isNotBlank()) { "table must not be blank" }
        require(chunksCommitted >= 1L) {
            "chunksCommitted must be >= 1, got $chunksCommitted"
        }
    }

    /** Summe aller Zeilenzaehler fuer Manifest-Fortschreibung. */
    val rowsProcessedTotal: Long get() =
        rowsInsertedTotal + rowsUpdatedTotal + rowsSkippedTotal +
            rowsUnknownTotal + rowsFailedTotal
}

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
    /**
     * 0.9.0 Phase B (`docs/ImpPlan-0.9.0-B.md` §4.5): stabile
     * `operationId` des Laufs. Symmetrisch zu [ExportResult.operationId].
     */
    val operationId: String? = null,
) {
    val success: Boolean get() = tables.all { it.error == null && it.failedFinish == null }
}
