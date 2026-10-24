package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Die Sonde fuer den Wertevorrat eines `ref_type`-Enums: ein entfernter Wert, den
 * Zeilen noch tragen, soll vor dem Apply auffallen — nicht erst, wenn der
 * `INSERT ... SELECT` des Neubaus abbricht.
 */
class CheckPreflightPlannerVocabularyTest : FunSpec({

    fun schema(customType: CustomTypeDefinition?, type: NeutralType) = SchemaDefinition(
        name = "app", version = "1",
        customTypes = if (customType == null) emptyMap() else mapOf("ee" to customType),
        tables = mapOf("t" to TableDefinition(columns = mapOf("e" to ColumnDefinition(type)))),
    )

    val enumEe = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("b", "a"))

    fun plan(desiredType: CustomTypeDefinition?, after: NeutralType = NeutralType.Enum(refType = "ee")) =
        DiffPlanner().plan(
            schema(null, NeutralType.Text()),
            schema(desiredType, after),
            SchemaDiff(
                tablesChanged = listOf(
                    TableDiff(
                        name = "t",
                        columnsChanged = listOf(
                            ColumnDiff(name = "e", type = ValueChange(NeutralType.Text(), after)),
                        ),
                    ),
                ),
            ),
        )

    fun probes(diff: DiffResult, enabled: Boolean) = CheckPreflightPlanner.plan(
        diff = diff,
        dialect = "sqlite",
        initialStatus = CheckPreflightPlanner.InitialStatus.NOT_RUN_POLICY,
        identifierQuoter = { "\"$it\"" },
        refTypeVocabulary = enabled,
    )

    test("ein AlterColumnType auf ein ref_type-Enum bekommt eine Sonde mit sortierten Werten") {
        val diff = plan(enumEe)
        val probe = probes(diff, enabled = true).single()

        probe.operationId shouldBe diff.operations.filterIsInstance<DiffOperation.AlterColumnType>().single().id
        probe.table shouldBe "t"
        probe.constraintName shouldBe "e"
        probe.probeSql shouldBe "SELECT count(*) FROM \"t\" WHERE NOT (\"e\" IN ('a', 'b'))"
        probe.expression shouldContain "'a', 'b'"
    }

    test("ohne das Dialekt-Gate entsteht keine Sonde") {
        probes(plan(enumEe), enabled = false).shouldBeEmpty()
    }

    test("eine Domain, ein unbekannter Typ und ein Typ ohne ref_type haben keine Sonde") {
        val domain = CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = "integer", check = "VALUE > 0")
        probes(plan(domain), enabled = true).shouldBeEmpty()
        probes(plan(null), enabled = true).shouldBeEmpty()
        probes(plan(enumEe, after = NeutralType.Enum(values = listOf("x"))), enabled = true).shouldBeEmpty()
        probes(plan(enumEe, after = NeutralType.Integer), enabled = true).shouldBeEmpty()
    }

    test("ein ENUM ohne Werte hat keine Sonde") {
        probes(plan(CustomTypeDefinition(kind = CustomTypeKind.ENUM)), enabled = true).shouldBeEmpty()
    }
})
