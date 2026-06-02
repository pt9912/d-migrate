package dev.dmigrate.format.yaml

import com.fasterxml.jackson.databind.JsonMappingException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayInputStream

/**
 * E.2 Sub-Slice A.1: pins that the schema YAML codec rejects duplicate
 * map keys instead of silently dropping the first occurrence. This is
 * the file-side half of the trigger-name-collision contract — the
 * other half is the `TriggerNameCollisionDetector` on raw reader
 * output (see hexagon-core tests).
 *
 * Without `DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY` Jackson
 * would silently overwrite the first `audit_log:` entry with the
 * second one and we would migrate against a phantom trigger.
 */
class YamlSchemaCodecDuplicateKeyTest : FunSpec({

    val codec = YamlSchemaCodec()

    test("duplicate trigger map key throws JsonMappingException") {
        val yaml = """
            name: Duplicate Trigger Schema
            version: 1.0.0
            tables:
              orders:
                columns:
                  id:
                    type: INT
                    nullable: false
              customers:
                columns:
                  id:
                    type: INT
                    nullable: false
            triggers:
              audit_log:
                table: orders
                event: INSERT
                timing: BEFORE
                body: audit_orders()
              audit_log:
                table: customers
                event: INSERT
                timing: BEFORE
                body: audit_customers()
        """.trimIndent()

        val ex = shouldThrow<JsonMappingException> {
            codec.read(ByteArrayInputStream(yaml.toByteArray(Charsets.UTF_8)))
        }
        ex.message!! shouldContain "audit_log"
    }

    test("duplicate table map key throws JsonMappingException") {
        // Same strict-mode contract for tables — covers the broader
        // map-key uniqueness invariant on which the trigger detector
        // depends.
        val yaml = """
            name: Duplicate Table Schema
            version: 1.0.0
            tables:
              orders:
                columns:
                  id:
                    type: INT
                    nullable: false
              orders:
                columns:
                  id:
                    type: BIGINT
                    nullable: false
        """.trimIndent()

        shouldThrow<JsonMappingException> {
            codec.read(ByteArrayInputStream(yaml.toByteArray(Charsets.UTF_8)))
        }
    }
})
