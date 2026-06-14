package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.SchemaOrigin
import dev.dmigrate.format.parquet.ParquetChunkWriter
import dev.dmigrate.format.parquet.manifest.ParquetBundleClosure
import dev.dmigrate.format.parquet.manifest.ParquetSingleFileManifestWriter
import dev.dmigrate.streaming.BundleClosureContext
import dev.dmigrate.streaming.BundleClosureTable
import dev.dmigrate.streaming.BundleResumeFingerprint
import dev.dmigrate.streaming.ImportInput
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ParquetImportInputResolutionHookTest : FunSpec({

    // Konsolidierte Tests fuer den vereinheitlichten
    // ParquetImportInputResolutionHook (Review-Finding F3). Ersetzt die
    // frueheren ParquetImportInputPhase{1,2}HookTest-Dateien.

    val hook = ParquetImportInputResolutionHook()

    fun writeMinimalBundle(dir: Path) {
        val usersSchema = ChunkSchema(
            table = "users",
            origin = SchemaOrigin.JDBC_METADATA,
            columns = listOf(ChunkColumnSchema("id", false, NeutralType.BigInteger)),
        )
        Files.newOutputStream(dir.resolve("users.parquet")).use { out ->
            ParquetChunkWriter(out).use { writer ->
                writer.begin("users", usersSchema)
                writer.write(
                    DataChunk(
                        table = "users", columns = emptyList(),
                        rows = listOf(arrayOf<Any?>(1L)), chunkIndex = 0L,
                    )
                )
                writer.end()
            }
        }
        val fixedClock = Clock.fixed(Instant.parse("2026-06-06T11:00:00Z"), ZoneOffset.UTC)
        ParquetBundleClosure(producerVersion = "0.9.8", manifestSha256 = false, clock = fixedClock)(
            BundleClosureContext(
                directory = dir,
                format = DataExportFormat.PARQUET,
                tables = listOf(
                    BundleClosureTable("users", dir.resolve("users.parquet"), usersSchema, rowCount = 1),
                ),
            )
        )
    }

    // ── resolveBeforeSchema (frueher Phase-1) ─────────────────────

    test("non-Parquet format short-circuits and returns the raw input untouched") {
        val stdin = ImportInput.Stdin(table = "users", input = ByteArrayInputStream("[]".toByteArray()))

        val result = hook.resolveBeforeSchema(stdin, DataExportFormat.JSON, computeContentSha256 = false)

        result shouldBeSameInstanceAs stdin
    }

    test("non-Parquet format leaves Directory untouched") {
        val dir = ImportInput.Directory(path = Path.of("/tmp/non-existent"))

        val result = hook.resolveBeforeSchema(dir, DataExportFormat.CSV, computeContentSha256 = true)

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

        val result = hook.resolveBeforeSchema(bundle, DataExportFormat.PARQUET, computeContentSha256 = false)

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

        val result = hook.resolveBeforeSchema(resolved, DataExportFormat.PARQUET, computeContentSha256 = false)

        result shouldBeSameInstanceAs resolved
    }

    test("Parquet Directory mit fehlendem manifest.yaml → PreflightExitException(4) (S9a-0.b MANIFEST_* → Exit 4)") {
        val dir = Files.createTempDirectory("parquet-hook-no-manifest-")
        try {
            // Leeres Verzeichnis → ParquetBundlePreflight wirft
            // MANIFEST_NOT_FOUND; der Hook uebersetzt es in das exit-code-
            // tragende PreflightExitException(4) (AP12 §9, Modulgrenze).
            val ex = shouldThrow<PreflightExitException> {
                hook.resolveBeforeSchema(
                    ImportInput.Directory(path = dir),
                    DataExportFormat.PARQUET,
                    computeContentSha256 = false,
                )
            }
            ex.exitCode shouldBe 4
            ex.message!! shouldContain "MANIFEST_NOT_FOUND"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("Parquet Directory mit unbekanntem tableFilter → PreflightExitException(5) (S9a-0.c BUNDLE_* → Exit 5)") {
        val dir = Files.createTempDirectory("parquet-hook-filter-unknown-")
        try {
            // Gueltiges Bundle, aber tableFilter referenziert eine nicht
            // existierende Tabelle → ParquetBundleIterationException; der Hook
            // uebersetzt die Iteration-Familie in PreflightExitException(5).
            writeMinimalBundle(dir)
            val ex = shouldThrow<PreflightExitException> {
                hook.resolveBeforeSchema(
                    ImportInput.Directory(path = dir, tableFilter = listOf("ghost")),
                    DataExportFormat.PARQUET,
                    computeContentSha256 = false,
                )
            }
            ex.exitCode shouldBe 5
            ex.message!! shouldContain "BUNDLE_FILTER_UNKNOWN_TABLE"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ── S9b-0: Single-File-Format-Codes (TABLE_*) → Exit 4 ──

    fun writeSingleFile(path: Path, footerTable: String?) {
        val schema = ChunkSchema(
            table = footerTable ?: "ignored",
            origin = SchemaOrigin.JDBC_METADATA,
            columns = listOf(ChunkColumnSchema("id", false, NeutralType.BigInteger)),
        )
        val out = Files.newOutputStream(path)
        val writer = if (footerTable != null) {
            val provider = ParquetSingleFileManifestWriter(
                producerVersion = "0.9.8",
                clock = Clock.fixed(Instant.parse("2026-06-06T11:00:00Z"), ZoneOffset.UTC),
            ).provider
            ParquetChunkWriter(out, extraMetaDataProvider = provider)
        } else {
            ParquetChunkWriter(out)
        }
        writer.use { w ->
            w.begin(schema.table, schema)
            w.write(DataChunk(table = schema.table, columns = emptyList(), rows = listOf(arrayOf<Any?>(1L)), chunkIndex = 0L))
            w.end()
        }
    }

    test("Parquet SingleFile ohne Footer-Tabelle und ohne --table → PreflightExitException(4) (S9b-0 TABLE_REQUIRED)") {
        val file = Files.createTempFile("parquet-hook-table-required-", ".parquet")
        Files.deleteIfExists(file)
        try {
            writeSingleFile(file, footerTable = null)
            val ex = shouldThrow<PreflightExitException> {
                hook.resolveBeforeSchema(
                    ImportInput.SingleFile(UNRESOLVED_PARQUET_TABLE_SENTINEL, file),
                    DataExportFormat.PARQUET,
                    computeContentSha256 = false,
                )
            }
            ex.exitCode shouldBe 4
            ex.message!! shouldContain "PARQUET_SINGLE_FILE_TABLE_REQUIRED"
        } finally {
            Files.deleteIfExists(file)
        }
    }

    test("Parquet SingleFile: --table widerspricht Footer-Tabelle → PreflightExitException(4) (S9b-0 TABLE_MISMATCH)") {
        val file = Files.createTempFile("parquet-hook-table-mismatch-", ".parquet")
        Files.deleteIfExists(file)
        try {
            writeSingleFile(file, footerTable = "users")
            val ex = shouldThrow<PreflightExitException> {
                hook.resolveBeforeSchema(
                    ImportInput.SingleFile("orders", file),
                    DataExportFormat.PARQUET,
                    computeContentSha256 = false,
                )
            }
            ex.exitCode shouldBe 4
            ex.message!! shouldContain "PARQUET_SINGLE_FILE_TABLE_MISMATCH"
        } finally {
            Files.deleteIfExists(file)
        }
    }

    test("Parquet + Stdin throws explicitly (Review-Finding I1 defense-in-depth)") {
        val stdin = ImportInput.Stdin(table = "users", input = ByteArrayInputStream("".toByteArray()))

        val ex = shouldThrow<IllegalStateException> {
            hook.resolveBeforeSchema(stdin, DataExportFormat.PARQUET, computeContentSha256 = false)
        }
        ex.message!! shouldContain "PARQUET_STDIN_NOT_SUPPORTED"
    }

    // ── finalizeBeforePrepare (frueher Phase-2) ───────────────────

    test("Stdin/Directory/ResolvedBundle pass through unchanged at finalize") {
        val stdin = ImportInput.Stdin(table = "users", input = ByteArrayInputStream("".toByteArray()))
        val dir = ImportInput.Directory(path = Path.of("/tmp/somewhere"))
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

        hook.finalizeBeforePrepare(stdin, resumeExpectedSha256 = null) shouldBeSameInstanceAs stdin
        hook.finalizeBeforePrepare(dir, resumeExpectedSha256 = null) shouldBeSameInstanceAs dir
        hook.finalizeBeforePrepare(bundle, resumeExpectedSha256 = null) shouldBeSameInstanceAs bundle
    }

    test("ResolvedSingleFile with null sha is identity-pass-through (kein Round-Trip-Allokation)") {
        val resolved = ImportInput.ResolvedSingleFile(
            table = "users",
            path = Path.of("/tmp/u.parquet"),
            schema = ChunkSchema(
                table = "users",
                origin = SchemaOrigin.MANIFEST_FALLBACK,
                columns = emptyList(),
            ),
            contentSha256 = "abcd",
            manifestPresent = true,
        )

        val result = hook.finalizeBeforePrepare(resolved, resumeExpectedSha256 = null)

        result shouldBeSameInstanceAs resolved
    }

    test("ResolvedSingleFile mit passender Sha256 ist Pass-Through-Ergebnis") {
        val sha = "deadbeefcafebabe"
        val resolved = ImportInput.ResolvedSingleFile(
            table = "users",
            path = Path.of("/tmp/u.parquet"),
            schema = ChunkSchema(
                table = "users",
                origin = SchemaOrigin.MANIFEST_FALLBACK,
                columns = emptyList(),
            ),
            contentSha256 = sha,
            manifestPresent = true,
        )

        val result = hook.finalizeBeforePrepare(resolved, resumeExpectedSha256 = sha)

        result shouldBe resolved
    }

    test("ResolvedSingleFile mit Sha256-Mismatch wirft ParquetSingleFileResumeException") {
        val resolved = ImportInput.ResolvedSingleFile(
            table = "users",
            path = Path.of("/tmp/u.parquet"),
            schema = ChunkSchema(
                table = "users",
                origin = SchemaOrigin.MANIFEST_FALLBACK,
                columns = emptyList(),
            ),
            contentSha256 = "actual-hash",
            manifestPresent = true,
        )

        val ex = shouldThrow<dev.dmigrate.format.parquet.ParquetSingleFileResumeException> {
            hook.finalizeBeforePrepare(resolved, resumeExpectedSha256 = "expected-hash")
        }
        ex.message!!.let { msg ->
            require("PARQUET_SINGLE_FILE_CONTENT_CHANGED_SINCE_CHECKPOINT" in msg)
        }
    }
})
