package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.core.cancel.OperationCancelledException
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.streaming.ImportInput
import dev.dmigrate.streaming.ImportResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.sql.Connection

/**
 * Phase E0.5: Cancel inside the import runner must surface as exit 130 —
 * never as the generic 5 (import failure) path that
 * [ImportStreamingInvoker]'s `catch (e: Throwable)` block would otherwise
 * produce. Plan §4.5 requires this filter to be deterministic.
 */
class DataImportRunnerCancelCheckpointTest : FunSpec({

    val pool = object : ConnectionPool {
        override val dialect = DatabaseDialect.SQLITE
        override fun borrow(): Connection = error("unused")
        override fun activeConnections() = 0
        override fun close() = Unit
    }

    val cfg = ConnectionConfig(DatabaseDialect.SQLITE, "h", null, "d", null, null)

    val writer = object : DataWriter {
        override val dialect = DatabaseDialect.SQLITE
        override fun schemaSync() = throw UnsupportedOperationException()
        override fun openTable(pool: ConnectionPool, table: String, options: ImportOptions) =
            error("not invoked")
    }

    fun buildRunner(executor: ImportExecutor) = DataImportRunner(
        targetResolver = { t, _ -> t ?: error("--target") },
        urlParser = { cfg },
        poolFactory = { pool },
        writerLookup = { writer },
        importExecutor = executor,
        stderr = { },
    )

    fun request(): DataImportRequest {
        val tmp = Files.createTempFile("e0-5-import", ".jsonl").also { Files.writeString(it, "") }
        return DataImportRequest(
            target = "sqlite:///x.db",
            source = tmp.toString(),
            format = "json",
            schema = null,
            table = "users",
            tables = null,
            onError = "abort",
            onConflict = null,
            triggerMode = "fire",
            truncate = false,
            disableFkChecks = false,
            reseedSequences = false,
            encoding = null,
            csvNoHeader = false,
            csvNullString = "",
            chunkSize = 1_000,
            cliConfigPath = null,
            quiet = true,
            noProgress = true,
        )
    }

    test("OperationCancelledException thrown by executor maps to exit 130") {
        val executor = ImportExecutor { _, _, _, _ ->
            throw OperationCancelledException("worker observed cancel")
        }
        val runner = buildRunner(executor)
        val token = CancellationTokenSource.create().token

        runner.execute(request(), token) shouldBe DataImportRunner.CANCELLED_EXIT_CODE
    }

    test("Cancel before pool open returns 130 without invoking the executor") {
        val invoked = java.util.concurrent.atomic.AtomicBoolean(false)
        val executor = ImportExecutor { _, _, _, _ ->
            invoked.set(true)
            ImportResult(
                tables = emptyList(),
                totalRowsInserted = 0, totalRowsUpdated = 0, totalRowsSkipped = 0,
                totalRowsUnknown = 0, totalRowsFailed = 0, durationMs = 0, operationId = null,
            )
        }
        val runner = buildRunner(executor)
        val source = CancellationTokenSource.create().also { it.cancel("before-pool") }

        runner.execute(request(), source.token) shouldBe DataImportRunner.CANCELLED_EXIT_CODE
        invoked.get() shouldBe false
    }

    test("Default token leaves executor running and returns its exit code (0)") {
        val executor = ImportExecutor { _, _, _, _ ->
            ImportResult(
                tables = emptyList(),
                totalRowsInserted = 0, totalRowsUpdated = 0, totalRowsSkipped = 0,
                totalRowsUnknown = 0, totalRowsFailed = 0, durationMs = 0, operationId = "ok",
            )
        }
        val runner = buildRunner(executor)

        runner.execute(request()) shouldBe 0
    }

    test("Importer-side ImportSchemaMismatchException still maps to 3, not 130") {
        val executor = ImportExecutor { _, _, _, _ ->
            throw dev.dmigrate.core.data.ImportSchemaMismatchException("header drift")
        }
        val runner = buildRunner(executor)

        // No cancel — generic schema-mismatch must keep its established 3.
        runner.execute(request()) shouldBe 3
    }
})
