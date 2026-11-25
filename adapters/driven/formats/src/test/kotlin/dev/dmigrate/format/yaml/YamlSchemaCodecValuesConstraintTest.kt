package dev.dmigrate.format.yaml

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.format.json.JsonSchemaCodec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream

/**
 * ADR 0069: `values_constraint` neben `type: enum` liest und schreibt der Codec, in YAML und JSON, und ein Schema ohne
 * das Feld schreibt es nicht.
 */
class YamlSchemaCodecValuesConstraintTest : FunSpec({

    fun yaml(extra: String) = """
        schema_format: "1.0"
        name: "repro"
        version: "1.0"
        tables:
          t:
            columns:
              id: { type: integer, required: true }
              a_state: { type: enum, values: ["ACTIVE", "DISABLED"]$extra }
            primary_key: [id]
    """.trimIndent()

    fun stateType(text: String) =
        YamlSchemaCodec().read(text.byteInputStream()).tables.getValue("t").columns.getValue("a_state").type

    test("lesen: values_constraint landet am Enum-Typ") {
        stateType(yaml(", values_constraint: ck_a_state")) shouldBe
            NeutralType.Enum(values = listOf("ACTIVE", "DISABLED"), valuesConstraintName = "ck_a_state")
    }

    test("lesen ohne Feld: kein Name") {
        stateType(yaml("")) shouldBe NeutralType.Enum(values = listOf("ACTIVE", "DISABLED"))
    }

    test("YAML-Rundlauf: schreiben und wieder lesen ergibt dasselbe Schema, der Name steht in der Datei") {
        val codec = YamlSchemaCodec()
        val schema = codec.read(yaml(", values_constraint: ck_a_state").byteInputStream())
        val out = ByteArrayOutputStream().also { codec.write(it, schema) }

        out.toString(Charsets.UTF_8) shouldContain "values_constraint: ck_a_state"
        codec.read(out.toByteArray().inputStream()) shouldBe schema
    }

    test("ein Schema ohne Namen schreibt kein values_constraint") {
        val codec = YamlSchemaCodec()
        val out = ByteArrayOutputStream().also { codec.write(it, codec.read(yaml("").byteInputStream())) }

        (out.toString(Charsets.UTF_8).contains("values_constraint")) shouldBe false
    }

    test("JSON-Rundlauf") {
        val codec = JsonSchemaCodec()
        val schema = YamlSchemaCodec().read(yaml(", values_constraint: ck_a_state").byteInputStream())
        val out = ByteArrayOutputStream().also { codec.write(it, schema) }

        out.toString(Charsets.UTF_8) shouldContain "\"values_constraint\""
        codec.read(out.toByteArray().inputStream()) shouldBe schema
    }

    test("ein Name, den YAML als Wahrheitswert liest, bricht ab statt still zu aendern (values_constraint: on)") {
        val e = runCatching { stateType(yaml(", values_constraint: on")) }.exceptionOrNull()

        (e?.message ?: "") shouldContain "values_constraint"
    }
})
