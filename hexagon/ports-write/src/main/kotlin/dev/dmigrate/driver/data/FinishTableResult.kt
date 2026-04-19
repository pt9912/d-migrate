package dev.dmigrate.driver.data

/**
 * Ergebnis von [TableImportSession.finishTable].
 *
 * - [Success]: Reseeding und post-import Cleanup erfolgreich.
 * - [PartialFailure]: Reseeding hat geklappt, Trigger-Reenable
 *   oder anderer writer-interner Cleanup nach erfolgreichem Write-Pfad
 *   ist gescheitert (z. B. Trigger-/FK-Reenable). [PartialFailure.cause]
 *   ist der originale Throwable.
 *
 * Wenn `reseedGenerators` selbst wirft, reicht `finishTable()` die
 * Exception direkt durch — es gibt dann keinen FinishTableResult.
 */
sealed class FinishTableResult {
    data class Success(
        val adjustments: List<SequenceAdjustment>,
    ) : FinishTableResult()

    data class PartialFailure(
        val adjustments: List<SequenceAdjustment>,
        val cause: Throwable,
    ) : FinishTableResult()
}
