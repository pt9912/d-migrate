package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain as shouldContainStr
import io.kotest.matchers.string.shouldNotContain as shouldNotContainStr

/**
 * AP2 of the implicit-`identifier`-PK-materialisation slice: SQLite
 * renders an `identifier` column inline as `INTEGER PRIMARY KEY
 * AUTOINCREMENT` ([SqliteTypeMapper]), so a single-column PK on that
 * column must NOT also emit a table-level `PRIMARY KEY (…)` — SQLite
 * rejects the duplicate. Paired with AP1 ([dev.dmigrate.core.diff.migration.OperationMapper]
 * materialises the effective PK), an identifier-only table now arrives
 * with `primaryKey = [id]` and stays single-PK here. Slice plan:
 * `docs/planning/in-progress/generate-implicit-identifier-pk-materialization.md`.
 */
class SqliteDiffPrimaryKeyDedupTest : FunSpec({

    val planner = DiffPlanner()
    val gen = SqliteDiffDdlGenerator()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun createTableSql(table: TableDefinition): String =
        gen.generateUp(
            planner.plan(
                emptySchema(),
                emptySchema().copy(tables = mapOf("t" to table)),
                SchemaDiff(tablesAdded = listOf(NamedTable("t", table))),
            ),
            DdlGenerationOptions(),
        ).statements.first().sql

    test("identifier-only table renders a single inline PK, no table-level clause") {
        // AP1 materialises primaryKey=[id]; AP2 dedups the table-level clause so
        // the previously green implicit case does not regress to a double PK.
        val sql = createTableSql(TableDefinition(columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier()))))
        sql shouldContainStr "PRIMARY KEY AUTOINCREMENT"
        sql shouldNotContainStr "PRIMARY KEY (\""
    }

    test("identifier column with an explicit primary_key on itself does not double the PK") {
        // This is the SQLite red case the slice fixes (was SQLITE_ERROR: two PKs).
        val table = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier())),
            primaryKey = listOf("id"),
        )
        val sql = createTableSql(table)
        sql shouldContainStr "PRIMARY KEY AUTOINCREMENT"
        sql shouldNotContainStr "PRIMARY KEY (\""
    }

    test("non-identifier single-column PK still emits the table-level clause") {
        val table = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer)),
            primaryKey = listOf("id"),
        )
        createTableSql(table) shouldContainStr "PRIMARY KEY (\"id\")"
    }

    test("multi-column PK still emits the table-level clause") {
        val table = TableDefinition(
            columns = mapOf(
                "a" to ColumnDefinition(NeutralType.Integer),
                "b" to ColumnDefinition(NeutralType.Integer),
            ),
            primaryKey = listOf("a", "b"),
        )
        createTableSql(table) shouldContainStr "PRIMARY KEY (\"a\", \"b\")"
    }
})
