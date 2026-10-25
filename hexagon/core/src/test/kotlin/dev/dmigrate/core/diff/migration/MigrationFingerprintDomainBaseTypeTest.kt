package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.SchemaDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/** Laenge und Array stehen im Basistyp der Domain im Abdruck (Issue #58, `schema-fingerprint-v23`). */
class MigrationFingerprintDomainBaseTypeTest : FunSpec({

    fun base(type: String) = MigrationFingerprint.project(
        SchemaDefinition(
            name = "App", version = "1",
            customTypes = mapOf("d" to CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = type)),
        ),
    )

    test("die Laenge steht im Abdruck, Synonyme gleichen sich an") {
        base("varchar(50)") shouldContain "base=varchar(50)"
        base("character varying(50)") shouldBe base("varchar(50)")
        base("varchar(50)") shouldNotBe base("varchar(254)")
        base("char(5)") shouldNotBe base("varchar(5)")
    }

    test("ein Array traegt [], die Dimension zaehlt nicht") {
        base("integer[]") shouldContain "base=integer[]"
        base("integer[]") shouldNotBe base("integer")
        base("integer[]") shouldBe base("int4[][]")
    }

    test("ohne Laenge und Array bleibt die Neutralform von zuvor") {
        base("bigint") shouldContain "base=biginteger"
        base("varchar") shouldContain "base=text"
        base("numeric(10,2)") shouldContain "base=decimal"
    }
})
