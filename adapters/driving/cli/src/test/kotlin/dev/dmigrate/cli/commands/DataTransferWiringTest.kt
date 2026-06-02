package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Unit tests for the [DataTransferWiring] pre-runner paths: filter
 * validation symmetric to [DataExportWiringTest].
 */
class DataTransferWiringTest : FunSpec({

    fun baseOptions(filter: String?): DataTransferOptions = DataTransferOptions(
        source = "jdbc:sqlite::memory:",
        target = "jdbc:sqlite::memory:",
        tables = null,
        filter = filter,
        sinceColumn = null,
        since = null,
        onConflict = "abort",
        triggerMode = "fire",
        truncate = false,
        chunkSize = 10_000,
        cliContext = CliContext(),
        configPath = null,
    )

    fun captureStderr(block: () -> Int): Pair<Int, String> {
        val capture = ByteArrayOutputStream()
        val originalErr = System.err
        System.setErr(PrintStream(capture, true, Charsets.UTF_8))
        return try {
            block() to capture.toString(Charsets.UTF_8)
        } finally {
            System.setErr(originalErr)
        }
    }

    test("blank --filter exits 2 with hint mentioning 'transfer'") {
        val (exit, err) = captureStderr {
            DataTransferWiring.execute(baseOptions(filter = "   "))
        }
        exit shouldBe 2
        err shouldContain "--filter must not be empty"
        err shouldContain "transfer without a filter"
    }

    test("unparseable --filter exits 2 with parser error message") {
        val (exit, err) = captureStderr {
            DataTransferWiring.execute(baseOptions(filter = "id ===== 1"))
        }
        exit shouldBe 2
        err shouldContain "Invalid --filter expression"
    }
})
