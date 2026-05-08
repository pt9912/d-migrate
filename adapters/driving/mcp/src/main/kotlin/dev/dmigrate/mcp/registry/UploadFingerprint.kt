package dev.dmigrate.mcp.registry

/**
 * Centralised `existingFingerprint` builders for
 * [dev.dmigrate.server.application.error.IdempotencyConflictException]
 * thrown by the upload-session handlers. The fingerprint is opaque
 * to clients (it gets stamped into the typed error envelope), but
 * the format must stay stable so log scrapers and audit trails can
 * recognise the same conflict across replays. Concentrating the
 * literals here also keeps the `session=…,state=COMPLETED` shape
 * from drifting between the three handlers that emit it.
 */
internal object UploadFingerprint {
    fun sessionCompleted(sessionId: String): String =
        "session=$sessionId,state=COMPLETED"

    fun segmentTotalMismatch(sessionId: String, expectedTotal: Int): String =
        "session=$sessionId,segmentTotal=$expectedTotal"

    fun segmentIndexUnknown(sessionId: String, segmentIndex: Int): String =
        "session=$sessionId,segmentIndex=$segmentIndex"

    fun segmentHashMismatch(segmentIndex: Int, existingSha256: String): String =
        "segment=$segmentIndex,sha256=$existingSha256"
}
