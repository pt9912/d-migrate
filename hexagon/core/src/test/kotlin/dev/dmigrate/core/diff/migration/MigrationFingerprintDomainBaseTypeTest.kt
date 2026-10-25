package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.SchemaDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** Der Basistyp einer Domain im Abdruck (Issue #52); eigene Datei, die Nachbarn sind gross genug. */
class MigrationFingerprintDomainBaseTypeTest : FunSpec({

    // Custom Types gehen nur mit projectsCustomTypes in den Abdruck.
    fun fp(s: SchemaDefinition) = MigrationFingerprint.compute(s, projectsCustomTypes = true)

    fun schema() = SchemaDefinition(name = "S", version = "1")

    fun withDomain(base: String, precision: Int? = null, scale: Int? = null, fields: Map<String, ColumnDefinition>? = null) =
        schema().copy(
            customTypes = mapOf(
                "d" to CustomTypeDefinition(
                    kind = CustomTypeKind.DOMAIN, baseType = base, precision = precision, scale = scale,
                    check = "VALUE > 0", fields = fields,
                ),
            ),
        )

    test("der Basistyp einer Domain geht in Neutralform in den Abdruck (#52)") {
        // Datei und Reader schreiben denselben Basistyp verschieden; der Post-Compare darf daran nicht driften.
        fp(withDomain("bigint")) shouldBe fp(withDomain("biginteger"))
        fp(withDomain("numeric", 10, 2)) shouldBe
            fp(withDomain("decimal", 10, 2))
        fp(withDomain("varchar(50)")) shouldBe fp(withDomain("text"))
        // Ein anderer Typ, andere Genauigkeit und Felder (leer gegen fehlend) bleiben unterscheidbar bzw. gleich.
        fp(withDomain("integer")) shouldNotBe fp(withDomain("bigint"))
        fp(withDomain("decimal", 10, 2)) shouldNotBe
            fp(withDomain("decimal", 12, 2))
        fp(withDomain("integer", fields = emptyMap())) shouldBe
            fp(withDomain("integer"))
    }

    test("precision und scale gehen nur bei decimal in den Abdruck, die Klammer des Typs zaehlt dort mit (#52)") {
        fp(withDomain("integer", 32, 0)) shouldBe fp(withDomain("integer"))
        fp(withDomain("numeric(10,2)")) shouldBe fp(withDomain("decimal", 10, 2))
        fp(withDomain("numeric(10,2)")) shouldNotBe fp(withDomain("numeric(12,2)"))
    }
})
