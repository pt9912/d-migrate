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
 * ADR 0025: SQLite has no fulltext index without an FTS5 virtual table (slice P4). Until
 * that lands a FULLTEXT index degrades with the dedicated W132 (fulltext) note — on both
 * the generate and the diff/migrate path — never a silent plain BTREE index.
 */
class SqliteFullTextDegradationTest : FunSpec({

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

    test("generate degrades a FULLTEXT index with W132, not the generic W102") {
        val result = SqliteDdlGenerator().generate(
            SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to docsTable(ftIndex))),
        )
        result.notes.any { it.code == "W132" && it.objectName == "docs_fts" } shouldBe true
        result.notes.none { it.code == "W102" && it.objectName == "docs_fts" } shouldBe true
        result.render() shouldNotContain "CREATE INDEX \"docs_fts\""
    }

    test("diff/migrate degrades a FULLTEXT AddIndex with W132 and no plain index") {
        val r = SqliteDiffDdlGenerator().generateUp(
            DiffPlanner().plan(
                SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to docsTable(null))),
                SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to docsTable(ftIndex))),
                SchemaDiff(tablesChanged = listOf(TableDiff(name = "docs", indicesAdded = listOf(ftIndex)))),
            ),
            DdlGenerationOptions(),
        )
        r.isBlocked shouldBe false
        r.diagnostics.any { it.code == "W132" } shouldBe true
        r.statements.none { it.sql.contains("CREATE INDEX") } shouldBe true
        r.statements.joinToString("\n") { it.sql } shouldContain "skipped"
    }
})
