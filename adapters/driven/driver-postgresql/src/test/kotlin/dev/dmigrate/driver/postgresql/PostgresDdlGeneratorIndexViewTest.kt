package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

// ── Helpers ────────────────────────────────────────────────

private fun schema(
    tables: Map<String, TableDefinition> = emptyMap(),
    views: Map<String, ViewDefinition> = emptyMap(),
) = SchemaDefinition(name = "test_schema", version = "1.0", tables = tables, views = views)

private fun table(
    columns: Map<String, ColumnDefinition>,
    indices: List<IndexDefinition> = emptyList(),
) = TableDefinition(columns = columns, indices = indices)

private fun col(type: NeutralType) = ColumnDefinition(type = type)

// ── Index/View portability + prefix (I-08 / I-09 / prefix-length slice) ──

class PostgresDdlGeneratorIndexViewTest : FunSpec({

    val generator = PostgresDdlGenerator()

    test("view with non-portable cross-dialect body is skipped with E053 (I-09)") {
        val s = schema(
            views = mapOf(
                "mysql_view" to ViewDefinition(query = "SELECT IFNULL(x, 0) FROM t", sourceDialect = "mysql")
            )
        )
        val result = generator.generate(s)
        // IFNULL is a MySQL-only function (PG has COALESCE); emitting it verbatim
        // would be invalid PG DDL, so the view is skipped, not emitted.
        result.render() shouldNotContain "CREATE OR REPLACE VIEW \"mysql_view\""
        result.notes.any { it.code == "E053" && it.objectName == "mysql_view" } shouldBe true
        result.skippedObjects.any { it.name == "mysql_view" } shouldBe true
    }

    test("view with MySQL backticks and group_concat is skipped with E053, not invalid DDL (I-09)") {
        val s = schema(
            views = mapOf(
                "report" to ViewDefinition(
                    query = "SELECT group_concat(`name`) FROM `app`.`users`", sourceDialect = "mysql"
                )
            )
        )
        val result = generator.generate(s)
        result.render() shouldNotContain "CREATE OR REPLACE VIEW \"report\""
        result.notes.any { it.code == "E053" && it.objectName == "report" } shouldBe true
    }

    test("portable cross-dialect view (plain SELECT) is still emitted (I-09)") {
        val s = schema(
            views = mapOf(
                "active_users" to ViewDefinition(
                    query = "SELECT id, name FROM users WHERE active = TRUE", sourceDialect = "mysql"
                )
            )
        )
        val result = generator.generate(s)
        result.render() shouldContain "CREATE OR REPLACE VIEW \"active_users\" AS"
        result.skippedObjects.any { it.name == "active_users" } shouldBe false
    }

    test("GIST index on a text column is skipped with W123 — no default operator class (I-08)") {
        val s = schema(
            tables = mapOf(
                "docs" to table(
                    columns = mapOf("body" to col(NeutralType.Text())),
                    indices = listOf(
                        IndexDefinition(name = "idx_docs_body", columns = listOf(IndexColumn("body")), type = IndexType.GIST)
                    )
                )
            )
        )
        val result = generator.generate(s)
        result.render() shouldNotContain "USING GIST"
        result.notes.any { it.code == "W123" && it.objectName == "idx_docs_body" } shouldBe true
    }

    test("GIST index on a fulltext (tsvector) column is emitted — has default operator class (ADR 0015)") {
        val s = schema(
            tables = mapOf(
                "docs" to table(
                    columns = mapOf("body" to col(NeutralType.FullText)),
                    indices = listOf(
                        IndexDefinition(name = "idx_docs_ft", columns = listOf(IndexColumn("body")), type = IndexType.GIST)
                    )
                )
            )
        )
        val result = generator.generate(s)
        result.render() shouldContain "USING GIST"
        result.render() shouldContain "tsvector"
        result.notes.any { it.code == "W123" } shouldBe false
    }

    test("GIN index on a jsonb column is still emitted — has default operator class (I-08)") {
        val s = schema(
            tables = mapOf(
                "events" to table(
                    columns = mapOf("payload" to col(NeutralType.Json)),
                    indices = listOf(
                        IndexDefinition(name = "idx_events_payload", columns = listOf(IndexColumn("payload")), type = IndexType.GIN)
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "USING GIN"
        ddl shouldContain "idx_events_payload"
    }

    test("index prefix length is dropped with W126 note in PostgreSQL (prefix-length slice)") {
        val s = schema(
            tables = mapOf(
                "docs" to table(
                    columns = mapOf("body" to col(NeutralType.Text())),
                    indices = listOf(
                        IndexDefinition(name = "idx_docs_body", columns = listOf(IndexColumn("body", prefixLength = 100)))
                    )
                )
            )
        )
        val result = generator.generate(s)
        result.render() shouldContain "CREATE INDEX \"idx_docs_body\" ON \"docs\" (\"body\");"
        result.render() shouldNotContain "body(100)"
        result.notes.any { it.code == "W126" && it.objectName == "idx_docs_body" } shouldBe true
    }

    test("duplicate explicit index name across tables is disambiguated schema-globally with W127 (N8)") {
        // MySQL allows the same index name on several tables (per-table
        // namespace); PostgreSQL index names are schema-global. The second
        // occurrence must be renamed, not emitted verbatim (else
        // "relation \"idx_fk_address_id\" already exists").
        val s = schema(
            tables = mapOf(
                "address" to table(
                    columns = mapOf("address_id" to col(NeutralType.Text())),
                    indices = listOf(
                        IndexDefinition(name = "idx_fk_address_id", columns = listOf(IndexColumn("address_id")))
                    )
                ),
                "customer" to table(
                    columns = mapOf("address_id" to col(NeutralType.Text())),
                    indices = listOf(
                        IndexDefinition(name = "idx_fk_address_id", columns = listOf(IndexColumn("address_id")))
                    )
                ),
            )
        )
        val result = generator.generate(s)
        val ddl = result.render()
        // First occurrence keeps the name; the colliding one is suffixed.
        ddl shouldContain "\"idx_fk_address_id\""
        ddl shouldContain "\"idx_fk_address_id_2\""
        // Exactly two distinct index names, one per table.
        Regex("CREATE INDEX \"idx_fk_address_id(_2)?\"").findAll(ddl).count() shouldBe 2
        // The rename is surfaced, not silent (objectName is the final name,
        // so this holds regardless of which table is emitted first).
        result.notes.any { it.code == "W127" && it.objectName == "idx_fk_address_id_2" } shouldBe true
    }

    test("schema-global index-name registry resets between generate() runs (N8)") {
        val s = schema(
            tables = mapOf(
                "t" to table(
                    columns = mapOf("c" to col(NeutralType.Text())),
                    indices = listOf(IndexDefinition(name = "idx_c", columns = listOf(IndexColumn("c"))))
                )
            )
        )
        generator.generate(s).render() shouldContain "CREATE INDEX \"idx_c\" ON \"t\""
        // A second run of the same schema must not keep suffixing.
        val second = generator.generate(s).render()
        second shouldContain "CREATE INDEX \"idx_c\" ON \"t\""
        second shouldNotContain "idx_c_2"
    }
})
