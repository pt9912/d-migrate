package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.NamedSequence
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.MysqlSequenceCanonicityKind
import dev.dmigrate.driver.MysqlSequenceCanonicityStatus
import dev.dmigrate.driver.SqliteCastPreflightDeclaration
import dev.dmigrate.driver.SqliteCastPreflightStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
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

    // ── E.3 MySQL Sequence Drift-Check Sub-Slice E ─────────────

    val sequencePlanner = DiffPlanner()
    fun planWithSequenceAdd(): DiffResult = sequencePlanner.plan(
        SchemaDefinition(name = "App", version = "1"),
        SchemaDefinition(name = "App", version = "1", sequences = mapOf("order_seq" to SequenceDefinition(start = 1L))),
        SchemaDiff(sequencesAdded = listOf(NamedSequence("order_seq", SequenceDefinition(start = 1L)))),
    )

    test("MySQL DB execute pre-plans sequence ops as NOT_RUN_POLICY") {
        val result = MigrationPreflightPlanner.plan(
            sqliteCastPlanner = null,
            request = SchemaMigrateRequest(source = "desired.yaml", target = "db:mysql", execute = true),
            target = CompareOperand.Database("mysql"),
            dialect = DatabaseDialect.MYSQL,
            plan = planWithSequenceAdd(),
        )
        result.mysqlSequenceCanonicity shouldHaveSize 1
        val decl = result.mysqlSequenceCanonicity.single()
        decl.status shouldBe MysqlSequenceCanonicityStatus.NOT_RUN_POLICY
        decl.kind shouldBe MysqlSequenceCanonicityKind.SEQUENCE_ROW
        decl.objectName shouldBe "order_seq"
        decl.dialect shouldBe "mysql"
    }

    test("file target pre-plans sequence ops as NOT_RUN_FILE_TARGET") {
        val result = MigrationPreflightPlanner.plan(
            sqliteCastPlanner = null,
            request = SchemaMigrateRequest(source = "desired.yaml", target = "file:current.yaml"),
            target = CompareOperand.File(Path.of("current.yaml")),
            dialect = DatabaseDialect.MYSQL,
            plan = planWithSequenceAdd(),
        )
        result.mysqlSequenceCanonicity.single().status shouldBe MysqlSequenceCanonicityStatus.NOT_RUN_FILE_TARGET
    }

    test("non-MySQL dialects → no sequence canonicity declarations") {
        for (dialect in listOf(DatabaseDialect.POSTGRESQL, DatabaseDialect.SQLITE)) {
            val result = MigrationPreflightPlanner.plan(
                sqliteCastPlanner = null,
                request = SchemaMigrateRequest(source = "desired.yaml", target = "db:${dialect.name.lowercase()}", execute = true),
                target = CompareOperand.Database(dialect.name.lowercase()),
                dialect = dialect,
                plan = planWithSequenceAdd(),
            )
            result.mysqlSequenceCanonicity.shouldBeEmpty()
        }
    }

    test("MySQL DB execute with no sequence ops → empty mysqlSequenceCanonicity") {
        val result = MigrationPreflightPlanner.plan(
            sqliteCastPlanner = null,
            request = SchemaMigrateRequest(source = "desired.yaml", target = "db:mysql", execute = true),
            target = CompareOperand.Database("mysql"),
            dialect = DatabaseDialect.MYSQL,
            plan = plan(),
        )
        result.mysqlSequenceCanonicity.shouldBeEmpty()
    }
})
