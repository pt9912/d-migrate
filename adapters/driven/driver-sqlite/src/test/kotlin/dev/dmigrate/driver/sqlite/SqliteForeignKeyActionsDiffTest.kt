package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintReferenceDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferentialAction
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

/**
 * Die Aktionen eines Fremdschluessels der Tabellenebene ueberleben den
 * Migrate-Pfad — in **beiden** Emittern, die eine `CREATE TABLE` schreiben.
 *
 * Gemessen an SQLite 3.45, bevor hier etwas gebaut wurde: `schema migrate
 * --execute` legte die Tabelle ohne `ON DELETE`/`ON UPDATE` an
 * (`PRAGMA foreign_key_list`: `NO ACTION|NO ACTION`), und ein Rebuild nahm sie
 * einer bestehenden Tabelle weg (`RESTRICT|CASCADE` -> `NO ACTION|NO ACTION`).
 * Der Post-Compare meldete beides als Drift (Exit 5) — laut, aber ein Defekt:
 * der Generate-Pfad schreibt die Aktionen, der Migrate-Pfad muss dasselbe
 * sagen.
 */
class SqliteForeignKeyActionsDiffTest : FunSpec({

    val planner = DiffPlanner()
    val gen = SqliteDiffDdlGenerator()

    val parent = TableDefinition(
        columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
        primaryKey = listOf("id"),
    )

    fun child(noteRequired: Boolean) = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "parent_id" to ColumnDefinition(NeutralType.Integer, required = true),
            "note" to ColumnDefinition(NeutralType.Text(), required = noteRequired),
        ),
        primaryKey = listOf("id"),
        constraints = listOf(
            ConstraintDefinition(
                name = "fk_child_parent",
                type = ConstraintType.FOREIGN_KEY,
                columns = listOf("parent_id"),
                references = ConstraintReferenceDefinition(
                    table = "parent",
                    columns = listOf("id"),
                    onDelete = ReferentialAction.CASCADE,
                    onUpdate = ReferentialAction.RESTRICT,
                ),
            ),
        ),
    )

    test("CreateTable carries ON DELETE and ON UPDATE") {
        val diff = SchemaDiff(tablesAdded = listOf(NamedTable("child", child(noteRequired = false))))
        val sql = gen.generateUp(
            planner.plan(
                SchemaDefinition(name = "App", version = "1", tables = mapOf("parent" to parent)),
                SchemaDefinition(
                    name = "App", version = "1",
                    tables = mapOf("parent" to parent, "child" to child(noteRequired = false)),
                ),
                diff,
            ),
            DdlGenerationOptions(),
        ).statements.first().sql

        sql shouldContain "FOREIGN KEY (\"parent_id\") REFERENCES \"parent\"(\"id\") " +
            "ON DELETE CASCADE ON UPDATE RESTRICT"
    }

    test("a rebuild keeps ON DELETE and ON UPDATE") {
        // Eine Nullbarkeits-Aenderung reicht fuer den Rebuild; die Tabelle wird
        // dabei neu geschrieben, und ohne die Aktionen verloere sie sie.
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "child",
                    columnsChanged = listOf(ColumnDiff(name = "note", required = ValueChange(false, true))),
                ),
            ),
        )
        val statements = gen.generateUp(
            planner.plan(
                SchemaDefinition(
                    name = "App", version = "1",
                    tables = mapOf("parent" to parent, "child" to child(noteRequired = false)),
                ),
                SchemaDefinition(
                    name = "App", version = "1",
                    tables = mapOf("parent" to parent, "child" to child(noteRequired = true)),
                ),
                diff,
            ),
            DdlGenerationOptions(),
        ).statements.map { it.sql }

        val rebuild = statements.single { it.contains("CREATE TABLE \"child__dmg_rebuild_") }
        rebuild shouldContain "FOREIGN KEY (\"parent_id\") REFERENCES \"parent\"(\"id\") " +
            "ON DELETE CASCADE ON UPDATE RESTRICT"
    }
})
