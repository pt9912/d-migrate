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
import dev.dmigrate.core.model.TableMetadata
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

    test("generateRollback inverts the FTS5 virtual table to DROP TABLE (no orphan)") {
        // Review P4: CREATE VIRTUAL TABLE must invert like CREATE TABLE, else the rollback drops
        // the sync triggers but leaves the virtual table orphaned (re-apply then fails).
        val rb = SqliteDdlGenerator().generateRollback(
            SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to docsTable(ftIndex))),
        )
        val sql = rb.statements.joinToString("\n") { it.sql }
        sql shouldContain "DROP TABLE IF EXISTS \"docs_fts\";"
        sql shouldContain "DROP TRIGGER IF EXISTS \"docs_fts_ai\";"
    }

    test("anonymous FULLTEXT index gets the SAME FTS5 name on the generate and diff paths") {
        val anon = IndexDefinition(columns = listOf(IndexColumn("title")), type = IndexType.FULLTEXT)
        val genDdl = SqliteDdlGenerator().generate(
            SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to docsTable(anon))),
        ).render()
        val diffSql = SqliteDiffDdlGenerator().generateUp(
            DiffPlanner().plan(
                SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to docsTable(null))),
                SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to docsTable(anon))),
                SchemaDiff(tablesChanged = listOf(TableDiff(name = "docs", indicesAdded = listOf(anon)))),
            ),
            DdlGenerationOptions(),
        ).statements.joinToString("\n") { it.sql }
        // Both paths must agree on the anonymous name (docs_title_idx), not idx_docs_title.
        genDdl shouldContain "CREATE VIRTUAL TABLE \"docs_title_idx\""
        diffSql shouldContain "CREATE VIRTUAL TABLE \"docs_title_idx\""
    }

    test("generate degrades a FULLTEXT index over a WITHOUT ROWID base table (W132, no broken DDL)") {
        val table = TableDefinition(
            columns = mapOf(
                "title" to ColumnDefinition(NeutralType.Text()),
                "fts" to ColumnDefinition(NeutralType.FullText),
            ),
            indices = listOf(ftIndex),
            metadata = TableMetadata(withoutRowid = true),
        )
        val result = SqliteDdlGenerator().generate(
            SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to table)),
        )
        result.render() shouldNotContain "CREATE VIRTUAL TABLE"
        result.notes.any { it.code == "W132" && it.objectName == "docs_fts" } shouldBe true
    }

    test("generate degrades a FULLTEXT index whose source column is a reserved FTS5 name (rank)") {
        val rankIndex = IndexDefinition(
            name = "docs_fts", columns = listOf(IndexColumn("rank")), type = IndexType.FULLTEXT,
        )
        val table = TableDefinition(
            columns = mapOf(
                "rank" to ColumnDefinition(NeutralType.Text()),
                "fts" to ColumnDefinition(NeutralType.FullText),
            ),
            indices = listOf(rankIndex),
        )
        val result = SqliteDdlGenerator().generate(
            SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to table)),
        )
        result.render() shouldNotContain "CREATE VIRTUAL TABLE"
        result.notes.any { it.code == "W132" && it.objectName == "docs_fts" } shouldBe true
    }

    test("createIndexSql degrades a FULLTEXT index to the skip marker (rebuild-bucket fallback)") {
        val sql = SqliteDiffSqlBuilders().createIndexSql("docs", ftIndex)
        sql shouldContain "FULLTEXT index"
        sql shouldContain "skipped"
        sql shouldNotContain "CREATE"
    }

    test("diff FULLTEXT over a WITHOUT ROWID table degrades on UP and DOWN (no FTS5, no orphan drop)") {
        fun wr(idx: IndexDefinition?) = TableDefinition(
            columns = mapOf(
                "title" to ColumnDefinition(NeutralType.Text()),
                "fts" to ColumnDefinition(NeutralType.FullText),
            ),
            indices = listOfNotNull(idx),
            metadata = TableMetadata(withoutRowid = true),
        )
        val plan = DiffPlanner().plan(
            SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to wr(null))),
            SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to wr(ftIndex))),
            SchemaDiff(tablesChanged = listOf(TableDiff(name = "docs", indicesAdded = listOf(ftIndex)))),
        )
        val up = SqliteDiffDdlGenerator().generateUp(plan, DdlGenerationOptions())
        up.statements.joinToString("\n") { it.sql } shouldNotContain "CREATE VIRTUAL TABLE"
        up.diagnostics.any { it.code == "W132" } shouldBe true
        // DOWN: nothing was built, so nothing is dropped (no orphan DROP TABLE of a missing FTS5).
        val down = SqliteDiffDdlGenerator().generateDown(plan, DdlGenerationOptions())
        down.statements.joinToString("\n") { it.sql } shouldNotContain "DROP TABLE"
    }
})
