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
import dev.dmigrate.driver.migration.TransactionScope
import dev.dmigrate.driver.mssql.MssqlDdlTestSupport.notesWithCode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain as shouldNotContainItem
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Volltext-Indizes fuer SQL Server: Katalog, Schluesselindex und die Faelle,
 * die der Server ablehnt.
 *
 * Ein `KEY INDEX` muss einspaltig,
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
        // Bedingt, weil der Katalog `DROP TABLE` ueberlebt und der
        // Tabellen-Neubau sonst am vorhandenen Namen scheiterte.
        ddl shouldContain "IF NOT EXISTS (SELECT 1 FROM sys.fulltext_catalogs WHERE name = 'ftc_docs')"
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

    // Volltext hat eine eigene Loesch-Syntax; `DROP INDEX <name> ON <t>` ist
    // dafuer ungueltiges T-SQL.
    // Der gewaehlte Schluesselindex muss auch entstehen.
    // Eine LOB-Schluesselspalte laesst SQL Server nicht indizieren, der
    // Primaerschluessel faellt dann weg — ein `KEY INDEX` darauf zeigte ins Leere.
    test("a LOB primary key is no valid key — E070, because the PK is not rendered") {
        val t = TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.Text(), required = true),
                "body" to ColumnDefinition(NeutralType.Text()),
            ),
            primaryKey = listOf("id"),
            indices = listOf(ftIndex),
        )
        val ddl = render(t).render()

        ddl shouldContain "E070"
        ddl shouldNotContain "CREATE FULLTEXT INDEX"
    }

    test("the chosen key index is actually created in the same DDL") {
        val t = table(
            primaryKey = emptyList(),
            constraints = listOf(
                ConstraintDefinition(name = "uq_docs_id", type = ConstraintType.UNIQUE, columns = listOf("id")),
            ),
        )
        val ddl = render(t).render()

        ddl shouldContain "CONSTRAINT [uq_docs_id] UNIQUE"
        ddl shouldContain "KEY INDEX [uq_docs_id]"
    }

    // D5: ein separat gerenderter Index taugt nur, wenn er VOR dem
    // Volltext-Index steht — sonst verweist KEY INDEX auf etwas, das erst
    // danach entsteht.
    test("a unique index after the full-text index is no valid key") {
        val late = IndexDefinition("ux_id", listOf(IndexColumn("id")), unique = true)
        val t = table(primaryKey = emptyList(), indices = listOf(ftIndex, late))

        render(t).render() shouldContain "E070"
    }

    test("a unique index before the full-text index serves as the key") {
        val early = IndexDefinition("ux_id", listOf(IndexColumn("id")), unique = true)
        val t = table(primaryKey = emptyList(), indices = listOf(early, ftIndex))
        val ddl = render(t).render()

        ddl shouldContain "CREATE UNIQUE INDEX [ux_id] ON [docs] ([id]);"
        ddl shouldContain "KEY INDEX [ux_id]"
    }

    test("a column-level unique serves as the key") {
        val t = TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true, unique = true),
                "body" to ColumnDefinition(NeutralType.Text()),
            ),
            indices = listOf(ftIndex),
        )
        render(t).render() shouldContain "KEY INDEX [uq_docs_id]"
    }

    // Der Rueckbau muss Katalog und Index nehmen: `DROP TABLE` nimmt den
    // Generate-Rollback liess den Katalog als Leiche stehen.
    test("the generated rollback drops the full-text index and its catalog") {
        val result = generator.generateRollback(
            SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to table())),
            DdlGenerationOptions(),
        )
        val down = result.render()

        down shouldContain "DROP FULLTEXT INDEX ON [docs];"
        down shouldContain "DROP FULLTEXT CATALOG [ftc_docs];"
    }

    // SQL Server weist `CREATE FULLTEXT INDEX` in einer offenen Transaktion ab.
    // Der Lauf klammert deshalb nicht mehr alles in eine: die Anweisung traegt
    // `NO_TRANSACTION`, und der Ausfuehrer setzt sie in einem eigenen
    // Abschnitt ab.
    test("the migration path renders a full-text index outside the transaction") {
        val planner = dev.dmigrate.core.diff.migration.DiffPlanner()
        val t = table()
        val schema = SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to t))
        val result = MssqlDiffDdlGenerator().generateUp(
            planner.plan(
                SchemaDefinition(name = "App", version = "1"),
                schema,
                dev.dmigrate.core.diff.SchemaDiff(
                    tablesAdded = listOf(dev.dmigrate.core.diff.NamedTable("docs", t)),
                ),
            ),
            DdlGenerationOptions(),
        )

        val fullText = result.statements.single { it.sql.contains("CREATE FULLTEXT INDEX") }
        fullText.transactionScope shouldBe TransactionScope.NO_TRANSACTION
        // Die Tabelle selbst bleibt in der Transaktion des Laufs — nur die
        // Volltext-Anweisung faellt heraus.
        result.statements.first { it.sql.startsWith("CREATE TABLE") }
            .transactionScope shouldBe TransactionScope.RUNNER_OWNED
        result.diagnostics.map { it.code } shouldNotContainItem "E072"
    }

    // Die Behebung war zunaechst halb: das Anlegen blockte, die Loeschpfade
    // Der Rueckbau steht unter derselben Regel — und `DROP INDEX` waere fuer
    // einen Volltext-Index ohnehin die falsche Anweisung.
    test("dropping a full-text index renders its own DDL outside the transaction") {
        val planner = dev.dmigrate.core.diff.migration.DiffPlanner()
        val t = table()
        val current = SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to t))
        val result = MssqlDiffDdlGenerator().generateUp(
            planner.plan(
                current,
                SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to t.copy(indices = emptyList()))),
                dev.dmigrate.core.diff.SchemaDiff(
                    tablesChanged = listOf(
                        dev.dmigrate.core.diff.TableDiff(name = "docs", indicesRemoved = listOf(ftIndex)),
                    ),
                ),
            ),
            DdlGenerationOptions(),
        )

        val statements = result.statements.filter { it.sql.contains("FULLTEXT") }
        statements.map { it.sql }.any { it.contains("DROP FULLTEXT INDEX ON [docs]") } shouldBe true
        statements.map { it.sql }.any { it.contains("DROP FULLTEXT CATALOG [ftc_docs]") } shouldBe true
        statements.all { it.transactionScope == TransactionScope.NO_TRANSACTION } shouldBe true
        result.diagnostics.map { it.code } shouldNotContainItem "E072"
    }

    test("the teardown drops index and catalog — DROP TABLE leaves the catalog behind") {
        MssqlFullTextDdl.dropStatements("docs") { "[$it]" } shouldBe listOf(
            "IF EXISTS (SELECT 1 FROM sys.fulltext_indexes WHERE object_id = OBJECT_ID('docs')) " +
                "DROP FULLTEXT INDEX ON [docs];",
            "IF EXISTS (SELECT 1 FROM sys.fulltext_catalogs WHERE name = 'ftc_docs') " +
                "DROP FULLTEXT CATALOG [ftc_docs];",
        )
    }
})
