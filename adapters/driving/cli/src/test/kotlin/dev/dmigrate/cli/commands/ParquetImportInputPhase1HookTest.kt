package dev.dmigrate.cli.commands

import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.SchemaOrigin
import dev.dmigrate.streaming.BundleResumeFingerprint
import dev.dmigrate.streaming.ImportInput
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.io.ByteArrayInputStream
import java.nio.file.Path

class ParquetImportInputPhase1HookTest : FunSpec({

    // Direct unit tests for the routing/early-out logic in
    // ParquetImportInputPhase1Hook. Real parquet-footer integrations are
    // covered separately in :adapters:driven:formats-parquet round-trip tests.
    // Review-Finding G4: closes the gap that the hook itself had no
    // module-level tests of its branching.

    val hook = ParquetImportInputPhase1Hook()

    test("non-Parquet format short-circuits and returns the raw input untouched") {
        val stdin = ImportInput.Stdin(table = "users", input = ByteArrayInputStream("[]".toByteArray()))

        val result = hook.maybeFinalize(stdin, DataExportFormat.JSON, computeContentSha256 = false)

        result shouldBeSameInstanceAs stdin
    }

    test("non-Parquet format leaves Directory untouched (FK ordering wieder via SchemaPreflight)") {
        val dir = ImportInput.Directory(path = Path.of("/tmp/non-existent"))

        val result = hook.maybeFinalize(dir, DataExportFormat.CSV, computeContentSha256 = true)

        result shouldBeSameInstanceAs dir
    }

    test("Parquet + already-resolved ResolvedBundle passes through (Idempotenz)") {
        val bundle = ImportInput.ResolvedBundle(
            bundleRoot = Path.of("/tmp/bundle"),
            tables = emptyList(),
            resumeFingerprint = BundleResumeFingerprint(
                manifestSha256 = "deadbeef",
                formatVersion = "1.0",
                producerVersion = "test",
                tableOrder = emptyList(),
            ),
        )

        val result = hook.maybeFinalize(bundle, DataExportFormat.PARQUET, computeContentSha256 = false)

        result shouldBeSameInstanceAs bundle
    }

    test("Parquet + already-resolved ResolvedSingleFile passes through (Idempotenz)") {
        val resolved = ImportInput.ResolvedSingleFile(
            table = "users",
            path = Path.of("/tmp/u.parquet"),
            schema = ChunkSchema(
                table = "users",
                origin = SchemaOrigin.MANIFEST_FALLBACK,
                columns = emptyList(),
            ),
            contentSha256 = null,
            manifestPresent = true,
        )

        val result = hook.maybeFinalize(resolved, DataExportFormat.PARQUET, computeContentSha256 = false)

        result shouldBeSameInstanceAs resolved
    }

    test("Parquet + Stdin passes through unchanged (validation upstream rejects)") {
        // validateFormatPathRequirements lehnt Parquet+Stdin upstream mit
        // Exit 2 ab; falls ein zukuenftiger Pfad das umgeht, soll der Hook
        // den Input nicht stillschweigend verschlucken, sondern unveraendert
        // weiterreichen — der bestehende Stream-Pfad im StreamingImporter
        // produziert dann eine klare Fehlermeldung.
        val stdin = ImportInput.Stdin(table = "users", input = ByteArrayInputStream("".toByteArray()))

        val result = hook.maybeFinalize(stdin, DataExportFormat.PARQUET, computeContentSha256 = false)

        result shouldBe stdin
    }
})
