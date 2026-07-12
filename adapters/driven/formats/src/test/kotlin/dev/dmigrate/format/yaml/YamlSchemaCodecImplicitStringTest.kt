package dev.dmigrate.format.yaml

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream

/**
 * LN-046 / `docs/planning/done/yaml-codec-arbitrary-string-roundtrip.md`:
 * YAML-1.1-implicit-typ-artige String-Werte (Zahl/Bool/Null/Timestamp) müssen
 * verlustfrei round-trippen. Ohne den [YamlImplicitAwareQuotingChecker] schreibt
 * `MINIMIZE_QUOTES` sie unquotiert und die Lese-Seite deutet sie um.
 */
class YamlSchemaCodecImplicitStringTest : FunSpec({

    val codec = YamlSchemaCodec()

    fun roundTrip(schema: SchemaDefinition): SchemaDefinition {
        val out = ByteArrayOutputStream()
        codec.write(out, schema)
        return codec.read(out.toByteArray().inputStream())
    }

    fun enumSchema(values: List<String>) = SchemaDefinition(
        name = "s", version = "1",
        tables = mapOf(
            "t" to TableDefinition(columns = mapOf("c" to ColumnDefinition(NeutralType.Enum(values = values)))),
        ),
    )

    test("YAML-implicit-typ-artige Enum-Werte round-trippen verlustfrei als String") {
        val ambiguous = listOf(
            "4.", "9_", "1_000", "1.2e3", "0x1f", "0o17", // Zahl-artig
            "yes", "no", "on", "off", "true", "false", "y", "n", // Bool-artig (YAML 1.1)
            "~", "null", "Null", // Null-artig
            "2024-01-01", "2024-01-01t12:00:00z", // Timestamp-artig
            "active", "pending", "shipped", // harmlos (Kontrolle)
        )
        val reparsed = roundTrip(enumSchema(ambiguous))
        (reparsed.tables["t"]!!.columns["c"]!!.type as NeutralType.Enum).values shouldBe ambiguous
    }

    test("number-artiger String-Default round-trippt als String") {
        val schema = SchemaDefinition(
            name = "s", version = "1",
            tables = mapOf(
                "t" to TableDefinition(
                    columns = mapOf(
                        "c" to ColumnDefinition(NeutralType.Text(), default = DefaultValue.StringLiteral("007")),
                    ),
                ),
            ),
        )
        val reparsed = roundTrip(schema)
        (reparsed.tables["t"]!!.columns["c"]!!.default as DefaultValue.StringLiteral).value shouldBe "007"
    }

    test("harmlose Strings bleiben unquotiert — MINIMIZE_QUOTES bleibt wirksam") {
        val out = ByteArrayOutputStream()
        codec.write(
            out,
            SchemaDefinition(
                name = "s", version = "1",
                tables = mapOf("t" to TableDefinition(columns = mapOf("c" to ColumnDefinition(NeutralType.Text())))),
            ),
        )
        val yaml = String(out.toByteArray())
        yaml shouldContain "type: text"
        yaml shouldContain "name: s"
    }
})
