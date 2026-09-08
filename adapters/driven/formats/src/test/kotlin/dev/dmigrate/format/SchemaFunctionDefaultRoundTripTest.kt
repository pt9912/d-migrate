package dev.dmigrate.format

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.format.yaml.YamlSchemaCodec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Ein Funktions-Default muss den Weg durch die Schemadatei ueberleben.
 *
 * Der Leser kennt als **Skalar** nur vier Namen; alles andere wurde zur
 * Zeichenkette. Ein reverse-gelesenes `newid()` kam damit als Text zurueck,
 * und der Generator schrieb es gequotet — aus einem Funktionsaufruf wurde
 * ein Text, der zufaellig wie einer aussieht.
 *
 * Raten hilft dort nicht: `default: "newid()"` waere von einem Text-Default
 * `'newid()'` nicht zu unterscheiden. Der Schreiber benutzt deshalb die
 * **Objektform**, die eindeutig ist.
 */
class SchemaFunctionDefaultRoundTripTest : FunSpec({

    val codec = YamlSchemaCodec()

    fun schemaWith(default: DefaultValue) = SchemaDefinition(
        name = "S", version = "1",
        tables = mapOf(
            "t" to TableDefinition(
                columns = mapOf("c" to ColumnDefinition(NeutralType.Text(maxLength = 40), default = default)),
            ),
        ),
    )

    fun render(default: DefaultValue): String =
        java.io.ByteArrayOutputStream().also { codec.write(it, schemaWith(default)) }.toString(Charsets.UTF_8)

    fun roundTrip(default: DefaultValue): DefaultValue? =
        codec.read(render(default).byteInputStream())
            .tables.getValue("t").columns.getValue("c").default

    test("a foreign function default keeps its function nature") {
        val call = DefaultValue.FunctionCall("newid()")
        roundTrip(call) shouldBe call
    }

    test("an expression-shaped default survives too") {
        val call = DefaultValue.FunctionCall("now() - interval '1 day'")
        roundTrip(call) shouldBe call
    }

    test("the four neutral names stay a friendly scalar") {
        // Sie sind als Skalar eindeutig -- die Objektform waere hier nur
        // laestig, und jede handgeschriebene Datei benutzt den Skalar.
        val yaml = render(DefaultValue.FunctionCall("current_timestamp"))
        yaml shouldContain "default: current_timestamp"
        roundTrip(DefaultValue.FunctionCall("current_timestamp")) shouldBe
            DefaultValue.FunctionCall("current_timestamp")
    }

    test("a text default that looks like a call stays text") {
        // Genau der Grund, warum der skalare Weg nicht raten darf.
        val literal = DefaultValue.StringLiteral("newid()")
        roundTrip(literal).shouldBeInstanceOf<DefaultValue.StringLiteral>()
        roundTrip(literal) shouldBe literal
    }

    test("the object form is what the writer emits for a foreign call") {
        val yaml = render(DefaultValue.FunctionCall("newid()"))
        yaml shouldContain "function:"
        yaml shouldContain "newid()"
    }
})
