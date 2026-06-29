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

    test("a null body yields no parse") {
        PostgresFullTextIndexSynthesis.parseTrigger(trigger("doc", null)) shouldBe null
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

    test("replaces the GiST-over-tsvector index with a FULLTEXT index over the source columns") {
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
        )
        // unrelated index untouched
        indices.single { it.name == "idx_title" }.type shouldBe IndexType.BTREE
    }

    test("no trigger leaves the table unchanged") {
        val out = PostgresFullTextIndexSynthesis.enrich(mapOf("film" to filmTable), emptyMap())
        out.getValue("film") shouldBe filmTable
    }

    test("a trigger whose tsvector column is not a FullText column leaves the table unchanged") {
        val table = filmTable.copy(
            columns = filmTable.columns + ("fulltext" to ColumnDefinition(type = NeutralType.Text())),
        )
        val out = PostgresFullTextIndexSynthesis.enrich(
            mapOf("film" to table),
            mapOf("t" to filmTrigger),
        )
        out.getValue("film") shouldBe table
    }

    test("a tsvector trigger without a backing GiST index leaves the table unchanged") {
        val table = filmTable.copy(
            indices = listOf(IndexDefinition(name = "idx_title", columns = listOf(IndexColumn("title")))),
        )
        val out = PostgresFullTextIndexSynthesis.enrich(
            mapOf("film" to table),
            mapOf("t" to filmTrigger),
        )
        out.getValue("film") shouldBe table
    }
})
