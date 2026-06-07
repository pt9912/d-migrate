package dev.dmigrate.cli.commands

import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.SchemaOrigin
import dev.dmigrate.streaming.BundleResumeFingerprint
import dev.dmigrate.streaming.ImportInput
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.io.ByteArrayInputStream
import java.nio.file.Path

class ParquetImportInputPhase2HookTest : FunSpec({

    // Direct unit tests for the routing logic in ParquetImportInputPhase2Hook.
    // Review-Finding G4.

    val hook = ParquetImportInputPhase2Hook()

    test("Stdin/Directory/ResolvedBundle pass through unchanged") {
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

        hook.finalize(stdin, resumeExpectedSha256 = null) shouldBeSameInstanceAs stdin
        hook.finalize(dir, resumeExpectedSha256 = null) shouldBeSameInstanceAs dir
        hook.finalize(bundle, resumeExpectedSha256 = null) shouldBeSameInstanceAs bundle
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

        val result = hook.finalize(resolved, resumeExpectedSha256 = null)

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

        // Adapter rebuildet die Field-Kopie; Result ist strukturell gleich.
        val result = hook.finalize(resolved, resumeExpectedSha256 = sha)

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
            hook.finalize(resolved, resumeExpectedSha256 = "expected-hash")
        }
        ex.message!!.let { msg ->
            // Format-spezifischer Fehlercode aus AP11 §6.4.
            require("PARQUET_SINGLE_FILE_CONTENT_CHANGED_SINCE_CHECKPOINT" in msg)
        }
    }
})
