package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Ein benannter Typ in Datei-Form (Parser: `fields` leer, kein `ordinal`) und in
 * Reader-Form (`fields` fehlend, `ordinal` gesetzt) ist derselbe Typ (Issue #51).
 */
class SchemaComparatorCustomTypeFormTest : FunSpec({

    fun schema(type: CustomTypeDefinition) =
        SchemaDefinition(name = "S", version = "1", customTypes = mapOf("ee" to type))

    val enumFile = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a", "b"), fields = emptyMap())
    val enumRead = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a", "b"))

    fun composite(ordinals: Boolean, type: NeutralType = NeutralType.Integer) = CustomTypeDefinition(
        kind = CustomTypeKind.COMPOSITE,
        fields = linkedMapOf(
            "x" to ColumnDefinition(type, ordinal = if (ordinals) 1 else null),
            "y" to ColumnDefinition(NeutralType.Integer, ordinal = if (ordinals) 2 else null),
        ),
    )

    test("fehlende und leere fields sind gleich — in beiden Richtungen") {
        SchemaComparator().compare(schema(enumRead), schema(enumFile)).customTypesChanged.shouldBeEmpty()
        SchemaComparator().compare(schema(enumFile), schema(enumRead)).customTypesChanged.shouldBeEmpty()
    }

    test("leere gegen gefuellte fields sind verschieden — in beiden Richtungen") {
        val withFields = composite(ordinals = false)
        val empty = withFields.copy(fields = emptyMap())
        SchemaComparator().compare(schema(empty), schema(withFields)).customTypesChanged shouldHaveSize 1
        SchemaComparator().compare(schema(withFields), schema(empty)).customTypesChanged shouldHaveSize 1
        SchemaComparator().compare(schema(withFields.copy(fields = null)), schema(withFields))
            .customTypesChanged shouldHaveSize 1
    }

    test("die Position eines Composite-Feldes zaehlt nicht, ein anderer Feldtyp schon") {
        SchemaComparator().compare(schema(composite(true)), schema(composite(false))).customTypesChanged.shouldBeEmpty()
        SchemaComparator().compare(schema(composite(true)), schema(composite(false, NeutralType.BigInteger)))
            .customTypesChanged shouldHaveSize 1
    }

    test("vertauschte Positionen sind eine Aenderung, fehlende nicht") {
        fun swapped(a: Int, b: Int) = composite(ordinals = false).copy(
            fields = linkedMapOf(
                "x" to ColumnDefinition(NeutralType.Integer, ordinal = a),
                "y" to ColumnDefinition(NeutralType.Integer, ordinal = b),
            ),
        )
        SchemaComparator().compare(schema(swapped(1, 2)), schema(swapped(2, 1))).customTypesChanged shouldHaveSize 1
        SchemaComparator().compare(schema(swapped(1, 2)), schema(swapped(1, 2))).customTypesChanged.shouldBeEmpty()
    }

    test("required und unique an einem Composite-Feld zaehlen nicht, ein Feld mehr schon") {
        val plain = composite(ordinals = true)
        val decorated = plain.copy(
            fields = plain.fields!!.mapValues { (_, f) -> f.copy(required = true, unique = true, ordinal = null) },
        )
        SchemaComparator().compare(schema(plain), schema(decorated)).customTypesChanged.shouldBeEmpty()
        val extra = decorated.copy(fields = decorated.fields!! + ("z" to ColumnDefinition(NeutralType.Integer)))
        SchemaComparator().compare(schema(plain), schema(extra)).customTypesChanged shouldHaveSize 1
    }

    test("die gemeldete Aenderung traegt die unveraenderten Felder (mit ordinal)") {
        val diff = SchemaComparator().compare(schema(composite(true)), schema(composite(false, NeutralType.BigInteger)))
        diff.customTypesChanged.single().fields?.before?.get("x")?.ordinal shouldBe 1
    }

    test("eine geaenderte Werteliste bleibt eine Aenderung") {
        SchemaComparator().compare(schema(enumRead), schema(enumFile.copy(values = listOf("a", "b", "c"))))
            .customTypesChanged shouldHaveSize 1
        SchemaComparator().compare(schema(enumRead), schema(enumFile.copy(values = listOf("b", "a"))))
            .customTypesChanged shouldHaveSize 1
    }

    test("die Beschreibung eines Typs: strikt eine Aenderung, gegen ein Ziel keine") {
        val described = enumFile.copy(description = "Zustand")
        SchemaComparator().compare(schema(enumRead), schema(described)).customTypesChanged shouldHaveSize 1
        SchemaComparator(TargetProjection(type = { it })).compare(schema(enumRead), schema(described))
            .customTypesChanged.shouldBeEmpty()
        // Eine Beschreibung speichert kein Reader: der Vergleich gegen ein Ziel (auch ein file:-Ziel)
        // meldet sie nicht.
    }
})
