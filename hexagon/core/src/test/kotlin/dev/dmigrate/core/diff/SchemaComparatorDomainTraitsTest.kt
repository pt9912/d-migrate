package dev.dmigrate.core.diff

import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.SchemaDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/** `DEFAULT`, `NOT NULL` und Kollation einer Domain im Vergleich (Issue #59). */
class SchemaComparatorDomainTraitsTest : FunSpec({

    fun domain(
        default: DefaultValue? = null,
        notNull: Boolean = false,
        collation: String? = null,
    ) = CustomTypeDefinition(
        kind = CustomTypeKind.DOMAIN, baseType = "text", check = "VALUE <> ''",
        default = default, notNull = notNull, collation = collation,
    )

    fun schema(type: CustomTypeDefinition) =
        SchemaDefinition(name = "S", version = "1", customTypes = mapOf("d" to type))

    fun changed(left: CustomTypeDefinition, right: CustomTypeDefinition) =
        SchemaComparator().compare(schema(left), schema(right)).customTypesChanged

    test("eine Domain ohne die Felder ist gleich sich selbst") {
        changed(domain(), domain()).shouldBeEmpty()
    }

    test("ein anderer, hinzugekommener oder entfernter Default ist eine Aenderung") {
        val a = domain(default = DefaultValue.StringLiteral("x"))
        val b = domain(default = DefaultValue.StringLiteral("y"))
        changed(a, b).single().default.shouldNotBeNull()
        changed(domain(), a).single().default.shouldNotBeNull()
        changed(a, domain()).single().default.shouldNotBeNull()
        changed(a, a).shouldBeEmpty()
    }

    test("Zahlen-Defaults gelten numerisch gleich, wie an einer Spalte") {
        changed(
            domain(default = DefaultValue.NumberLiteral(5)),
            domain(default = DefaultValue.NumberLiteral(5L)),
        ).shouldBeEmpty()
        changed(
            domain(default = DefaultValue.NumberLiteral(2.0)),
            domain(default = DefaultValue.NumberLiteral(2)),
        ).shouldBeEmpty()
    }

    test("NOT NULL der Domain ist eine Aenderung — in beiden Richtungen") {
        val diffs = changed(domain(notNull = false), domain(notNull = true))
        diffs shouldHaveSize 1
        diffs.single().notNull?.after shouldBe true
        changed(domain(notNull = true), domain(notNull = false)).single().notNull?.after shouldBe false
    }

    test("die Kollation zaehlt ohne Anfuehrungszeichen, aber mit Gross-/Kleinschreibung") {
        changed(domain(collation = "C"), domain(collation = "\"C\"")).shouldBeEmpty()
        changed(domain(collation = null), domain(collation = " ")).shouldBeEmpty()
        changed(domain(collation = "C"), domain(collation = "c")).single().collation.shouldNotBeNull()
        changed(domain(), domain(collation = "C")).single().collation.shouldNotBeNull()
    }

    test("ein Punkt gehoert zum Kollationsnamen, eine gequotete Qualifizierung bleibt erhalten") {
        changed(domain(collation = "en_US.utf8"), domain(collation = "\"en_US.utf8\"")).shouldBeEmpty()
        changed(domain(collation = "en_US.utf8"), domain(collation = "en_US")).single().collation.shouldNotBeNull()
        changed(domain(collation = "\"app\".\"ci\""), domain(collation = "\"app\".\"ci\"")).shouldBeEmpty()
        changed(domain(collation = "\"app\".\"ci\""), domain(collation = "app.ci")).single().collation.shouldNotBeNull()
    }

    test("eine Aenderung nur an den drei Feldern laesst die uebrigen Unterschiede leer") {
        val diff = changed(domain(), domain(notNull = true)).single()
        diff.check.shouldBeNull()
        diff.baseType.shouldBeNull()
        diff.default.shouldBeNull()
        diff.collation.shouldBeNull()
    }

    test("ein Ziel ohne Domain (comparesDomainTraits = false) sieht die drei Felder nicht") {
        val left = domain()
        val right = domain(default = DefaultValue.StringLiteral("x"), notNull = true, collation = "C")
        val projection = TargetProjection(type = { it }, comparesDomainTraits = false)
        SchemaComparator(projection).compare(schema(left), schema(right)).customTypesChanged.shouldBeEmpty()
        // Der strikte Vergleich und ein Ziel mit Domain zeigen sie.
        SchemaComparator().compare(schema(left), schema(right)).customTypesChanged shouldHaveSize 1
        SchemaComparator(TargetProjection(type = { it })).compare(schema(left), schema(right))
            .customTypesChanged shouldHaveSize 1
        // Ein anderer Unterschied bleibt auch ohne die drei Felder sichtbar.
        SchemaComparator(projection).compare(schema(left), schema(right.copy(check = "VALUE <> 'y'")))
            .customTypesChanged shouldHaveSize 1
    }
})
