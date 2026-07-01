package dev.dmigrate.driver.sqlite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** ADR 0025 (Slice P5): FTS5 reverse parsing — folding the FULLTEXT expansion back (see
 *  [SqliteFts5Reverse]). End-to-end reconstruction is covered live in SqliteSchemaReaderTest. */
class SqliteFts5ReverseTest : FunSpec({

    test("isFts5VirtualTable matches only fts5 virtual tables") {
        SqliteFts5Reverse.isFts5VirtualTable("CREATE VIRTUAL TABLE x USING fts5(a, b)") shouldBe true
        SqliteFts5Reverse.isFts5VirtualTable("CREATE VIRTUAL TABLE x USING rtree(id, minx, maxx)") shouldBe false
        SqliteFts5Reverse.isFts5VirtualTable("CREATE TABLE x (id INTEGER)") shouldBe false
    }

    test("parseFts5 extracts source columns + external content table, ignoring other options") {
        val def = SqliteFts5Reverse.parseFts5(
            "docs_fts",
            "CREATE VIRTUAL TABLE \"docs_fts\" USING fts5(\"title\", \"body\", content='docs', tokenize='porter unicode61');",
        )
        def.name shouldBe "docs_fts"
        def.contentTable shouldBe "docs"
        def.columns shouldBe listOf("title", "body")
    }

    test("parseFts5 handles a contentless table (no content= → null content table)") {
        val def = SqliteFts5Reverse.parseFts5("search", "CREATE VIRTUAL TABLE search USING fts5(content)")
        def.contentTable shouldBe null
        def.columns shouldBe listOf("content")
    }

    test("parseFts5 tolerates a content literal that itself contains a comma/paren") {
        // The quote-aware splitter must not break on commas/parens inside the string literal.
        val def = SqliteFts5Reverse.parseFts5(
            "t_fts",
            "CREATE VIRTUAL TABLE t_fts USING fts5(a, content='weird,name(x)');",
        )
        def.contentTable shouldBe "weird,name(x)"
        def.columns shouldBe listOf("a")
    }

    test("fts5 shadow tables and sync trigger names are the FTS5-managed set") {
        SqliteFts5Reverse.fts5ShadowTables("docs_fts") shouldBe
            setOf("docs_fts_data", "docs_fts_idx", "docs_fts_docsize", "docs_fts_config", "docs_fts_content")
        SqliteFts5Reverse.fts5SyncTriggerNames("docs_fts") shouldBe
            setOf("docs_fts_ai", "docs_fts_ad", "docs_fts_au")
    }
})
