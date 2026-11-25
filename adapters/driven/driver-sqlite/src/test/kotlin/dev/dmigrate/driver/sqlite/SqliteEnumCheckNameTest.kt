package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * ADR 0069 auf SQLite: der gesetzte `values_constraint` benennt den CHECK der Inline-Enum, sonst gilt
 * `ck_<tabelle>_<spalte>`. Generate-Pfad und Tabellen-Neubau schreiben denselben Namen.
 */
class SqliteEnumCheckNameTest : FunSpec({

    fun enumOf(name: String?) = NeutralType.Enum(values = listOf("open", "closed"), valuesConstraintName = name)

    fun table(type: NeutralType) = TableDefinition(
        columns = linkedMapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "status" to ColumnDefinition(type),
        ),
        primaryKey = listOf("id"),
    )

    test("CREATE TABLE: der gesetzte Name ersetzt die Konvention") {
        val ddl = SqliteDdlGenerator()
            .generate(SchemaDefinition(name = "s", version = "1", tables = mapOf("tickets" to table(enumOf("ck_state")))))
            .render()

        ddl shouldContain "CONSTRAINT \"ck_state\" CHECK (\"status\" IN ('open', 'closed'))"
        ddl shouldNotContain "ck_tickets_status"
    }

    test("CREATE TABLE ohne Namen: weiter die Konvention") {
        val ddl = SqliteDdlGenerator()
            .generate(SchemaDefinition(name = "s", version = "1", tables = mapOf("tickets" to table(enumOf(null)))))
            .render()

        ddl shouldContain "CONSTRAINT \"ck_tickets_status\" CHECK"
    }

    test("ADD COLUMN und Tabellen-Neubau tragen denselben Namen") {
        val planner = DiffPlanner()
        val gen = SqliteDiffDdlGenerator()
        val empty = SchemaDefinition(name = "App", version = "1")
        val created = gen.generateUp(
            planner.plan(empty, empty, SchemaDiff(tablesAdded = listOf(NamedTable("tickets", table(enumOf("ck_state")))))),
            DdlGenerationOptions(),
        ).statements.map { it.sql }.first { it.contains("CREATE TABLE") }
        created shouldContain "CONSTRAINT \"ck_state\" CHECK (\"status\" IN ('open', 'closed'))"

        val before = enumOf("ck_old")
        val after = enumOf("ck_new")
        val current = SchemaDefinition(name = "App", version = "1", tables = mapOf("tickets" to table(before)))
        val desired = SchemaDefinition(name = "App", version = "1", tables = mapOf("tickets" to table(after)))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "tickets",
                    columnsChanged = listOf(ColumnDiff(name = "status", type = ValueChange(before, after))),
                ),
            ),
        )
        val sql = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
            .statements.joinToString("\n") { it.sql }

        sql shouldContain "CONSTRAINT \"ck_new\" CHECK (\"status\" IN ('open', 'closed'))"
        sql shouldNotContain "ck_old"
    }
})
