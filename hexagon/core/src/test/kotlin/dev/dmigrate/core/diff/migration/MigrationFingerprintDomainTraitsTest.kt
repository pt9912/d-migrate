package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.SchemaDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** `DEFAULT`, `NOT NULL` und Kollation einer Domain im Abdruck und in der Operations-Nutzlast (Issue #59). */
class MigrationFingerprintDomainTraitsTest : FunSpec({

    fun domain(default: DefaultValue? = null, notNull: Boolean = false, collation: String? = null) =
        CustomTypeDefinition(
            kind = CustomTypeKind.DOMAIN, baseType = "text", check = "VALUE <> ''",
            default = default, notNull = notNull, collation = collation,
        )

    fun schema(type: CustomTypeDefinition) =
        SchemaDefinition(name = "S", version = "1", customTypes = mapOf("d" to type))

    fun fp(type: CustomTypeDefinition) = MigrationFingerprint.compute(schema(type), projectsCustomTypes = true)

    test("jedes der drei Felder aendert den Abdruck") {
        val plain = fp(domain())
        fp(domain(default = DefaultValue.StringLiteral("x"))) shouldNotBe plain
        fp(domain(notNull = true)) shouldNotBe plain
        fp(domain(collation = "C")) shouldNotBe plain
        fp(domain(default = DefaultValue.StringLiteral("x"))) shouldNotBe fp(domain(default = DefaultValue.StringLiteral("y")))
    }

    test("der Abdruck folgt dem Vergleich: Zahlen numerisch, Kollation ohne Anfuehrungszeichen") {
        fp(domain(default = DefaultValue.NumberLiteral(5))) shouldBe fp(domain(default = DefaultValue.NumberLiteral(5L)))
        fp(domain(default = DefaultValue.NumberLiteral(2.0))) shouldBe fp(domain(default = DefaultValue.NumberLiteral(2)))
        fp(domain(collation = "C")) shouldBe fp(domain(collation = "\"C\""))
    }

    test("ein Typ ohne die Felder tragen keine Segmente — Abdruck und Operations-Nutzlast") {
        val projected = MigrationFingerprint.project(schema(domain()), projectsCustomTypes = true)
        projected shouldNotContain "default="
        projected shouldNotContain "not_null"
        projected shouldNotContain "collation="
        CanonicalPayload.customType(domain()) shouldNotContain "not_null"
        CanonicalPayload.customType(domain()) shouldNotContain "collation"
    }

    test("die Operations-Nutzlast traegt gesetzte Felder") {
        val payload = CanonicalPayload.customType(
            domain(default = DefaultValue.StringLiteral("x"), notNull = true, collation = "C"),
        )
        payload shouldContain "default=str:x"
        payload shouldContain "not_null=true"
        payload shouldContain "collation=C"
    }

    test("ohne projectsCustomTypes (Dialekt ohne benannte Typen) sieht der Abdruck die Felder nicht") {
        MigrationFingerprint.compute(schema(domain(notNull = true, collation = "C")), projectsCustomTypes = false) shouldBe
            MigrationFingerprint.compute(schema(domain()), projectsCustomTypes = false)
    }
})
