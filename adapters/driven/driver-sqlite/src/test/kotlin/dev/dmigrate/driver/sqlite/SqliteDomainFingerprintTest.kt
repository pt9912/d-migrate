package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Issue #56 aendert den Vergleich, nicht den Abdruck: der Fingerabdruck einer Domain-Spalte ueber den
 * schema-bewussten Kanonisierer ist fuer eine feste Eingabe ein fester Wert. Der erwartete Wert gilt vor und nach
 * der Aenderung (`MigrationFingerprint`, `CanonicalPayload` und der Kanonisierer sind unberuehrt); ein Wechsel
 * hier hiesse Tag-Bump (`ALGORITHM`).
 */
class SqliteDomainFingerprintTest : FunSpec({

    fun schema(base: String, precision: Int? = null, scale: Int? = null) = SchemaDefinition(
        name = "s", version = "1",
        customTypes = mapOf(
            "d" to CustomTypeDefinition(
                kind = CustomTypeKind.DOMAIN, baseType = base, precision = precision, scale = scale,
            ),
        ),
        tables = mapOf(
            "t" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "n" to ColumnDefinition(NeutralType.Enum(refType = "d")),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    fun hash(schema: SchemaDefinition) = MigrationFingerprint.compute(
        schema,
        canonicalizeType = { SqliteNeutralTypeCanonicalizer.canonicalize(it, schema.customTypes) },
        projectsCustomTypes = false,
        resolvesRefTypeVocabulary = true,
    )

    test("Domain ueber boolean: fester Abdruck") {
        hash(schema("boolean")) shouldBe "30d0ca7ddc1c6d959a6433fc449919f4f62b98be1c62a12649eda37c75d684fb"
    }

    test("Domain ueber decimal(10,2): fester Abdruck") {
        hash(schema("decimal", 10, 2)) shouldBe "ae460253e23be857f5760022cd3a17929c0ba7efa78f37dcab03616ab32f8e58"
    }

    test("die Reverse-Form des Basistyps hat denselben Abdruck wie die Domain") {
        val reverse = schema("boolean").let { s ->
            s.copy(
                customTypes = emptyMap(),
                tables = mapOf(
                    "t" to s.tables.getValue("t").copy(
                        columns = s.tables.getValue("t").columns + ("n" to ColumnDefinition(NeutralType.Integer)),
                    ),
                ),
            )
        }

        hash(reverse) shouldBe hash(schema("boolean"))
    }
})
