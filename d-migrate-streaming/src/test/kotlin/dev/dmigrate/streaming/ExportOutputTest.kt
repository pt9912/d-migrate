package dev.dmigrate.streaming

import dev.dmigrate.format.data.DataExportFormat
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

/**
 * Tests für [ExportOutput.resolve] — die CLI-Auflösungsmatrix aus Plan §6.9.
 */
class ExportOutputTest : FunSpec({

    // ─── Stdout ──────────────────────────────────────────────────

    test("no output, no split-files, 1 table → Stdout") {
        ExportOutput.resolve(outputPath = null, splitFiles = false, tableCount = 1) shouldBe ExportOutput.Stdout
    }

    test("no output, no split-files, multiple tables → Exit 2 (IllegalArgumentException)") {
        val ex = shouldThrow<IllegalArgumentException> {
            ExportOutput.resolve(outputPath = null, splitFiles = false, tableCount = 3)
        }
        ex.message!! shouldContain "stdout"
        ex.message!! shouldContain "--split-files"
    }

    test("no output, split-files → Exit 2 (split-files needs --output)") {
        shouldThrow<IllegalArgumentException> {
            ExportOutput.resolve(outputPath = null, splitFiles = true, tableCount = 1)
        }
    }

    // ─── SingleFile ──────────────────────────────────────────────

    test("output file, no split-files, 1 table → SingleFile") {
        val p = Path.of("/tmp/foo.json")
        ExportOutput.resolve(outputPath = p, splitFiles = false, tableCount = 1) shouldBe ExportOutput.SingleFile(p)
    }

    test("output file, no split-files, multiple tables → Exit 2") {
        shouldThrow<IllegalArgumentException> {
            ExportOutput.resolve(outputPath = Path.of("/tmp/foo.json"), splitFiles = false, tableCount = 5)
        }
    }

    // ─── FilePerTable ────────────────────────────────────────────

    test("output dir, split-files, multiple tables → FilePerTable") {
        val tmpDir = Files.createTempDirectory("export-output-test-")
        try {
            val resolved = ExportOutput.resolve(outputPath = tmpDir, splitFiles = true, tableCount = 3)
            resolved shouldBe ExportOutput.FilePerTable(tmpDir)
        } finally {
            tmpDir.deleteIfExists()
        }
    }

    test("output dir, split-files, 1 table → FilePerTable (still valid)") {
        val tmpDir = Files.createTempDirectory("export-output-test-")
        try {
            ExportOutput.resolve(outputPath = tmpDir, splitFiles = true, tableCount = 1) shouldBe ExportOutput.FilePerTable(tmpDir)
        } finally {
            tmpDir.deleteIfExists()
        }
    }

    test("output dir, NO split-files → Exit 2") {
        val tmpDir = Files.createTempDirectory("export-output-test-")
        try {
            shouldThrow<IllegalArgumentException> {
                ExportOutput.resolve(outputPath = tmpDir, splitFiles = false, tableCount = 2)
            }
        } finally {
            tmpDir.deleteIfExists()
        }
    }

    test("split-files: existing non-directory output throws") {
        val tmpFile = Files.createTempFile("export-output-test-", ".txt")
        try {
            shouldThrow<IllegalArgumentException> {
                ExportOutput.resolve(outputPath = tmpFile, splitFiles = true, tableCount = 1)
            }
        } finally {
            tmpFile.deleteIfExists()
        }
    }

    // ─── tableCount validation ───────────────────────────────────

    test("tableCount must be > 0") {
        shouldThrow<IllegalArgumentException> {
            ExportOutput.resolve(outputPath = null, splitFiles = false, tableCount = 0)
        }
    }

    // ─── fileNameFor ─────────────────────────────────────────────

    test("fileNameFor unqualified table name") {
        ExportOutput.fileNameFor("users", DataExportFormat.JSON) shouldBe "users.json"
    }

    test("fileNameFor schema-qualified table preserves the schema (Plan §6.9)") {
        ExportOutput.fileNameFor("public.orders", DataExportFormat.CSV) shouldBe "public.orders.csv"
        ExportOutput.fileNameFor("reporting.orders", DataExportFormat.YAML) shouldBe "reporting.orders.yaml"
    }
})
