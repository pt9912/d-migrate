package dev.dmigrate.driver.postgresql

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
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * ADR 0025: the PG diff/migrate path expands a neutral FULLTEXT index back to a GiST
 * index over its backing `tsvector` column — the same expansion the generate path does,
 * so `schema migrate` (not just `schema generate`) of a reversed fulltext table is valid.
 */
class PostgresDiffFullTextTest : FunSpec({

    val planner = DiffPlanner()
    val gen = PostgresDiffDdlGenerator()

    fun filmTable(index: IndexDefinition?, withVectorColumn: Boolean = true) = TableDefinition(
        columns = buildMap {
            put("title", ColumnDefinition(NeutralType.Text()))
            put("description", ColumnDefinition(NeutralType.Text()))
            if (withVectorColumn) put("fulltext", ColumnDefinition(NeutralType.FullText))
        },
        indices = listOfNotNull(index),
    )

    fun addIndex(index: IndexDefinition, withVectorColumn: Boolean = true) = gen.generateUp(
        planner.plan(
            SchemaDefinition(name = "App", version = "1", tables = mapOf("film" to filmTable(null, withVectorColumn))),
            SchemaDefinition(name = "App", version = "1", tables = mapOf("film" to filmTable(index, withVectorColumn))),
            SchemaDiff(tablesChanged = listOf(TableDiff(name = "film", indicesAdded = listOf(index)))),
        ),
        DdlGenerationOptions(),
    )

    val ftIndex = IndexDefinition(
        name = "film_fulltext_idx",
        columns = listOf(IndexColumn("title"), IndexColumn("description")),
        type = IndexType.FULLTEXT,
        textSearchConfig = "english",
        fullTextVectorColumn = "fulltext",
    )

    test("AddIndex FULLTEXT expands to a GiST index over the recorded tsvector column") {
        val r = addIndex(ftIndex)
        r.isBlocked shouldBe false
        r.statements.single().sql shouldContain "CREATE INDEX \"film_fulltext_idx\" ON \"film\" USING GIST (\"fulltext\")"
    }

    test("AddIndex FULLTEXT without a recorded vector column resolves the table's sole tsvector column") {
        val r = addIndex(ftIndex.copy(fullTextVectorColumn = null))
        r.isBlocked shouldBe false
        r.statements.single().sql shouldContain "USING GIST (\"fulltext\")"
    }

    test("AddIndex FULLTEXT restores the recorded GIN access method") {
        val r = addIndex(ftIndex.copy(fullTextAccessMethod = IndexType.GIN))
        r.isBlocked shouldBe false
        r.statements.single().sql shouldContain "USING GIN (\"fulltext\")"
    }

    test("AddIndex FULLTEXT without a resolvable tsvector column BLOCKS (FULLTEXT_VECTOR_UNKNOWN)") {
        // Hard block, like the sibling spatial/op-class guards: a FULLTEXT index PG cannot build
        // must not silently succeed — a warn-and-skip would also leave the DOWN `DROP INDEX`
        // dropping a never-created index.
        val r = addIndex(ftIndex.copy(fullTextVectorColumn = null), withVectorColumn = false)
        r.isBlocked shouldBe true
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.diagnostics.any { it.code == "FULLTEXT_VECTOR_UNKNOWN" } shouldBe true
        r.statements.none { it.sql.contains("USING") } shouldBe true
    }

    test("CreateTable with an unresolvable FULLTEXT index BLOCKS (never silently drops it)") {
        val table = filmTable(ftIndex.copy(fullTextVectorColumn = null), withVectorColumn = false)
        val r = gen.generateUp(
            planner.plan(
                SchemaDefinition(name = "App", version = "1"),
                SchemaDefinition(name = "App", version = "1", tables = mapOf("film" to table)),
                SchemaDiff(tablesAdded = listOf(dev.dmigrate.core.diff.NamedTable("film", table))),
            ),
            DdlGenerationOptions(),
        )
        r.isBlocked shouldBe true
        r.diagnostics.any { it.code == "FULLTEXT_VECTOR_UNKNOWN" } shouldBe true
        r.statements.none { it.sql.contains("USING") } shouldBe true
    }
})
