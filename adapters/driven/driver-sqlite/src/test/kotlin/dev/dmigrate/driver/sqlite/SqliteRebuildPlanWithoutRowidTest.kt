package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TableMetadata
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SqliteRebuildPlanWithoutRowidTest : FunSpec({

    val planner = DiffPlanner()
    val gen = SqliteDiffDdlGenerator()

    fun schemaWith(tables: Map<String, TableDefinition>) =
        SchemaDefinition(name = "App", version = "1", tables = tables)

    fun table(withoutRowid: Boolean?) = TableDefinition(
        columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
        primaryKey = listOf("id"),
        metadata = withoutRowid?.let { TableMetadata(withoutRowid = it) },
    )

    fun plan(old: TableDefinition, new: TableDefinition) = SqliteRebuildPlan(
        originalTableName = "t",
        oldTable = old,
        newTable = new,
        newTableTempName = "t_new",
        bucketOperations = emptyList(),
        sourceOperationIds = emptySet(),
        risk = OperationRisk.SAFE,
        mapping = SqliteColumnMappingModel(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
        indexesToRecreate = emptyList(),
    )

    test("Merkmal der Datenbank-Seite zaehlt, auch wenn die Datei es nicht kennt (Up: oldTable)") {
        plan(table(true), table(null)).withoutRowid shouldBe true
    }

    test("Merkmal der Datenbank-Seite zaehlt in der Gegenrichtung (Down: newTable)") {
        plan(table(null), table(true)).withoutRowid shouldBe true
    }

    test("Merkmal der Datei zaehlt, wenn die Datenbank es nicht fuehrt") {
        plan(table(false), table(true)).withoutRowid shouldBe true
    }

    test("keine Seite traegt das Merkmal") {
        plan(table(null), table(null)).withoutRowid shouldBe false
        plan(table(false), table(false)).withoutRowid shouldBe false
    }

    test("FULLTEXT recreate degrades when only the database side is WITHOUT ROWID (file silent)") {
        val fulltext = IndexDefinition(
            name = "docs_fts",
            columns = listOf(IndexColumn("title")),
            type = IndexType.FULLTEXT,
        )
        val before = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "title" to ColumnDefinition(NeutralType.Text()),
                "views" to ColumnDefinition(NeutralType.SmallInt),
            ),
            primaryKey = listOf("id"),
            indices = listOf(fulltext),
            metadata = dev.dmigrate.core.model.TableMetadata(withoutRowid = true),
        )
        val after = before.copy(
            columns = before.columns + ("views" to ColumnDefinition(NeutralType.Integer)),
            metadata = null,
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "docs",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "views",
                            type = ValueChange(NeutralType.SmallInt, NeutralType.Integer),
                        ),
                    ),
                ),
            ),
        )
        val r = gen.generateUp(
            planner.plan(schemaWith(mapOf("docs" to before)), schemaWith(mapOf("docs" to after)), diff),
            DdlGenerationOptions(),
        )

        r.isBlocked shouldBe false
        r.statements.any { it.sql.startsWith("CREATE VIRTUAL TABLE") } shouldBe false
        r.statements.any { it.sql.startsWith("-- FULLTEXT index \"docs_fts\" skipped") } shouldBe true
        r.statements.any { it.sql.startsWith("CREATE TABLE") && it.sql.endsWith(") WITHOUT ROWID;") } shouldBe true
        r.diagnostics.any { it.code == "W132" } shouldBe true
    }
})
