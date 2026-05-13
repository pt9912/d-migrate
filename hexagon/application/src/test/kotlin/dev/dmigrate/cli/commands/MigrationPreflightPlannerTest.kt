package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqliteCastPreflightDeclaration
import dev.dmigrate.driver.SqliteCastPreflightStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class MigrationPreflightPlannerTest : FunSpec({

    fun plan(): DiffResult =
        DiffResult(
            current = DiffEndpoint(schemaName = "current"),
            desired = DiffEndpoint(schemaName = "desired"),
            schemaDiff = SchemaDiff(),
            operations = emptyList(),
        )

    fun declaration(status: SqliteCastPreflightStatus, problem: String?) =
        SqliteCastPreflightDeclaration(
            operationId = "op-cast",
            table = "orders",
            column = "amount",
            sourceType = "TEXT",
            targetType = "INTEGER",
            status = status,
            sqlHash = "a".repeat(64),
            problem = problem,
        )

    test("B.2 pre-render planner declares SQLite DB execute casts as policy-pending") {
        val result = MigrationPreflightPlanner.plan(
            sqliteCastPlanner = { _, status, problem -> listOf(declaration(status, problem)) },
            request = SchemaMigrateRequest(source = "desired.yaml", target = "db:sqlite", execute = true),
            target = CompareOperand.Database("sqlite"),
            dialect = DatabaseDialect.SQLITE,
            plan = plan(),
        )

        result.sqliteCastPreflights.single().status shouldBe SqliteCastPreflightStatus.NOT_RUN_POLICY
    }

    test("B.2 pre-render planner declares SQLite file casts as not run for file target") {
        val result = MigrationPreflightPlanner.plan(
            sqliteCastPlanner = { _, status, problem -> listOf(declaration(status, problem)) },
            request = SchemaMigrateRequest(source = "desired.yaml", target = "file:current.yaml"),
            target = CompareOperand.File(Path.of("current.yaml")),
            dialect = DatabaseDialect.SQLITE,
            plan = plan(),
        )

        result.sqliteCastPreflights.single().status shouldBe SqliteCastPreflightStatus.NOT_RUN_FILE_TARGET
    }

    test("B.2 pre-render planner is silent for non-SQLite dialects") {
        val result = MigrationPreflightPlanner.plan(
            sqliteCastPlanner = { _, status, problem -> listOf(declaration(status, problem)) },
            request = SchemaMigrateRequest(source = "desired.yaml", target = "db:postgresql", execute = true),
            target = CompareOperand.Database("postgresql"),
            dialect = DatabaseDialect.POSTGRESQL,
            plan = plan(),
        )

        result.sqliteCastPreflights.shouldBeEmpty()
    }
})
