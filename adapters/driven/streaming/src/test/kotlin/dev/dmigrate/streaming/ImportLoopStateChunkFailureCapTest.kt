package dev.dmigrate.streaming

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * LN-005 (R4): `ImportLoopState.recordChunkFailure` deckelt das Chunk-Fehler-Detail-Sample
 * (`--on-error log`) auf `MAX_LOGGED_CHUNK_FAILURES` und zählt darüber hinausgehende Fehler,
 * statt die Liste unbounded wachsen zu lassen. Die wahre Fehlerzahl trägt `rowsFailed`.
 */
class ImportLoopStateChunkFailureCapTest : FunSpec({

    test("recordChunkFailure caps the detail sample and counts the overflow") {
        val state = ImportLoopState()
        val overflow = 250
        val total = ImportLoopState.MAX_LOGGED_CHUNK_FAILURES + overflow
        repeat(total) { i -> state.recordChunkFailure(ChunkFailure("t", i.toLong(), 1, "boom")) }

        state.chunkFailures.size shouldBe ImportLoopState.MAX_LOGGED_CHUNK_FAILURES
        state.chunkFailuresSuppressed shouldBe overflow.toLong()
        // The bounded sample keeps the earliest failures (first-N), not the last.
        state.chunkFailures.first().chunkIndex shouldBe 0L
        state.chunkFailures.last().chunkIndex shouldBe (ImportLoopState.MAX_LOGGED_CHUNK_FAILURES - 1).toLong()
    }

    test("below the cap nothing is suppressed") {
        val state = ImportLoopState()
        repeat(5) { i -> state.recordChunkFailure(ChunkFailure("t", i.toLong(), 1, "x")) }
        state.chunkFailures.size shouldBe 5
        state.chunkFailuresSuppressed shouldBe 0L
    }
})
