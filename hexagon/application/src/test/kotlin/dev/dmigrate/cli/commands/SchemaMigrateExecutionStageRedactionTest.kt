package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * E.1 Slice F.1: pins that
 * [SchemaMigrateExecutionStage.maybeExecute] runs JDBC-driver
 * exception messages through [dev.dmigrate.core.diff.routine.RoutineBodyLogRedactor]
 * before storing them on `ExecutionTrace.executionError`.
 *
 * Driver-quoted body fragments in error messages would otherwise leak
 * into the report and stderr; with redaction, only the scrubbed
 * representation survives unless `--debug-body` (`RoutineBodyDisplay.RAW_DEBUG`)
 * is set.
 */
class SchemaMigrateExecutionStageRedactionTest : FunSpec({

    fun stage(executor: SegmentAwareExecutorFn?) = SchemaMigrateExecutionStage(
        executor = executor,
        dbLoader = null,
        normalizer = { it },
        fingerprint = { s, _ -> s.name + ":" + s.version },
        printError = { _, _ -> },
    )

    fun rendered() = MigrationDdlResult(
        statements = listOf(
            MigrationDdlStatement(
                sql = "CREATE FUNCTION f() RETURNS int AS $\$BEGIN RETURN 1; END$\$ LANGUAGE plpgsql;",
                operationIds = setOf("op-1"),
                risk = OperationRisk.SAFE,
                phase = DiffPhase.TABLES,
            ),
        ),
        operationsRendered = setOf("op-1"),
    )

    fun request(debugBody: Boolean) = SchemaMigrateRequest(
        source = "file:src",
        target = "db:test",
        dialect = DatabaseDialect.POSTGRESQL,
        execute = true,
        debugBody = debugBody,
    )

    val target = CompareOperand.Database("db:test")

    test("executor exception is redacted by default (no --debug-body)") {
        // Driver-style message quoting a routine body fragment that
        // includes a credential-shaped literal. RoutineBodyLogRedactor
        // delegates to RoutineBodyScrubber which masks password=' ... '
        // patterns.
        val executor: SegmentAwareExecutorFn = { _, _, _, _, _ ->
            throw RuntimeException(
                "ERROR: syntax error at \"BEGIN\" in CREATE FUNCTION login() " +
                    "AS \$\$ password = 'hunter2-very-secret' \$\$",
            )
        }
        val trace = stage(executor).maybeExecute(
            request = request(debugBody = false),
            target = target,
            combined = rendered(),
            atomicBatch = null,
            cancellationToken = CancellationTokenSource.create().token,
        )
        trace shouldNotBe null
        val msg = trace!!.executionError
        msg shouldNotBe null
        // Secret literal is scrubbed out
        msg!! shouldNotContain "hunter2-very-secret"
        msg shouldContain "***SCRUBBED***"
        // Structural error prefix survives so operators still see the cause
        msg shouldContain "ERROR"
    }

    test("--debug-body bypasses redaction and emits raw driver message") {
        val executor: SegmentAwareExecutorFn = { _, _, _, _, _ ->
            throw RuntimeException(
                "ERROR in CREATE FUNCTION: password = 'hunter2-very-secret' invalid",
            )
        }
        val trace = stage(executor).maybeExecute(
            request = request(debugBody = true),
            target = target,
            combined = rendered(),
            atomicBatch = null,
            cancellationToken = CancellationTokenSource.create().token,
        )
        trace shouldNotBe null
        // RAW_DEBUG path emits the raw message unchanged
        trace!!.executionError!! shouldContain "hunter2-very-secret"
    }

    test("non-execute request returns null without touching the executor") {
        val executor: SegmentAwareExecutorFn = { _, _, _, _, _ ->
            throw IllegalStateException("must not be called")
        }
        val trace = stage(executor).maybeExecute(
            request = request(debugBody = false).copy(execute = false),
            target = target,
            combined = rendered(),
            atomicBatch = null,
            cancellationToken = CancellationTokenSource.create().token,
        )
        trace shouldBe null
    }
})
