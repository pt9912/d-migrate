package dev.dmigrate.driver.mysql

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

/**
 * ADR 0025: the MySQL diff/migrate path emits a native `CREATE FULLTEXT INDEX` over the
 * source text columns (mirrors the generate path) and — unlike a BTREE index — is exempt
 * from the TEXT/BLOB prefix-length rule (ERROR 1170).
 */
class MysqlDiffFullTextTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MysqlDiffDdlGenerator()

    fun docsTable(index: IndexDefinition?) = TableDefinition(
        columns = mapOf(
            // unbounded TEXT — a BTREE index here would need a prefix length; FULLTEXT must not.
            "title" to ColumnDefinition(NeutralType.Text()),
            "body" to ColumnDefinition(NeutralType.Text()),
            "fts" to ColumnDefinition(NeutralType.FullText),
        ),
        indices = listOfNotNull(index),
    )

    val ftIndex = IndexDefinition(
        name = "docs_fts",
        columns = listOf(IndexColumn("title"), IndexColumn("body")),
        type = IndexType.FULLTEXT,
        textSearchConfig = "english",
        fullTextVectorColumn = "fts",
    )

    test("AddIndex FULLTEXT emits CREATE FULLTEXT INDEX over the source columns, not blocked by the prefix rule") {
        val r = gen.generateUp(
            planner.plan(
                SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to docsTable(null))),
                SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to docsTable(ftIndex))),
                SchemaDiff(tablesChanged = listOf(TableDiff(name = "docs", indicesAdded = listOf(ftIndex)))),
            ),
            DdlGenerationOptions(),
        )
        r.isBlocked shouldBe false
        r.statements.single().sql shouldContain "CREATE FULLTEXT INDEX `docs_fts` ON `docs` (`title`, `body`)"
    }

    test("columnNeedingPrefix exempts a FULLTEXT index over unbounded TEXT columns") {
        MysqlIndexPrefix.columnNeedingPrefix(ftIndex) { NeutralType.Text() } shouldBe null
    }

    test("columnNeedingPrefix still flags a BTREE index over an unbounded TEXT column") {
        val btree = IndexDefinition(name = "i", columns = listOf(IndexColumn("title")), type = IndexType.BTREE)
        MysqlIndexPrefix.columnNeedingPrefix(btree) { NeutralType.Text() } shouldBe "title"
    }
})
