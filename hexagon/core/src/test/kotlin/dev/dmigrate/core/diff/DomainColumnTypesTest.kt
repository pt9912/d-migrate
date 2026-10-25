package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Issue #56: ein Dialekt, der eine Domain als ihren Basistyp ablegt (SQLite), projiziert die Domain-Spalte im
 * Vergleich ueber den Basistyp. Gegen die Reverse-Form (`Integer`) ist sie dann keine Aenderung; gegen eine Spalte
 * anderen Basistyps schon; auf beiden Seiten als `ref_type` bleibt der Basistypwechsel ein `AlterCustomType`.
 */
class DomainColumnTypesTest : FunSpec({

    fun domain(base: String) = CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = base)

    fun table(type: NeutralType) = TableDefinition(columns = mapOf("n" to ColumnDefinition(type)))

    fun schema(type: NeutralType, vararg customTypes: Pair<String, CustomTypeDefinition>) = SchemaDefinition(
        name = "s", version = "1", customTypes = mapOf(*customTypes), tables = mapOf("t" to table(type)),
    )

    // Wie SQLite: die kontextfreie Projektion macht aus Enum Text, die kontextbewusste aus der Domain ihren Basistyp.
    val sqliteLike = TargetProjection(
        type = { if (it is NeutralType.Enum) NeutralType.Text() else it },
        typeWithCustomTypes = { type, customTypes ->
            val base = (type as? NeutralType.Enum)?.refType?.let { customTypes[it] }?.baseType
            when (base) {
                "integer" -> NeutralType.Integer
                "text" -> NeutralType.Text()
                else -> if (type is NeutralType.Enum) NeutralType.Text() else type
            }
        },
        resolvesRefTypeVocabulary = true,
    )

    fun diff(current: SchemaDefinition, desired: SchemaDefinition, projection: TargetProjection? = sqliteLike) =
        SchemaComparator(projection).compare(current, desired)

    val domainColumn = NeutralType.Enum(refType = "d")

    test("Domain gegen die Reverse-Form ihres Basistyps ist keine Typaenderung") {
        val result = diff(schema(NeutralType.Integer), schema(domainColumn, "d" to domain("integer")))

        result.tablesChanged.shouldBeEmpty()
    }

    test("ohne Projektion bleibt der Unterschied sichtbar (strikter Vergleich)") {
        val result = diff(schema(NeutralType.Integer), schema(domainColumn, "d" to domain("integer")), null)

        result.tablesChanged.single().columnsChanged.single().type?.after shouldBe domainColumn
    }

    test("ohne kontextbewusste Projektion gilt die kontextfreie: Domain ueber integer gegen Integer ist ein Unterschied") {
        val contextFree = TargetProjection(type = sqliteLike.type, resolvesRefTypeVocabulary = true)

        val result = diff(schema(NeutralType.Integer), schema(domainColumn, "d" to domain("integer")), contextFree)

        result.tablesChanged.single().columnsChanged.single().name shouldBe "n"
    }

    test("Domain ueber integer gegen eine Textspalte ist eine Typaenderung") {
        val result = diff(schema(NeutralType.Text()), schema(domainColumn, "d" to domain("integer")))

        result.tablesChanged.single().columnsChanged.single().name shouldBe "n"
    }

    test("Domain auf beiden Seiten: ein Basistypwechsel ist kein ColumnDiff, nur der Custom-Type-Vergleich meldet ihn") {
        val result = diff(
            schema(domainColumn, "d" to domain("integer")),
            schema(domainColumn, "d" to domain("text")),
        )

        result.tablesChanged.shouldBeEmpty()
        result.customTypesChanged.single().name shouldBe "d"
    }

    test("projected ohne Custom Types ist leer und ruft die Projektion nicht auf") {
        val projected = DomainColumnTypes.projected(
            table(domainColumn), emptyMap(), table(NeutralType.Integer),
        ) { _, _ -> error("darf nicht aufgerufen werden") }

        projected shouldBe emptyMap()
    }

    test("projected nimmt nur Domains ohne ref_type-Gegenseite auf") {
        val customTypes = mapOf("d" to domain("integer"), "e" to CustomTypeDefinition(CustomTypeKind.ENUM, values = listOf("a")))
        val project: (NeutralType, Map<String, CustomTypeDefinition>) -> NeutralType = { _, _ -> NeutralType.Integer }

        DomainColumnTypes.projected(table(domainColumn), customTypes, table(NeutralType.Integer), project) shouldBe
            mapOf("n" to NeutralType.Integer)
        DomainColumnTypes.projected(table(domainColumn), customTypes, table(domainColumn), project) shouldBe emptyMap()
        DomainColumnTypes.projected(table(NeutralType.Enum(refType = "e")), customTypes, table(NeutralType.Text()), project) shouldBe
            emptyMap()
    }
})
