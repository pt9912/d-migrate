package dev.dmigrate.format

import com.fasterxml.jackson.databind.ObjectMapper
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * 0.9.7 preserve-current-value Sub-Slice A: pins the YAML
 * read/write contract for `SequenceDefinition.preserveCurrentValue`.
 *
 * The format-side codec is the only path through which user-written
 * schemas reach the new field — without it the planner's
 * `preserveCurrentValue == true` branch is dead code. Three
 * round-trip directions are pinned:
 *
 * - **Default-on-missing**: a YAML sequence with no
 *   `preserve_current_value` key parses to `preserveCurrentValue =
 *   false` (the default).
 * - **Explicit true**: `preserve_current_value: true` parses to
 *   `preserveCurrentValue = true` and the builder emits the key.
 * - **Explicit false elided on write**: when the operator
 *   re-serialises a `SequenceDefinition` whose field is `false`,
 *   the builder omits the key (matches the existing `cycle: false`
 *   elision convention from §3.1).
 */
class SchemaNodeSequencePreserveCurrentValueRoundtripTest : FunSpec({

    val mapper = ObjectMapper()

    test("missing preserve_current_value parses to default false") {
        val root = mapper.readTree(
            """
            {
              "schema_format": "1.1",
              "name": "demo",
              "version": "1",
              "sequences": {
                "order_seq": { "start": 1, "increment": 1 }
              }
            }
            """.trimIndent(),
        )
        val schema = SchemaNodeParser.parse(root)
        schema.sequences["order_seq"]?.preserveCurrentValue shouldBe false
    }

    test("explicit preserve_current_value: true parses to true and re-serialises with the key") {
        val root = mapper.readTree(
            """
            {
              "schema_format": "1.1",
              "name": "demo",
              "version": "1",
              "sequences": {
                "order_seq": { "start": 1, "preserve_current_value": true }
              }
            }
            """.trimIndent(),
        )
        val parsed = SchemaNodeParser.parse(root)
        parsed.sequences["order_seq"]?.preserveCurrentValue shouldBe true

        val rebuilt = SchemaNodeBuilder.build(mapper, parsed)
        val sequenceNode = rebuilt["sequences"]["order_seq"]
        sequenceNode["preserve_current_value"] shouldNotBe null
        sequenceNode["preserve_current_value"].asBoolean() shouldBe true
    }

    test("preserve_current_value: false is elided on write — matches the cycle/false convention") {
        val schema = SchemaDefinition(
            name = "demo",
            version = "1",
            sequences = mapOf(
                "order_seq" to SequenceDefinition(start = 1, preserveCurrentValue = false),
            ),
        )
        val root = SchemaNodeBuilder.build(mapper, schema)
        val sequenceNode = root["sequences"]["order_seq"]
        sequenceNode["preserve_current_value"] shouldBe null
    }

    test("explicit preserve_current_value: false in input parses to false and re-serialises elided") {
        // Operator-supplied explicit `false` should not round-trip
        // as a noisy `preserve_current_value: false` line — the
        // builder elides it just like the read-default does. This
        // pins the "schema files stay terse" contract callers rely
        // on for diff-clean dumps.
        val root = mapper.readTree(
            """
            {
              "schema_format": "1.1",
              "name": "demo",
              "version": "1",
              "sequences": {
                "order_seq": { "start": 1, "preserve_current_value": false }
              }
            }
            """.trimIndent(),
        )
        val parsed = SchemaNodeParser.parse(root)
        parsed.sequences["order_seq"]?.preserveCurrentValue shouldBe false

        val rebuilt = SchemaNodeBuilder.build(mapper, parsed)
        rebuilt["sequences"]["order_seq"]["preserve_current_value"] shouldBe null
    }
})
