package dev.dmigrate.cli.commands

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

/**
 * Preflight flag-validation cases for `data import`
 * ([DataImportHelpers.validateCliFlags] and its extracted
 * [DataImportHelpers.validateAtomicFlags] / [DataImportHelpers.validateParallelFlags]).
 * Split out of `DataImportHelpersTest` to keep both classes under the
 * detekt `LargeClass` bound (no `@Suppress`).
 */
class DataImportFlagValidationTest : FunSpec({

    fun request(
        target: String? = "sqlite:///tmp/test.db",
        source: String = "/tmp/users.json",
        format: String? = null,
        table: String? = "users",
        tables: List<String>? = null,
        schema: Path? = null,
        triggerMode: String = "fire",
        disableFkChecks: Boolean = false,
        encoding: String? = null,
    ) = DataImportRequest(
        target = target,
        source = source,
        format = format,
        schema = schema,
        table = table,
        tables = tables,
        onError = "abort",
        onConflict = null,
        triggerMode = triggerMode,
        truncate = false,
        disableFkChecks = disableFkChecks,
        reseedSequences = true,
        encoding = encoding,
        csvNoHeader = false,
        csvNullString = "",
        chunkSize = 10_000,
        cliConfigPath = null,
        quiet = false,
        noProgress = false,
    )

    test("validateCliFlags rejects conflicting table selectors") {
        val stderr = mutableListOf<String>()

        val exit = DataImportHelpers.validateCliFlags(
            request(table = "users", tables = listOf("orders")),
            stderr::add,
        )

        exit shouldBe 2
        stderr.single() shouldContain "mutually exclusive"
    }

    test("validateCliFlags rejects --table combined with --table-order") {
        val stderr = mutableListOf<String>()
        val exit = DataImportHelpers.validateCliFlags(
            request(table = "users").copy(tableOrder = listOf("users", "orders")),
            stderr::add,
        )
        exit shouldBe 2
        stderr.single() shouldContain "--table and --table-order are mutually exclusive"
    }

    test("validateCliFlags rejects --table-order on stdin import") {
        val stderr = mutableListOf<String>()
        val exit = DataImportHelpers.validateCliFlags(
            request(source = "-", table = null).copy(tableOrder = listOf("users", "orders")),
            stderr::add,
        )
        exit shouldBe 2
        stderr.single() shouldContain "--table-order is not supported for stdin"
    }

    test("validateCliFlags accepts --table-order with valid identifiers on directory source") {
        val stderr = mutableListOf<String>()
        val exit = DataImportHelpers.validateCliFlags(
            request(source = "/tmp/bundle", table = null).copy(tableOrder = listOf("public.users", "orders")),
            stderr::add,
        )
        exit shouldBe null
        stderr.shouldBeEmpty()
    }

    test("validateCliFlags rejects --no-checkpoint combined with --resume") {
        val stderr = mutableListOf<String>()

        val exit = DataImportHelpers.validateCliFlags(
            request().copy(resume = "run-123", noCheckpoint = true),
            stderr::add,
        )

        exit shouldBe 2
        stderr.single() shouldContain "--no-checkpoint and --resume are mutually exclusive"
    }

    test("validateCliFlags rejects --atomic without --truncate (LN-013)") {
        val stderr = mutableListOf<String>()

        val exit = DataImportHelpers.validateCliFlags(
            request().copy(atomic = true, truncate = false),
            stderr::add,
        )

        exit shouldBe 2
        stderr.single() shouldContain "--atomic requires --truncate"
    }

    test("validateCliFlags rejects --atomic combined with --resume (LN-013)") {
        val stderr = mutableListOf<String>()

        val exit = DataImportHelpers.validateCliFlags(
            request().copy(atomic = true, truncate = true, resume = "run-123"),
            stderr::add,
        )

        exit shouldBe 2
        stderr.single() shouldContain "--atomic and --resume are mutually exclusive"
    }

    test("validateCliFlags accepts --atomic with --truncate (LN-013)") {
        val stderr = mutableListOf<String>()

        val exit = DataImportHelpers.validateCliFlags(
            request().copy(atomic = true, truncate = true),
            stderr::add,
        )

        exit shouldBe null
        stderr.shouldBeEmpty()
    }

    test("validateCliFlags rejects --parallel < 1 (LN-007/LN-008)") {
        val stderr = mutableListOf<String>()

        val exit = DataImportHelpers.validateCliFlags(
            request().copy(parallel = 0),
            stderr::add,
        )

        exit shouldBe 2
        stderr.single() shouldContain "--parallel must be >= 1"
    }

    test("validateCliFlags rejects --parallel > 1 combined with --resume (LN-007/LN-008)") {
        val stderr = mutableListOf<String>()

        val exit = DataImportHelpers.validateCliFlags(
            request().copy(parallel = 4, resume = "run-123", parallelFromCli = true),
            stderr::add,
        )

        exit shouldBe 2
        stderr.single() shouldContain "--parallel > 1 is incompatible with --resume"
    }

    test("validateCliFlags accepts --parallel > 1 without --resume (LN-007/LN-008)") {
        val stderr = mutableListOf<String>()

        val exit = DataImportHelpers.validateCliFlags(
            request().copy(parallel = 4),
            stderr::add,
        )

        exit shouldBe null
        stderr.shouldBeEmpty()
    }

    test("validateCliFlags rejects --parallel > 1 combined with --atomic (LN-007/LN-008)") {
        val stderr = mutableListOf<String>()

        val exit = DataImportHelpers.validateCliFlags(
            request().copy(parallel = 4, atomic = true, truncate = true, parallelFromCli = true),
            stderr::add,
        )

        exit shouldBe 2
        stderr.single() shouldContain "--parallel > 1 is incompatible with --atomic"
    }

    test("validateCliFlags does NOT hard-fail CONFIG-sourced parallel + --resume (falls back later)") {
        val stderr = mutableListOf<String>()
        val exit = DataImportHelpers.validateCliFlags(
            request().copy(parallel = 4, resume = "run-123", parallelFromCli = false),
            stderr::add,
        )
        exit shouldBe null // no hard error; the fallback to 1 happens at the preflight clamp
        stderr.shouldBeEmpty()
    }

    test("validateCliFlags does NOT hard-fail CONFIG-sourced parallel + --atomic (falls back later)") {
        val stderr = mutableListOf<String>()
        val exit = DataImportHelpers.validateCliFlags(
            request().copy(parallel = 4, atomic = true, truncate = true, parallelFromCli = false),
            stderr::add,
        )
        exit shouldBe null
        stderr.shouldBeEmpty()
    }

    test("validateCliFlags accepts --no-checkpoint alone") {
        val stderr = mutableListOf<String>()

        val exit = DataImportHelpers.validateCliFlags(
            request().copy(noCheckpoint = true),
            stderr::add,
        )

        exit shouldBe null
        stderr.shouldBeEmpty()
    }

    test("validateCliFlags rejects --no-checkpoint combined with --checkpoint-dir") {
        val stderr = mutableListOf<String>()

        val exit = DataImportHelpers.validateCliFlags(
            request().copy(noCheckpoint = true, checkpointDir = Path.of("/tmp/dummy")),
            stderr::add,
        )

        exit shouldBe 2
        stderr.single() shouldContain "--no-checkpoint and --checkpoint-dir are mutually exclusive"
    }
})
