package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * W135 ([SqliteCompositePkIdentity]) on the migrate/diff and table-rebuild paths.
 *
 * An identity/AUTO_INCREMENT column inside a *composite* primary key cannot keep SQLite's
 * single-column `INTEGER PRIMARY KEY AUTOINCREMENT` — emitting it inline *and* the table-level
 * composite `PRIMARY KEY (…)` yields "more than one primary key" (unloadable DDL). The column
 * degrades to a plain INTEGER member of the composite key and the loss is reported loud (W135).
 * The generate path is covered by SqliteDdlGeneratorTableTest; this covers the two `columnLine`
 * emitters (SqliteDiffSimpleOps CREATE TABLE and SqliteRebuildRenderer).
 */
class SqliteCompositePkIdentityTest : FunSpec({

    val planner = DiffPlanner()
    val gen = SqliteDiffDdlGenerator()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun paymentTable(noteRequired: Boolean = false) = TableDefinition(
        columns = mapOf(
            "payment_id" to ColumnDefinition(NeutralType.Identifier(autoIncrement = true), required = true),
            "part_id" to ColumnDefinition(NeutralType.Integer, required = true),
            "note" to ColumnDefinition(NeutralType.Text(), required = noteRequired),
        ),
        primaryKey = listOf("payment_id", "part_id"),
    )

    test("diff CreateTable: identity in a composite PK drops AUTOINCREMENT (single valid PK) + W135") {
        val diff = SchemaDiff(tablesAdded = listOf(NamedTable("payment", paymentTable())))
        val r = gen.generateUp(planner.plan(emptySchema(), emptySchema(), diff), DdlGenerationOptions())

        val createSql = r.statements.map { it.sql }.first { it.contains("CREATE TABLE") }
        Regex("PRIMARY KEY").findAll(createSql).count() shouldBe 1
        createSql shouldContain "PRIMARY KEY (\"payment_id\", \"part_id\")"
        createSql shouldNotContain "AUTOINCREMENT"
        createSql shouldContain "\"payment_id\" INTEGER NOT NULL"
        r.diagnostics.any { it.code == "W135" } shouldBe true
    }

    test("table rebuild: identity in a composite PK drops AUTOINCREMENT (single valid PK) + W135") {
        fun schema(noteRequired: Boolean) = SchemaDefinition(
            name = "App",
            version = "1",
            tables = mapOf("payment" to paymentTable(noteRequired)),
        )
        val current = schema(noteRequired = false)
        val desired = schema(noteRequired = true) // nullability change on `note` → table rebuild
        val diff = SchemaComparator().compare(current, desired)
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())

        val createSql = r.statements.map { it.sql }.first { it.contains("CREATE TABLE") }
        // sanity: the change really went through the rebuild path (temp-table recreate)
        createSql shouldContain "PRIMARY KEY (\"payment_id\", \"part_id\")"
        Regex("PRIMARY KEY").findAll(createSql).count() shouldBe 1
        createSql shouldNotContain "AUTOINCREMENT"
        r.diagnostics.any { it.code == "W135" } shouldBe true
    }
})
