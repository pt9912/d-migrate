package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DialectCapabilities
import dev.dmigrate.driver.TransferTypeCompatibility
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Issue #68: der PostgreSQL-Reader nennt die Domain an der Spalte (`Enum(refType)`). Der Transfer-Preflight vergleicht
 * die Spalte als ihren Basistyp: gegen `Integer` eines anderen Dialekts ist sie kompatibel, eine Enum bleibt es nicht.
 */
class TransferPreflightPlannerDomainTest : FunSpec({

    // Wie ein Zieldialekt mit eigenen Typen: nur gleiche Typen sind kompatibel.
    val sameType = TransferTypeCompatibility { s, t -> s == t }

    fun schema(type: NeutralType, vararg customTypes: Pair<String, CustomTypeDefinition>) = SchemaDefinition(
        name = "s", version = "1", customTypes = mapOf(*customTypes),
        tables = mapOf("t" to TableDefinition(columns = mapOf("d" to ColumnDefinition(type)))),
    )

    fun plan(source: SchemaDefinition, target: SchemaDefinition) = TransferPreflightPlanner().planTables(
        DataTransferRequest(source = "s", target = "t"), source, target, sameType,
        DialectCapabilities.forDialect(dev.dmigrate.driver.DatabaseDialect.SQLITE),
    )

    val domain = "dd" to CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = "integer")
    val ref = NeutralType.Enum(refType = "dd")

    test("Domain-Spalte der Quelle gegen die Integer-Spalte des Ziels: kompatibel") {
        plan(schema(ref, domain), schema(NeutralType.Integer)) shouldBe listOf("t")
    }

    test("Domain-Spalte auf beiden Seiten und Integer-Quelle gegen Domain-Ziel: kompatibel") {
        plan(schema(ref, domain), schema(ref, domain)) shouldBe listOf("t")
        plan(schema(NeutralType.Integer), schema(ref, domain)) shouldBe listOf("t")
    }

    test("Domain-Spalte gegen eine Textspalte bleibt ein Typkonflikt, benannt mit dem Basistyp") {
        val e = shouldThrow<TransferPreflightException> { plan(schema(ref, domain), schema(NeutralType.Text())) }
        e.message!! shouldContain "Integer"
    }

    test("eine Enum-Spalte (kind enum) wird nicht aufgeloest") {
        val enumType = "dd" to CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a"))
        shouldThrow<TransferPreflightException> { plan(schema(ref, enumType), schema(NeutralType.Integer)) }
    }
})
