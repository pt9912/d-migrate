package dev.dmigrate.core.validation

import dev.dmigrate.core.model.*
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Die Prueflogik einer Spalte vom Typ einer Domain (`type: enum` + `ref_type` auf `kind: domain`): Default,
 * Fremdschluessel und Identity gegen den Basistyp der Domain. Eine Enum bleibt wie bisher eine Textfamilie.
 */
class SchemaColumnDomainValidationTest : FunSpec({

    fun domain(base: String, precision: Int? = null, scale: Int? = null) =
        CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = base, precision = precision, scale = scale)

    val enumType = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a", "b"))
    val ref = NeutralType.Enum(refType = "dd")

    fun codes(
        column: ColumnDefinition,
        customTypes: Map<String, CustomTypeDefinition>,
        tables: Map<String, TableDefinition> = emptyMap(),
    ): List<String> {
        val schema = SchemaDefinition(name = "T", version = "1", tables = tables, customTypes = customTypes)
        return SchemaColumnValidationRules.validate(
            "tables.t.columns.d", column, TableDefinition(columns = mapOf("d" to column)), schema,
        ).map { it.code }
    }

    test("Default einer Domain-Spalte wird gegen den Basistyp geprueft: Zahl, Boolean, Funktion") {
        val cases = listOf(
            Triple(domain("integer"), DefaultValue.NumberLiteral(5), emptyList<String>()),
            Triple(domain("decimal", 10, 2), DefaultValue.NumberLiteral(1.5), emptyList()),
            Triple(domain("boolean"), DefaultValue.BooleanLiteral(true), emptyList()),
            Triple(domain("text"), DefaultValue.StringLiteral("x"), emptyList()),
            Triple(domain("uuid"), DefaultValue.FunctionCall("gen_uuid"), emptyList()),
            Triple(domain("timestamptz"), DefaultValue.FunctionCall("current_timestamp"), emptyList()),
            Triple(domain("date"), DefaultValue.FunctionCall("current_date"), emptyList()),
            // Gegenprobe: was am Basistyp scheitert, scheitert an der Domain-Spalte.
            Triple(domain("integer"), DefaultValue.StringLiteral("x"), listOf("E009")),
            Triple(domain("integer"), DefaultValue.BooleanLiteral(true), listOf("E009")),
            Triple(domain("text"), DefaultValue.NumberLiteral(5), listOf("E009")),
            Triple(domain("integer"), DefaultValue.FunctionCall("gen_uuid"), listOf("E009")),
        )
        for ((def, default, expected) in cases) {
            withClue("${def.baseType} / $default") {
                codes(ColumnDefinition(ref, default = default), mapOf("dd" to def)) shouldBe expected
            }
        }
    }

    test("eine Enum bleibt eine Textfamilie: Zahl scheitert wie bisher, String besteht") {
        val column = { default: DefaultValue -> ColumnDefinition(ref, default = default) }
        codes(column(DefaultValue.NumberLiteral(5)), mapOf("dd" to enumType)) shouldBe listOf("E009")
        codes(column(DefaultValue.StringLiteral("a")), mapOf("dd" to enumType)) shouldBe emptyList()
    }

    test("Fremdschluessel: Domain-Spalte gegen integer-Schluessel und umgekehrt") {
        val customTypes = mapOf("dd" to domain("integer"))
        val intPk = mapOf("p" to TableDefinition(columns = mapOf("id" to ColumnDefinition(NeutralType.Integer))))
        val domainPk = mapOf("p" to TableDefinition(columns = mapOf("id" to ColumnDefinition(ref))))
        val fk = ReferenceDefinition(table = "p", column = "id")

        codes(ColumnDefinition(ref, references = fk), customTypes, intPk) shouldBe emptyList()
        codes(ColumnDefinition(NeutralType.Integer, references = fk), customTypes, domainPk) shouldBe emptyList()
        codes(ColumnDefinition(ref, references = fk), customTypes, domainPk) shouldBe emptyList()
        // bigint-Domain gegen integer: wie integer gegen biginteger kompatibel; Text-Domain nicht.
        codes(ColumnDefinition(ref, references = fk), mapOf("dd" to domain("text")), intPk) shouldBe listOf("E017")
        codes(ColumnDefinition(NeutralType.Text(), references = fk), customTypes, domainPk) shouldBe listOf("E017")
    }

    test("Identity an einer Domain-Spalte bleibt E130") {
        val column = ColumnDefinition(ref, generation = ColumnGeneration.Identity(mode = IdentityMode.BY_DEFAULT))
        codes(column, mapOf("dd" to domain("integer"))) shouldBe listOf("E130")
    }

    test("Domain ueber einer Domain loest eine Stufe auf: die aeussere bleibt unaufgeloest (Rueckfall Textfamilie)") {
        val customTypes = mapOf("d1" to domain("integer"), "d2" to domain("d1"))
        val outer = NeutralType.Enum(refType = "d2")
        codes(ColumnDefinition(outer, default = DefaultValue.StringLiteral("x")), customTypes) shouldBe emptyList()
        codes(ColumnDefinition(outer, default = DefaultValue.NumberLiteral(5)), customTypes) shouldBe listOf("E009")
        // Die innere Domain, direkt verwendet, loest auf.
        codes(
            ColumnDefinition(NeutralType.Enum(refType = "d1"), default = DefaultValue.NumberLiteral(5)), customTypes,
        ) shouldBe emptyList()
    }

    test("unbekannte Domain: E007, sonst keine Folgefehler") {
        codes(ColumnDefinition(ref, default = DefaultValue.StringLiteral("x")), emptyMap()) shouldBe listOf("E007")
    }

    test("Array-Domain loest auf den Array-Typ auf") {
        codes(ColumnDefinition(ref), mapOf("dd" to domain("integer[]"))).shouldBeEmpty()
    }
})
