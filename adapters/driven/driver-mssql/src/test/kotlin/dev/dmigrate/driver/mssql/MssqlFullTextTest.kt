package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.mssql.MssqlDdlTestSupport.notesWithCode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Volltext-Indizes fuer SQL Server: Katalog, Schluesselindex und die Faelle,
 * die der Server ablehnt.
 *
 * Die Regeln sind am Server gemessen — ein `KEY INDEX` muss einspaltig,
 * eindeutig und nicht nullbar sein, und je Tabelle gibt es hoechstens einen
 * Volltext-Index.
 */
class MssqlFullTextTest : FunSpec({

    val generator = MssqlDdlGenerator()

    fun render(table: TableDefinition) = generator.generate(
        SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to table)),
        DdlGenerationOptions(),
    )

    val ftIndex = IndexDefinition("fx_body", listOf(IndexColumn("body")), type = IndexType.FULLTEXT)

    fun table(
        primaryKey: List<String> = listOf("id"),
        constraints: List<ConstraintDefinition> = emptyList(),
        indices: List<IndexDefinition> = listOf(ftIndex),
        idRequired: Boolean = true,
    ) = TableDefinition(
        columns = linkedMapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = idRequired),
            "body" to ColumnDefinition(NeutralType.Text()),
            "title" to ColumnDefinition(NeutralType.Text(200)),
        ),
        primaryKey = primaryKey,
        constraints = constraints,
        indices = indices,
    )

    test("catalog and index are rendered, keyed on the single-column primary key") {
        val ddl = render(table()).render()

        ddl shouldContain "CREATE FULLTEXT CATALOG [ftc_docs];"
        ddl shouldContain "CREATE FULLTEXT INDEX ON [docs] ([body]) KEY INDEX [pk_docs] ON [ftc_docs];"
    }

    test("W146 reports that the catalog was created for this table alone") {
        render(table()).notesWithCode("W146").size shouldBe 1
    }

    test("a full-text index may cover several columns") {
        val multi = IndexDefinition(
            "fx_all", listOf(IndexColumn("body"), IndexColumn("title")), type = IndexType.FULLTEXT,
        )
        render(table(indices = listOf(multi))).render() shouldContain
            "CREATE FULLTEXT INDEX ON [docs] ([body], [title]) KEY INDEX [pk_docs] ON [ftc_docs];"
    }

    // Gemessen: „A full-text search key must be a unique, non-nullable,
    // single-column index."
    test("a composite primary key is no valid key — E070") {
        val ddl = render(table(primaryKey = listOf("id", "title"))).render()

        ddl shouldContain "E070"
        ddl shouldNotContain "CREATE FULLTEXT"
    }

    test("a single-column unique constraint serves as the key when there is no primary key") {
        val t = table(
            primaryKey = emptyList(),
            constraints = listOf(
                ConstraintDefinition(name = "uq_docs_id", type = ConstraintType.UNIQUE, columns = listOf("id")),
            ),
        )
        render(t).render() shouldContain "KEY INDEX [uq_docs_id] ON [ftc_docs];"
    }

    test("a nullable unique column is no valid key — E070") {
        val t = table(
            primaryKey = emptyList(),
            idRequired = false,
            constraints = listOf(
                ConstraintDefinition(name = "uq_docs_id", type = ConstraintType.UNIQUE, columns = listOf("id")),
            ),
        )
        render(t).render() shouldContain "E070"
    }

    // Gemessen: „A full-text index for table 't4' has already been created."
    test("two full-text indexes on one table refuse with E071") {
        val second = IndexDefinition("fx_title", listOf(IndexColumn("title")), type = IndexType.FULLTEXT)
        val ddl = render(table(indices = listOf(ftIndex, second))).render()

        ddl shouldContain "E071"
        ddl shouldNotContain "CREATE FULLTEXT INDEX"
    }

    test("the teardown drops index and catalog — DROP TABLE leaves the catalog behind") {
        MssqlFullTextDdl.dropStatements("docs") { "[$it]" } shouldBe listOf(
            "DROP FULLTEXT INDEX ON [docs];",
            "DROP FULLTEXT CATALOG [ftc_docs];",
        )
    }
})
