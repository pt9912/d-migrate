package dev.dmigrate.format.yaml

import dev.dmigrate.core.model.NeutralType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Nebenbefund aus Issue #81: ein ungequoteter Skalar in einem **Textfeld**, den der YAML-1.1-Resolver
 * verlustbehaftet typisiert, aenderte den Wert still (`values: [OFF, ON]` → `'false', 'true'`). Jetzt
 * bricht das Lesen mit Pfad und Abhilfe ab. Verlustfreie Skalare und typisierte Felder bleiben, wie sie sind.
 */
class YamlSchemaCodecWrittenScalarTest : FunSpec({

    val codec = YamlSchemaCodec()

    fun schema(values: String, version: String = "\"1.0\"") = """
        schema_format: "1.0"
        name: "repro"
        version: $version
        tables:
          t:
            columns:
              id: { type: integer, required: true }
              a_state: { type: enum, values: $values }
            primary_key: [id]
    """.trimIndent()

    fun read(yaml: String) = codec.read(yaml.byteInputStream())

    fun enumValues(yaml: String): List<String> =
        (read(yaml).tables.getValue("t").columns.getValue("a_state").type as NeutralType.Enum).values.orEmpty()

    val lossy = listOf(
        "[OFF, ON]" to "OFF",
        "[yes, no]" to "yes",
        "[True]" to "True",
        "[010]" to "010",
        "[0x1F]" to "0x1F",
        "[1_000]" to "1_000",
        "[1.50]" to "1.50",
        "[1e3]" to "1e3",
        // Jenseits von Int: als Long bzw. BigInteger gelesen, hexadezimal geschrieben.
        "[0x7FFFFFFFFF]" to "0x7FFFFFFFFF",
        "[0x1FFFFFFFFFFFFFFFFFF]" to "0x1FFFFFFFFFFFFFFFFFF",
        "[a, ~]" to "~",
        "[a, null]" to "null",
    )
    for ((values, written) in lossy) {
        test("verlustbehaftet: values: $values bricht mit Pfad und Abhilfe ab") {
            val e = shouldThrow<IllegalArgumentException> { read(schema(values)) }
            withClue(e.message) {
                e.message.orEmpty() shouldContain "tables.t.columns.a_state.values["
                e.message.orEmpty() shouldContain "`$written`"
                e.message.orEmpty() shouldContain "Anfuehrungszeichen"
            }
        }
    }

    test("die Datei aus #81 ergibt nie 'false', 'true': gequotet kommt der Text an") {
        enumValues(schema("[\"OFF\", \"ON\"]")) shouldBe listOf("OFF", "ON")
    }

    test("verlustfreie Zahlen bleiben erlaubt: values: [1, 2, 10, 99999999999]") {
        enumValues(schema("[1, 2, 10, 99999999999]")) shouldBe listOf("1", "2", "10", "99999999999")
    }

    test("ein Dokument, das nur ein Skalar ist, scheitert wie bisher, nicht an der Markierung") {
        shouldThrow<Exception> { read("010") }
    }

    test("Text, den YAML nicht typisiert, bleibt unveraendert: Y, n, maybe, 2024-01-01") {
        enumValues(schema("[Y, n, maybe, 2024-01-01]")) shouldBe listOf("Y", "n", "maybe", "2024-01-01")
    }

    test("version: 1.10 wird nicht mehr still zu 1.1") {
        val e = shouldThrow<IllegalArgumentException> { read(schema("[\"a\"]", version = "1.10")) }
        e.message.orEmpty() shouldContain "version"
        e.message.orEmpty() shouldContain "`1.10`"
    }

    test("version: 1.0 ist verlustfrei und bleibt erlaubt") {
        read(schema("[\"a\"]", version = "1.0")).version shouldBe "1.0"
    }

    test("ein typisiertes Feld liest den YAML-1.1-Boolean weiter: required: yes") {
        val yaml = schema("[\"a\"]").replace(
            "      a_state:",
            "      flag: { type: boolean, required: yes }\n      a_state:",
        )
        read(yaml).tables.getValue("t").columns.getValue("flag").required shouldBe true
    }
})
