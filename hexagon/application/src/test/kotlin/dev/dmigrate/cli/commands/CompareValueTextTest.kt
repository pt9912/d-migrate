package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.ReferentialAction
import dev.dmigrate.core.model.RoutineSecurity
import dev.dmigrate.core.model.TableMetadata
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerTiming
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Ein verglichener Wert als Text — in der Schreibweise des Schema-Dokuments,
 * nie als Kotlin-Darstellung (`spec/mcp-server.md`, `details`).
 */
class CompareValueTextTest : FunSpec({

    test("scalars stay as they are, a missing value stays missing") {
        CompareValueText.of(null) shouldBe null
        CompareValueText.of("public") shouldBe "public"
        CompareValueText.of(10L) shouldBe "10"
        CompareValueText.of(true) shouldBe "true"
    }

    test("enum values are written in lower case, as in the document") {
        CompareValueText.of(TriggerTiming.INSTEAD_OF) shouldBe "instead_of"
        CompareValueText.of(RoutineSecurity.DEFINER) shouldBe "definer"
        CompareValueText.of(CustomTypeKind.ENUM) shouldBe "enum"
    }

    test("lists are bracketed; trigger events follow the document order") {
        CompareValueText.of(listOf("app", "public")) shouldBe "[app, public]"
        CompareValueText.of(emptyList<String>()) shouldBe "[]"
        CompareValueText.of(linkedSetOf(TriggerEvent.DELETE, TriggerEvent.INSERT)) shouldBe "[insert, delete]"
        CompareValueText.of(listOf("a", null)) shouldBe "[a, null]"
    }

    test("column types use the short form of the comparison") {
        CompareValueText.of(NeutralType.Identifier(autoIncrement = true)) shouldBe "identifier(auto)"
        CompareValueText.of(NeutralType.Identifier()) shouldBe "identifier"
        CompareValueText.of(NeutralType.Text()) shouldBe "text"
        CompareValueText.of(NeutralType.Text(maxLength = 20)) shouldBe "text(20)"
        CompareValueText.of(NeutralType.Char(3)) shouldBe "char(3)"
        CompareValueText.of(NeutralType.Float(FloatPrecision.SINGLE)) shouldBe "float(single)"
        CompareValueText.of(NeutralType.Decimal(10, 2)) shouldBe "decimal(10,2)"
        CompareValueText.of(NeutralType.DateTime(timezone = true)) shouldBe "datetime(tz)"
        CompareValueText.of(NeutralType.DateTime()) shouldBe "datetime"
        CompareValueText.of(NeutralType.Enum(refType = "citext")) shouldBe "enum(ref:citext)"
        CompareValueText.of(NeutralType.Enum(values = listOf("a", "b"))) shouldBe "enum(a,b)"
        CompareValueText.of(NeutralType.Enum()) shouldBe "enum"
        CompareValueText.of(NeutralType.Array("text")) shouldBe "array(text)"
        CompareValueText.of(NeutralType.Geometry(GeometryType.of("point"), srid = 4326)) shouldBe "geometry(point,4326)"
        CompareValueText.of(NeutralType.Geometry()) shouldBe "geometry(geometry)"
        listOf(
            NeutralType.Integer to "integer", NeutralType.SmallInt to "smallint",
            NeutralType.BigInteger to "biginteger", NeutralType.BooleanType to "boolean",
            NeutralType.Date to "date", NeutralType.Time to "time", NeutralType.Uuid to "uuid",
            NeutralType.Json to "json", NeutralType.Xml to "xml", NeutralType.Binary to "binary",
            NeutralType.Email to "email", NeutralType.FullText to "fulltext",
        ).forEach { (type, text) -> CompareValueText.of(type) shouldBe text }
    }

    test("defaults, references, generations and table metadata") {
        CompareValueText.of(DefaultValue.StringLiteral("x")) shouldBe "\"x\""
        CompareValueText.of(DefaultValue.NumberLiteral(0)) shouldBe "0"
        CompareValueText.of(DefaultValue.BooleanLiteral(false)) shouldBe "false"
        CompareValueText.of(DefaultValue.FunctionCall("current_timestamp")) shouldBe "current_timestamp()"
        CompareValueText.of(DefaultValue.SequenceNextVal("s")) shouldBe "sequence_nextval(s)"
        CompareValueText.of(ReferenceDefinition("orders", "id")) shouldBe "orders.id"
        CompareValueText.of(
            ReferenceDefinition("orders", "id", ReferentialAction.CASCADE, ReferentialAction.NO_ACTION),
        ) shouldBe "orders.id (on_delete=cascade, on_update=no_action)"
        CompareValueText.of(ColumnGeneration.Identity(IdentityMode.ALWAYS)) shouldBe "identity(mode=always)"
        CompareValueText.of(ColumnGeneration.Identity(sequenceName = "s", legacySerialSyntax = true)) shouldBe
            "identity(mode=by_default,sequence=s,legacy_serial_syntax=true)"
        CompareValueText.of(ColumnGeneration.Computed("a * b", stored = true)) shouldBe "computed(a * b, stored)"
        CompareValueText.of(ColumnGeneration.Computed("a * b", stored = false)) shouldBe "computed(a * b)"
        CompareValueText.of(TableMetadata(engine = "InnoDB", withoutRowid = true)) shouldBe
            "engine=InnoDB, without_rowid=true"
        CompareValueText.of(TableMetadata()) shouldBe ""
    }

    test("any other value falls back to its text") {
        CompareValueText.of(StringBuilder("sb")) shouldBe "sb"
    }
})
