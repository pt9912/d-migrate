package dev.dmigrate.format

import com.fasterxml.jackson.databind.ObjectMapper
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * 0.9.7 E.2 Folge-Slice: pin that the trigger attributes the SQLite
 * reverse-read now populates (WHEN clause, INSTEAD OF timing, multi-
 * statement body, STATEMENT-level forEach) survive a `build → parse`
 * roundtrip on [TriggerDefinition]. Closes the verification gap
 * called out in the post-merge review — the plan-doc spec speaks of
 * "Reverse → emit YAML → Reverse → identisch", and this test pins
 * the YAML-codec leg of that contract end-to-end.
 *
 * Companion tests live in:
 * - SqliteTriggerSqlParserTest (parser surface)
 * - SqliteSchemaReaderTest (reader → DDL renderer round-trip)
 * - SqliteTriggerReverseReadIntegrationTest (live-DB → reader → no-op compare)
 */
class SchemaNodeProgrammabilityTriggerRoundtripTest : FunSpec({

    val mapper = ObjectMapper()

    fun roundtrip(schema: SchemaDefinition): SchemaDefinition {
        val node = SchemaNodeBuilder.build(mapper, schema)
        return SchemaNodeParser.parse(node)
    }

    test("INSTEAD OF trigger with WHEN clause and multi-statement body roundtrips") {
        val trg = TriggerDefinition(
            table = "vt",
            event = TriggerEvent.UPDATE,
            timing = TriggerTiming.INSTEAD_OF,
            forEach = TriggerForEach.ROW,
            condition = "NEW.name <> OLD.name",
            body = "UPDATE t SET name = NEW.name WHERE id = OLD.id;\n" +
                "  INSERT INTO log VALUES (NEW.id)",
            sourceDialect = "sqlite",
        )
        val schema = SchemaDefinition(name = "App", version = "1", triggers = mapOf("vt::trg" to trg))
        val parsed = roundtrip(schema).triggers.getValue("vt::trg")
        parsed.timing shouldBe TriggerTiming.INSTEAD_OF
        parsed.events shouldBe setOf(TriggerEvent.UPDATE)
        parsed.forEach shouldBe TriggerForEach.ROW
        parsed.condition shouldBe "NEW.name <> OLD.name"
        parsed.body shouldBe "UPDATE t SET name = NEW.name WHERE id = OLD.id;\n" +
            "  INSERT INTO log VALUES (NEW.id)"
        parsed.sourceDialect shouldBe "sqlite"
    }

    test("AFTER INSERT trigger with body and no WHEN roundtrips condition as null") {
        val trg = TriggerDefinition(
            table = "t",
            event = TriggerEvent.INSERT,
            timing = TriggerTiming.AFTER,
            forEach = TriggerForEach.ROW,
            condition = null,
            body = "SELECT 1",
            sourceDialect = "sqlite",
        )
        val schema = SchemaDefinition(name = "App", version = "1", triggers = mapOf("t::trg" to trg))
        val parsed = roundtrip(schema).triggers.getValue("t::trg")
        parsed.condition shouldBe null
        parsed.body shouldBe "SELECT 1"
        parsed.forEach shouldBe TriggerForEach.ROW
    }

    test("STATEMENT-level forEach roundtrips (non-default needs explicit emit)") {
        // The builder skips for_each when it equals the default (ROW)
        // — pin that the STATEMENT case is emitted and parsed back.
        val trg = TriggerDefinition(
            table = "t",
            event = TriggerEvent.UPDATE,
            timing = TriggerTiming.AFTER,
            forEach = TriggerForEach.STATEMENT,
            body = "INSERT INTO audit VALUES (1)",
            sourceDialect = "mysql",
        )
        val schema = SchemaDefinition(name = "App", version = "1", triggers = mapOf("t::trg" to trg))
        val parsed = roundtrip(schema).triggers.getValue("t::trg")
        parsed.forEach shouldBe TriggerForEach.STATEMENT
    }

    test("multi-event trigger serialises events as a canonical-order array and roundtrips (F4)") {
        // Pagila's film_fulltext_trigger: BEFORE INSERT OR UPDATE. Build the
        // set UPDATE-first to prove serialisation pins canonical enum order.
        val trg = TriggerDefinition(
            table = "film",
            events = setOf(TriggerEvent.UPDATE, TriggerEvent.INSERT),
            timing = TriggerTiming.BEFORE,
            forEach = TriggerForEach.ROW,
            body = "EXECUTE FUNCTION film_fulltext_update()",
            sourceDialect = "postgresql",
        )
        val schema = SchemaDefinition(name = "App", version = "1", triggers = mapOf("film::ft" to trg))
        val node = SchemaNodeBuilder.build(mapper, schema)
        val eventNode = node.get("triggers").get("film::ft").get("event")
        eventNode.isArray shouldBe true
        eventNode.map { it.asText() } shouldBe listOf("insert", "update")
        SchemaNodeParser.parse(node).triggers.getValue("film::ft").events shouldBe
            setOf(TriggerEvent.INSERT, TriggerEvent.UPDATE)
    }

    test("single-event trigger keeps the scalar `event` form and roundtrips (F4 back-compat)") {
        // The scalar form keeps single-event output byte-identical to pre-F4
        // and lets existing hand-written schema files parse unchanged.
        val trg = TriggerDefinition(
            table = "t",
            event = TriggerEvent.INSERT,
            timing = TriggerTiming.AFTER,
            body = "SELECT 1",
            sourceDialect = "sqlite",
        )
        val schema = SchemaDefinition(name = "App", version = "1", triggers = mapOf("t::trg" to trg))
        val node = SchemaNodeBuilder.build(mapper, schema)
        val eventNode = node.get("triggers").get("t::trg").get("event")
        eventNode.isTextual shouldBe true
        eventNode.asText() shouldBe "insert"
        SchemaNodeParser.parse(node).triggers.getValue("t::trg").events shouldBe setOf(TriggerEvent.INSERT)
    }
})
