package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.SpatialProfile
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * `--spatial-profile none` auf dem Migrate-Pfad: bis dahin las kein Renderer
 * ausser SQLite das Profil, die Angabe blieb also wirkungslos, waehrend
 * `schema generate` dieselbe Tabelle mit `E052` blockt.
 */
class SpatialProfileStageTest : FunSpec({

    fun tableRef(name: String) = DiffObjectRef(DiffObjectType.TABLE, listOf(name))
    fun columnRef(table: String, column: String) = DiffObjectRef(DiffObjectType.COLUMN, listOf(table, column))

    fun planOf(vararg operations: DiffOperation) = DiffResult(
        current = DiffEndpoint(schemaName = "App", fingerprint = "a"),
        desired = DiffEndpoint(schemaName = "App", fingerprint = "b"),
        schemaDiff = SchemaDiff(),
        operations = operations.toList(),
    )

    val geometryTable = DiffOperation.CreateTable(
        id = "op-1",
        objectRef = tableRef("places"),
        table = TableDefinition(columns = mapOf("geom" to ColumnDefinition(type = NeutralType.Geometry()))),
    )
    val plainTable = DiffOperation.CreateTable(
        id = "op-2",
        objectRef = tableRef("customers"),
        table = TableDefinition(columns = mapOf("name" to ColumnDefinition(type = NeutralType.Text()))),
    )

    test("a profile that renders geometry lets the plan through") {
        for (profile in listOf(SpatialProfile.POSTGIS, SpatialProfile.NATIVE, SpatialProfile.SPATIALITE)) {
            SpatialProfileStage.run(planOf(geometryTable), profile) shouldBe SpatialProfileStage.Outcome.Allowed
        }
    }

    test("profile none without geometry in the plan is no reason to refuse") {
        SpatialProfileStage.run(planOf(plainTable), SpatialProfile.NONE) shouldBe SpatialProfileStage.Outcome.Allowed
    }

    test("profile none refuses every operation that introduces geometry, naming them") {
        val plan = planOf(
            geometryTable,
            plainTable,
            DiffOperation.AddColumn(
                id = "op-3",
                objectRef = columnRef("routes", "shape"),
                column = ColumnDefinition(type = NeutralType.Geometry()),
            ),
            DiffOperation.AlterColumnType(
                id = "op-4",
                objectRef = columnRef("areas", "outline"),
                before = NeutralType.Text(),
                after = NeutralType.Geometry(),
            ),
            // Eine Spalte, die AUS einer Geometrie wird, fuehrt keine ein.
            DiffOperation.AlterColumnType(
                id = "op-5",
                objectRef = columnRef("legacy", "old"),
                before = NeutralType.Geometry(),
                after = NeutralType.Text(),
            ),
        )

        val outcome = SpatialProfileStage.run(plan, SpatialProfile.NONE)
            .shouldBeInstanceOf<SpatialProfileStage.Outcome.Refused>()
        outcome.operations shouldContainExactly listOf("areas.outline", "places", "routes.shape")
    }

    test("the refusal blocks the whole run and carries E052") {
        val result = SpatialProfileStage.buildFailureResult(listOf("places"))

        result.statements shouldBe emptyList()
        result.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        result.diagnostics.single().code shouldBe "E052"
        // Ein abhaengigkeitssortierter Plan traegt keine Teilausfuehrung: die
        // uebrigen Anweisungen verwiesen sonst auf eine Tabelle, die fehlt.
        result.diagnostics.single().message shouldContain "places"
    }
})
