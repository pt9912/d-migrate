package dev.dmigrate.streaming

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * LN-005 (R4): `ImportLoopState.recordChunkFailure` deckelt das Chunk-Fehler-Detail-Sample
 * (`--on-error log`) auf `MAX_LOGGED_CHUNK_FAILURES`, statt die Liste unbounded wachsen zu
 * lassen. Überzählige Einträge werden verworfen (die Liste wird nicht gerendert; die wahre
 * Fehlerzahl trägt `rowsFailed`).
 */
class ImportLoopStateChunkFailureCapTest : FunSpec({

    test("recordChunkFailure caps the detail sample and keeps the earliest entries") {
        val state = ImportLoopState()
        val total = ImportLoopState.MAX_LOGGED_CHUNK_FAILURES + 250
        repeat(total) { i -> state.recordChunkFailure(ChunkFailure("t", i.toLong(), 1, "boom")) }

        state.chunkFailures.size shouldBe ImportLoopState.MAX_LOGGED_CHUNK_FAILURES
        // The bounded sample keeps the earliest failures (first-N), not the last.
        state.chunkFailures.first().chunkIndex shouldBe 0L
        state.chunkFailures.last().chunkIndex shouldBe (ImportLoopState.MAX_LOGGED_CHUNK_FAILURES - 1).toLong()
    }

    test("below the cap keeps every entry") {
        val state = ImportLoopState()
        repeat(5) { i -> state.recordChunkFailure(ChunkFailure("t", i.toLong(), 1, "x")) }
        state.chunkFailures.size shouldBe 5
    }
})
