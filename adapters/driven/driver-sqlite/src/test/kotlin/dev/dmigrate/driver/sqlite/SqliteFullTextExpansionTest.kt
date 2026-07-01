package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * ADR 0025 (Slice P4): a neutral FULLTEXT index expands into a SQLite FTS5 external-content
 * virtual table + initial `'rebuild'` + three sync triggers over the source columns
 * ([SqliteFullTextExpansion]) — on BOTH the generate and the diff/migrate render path, never a
 * silent plain BTREE. The `tsvector` column itself still degrades to TEXT with its own W132
 * (covered in SqliteDdlGeneratorTableTest / SqliteColumnConstraintHelper) — that is a separate,
 * unchanged contract.
 */
class SqliteFullTextExpansionTest : FunSpec({

    fun docsTable(index: IndexDefinition?) = TableDefinition(
        columns = mapOf(
            "title" to ColumnDefinition(NeutralType.Text()),
            "fts" to ColumnDefinition(NeutralType.FullText),
        ),
        indices = listOfNotNull(index),
    )

    val ftIndex = IndexDefinition(
        name = "docs_fts",
        columns = listOf(IndexColumn("title")),
        type = IndexType.FULLTEXT,
        fullTextVectorColumn = "fts",
    )

    test("generate expands a FULLTEXT index into an FTS5 virtual table + rebuild + three triggers") {
        val result = SqliteDdlGenerator().generate(
            SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to docsTable(ftIndex))),
        )
        val ddl = result.render()
        ddl shouldContain "CREATE VIRTUAL TABLE \"docs_fts\" USING fts5(\"title\", content='docs');"
        ddl shouldContain "INSERT INTO \"docs_fts\"(\"docs_fts\") VALUES('rebuild');"
        ddl shouldContain "CREATE TRIGGER \"docs_fts_ai\" AFTER INSERT ON \"docs\""
        ddl shouldContain "CREATE TRIGGER \"docs_fts_ad\" AFTER DELETE ON \"docs\""
        ddl shouldContain "CREATE TRIGGER \"docs_fts_au\" AFTER UPDATE ON \"docs\""
        ddl shouldContain "VALUES('delete', old.rowid, old.\"title\")"
        // The index no longer degrades: no W132 keyed on the index, no plain CREATE INDEX for it.
        result.notes.none { it.code == "W132" && it.objectName == "docs_fts" } shouldBe true
        result.notes.none { it.code == "W102" && it.objectName == "docs_fts" } shouldBe true
        ddl shouldNotContain "CREATE INDEX \"docs_fts\""
        // The tsvector column still degrades to TEXT with its own W132 (unchanged contract).
        result.notes.any { it.code == "W132" && it.objectName == "docs.fts" } shouldBe true
    }

    test("diff/migrate expands a FULLTEXT AddIndex into FTS5 (no W132 skip, no plain index)") {
        val r = SqliteDiffDdlGenerator().generateUp(
            DiffPlanner().plan(
                SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to docsTable(null))),
                SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to docsTable(ftIndex))),
                SchemaDiff(tablesChanged = listOf(TableDiff(name = "docs", indicesAdded = listOf(ftIndex)))),
            ),
            DdlGenerationOptions(),
        )
        val sql = r.statements.joinToString("\n") { it.sql }
        r.isBlocked shouldBe false
        sql shouldContain "CREATE VIRTUAL TABLE \"docs_fts\" USING fts5(\"title\", content='docs');"
        sql shouldContain "CREATE TRIGGER \"docs_fts_ai\""
        sql shouldNotContain "skipped"
        r.statements.none { it.sql.contains("CREATE INDEX") } shouldBe true
        r.diagnostics.none { it.code == "W132" } shouldBe true
    }

    test("AddIndex(FULLTEXT) DOWN tears down the FTS5 structure (rollback symmetry)") {
        val plan = DiffPlanner().plan(
            SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to docsTable(null))),
            SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to docsTable(ftIndex))),
            SchemaDiff(tablesChanged = listOf(TableDiff(name = "docs", indicesAdded = listOf(ftIndex)))),
        )
        val down = SqliteDiffDdlGenerator().generateDown(plan, DdlGenerationOptions())
        val sql = down.statements.joinToString("\n") { it.sql }
        sql shouldContain "DROP TRIGGER IF EXISTS \"docs_fts_ai\";"
        sql shouldContain "DROP TABLE IF EXISTS \"docs_fts\";"
        down.statements.none { it.sql.contains("DROP INDEX") } shouldBe true
    }

    test("CreateTable carrying a FULLTEXT index emits the FTS5 expansion, not a plain index") {
        val table = docsTable(ftIndex)
        val r = SqliteDiffDdlGenerator().generateUp(
            DiffPlanner().plan(
                SchemaDefinition(name = "App", version = "1"),
                SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to table)),
                SchemaDiff(tablesAdded = listOf(dev.dmigrate.core.diff.NamedTable("docs", table))),
            ),
            DdlGenerationOptions(),
        )
        val sql = r.statements.joinToString("\n") { it.sql }
        r.statements.any { it.sql.contains("CREATE TABLE") } shouldBe true
        sql shouldContain "CREATE VIRTUAL TABLE \"docs_fts\" USING fts5(\"title\", content='docs');"
        sql shouldNotContain "skipped"
        r.statements.none { it.sql.contains("CREATE INDEX") } shouldBe true
    }

    test("CreateTable DOWN tears down the FTS5 structure before dropping the base table") {
        val table = docsTable(ftIndex)
        val plan = DiffPlanner().plan(
            SchemaDefinition(name = "App", version = "1"),
            SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to table)),
            SchemaDiff(tablesAdded = listOf(dev.dmigrate.core.diff.NamedTable("docs", table))),
        )
        val down = SqliteDiffDdlGenerator().generateDown(plan, DdlGenerationOptions())
        val sql = down.statements.joinToString("\n") { it.sql }
        sql shouldContain "DROP TABLE IF EXISTS \"docs_fts\";"
        sql shouldContain "DROP TABLE \"docs\";"
    }
})
