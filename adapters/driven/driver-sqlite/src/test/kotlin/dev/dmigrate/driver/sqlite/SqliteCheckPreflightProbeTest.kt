package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.CheckPreflightStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.sql.DriverManager

class SqliteCheckPreflightProbeTest : FunSpec({

    val planner = DiffPlanner()

    val baseTable = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "age" to ColumnDefinition(NeutralType.Integer),
        ),
        primaryKey = listOf("id"),
    )
    val ageCheck = ConstraintDefinition(name = "chk_age", type = ConstraintType.CHECK, expression = "age >= 0")

    fun schema(t: TableDefinition) = SchemaDefinition(name = "App", version = "1", tables = mapOf("u" to t))

    fun planAddCheck() = planner.plan(
        current = schema(baseTable),
        desired = schema(baseTable.copy(constraints = listOf(ageCheck))),
        schemaDiff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "u", constraintsAdded = listOf(ageCheck))),
        ),
    )

    fun planNoChange() = planner.plan(
        current = schema(baseTable), desired = schema(baseTable),
        schemaDiff = SchemaDiff(),
    )

    test("no CHECK Add ops → empty declarations") {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE u (id INTEGER PRIMARY KEY, age INTEGER)")
            }
            SqliteCheckPreflightProbe.probe(conn, planNoChange()).shouldBeEmpty()
        }
    }

    test("zero violations → PASSED + no failingRows") {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE u (id INTEGER PRIMARY KEY, age INTEGER)")
                stmt.execute("INSERT INTO u (id, age) VALUES (1, 17), (2, 41)")
            }
            val d = SqliteCheckPreflightProbe.probe(conn, planAddCheck()).single()
            d.status shouldBe CheckPreflightStatus.PASSED
            d.failingRows shouldBe null
            d.dialect shouldBe "sqlite"
            d.constraintName shouldBe "chk_age"
        }
    }

    test("non-zero violations → FAILED + failingRows populated") {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE u (id INTEGER PRIMARY KEY, age INTEGER)")
                stmt.execute("INSERT INTO u (id, age) VALUES (1, 17), (2, -3), (3, -5)")
            }
            val d = SqliteCheckPreflightProbe.probe(conn, planAddCheck()).single()
            d.status shouldBe CheckPreflightStatus.FAILED
            d.failingRows shouldBe 2L
        }
    }

    test("missing table → PROBE_RUNTIME_ERROR with SQLException message") {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            // No CREATE TABLE — the probe query fires against a missing table.
            val d = SqliteCheckPreflightProbe.probe(conn, planAddCheck()).single()
            d.status shouldBe CheckPreflightStatus.PROBE_RUNTIME_ERROR
            d.problem shouldContain "u"
        }
    }

    test("sqlHash is the 64-char sha256 hex (same shape as the cast preflight)") {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE u (id INTEGER PRIMARY KEY, age INTEGER)")
            }
            val d = SqliteCheckPreflightProbe.probe(conn, planAddCheck()).single()
            d.sqlHash.length shouldBe 64
        }
    }
})
