package dev.dmigrate.cli.output

import dev.dmigrate.streaming.ProgressEvent
import dev.dmigrate.streaming.ProgressOperation
import dev.dmigrate.streaming.TableProgressStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class ProgressRendererTest : FunSpec({

    val renderer = ProgressRenderer(stderr = {})

    test("RunStarted for export") {
        val line = renderer.render(ProgressEvent.RunStarted(ProgressOperation.EXPORT, 3))
        line shouldBe "Exporting 3 table(s)"
    }

    test("RunStarted for import") {
        val line = renderer.render(ProgressEvent.RunStarted(ProgressOperation.IMPORT, 2))
        line shouldBe "Importing 2 table(s)"
    }

    test("ExportTableStarted with ordinal") {
        val line = renderer.render(ProgressEvent.ExportTableStarted("users", 1, 3))
        line shouldBe "Exporting table 'users' (1/3)"
    }

    test("ExportChunkProcessed shows cumulative rowsProcessed") {
        val line = renderer.render(ProgressEvent.ExportChunkProcessed(
            table = "users", tableOrdinal = 1, tableCount = 3,
            chunkIndex = 2, rowsInChunk = 10_000, rowsProcessed = 20_000,
            bytesWritten = 2_097_152,
        ))
        line shouldContain "chunk 2"
        line shouldContain "20,000 rows"
        line shouldContain "2.00 MB"
    }

    test("ExportTableFinished completed") {
        val line = renderer.render(ProgressEvent.ExportTableFinished(
            table = "users", tableOrdinal = 1, tableCount = 3,
            rowsProcessed = 12_345, chunksProcessed = 2, bytesWritten = 1_048_576,
            durationMs = 1200, status = TableProgressStatus.COMPLETED,
        ))
        line shouldContain "Exported"
        line shouldContain "12,345 rows"
        line shouldContain "2 chunks"
    }

    test("ExportTableFinished failed") {
        val line = renderer.render(ProgressEvent.ExportTableFinished(
            table = "users", tableOrdinal = 1, tableCount = 1,
            rowsProcessed = 0, chunksProcessed = 0, bytesWritten = 0,
            durationMs = 100, status = TableProgressStatus.FAILED,
        ))
        line shouldContain "FAILED"
    }

    test("ImportTableStarted") {
        val line = renderer.render(ProgressEvent.ImportTableStarted("orders", 2, 5))
        line shouldBe "Importing table 'orders' (2/5)"
    }

    test("ImportChunkProcessed with row outcomes") {
        val line = renderer.render(ProgressEvent.ImportChunkProcessed(
            table = "orders", tableOrdinal = 1, tableCount = 2,
            chunkIndex = 1, rowsInChunk = 10_000, rowsProcessed = 10_000,
            rowsInserted = 9_980, rowsUpdated = 0, rowsSkipped = 20,
            rowsUnknown = 0, rowsFailed = 0,
        ))
        line shouldContain "chunk 1"
        line shouldContain "10,000 rows processed"
        line shouldContain "9,980 inserted"
        line shouldContain "20 skipped"
        line shouldNotContain "updated"
        line shouldNotContain "failed"
    }

    test("ImportTableFinished completed") {
        val line = renderer.render(ProgressEvent.ImportTableFinished(
            table = "orders", tableOrdinal = 1, tableCount = 2,
            rowsInserted = 12_000, rowsUpdated = 0, rowsSkipped = 20,
            rowsUnknown = 0, rowsFailed = 0, durationMs = 2000, status = TableProgressStatus.COMPLETED,
        ))
        line shouldContain "Imported"
        line shouldContain "12,000 inserted"
        line shouldContain "20 skipped"
    }

    test("ImportTableFinished failed") {
        val line = renderer.render(ProgressEvent.ImportTableFinished(
            table = "orders", tableOrdinal = 1, tableCount = 1,
            rowsInserted = 5_000, rowsUpdated = 0, rowsSkipped = 0,
            rowsUnknown = 0, rowsFailed = 100, durationMs = 500, status = TableProgressStatus.FAILED,
        ))
        line shouldContain "FAILED"
        line shouldContain "100 failed"
    }

    test("number formatting uses US locale with thousands separator") {
        ProgressRenderer.formatNumber(1_234_567) shouldBe "1,234,567"
    }

    test("MB formatting uses US locale") {
        ProgressRenderer.formatMb(1_048_576) shouldBe "1.00 MB"
        ProgressRenderer.formatMb(0) shouldBe "0.00 MB"
    }

    test("renderer writes to stderr sink") {
        val captured = mutableListOf<String>()
        val r = ProgressRenderer(stderr = { captured += it })
        r.report(ProgressEvent.RunStarted(ProgressOperation.EXPORT, 1))
        captured shouldBe listOf("Exporting 1 table(s)")
    }

    // ─── German locale ──────────────────────────────────────

    test("German RunStarted for export") {
        val de = ProgressRenderer(messages = MessageResolver(java.util.Locale.GERMAN), stderr = {})
        de.render(ProgressEvent.RunStarted(ProgressOperation.EXPORT, 3)) shouldContain "Tabelle(n) werden exportiert"
    }

    test("German ImportTableFinished completed") {
        val de = ProgressRenderer(messages = MessageResolver(java.util.Locale.GERMAN), stderr = {})
        val line = de.render(ProgressEvent.ImportTableFinished(
            table = "orders", tableOrdinal = 1, tableCount = 1,
            rowsInserted = 100, rowsUpdated = 0, rowsSkipped = 0,
            rowsUnknown = 0, rowsFailed = 0, durationMs = 500, status = TableProgressStatus.COMPLETED,
        ))
        line shouldContain "Importiert"
        line shouldContain "eingefügt"
    }

    test("German ExportTableStarted") {
        val de = ProgressRenderer(messages = MessageResolver(java.util.Locale.GERMAN), stderr = {})
        val line = de.render(ProgressEvent.ExportTableStarted("users", 1, 3))
        line shouldContain "Exportiere Tabelle"
    }
})
