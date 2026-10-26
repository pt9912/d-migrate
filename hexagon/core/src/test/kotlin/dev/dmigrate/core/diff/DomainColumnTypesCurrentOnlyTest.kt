package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Issue #68: ein Dialekt, dessen Reverse die Domain-Spalte als `ref_type` liest (PostgreSQL), projiziert im Vergleich
 * nur die **Datenbank-Seite** ([TargetProjection.domainProjectionCurrentOnly]). Eine aeltere Datei mit Basistyp gilt
 * als unveraendert; eine Datei, die eine Domain-Spalte verlangt, gegen eine Basistyp-Spalte bleibt eine Aenderung.
 */
class DomainColumnTypesCurrentOnlyTest : FunSpec({

    fun domain(base: String) = CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = base)

    fun schema(type: NeutralType, vararg customTypes: Pair<String, CustomTypeDefinition>) = SchemaDefinition(
        name = "s", version = "1", customTypes = mapOf(*customTypes),
        tables = mapOf("t" to TableDefinition(columns = mapOf("n" to ColumnDefinition(type)))),
    )

    val baseTypes = mapOf(
        "integer" to NeutralType.Integer,
        "text" to NeutralType.Text(),
        "varchar(50)" to NeutralType.Text(50),
        "decimal" to NeutralType.Decimal(10, 2),
        "uuid" to NeutralType.Uuid,
        "boolean" to NeutralType.BooleanType,
        "biginteger" to NeutralType.BigInteger,
    )

    // Wie PostgreSQL: kontextfrei bleibt Enum(refType) stehen, kontextbewusst loest die Domain auf ihren Basistyp auf.
    val postgresLike = TargetProjection(
        type = { it },
        typeWithCustomTypes = { type, customTypes ->
            val base = (type as? NeutralType.Enum)?.refType?.let { customTypes[it] }?.baseType
            baseTypes[base] ?: type
        },
        domainProjectionCurrentOnly = true,
    )

    fun diff(current: SchemaDefinition, desired: SchemaDefinition, projection: TargetProjection? = postgresLike) =
        SchemaComparator(projection).compare(current, desired)

    val ref = NeutralType.Enum(refType = "dd")

    test("Datenbank-Domain-Spalte gegen die Datei mit dem Basistyp: keine Aenderung, fuer jeden Basistyp") {
        for ((base, type) in baseTypes) {
            withClue(base) {
                diff(schema(ref, "dd" to domain(base)), schema(type, "dd" to domain(base))).tablesChanged.shouldBeEmpty()
            }
        }
    }

    test("Datei mit ref_type gegen eine Datenbank-Spalte vom Basistyp bleibt eine Typaenderung, fuer jeden Basistyp") {
        for ((base, type) in baseTypes) {
            withClue(base) {
                val result = diff(schema(type, "dd" to domain(base)), schema(ref, "dd" to domain(base)))
                result.tablesChanged.single().columnsChanged.single().type?.after shouldBe ref
            }
        }
    }

    test("Domain auf beiden Seiten gleich: keine Aenderung; ein anderer Verweis bleibt eine Aenderung") {
        val same = diff(schema(ref, "dd" to domain("integer")), schema(ref, "dd" to domain("integer")))
        same.tablesChanged.shouldBeEmpty()

        val other = diff(
            schema(ref, "dd" to domain("integer"), "ee" to domain("integer")),
            schema(NeutralType.Enum(refType = "ee"), "dd" to domain("integer"), "ee" to domain("integer")),
        )
        other.tablesChanged.single().columnsChanged.single().name shouldBe "n"
    }

    test("Basistypwechsel der Domain bei ref_type auf beiden Seiten: nur der Custom-Type-Vergleich meldet ihn") {
        val result = diff(schema(ref, "dd" to domain("integer")), schema(ref, "dd" to domain("text")))

        result.tablesChanged.shouldBeEmpty()
        result.customTypesChanged.single().name shouldBe "dd"
    }

    test("Datenbank-Domain-Spalte gegen eine Datei mit anderem Basistyp ist eine Typaenderung") {
        val result = diff(schema(ref, "dd" to domain("integer")), schema(NeutralType.Text(), "dd" to domain("integer")))

        result.tablesChanged.single().columnsChanged.single().name shouldBe "n"
    }

    test("strikter Vergleich (ohne Projektion) zeigt die Domain-Spalte gegen den Basistyp") {
        val result = diff(schema(ref, "dd" to domain("integer")), schema(NeutralType.Integer, "dd" to domain("integer")), null)

        result.tablesChanged.single().columnsChanged.single().type?.before shouldBe ref
    }

    test("nur die Datenbank-Seite wird projiziert: die Datei-Seite bleibt Enum(refType) (kein beidseitiges Verdrahten)") {
        // Datei ref gegen Datenbank-Basistyp wuerde bei beidseitiger Projektion gleich (Integer == Integer).
        val result = diff(schema(NeutralType.Integer, "dd" to domain("integer")), schema(ref, "dd" to domain("integer")))

        result.tablesChanged.single().columnsChanged.single().name shouldBe "n"
    }
})
