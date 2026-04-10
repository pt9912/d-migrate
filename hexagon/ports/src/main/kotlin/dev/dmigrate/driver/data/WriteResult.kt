package dev.dmigrate.driver.data

/**
 * Per-Chunk-Ergebnis eines [TableImportSession.write].
 *
 * Alle Felder sind nicht-negativ. [rowsUnknown] ist > 0 nur im
 * MySQL-Pfad bei `Statement.SUCCESS_NO_INFO` (R10/M-R8).
 */
data class WriteResult(
    val rowsInserted: Long,
    val rowsUpdated: Long,
    val rowsSkipped: Long,
    val rowsUnknown: Long = 0L,
) {
    init {
        require(rowsInserted >= 0) { "rowsInserted must be >= 0, got $rowsInserted" }
        require(rowsUpdated >= 0) { "rowsUpdated must be >= 0, got $rowsUpdated" }
        require(rowsSkipped >= 0) { "rowsSkipped must be >= 0, got $rowsSkipped" }
        require(rowsUnknown >= 0) { "rowsUnknown must be >= 0, got $rowsUnknown" }
    }

    /** Gesamtzahl verarbeiteter Rows in diesem Chunk. */
    val totalRows: Long get() = rowsInserted + rowsUpdated + rowsSkipped + rowsUnknown
}
