package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.driver.DatabaseDialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.nio.file.Files
import java.sql.DriverManager

/**
 * Unit tests for [CheckPreflightProbeRunner].
 *
 * Coverage split mirrors the runner shape:
 *
 * 1. `dispatch(conn, dialect, plan)` — pure when-arm selection over
 *    [DatabaseDialect]. Each arm delegates to a per-dialect probe;
 *    with an empty [DiffResult] no SQL is sent to the connection, so
 *    we exercise all three arms against a single in-memory SQLite
 *    JDBC connection.
 *
 * 2. `probe(...)` — outer wrapper. Hikari + a real MySQL / Postgres
 *    server belong in `:test:integration-*`, mirroring the precedent
 *    for `SqliteCastPreflightProbeRunner`. Here we only assert the
 *    two `CompareConfigException` error paths.
 */
class CheckPreflightProbeRunnerTest : FunSpec({

    fun emptyPlan(): DiffResult = DiffResult(
        current = DiffEndpoint("acme", schemaVersion = "1"),
        desired = DiffEndpoint("acme", schemaVersion = "2"),
        schemaDiff = SchemaDiff(),
        operations = emptyList(),
    )

    // Use an in-memory SQLite connection as a no-op "real" JDBC connection.
    // For an empty plan, none of the three per-dialect probes execute any
    // SQL against it — they map over an empty planner output.
    fun useSqliteConn(block: (java.sql.Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use(block)
    }

    test("dispatch routes POSTGRESQL → empty plan returns empty list") {
        useSqliteConn { conn ->
            CheckPreflightProbeRunner
                .dispatch(conn, DatabaseDialect.POSTGRESQL, emptyPlan())
                .shouldBeEmpty()
        }
    }

    test("dispatch routes MYSQL → empty plan returns empty list") {
        useSqliteConn { conn ->
            CheckPreflightProbeRunner
                .dispatch(conn, DatabaseDialect.MYSQL, emptyPlan())
                .shouldBeEmpty()
        }
    }

    test("dispatch routes SQLITE → empty plan returns empty list") {
        useSqliteConn { conn ->
            CheckPreflightProbeRunner
                .dispatch(conn, DatabaseDialect.SQLITE, emptyPlan())
                .shouldBeEmpty()
        }
    }

    test("probe() throws CompareConfigException when NamedConnectionResolver fails") {
        // Bare alias (no `://`) triggers config-file resolution; with a
        // non-existent config path the resolver throws, which the runner
        // wraps into CompareConfigException.
        val nonExistentConfig = Files.createTempDirectory("dmigrate-check-preflight-runner-")
            .resolve("does-not-exist.yaml")

        shouldThrow<CompareConfigException> {
            CheckPreflightProbeRunner.probe(
                target = CompareOperand.Database("unknown_alias"),
                configPath = nonExistentConfig,
                plan = emptyPlan(),
                dialect = DatabaseDialect.SQLITE,
            )
        }
    }

    test("probe() throws CompareConfigException when the URL has an unsupported dialect") {
        // URL contains `://` so we skip the file-based resolver but the
        // URL parser then rejects `oracle://...` as unsupported, which
        // the runner re-throws as CompareConfigException.
        shouldThrow<CompareConfigException> {
            CheckPreflightProbeRunner.probe(
                target = CompareOperand.Database("oracle://localhost/db"),
                configPath = null,
                plan = emptyPlan(),
                dialect = DatabaseDialect.SQLITE,
            )
        }
    }
})
