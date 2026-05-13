package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.ExecutionMode
import dev.dmigrate.driver.SqliteCastPreflightDeclaration
import dev.dmigrate.driver.SqliteCastPreflightStatus
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.sql.DriverManager

class SqliteCastPreflightProbeTest : FunSpec({

    val planner = DiffPlanner()
    val generator = SqliteDiffDdlGenerator()

    fun schema(table: TableDefinition) = SchemaDefinition(
        name = "App",
        version = "1",
        tables = mapOf("u" to table),
    )

    fun currentTable() = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "age" to ColumnDefinition(NeutralType.SmallInt),
        ),
        primaryKey = listOf("id"),
    )

    fun desiredTable() = currentTable().copy(
        columns = mapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "age" to ColumnDefinition(NeutralType.Integer),
        ),
    )

    fun plan() = planner.plan(
        current = schema(currentTable()),
        desired = schema(desiredTable()),
        schemaDiff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "age",
                            type = ValueChange(NeutralType.SmallInt, NeutralType.Integer),
                        ),
                    ),
                ),
            ),
        ),
    )

    test("B.2 positive: live SQLite cast preflight passes and execute rendering is allowed") {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE u (id INTEGER PRIMARY KEY, age INTEGER)")
                stmt.execute("INSERT INTO u (id, age) VALUES (1, 41)")
            }
            val diff = plan()
            val declarations = SqliteCastPreflightProbe.probe(conn, diff)

            declarations shouldHaveSize 1
            declarations.single().status shouldBe SqliteCastPreflightStatus.PASSED
            declarations.single().dialect shouldBe "sqlite"
            declarations.single().sqlHash.length shouldBe 64
            declarations.single().failingRows shouldBe 0

            val rendered = generator.generateUp(
                diff,
                DdlGenerationOptions(
                    executionMode = ExecutionMode.EXECUTE,
                    sqliteCastPreflights = declarations,
                ),
            )
            rendered.isBlocked shouldBe false
            rendered.diagnostics.any { it.code == "SQLITE_CAST_PREFLIGHT_PASSED" } shouldBe true
            rendered.sqliteCastPreflights.single().status shouldBe SqliteCastPreflightStatus.PASSED
            rendered.sqliteCastPreflights.single().totalRows shouldBe 1
        }
    }

    test("B.2 negative: non-convertible live rows block execute rendering before CAST SQL") {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE u (id INTEGER PRIMARY KEY, age INTEGER)")
                stmt.execute("INSERT INTO u (id, age) VALUES (1, 'not-an-integer')")
            }
            val diff = plan()
            val declarations = SqliteCastPreflightProbe.probe(conn, diff)

            declarations shouldHaveSize 1
            declarations.single().status shouldBe SqliteCastPreflightStatus.FAILED
            declarations.single().failingRows shouldBe 1

            val rendered = generator.generateUp(
                diff,
                DdlGenerationOptions(
                    executionMode = ExecutionMode.EXECUTE,
                    sqliteCastPreflights = declarations,
                ),
            )
            rendered.isBlocked shouldBe true
            rendered.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
            rendered.diagnostics.any { it.code == "SQLITE_CAST_PREFLIGHT_FAILED" } shouldBe true
            rendered.statements.any { it.sql.contains("CAST(\"age\" AS INTEGER)") } shouldBe false
            rendered.sqliteCastPreflights.single().status shouldBe SqliteCastPreflightStatus.FAILED
            rendered.sqliteCastPreflights.single().failingRows shouldBe 1
        }
    }

    test("B.2 execute without a declared fresh preflight is blocked") {
        val rendered = generator.generateUp(
            plan(),
            DdlGenerationOptions(executionMode = ExecutionMode.EXECUTE),
        )

        rendered.isBlocked shouldBe true
        rendered.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        rendered.diagnostics.any { it.code == "SQLITE_CAST_PREFLIGHT_MISSING" } shouldBe true
        rendered.sqliteCastPreflights.single().status shouldBe SqliteCastPreflightStatus.NOT_RUN_POLICY
        rendered.sqliteCastPreflights.single().problem shouldBe
            "Execute requires a fresh SQLite cast preflight declaration."
    }

    test("B.2 planner emits deterministic not-run declarations before live probing") {
        val declarations = SqliteCastPreflightPlanner.plan(
            plan(),
            SqliteCastPreflightStatus.NOT_RUN_POLICY,
            problem = "preflight probe failed",
        )

        declarations shouldHaveSize 1
        declarations.single().status shouldBe SqliteCastPreflightStatus.NOT_RUN_POLICY
        declarations.single().problem shouldBe "preflight probe failed"
        declarations.single().sqlHash.length shouldBe 64
    }

    test("B.2 file target is deterministic and reports NOT_RUN_FILE_TARGET") {
        val first = generator.generateUp(plan(), DdlGenerationOptions())
        val second = generator.generateUp(plan(), DdlGenerationOptions())

        first.isBlocked shouldBe false
        first.statements.map { it.sql } shouldBe second.statements.map { it.sql }
        first.diagnostics.any { it.code == "SQLITE_CAST_PREFLIGHT_NOT_RUN_FILE_TARGET" } shouldBe true
        first.sqliteCastPreflights.single().status shouldBe SqliteCastPreflightStatus.NOT_RUN_FILE_TARGET
    }

    test("B.2 report distinguishes NOT_RUN_POLICY from file-target not-run") {
        val diff = plan()
        val binding = SqliteCastPreflightSql.bindingsFor(diff).single()
        val rendered = generator.generateUp(
            diff,
            DdlGenerationOptions(
                sqliteCastPreflights = listOf(
                    SqliteCastPreflightDeclaration(
                        operationId = binding.operationId,
                        dialect = binding.dialect,
                        table = binding.table,
                        column = binding.column,
                        sourceType = binding.sourceTypeText,
                        targetType = binding.targetTypeText,
                        status = SqliteCastPreflightStatus.NOT_RUN_POLICY,
                        sqlHash = binding.sqlHash,
                    ),
                ),
            ),
        )

        rendered.isBlocked shouldBe false
        rendered.diagnostics.any { it.code == "SQLITE_CAST_PREFLIGHT_NOT_RUN_POLICY" } shouldBe true
        rendered.diagnostics.any { it.code == "SQLITE_CAST_PREFLIGHT_NOT_RUN_FILE_TARGET" } shouldBe false
        rendered.sqliteCastPreflights.single().status shouldBe SqliteCastPreflightStatus.NOT_RUN_POLICY
    }
})
