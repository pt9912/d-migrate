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

    test("parseFts5 strips UNINDEXED column options (keeps the identifier only)") {
        val def = SqliteFts5Reverse.parseFts5(
            "t_fts",
            "CREATE VIRTUAL TABLE t_fts USING fts5(title, body UNINDEXED, content='docs')",
        )
        def.columns shouldBe listOf("title", "body")
        def.contentTable shouldBe "docs"
    }

    test("parseFts5 keeps a content= value that contains a bracket-quoted close-paren") {
        // Bracket [..] quoting must be balanced-args-aware, not just handled by unquote.
        val def = SqliteFts5Reverse.parseFts5("x_fts", "CREATE VIRTUAL TABLE x_fts USING fts5(a, content=[we)ird])")
        def.contentTable shouldBe "we)ird"
        def.columns shouldBe listOf("a")
    }

    test("fts5 shadow-table set is external-content-aware (no phantom _content for external)") {
        // External content (content=): SQLite creates no _content shadow, so it must NOT be filtered.
        SqliteFts5Reverse.fts5ShadowTables("docs_fts", externalContent = true) shouldBe
            setOf("docs_fts_data", "docs_fts_idx", "docs_fts_docsize", "docs_fts_config")
        // Regular/contentless: _content is a real shadow.
        SqliteFts5Reverse.fts5ShadowTables("docs_fts", externalContent = false) shouldBe
            setOf("docs_fts_data", "docs_fts_idx", "docs_fts_docsize", "docs_fts_config", "docs_fts_content")
        SqliteFts5Reverse.fts5SyncTriggerNames("docs_fts") shouldBe
            setOf("docs_fts_ai", "docs_fts_ad", "docs_fts_au")
    }

    test("isFts5SyncTrigger detects a custom-named trigger that INSERTs into a known fts5 table") {
        val fts = setOf("docs_fts")
        SqliteFts5Reverse.isFts5SyncTrigger(
            "CREATE TRIGGER docs_sync AFTER INSERT ON docs BEGIN INSERT INTO docs_fts(rowid) VALUES(new.rowid); END",
            fts,
        ) shouldBe true
        // A user trigger that neither inserts nor references an fts5 table is left alone.
        SqliteFts5Reverse.isFts5SyncTrigger(
            "CREATE TRIGGER audit AFTER UPDATE ON docs BEGIN UPDATE meta SET n=n+1; END",
            fts,
        ) shouldBe false
    }
})
