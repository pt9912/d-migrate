package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerTiming
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * P2 (ADR 0025): unit pins for recovering a FULLTEXT index from a PostgreSQL
 * `tsvector_update_trigger(...)` populating trigger.
 */
class PostgresFullTextIndexSynthesisTest : FunSpec({

    fun trigger(table: String, body: String?) = TriggerDefinition(
        table = table,
        event = TriggerEvent.INSERT,
        timing = TriggerTiming.BEFORE,
        body = body,
    )

    // ── parseTrigger ────────────────────────────────────────────

    test("parses the Pagila tsvector_update_trigger body (tsvcol, config, source columns)") {
        val parsed = PostgresFullTextIndexSynthesis.parseTrigger(
            trigger(
                "film",
                "EXECUTE FUNCTION tsvector_update_trigger('fulltext', 'pg_catalog.english', 'title', 'description')",
            ),
        )
        parsed shouldBe PostgresFullTextIndexSynthesis.ParsedTrigger(
            table = "film",
            tsvectorColumn = "fulltext",
            textSearchConfig = "english",
            sourceColumns = listOf("title", "description"),
        )
    }

    test("config without a pg_catalog prefix is taken verbatim") {
        val parsed = PostgresFullTextIndexSynthesis.parseTrigger(
            trigger("doc", "EXECUTE FUNCTION tsvector_update_trigger('fts', 'simple', 'body')"),
        )
        parsed?.textSearchConfig shouldBe "simple"
        parsed?.sourceColumns shouldBe listOf("body")
    }

    test("a user-schema-qualified config keeps its qualifier (only pg_catalog. is stripped)") {
        PostgresFullTextIndexSynthesis.parseTrigger(
            trigger("doc", "EXECUTE FUNCTION tsvector_update_trigger('fts', 'myschema.german', 'body')"),
        )?.textSearchConfig shouldBe "myschema.german"
    }

    test("a non-tsvector trigger body is not a fulltext trigger") {
        PostgresFullTextIndexSynthesis.parseTrigger(
            trigger("users", "EXECUTE FUNCTION audit_fn()"),
        ) shouldBe null
    }

    test("a tsvector trigger without source columns is rejected") {
        PostgresFullTextIndexSynthesis.parseTrigger(
            trigger("doc", "EXECUTE FUNCTION tsvector_update_trigger('fts', 'english')"),
        ) shouldBe null
    }

    test("the tsvector_update_trigger_column variant is not parsed (different signature)") {
        PostgresFullTextIndexSynthesis.parseTrigger(
            trigger("doc", "EXECUTE FUNCTION tsvector_update_trigger_column('fts', 'cfgcol', 'body', 'A')"),
        ) shouldBe null
    }

    test("a user-defined wrapper ending in tsvector_update_trigger is NOT matched (left boundary)") {
        PostgresFullTextIndexSynthesis.parseTrigger(
            trigger("doc", "EXECUTE FUNCTION app_tsvector_update_trigger('fts', 'english', 'body')"),
        ) shouldBe null
    }

    test("a null body yields no parse") {
        PostgresFullTextIndexSynthesis.parseTrigger(trigger("doc", null)) shouldBe null
    }

    // ── parseTrigger: quote-aware argument handling ─────────────

    test("a comma inside a quoted column identifier is not a separator") {
        PostgresFullTextIndexSynthesis.parseTrigger(
            trigger("doc", "EXECUTE FUNCTION tsvector_update_trigger('fts', 'english', 'last,first')"),
        )?.sourceColumns shouldBe listOf("last,first")
    }

    test("a parenthesis inside a quoted column identifier does not truncate the arg list") {
        PostgresFullTextIndexSynthesis.parseTrigger(
            trigger("doc", "EXECUTE FUNCTION tsvector_update_trigger('fts', 'english', 'note(s)', 'body')"),
        )?.sourceColumns shouldBe listOf("note(s)", "body")
    }

    test("a doubled single-quote in a literal collapses to one apostrophe") {
        PostgresFullTextIndexSynthesis.parseTrigger(
            trigger("doc", "EXECUTE FUNCTION tsvector_update_trigger('fts', 'english', 'O''Brien')"),
        )?.sourceColumns shouldBe listOf("O'Brien")
    }

    test("an empty config position does not shift the source columns (positional parse)") {
        // Defensive: a blank config must not be mistaken for the first source column.
        val parsed = PostgresFullTextIndexSynthesis.parseTrigger(
            trigger("doc", "EXECUTE FUNCTION tsvector_update_trigger('fts', '', 'title', 'body')"),
        )
        parsed?.tsvectorColumn shouldBe "fts"
        parsed?.textSearchConfig shouldBe null
        parsed?.sourceColumns shouldBe listOf("title", "body")
    }

    // ── enrich ──────────────────────────────────────────────────

    val filmTable = TableDefinition(
        columns = mapOf(
            "title" to ColumnDefinition(type = NeutralType.Text(), required = true),
            "description" to ColumnDefinition(type = NeutralType.Text()),
            "fulltext" to ColumnDefinition(type = NeutralType.FullText, required = true),
        ),
        indices = listOf(
            IndexDefinition(name = "film_fulltext_idx", columns = listOf(IndexColumn("fulltext")), type = IndexType.GIST),
            IndexDefinition(name = "idx_title", columns = listOf(IndexColumn("title"))),
        ),
    )

    val filmTrigger = trigger(
        "film",
        "EXECUTE FUNCTION tsvector_update_trigger('fulltext', 'pg_catalog.english', 'title', 'description')",
    )

    test("replaces the GiST-over-tsvector index with a FULLTEXT index carrying the vector column") {
        val out = PostgresFullTextIndexSynthesis.enrich(
            mapOf("film" to filmTable),
            mapOf("film::film_fulltext_trigger" to filmTrigger),
        )
        val indices = out.getValue("film").indices
        indices.single { it.name == "film_fulltext_idx" } shouldBe IndexDefinition(
            name = "film_fulltext_idx",
            columns = listOf(IndexColumn("title"), IndexColumn("description")),
            type = IndexType.FULLTEXT,
            textSearchConfig = "english",
            fullTextVectorColumn = "fulltext",
        )
        indices.single { it.name == "idx_title" }.type shouldBe IndexType.BTREE
    }

    test("a GIN-over-tsvector index is also replaced (GIN is a valid tsvector access method)") {
        val table = filmTable.copy(
            indices = listOf(
                IndexDefinition(name = "film_fulltext_idx", columns = listOf(IndexColumn("fulltext")), type = IndexType.GIN),
            ),
        )
        val out = PostgresFullTextIndexSynthesis.enrich(mapOf("film" to table), mapOf("t" to filmTrigger))
        val idx = out.getValue("film").indices.single()
        idx.type shouldBe IndexType.FULLTEXT
        idx.fullTextVectorColumn shouldBe "fulltext"
    }

    test("with two tsvector columns each index is mapped to its own vector column") {
        val table = TableDefinition(
            columns = mapOf(
                "title" to ColumnDefinition(type = NeutralType.Text()),
                "body" to ColumnDefinition(type = NeutralType.Text()),
                "fts_a" to ColumnDefinition(type = NeutralType.FullText),
                "fts_b" to ColumnDefinition(type = NeutralType.FullText),
            ),
            indices = listOf(
                IndexDefinition(name = "idx_a", columns = listOf(IndexColumn("fts_a")), type = IndexType.GIST),
                IndexDefinition(name = "idx_b", columns = listOf(IndexColumn("fts_b")), type = IndexType.GIST),
            ),
        )
        val triggers = mapOf(
            "a" to trigger("t", "EXECUTE FUNCTION tsvector_update_trigger('fts_a', 'english', 'title')"),
            "b" to trigger("t", "EXECUTE FUNCTION tsvector_update_trigger('fts_b', 'english', 'body')"),
        )
        val out = PostgresFullTextIndexSynthesis.enrich(mapOf("t" to table), triggers)
        val indices = out.getValue("t").indices.associateBy { it.name }
        indices.getValue("idx_a").fullTextVectorColumn shouldBe "fts_a"
        indices.getValue("idx_a").columns shouldBe listOf(IndexColumn("title"))
        indices.getValue("idx_b").fullTextVectorColumn shouldBe "fts_b"
        indices.getValue("idx_b").columns shouldBe listOf(IndexColumn("body"))
    }

    test("no trigger leaves the table unchanged") {
        val out = PostgresFullTextIndexSynthesis.enrich(mapOf("film" to filmTable), emptyMap())
        out.getValue("film") shouldBe filmTable
    }

    test("a trigger whose tsvector column is not a FullText column leaves the table unchanged") {
        val table = filmTable.copy(
            columns = filmTable.columns + ("fulltext" to ColumnDefinition(type = NeutralType.Text())),
        )
        val out = PostgresFullTextIndexSynthesis.enrich(mapOf("film" to table), mapOf("t" to filmTrigger))
        out.getValue("film") shouldBe table
    }

    test("a tsvector trigger without a backing GiST/GIN index leaves the table unchanged") {
        val table = filmTable.copy(
            indices = listOf(IndexDefinition(name = "idx_title", columns = listOf(IndexColumn("title")))),
        )
        val out = PostgresFullTextIndexSynthesis.enrich(mapOf("film" to table), mapOf("t" to filmTrigger))
        out.getValue("film") shouldBe table
    }
})
