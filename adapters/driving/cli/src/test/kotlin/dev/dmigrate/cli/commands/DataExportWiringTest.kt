package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Unit tests for the [DataExportWiring] pre-runner paths: filter validation
 * and invalid-filter exit codes. Both branches return before any DB / JDBC
 * code is touched, so the Wiring can be exercised without spinning up the
 * full Clikt pipeline or a SQLite fixture.
 *
 * The Runner-construction + execute path is covered by the integration
 * test `CliDataExportFilterTest`.
 */
class DataExportWiringTest : FunSpec({

    fun baseOptions(filter: String?): DataExportOptions = DataExportOptions(
        source = "jdbc:sqlite::memory:",
        format = "json",
        output = null,
        tables = null,
        filter = filter,
        sinceColumn = null,
        since = null,
        encoding = "utf-8",
        chunkSize = 10_000,
        parallel = 1,
        splitFiles = false,
        csvDelimiter = ",",
        csvBom = false,
        csvNoHeader = false,
        nullString = "",
        resume = null,
        checkpointDir = null,
        manifestSha256 = false,
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

    test("blank --filter exits 2 with hint") {
        val (exit, err) = captureStderr {
            DataExportWiring.execute(baseOptions(filter = "   "))
        }
        exit shouldBe 2
        err shouldContain "--filter must not be empty"
    }

    test("unparseable --filter exits 2 with parser error message") {
        val (exit, err) = captureStderr {
            DataExportWiring.execute(baseOptions(filter = "id ===== 1"))
        }
        exit shouldBe 2
        err shouldContain "Invalid --filter expression"
    }
})
