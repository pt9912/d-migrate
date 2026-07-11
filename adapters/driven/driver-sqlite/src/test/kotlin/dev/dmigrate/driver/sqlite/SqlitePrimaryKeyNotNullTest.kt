package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Regression: SQLite's `PRIMARY KEY` does not imply `NOT NULL` (unlike
 * PostgreSQL/MySQL), except for `INTEGER PRIMARY KEY` / `WITHOUT ROWID`.
 * The neutral model leaves `required` unset on PK columns — the invariant
 * "PK ⇒ required implicit" (see `ImportTableValidator`), which the reverse
 * relies on. Without materialising `NOT NULL` on the SQLite side, a schema
 * d-migrate reversed then re-generated itself would silently drop the
 * `NOT NULL` constraint on every PK column.
 */
class SqlitePrimaryKeyNotNullTest : FunSpec({

    val generator = SqliteDdlGenerator()

    fun schema(tables: Map<String, TableDefinition>) =
        SchemaDefinition(name = "m-trace", version = "1.0.0", tables = tables)

    fun createTableSql(s: SchemaDefinition): String =
        generator.generate(s).statements.map { it.sql.trim() }.first { it.startsWith("CREATE TABLE") }

    test("single-column TEXT primary key renders NOT NULL even when `required` is unset") {
        val s = schema(
            mapOf(
                "projects" to TableDefinition(
                    columns = mapOf("project_id" to ColumnDefinition(NeutralType.Text())),
                    primaryKey = listOf("project_id"),
                ),
            ),
        )
        createTableSql(s) shouldContain "\"project_id\" TEXT NOT NULL"
    }

    test("composite TEXT primary key renders NOT NULL on every key column") {
        val s = schema(
            mapOf(
                "stream_sessions" to TableDefinition(
                    columns = mapOf(
                        "session_id" to ColumnDefinition(NeutralType.Text()),
                        "project_id" to ColumnDefinition(NeutralType.Text()),
                    ),
                    primaryKey = listOf("project_id", "session_id"),
                ),
            ),
        )
        val sql = createTableSql(s)
        sql shouldContain "\"session_id\" TEXT NOT NULL"
        sql shouldContain "\"project_id\" TEXT NOT NULL"
    }

    test("non-PK nullable column stays nullable") {
        val s = schema(
            mapOf(
                "projects" to TableDefinition(
                    columns = mapOf(
                        "project_id" to ColumnDefinition(NeutralType.Text()),
                        "note" to ColumnDefinition(NeutralType.Text()),
                    ),
                    primaryKey = listOf("project_id"),
                ),
            ),
        )
        val sql = createTableSql(s)
        sql shouldContain "\"project_id\" TEXT NOT NULL"
        sql shouldNotContain "\"note\" TEXT NOT NULL"
    }

    test("enum primary key column renders NOT NULL") {
        val s = schema(
            mapOf(
                "flags" to TableDefinition(
                    columns = mapOf("state" to ColumnDefinition(NeutralType.Enum(values = listOf("on", "off")))),
                    primaryKey = listOf("state"),
                ),
            ),
        )
        createTableSql(s) shouldContain "\"state\" TEXT NOT NULL"
    }

    test("INTEGER PRIMARY KEY rowid alias is unaffected — no redundant NOT NULL, no duplicate PK clause") {
        val s = schema(
            mapOf(
                "events" to TableDefinition(
                    columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier(autoIncrement = true))),
                    primaryKey = listOf("id"),
                ),
            ),
        )
        val sql = createTableSql(s)
        sql shouldContain "\"id\" INTEGER PRIMARY KEY AUTOINCREMENT"
        // rowid alias already forbids NULL; must not gain a redundant/invalid trailing NOT NULL
        sql shouldNotContain "AUTOINCREMENT NOT NULL"
        // single-column rowid PK must not also emit a table-level PRIMARY KEY clause
        sql shouldNotContain "PRIMARY KEY (\"id\")"
    }

    test("table rebuild recreates PK columns with NOT NULL") {
        val planner = DiffPlanner()
        val diffGen = SqliteDiffDdlGenerator()
        fun s(tables: Map<String, TableDefinition>) =
            SchemaDefinition(name = "m-trace", version = "1", tables = tables)

        val before = TableDefinition(
            columns = mapOf(
                "project_id" to ColumnDefinition(NeutralType.Text()),
                "status" to ColumnDefinition(NeutralType.SmallInt),
            ),
            primaryKey = listOf("project_id"),
        )
        val after = before.copy(
            columns = mapOf(
                "project_id" to ColumnDefinition(NeutralType.Text()),
                "status" to ColumnDefinition(NeutralType.Integer),
            ),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "projects",
                    columnsChanged = listOf(
                        ColumnDiff(name = "status", type = ValueChange(NeutralType.SmallInt, NeutralType.Integer)),
                    ),
                ),
            ),
        )
        val r = diffGen.generateUp(
            planner.plan(s(mapOf("projects" to before)), s(mapOf("projects" to after)), diff),
            DdlGenerationOptions(),
        )
        val createTemp = r.statements.map { it.sql }
            .first { it.contains("CREATE TABLE \"projects__dmg_rebuild_") }
        createTemp shouldContain "\"project_id\" TEXT NOT NULL"
    }

    context("SqlitePrimaryKeyNullability.materialize") {
        test("promotes a non-required PK column to required") {
            val col = ColumnDefinition(NeutralType.Text())
            SqlitePrimaryKeyNullability.materialize("id", col, listOf("id")).required shouldBe true
        }

        test("leaves a non-PK column untouched") {
            val col = ColumnDefinition(NeutralType.Text())
            SqlitePrimaryKeyNullability.materialize("note", col, listOf("id")) shouldBe col
        }

        test("leaves an already-required PK column untouched") {
            val col = ColumnDefinition(NeutralType.Text(), required = true)
            SqlitePrimaryKeyNullability.materialize("id", col, listOf("id")) shouldBe col
        }

        test("does not promote a sequence-backed PK column — SQLite needs a transient NULL") {
            val col = ColumnDefinition(NeutralType.Integer, default = DefaultValue.SequenceNextVal("seq_id"))
            SqlitePrimaryKeyNullability.materialize("id", col, listOf("id")).required shouldBe false
        }
    }
})
