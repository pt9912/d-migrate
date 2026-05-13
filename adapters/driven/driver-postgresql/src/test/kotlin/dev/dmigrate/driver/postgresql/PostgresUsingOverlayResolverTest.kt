package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayConversionReversibility
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDataRisk
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.OverlayText
import dev.dmigrate.core.diff.migration.overlay.UsingExpressionOverlayEntry
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class PostgresUsingOverlayResolverTest : FunSpec({

    val planner = DiffPlanner()
    val generator = PostgresDiffDdlGenerator()

    fun integerToTextDiff() =
        SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "age",
                            type = ValueChange(NeutralType.Integer, NeutralType.Text()),
                        ),
                    ),
                ),
            ),
        )

    fun planWithOverlay(
        expressionSource: String = "user",
        downUsingExpression: OverlayText? = null,
        reversibility: MigrationOverlayConversionReversibility = MigrationOverlayConversionReversibility.NOT_REVERSIBLE,
    ): DiffResult {
        val planned = planner.plan(
            current = dev.dmigrate.core.model.SchemaDefinition(name = "App", version = "1"),
            desired = dev.dmigrate.core.model.SchemaDefinition(name = "App", version = "1"),
            schemaDiff = integerToTextDiff(),
        )
        return planned.copy(
            migrationOverlays = listOf(
                MigrationOverlayDocument(
                    source = "overlays/age-using.json",
                    overlay = usingOverlay(
                        planned = planned,
                        expressionSource = expressionSource,
                        downUsingExpression = downUsingExpression,
                        reversibility = reversibility,
                    ),
                ),
            ),
        )
    }

    test("B.1 validates using-expression source before rendering") {
        val result = generator.generateUp(planWithOverlay("schema-comment"), DdlGenerationOptions())

        result.isBlocked shouldBe true
        result.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        result.statements shouldBe emptyList()
        result.diagnostics.map { it.code }.shouldContain("PG_USING_OVERLAY_INVALID_EXPRESSION_SOURCE")
    }

    test("B.1 down rendering uses the explicit down expression") {
        val result = generator.generateDown(
            planWithOverlay(
                downUsingExpression = OverlayText("CAST(\"age\" AS INTEGER)"),
                reversibility = MigrationOverlayConversionReversibility.AUTOMATIC,
            ),
            DdlGenerationOptions(),
        )

        result.isBlocked shouldBe false
        result.statements.single().sql shouldBe
            "ALTER TABLE \"users\" ALTER COLUMN \"age\" TYPE INTEGER USING CAST(\"age\" AS INTEGER);"
        val message = result.diagnostics.single { it.code == "PG_USING_OVERLAY_APPLIED" }.message
        message shouldContain "source=overlays/age-using.json"
        message shouldContain "dataRisk=USER_ASSERTED_SAFE"
        message shouldContain "downStatus=EXPLICIT"
        message shouldContain "expressionSource=user"
    }
})

private fun usingOverlay(
    planned: DiffResult,
    expressionSource: String,
    downUsingExpression: OverlayText?,
    reversibility: MigrationOverlayConversionReversibility,
): MigrationOverlay =
    MigrationOverlay(
        overlayKind = MigrationOverlayKinds.USING_EXPRESSION,
        sourceFingerprint = planned.current.fingerprint!!,
        targetFingerprint = planned.desired.fingerprint!!,
        dialect = "postgresql",
        entries = listOf(
            UsingExpressionOverlayEntry(
                id = "age-int-to-text",
                table = "users",
                column = "age",
                sourceType = "INTEGER",
                targetType = "TEXT",
                upUsingExpression = OverlayText("\"age\"::TEXT"),
                downUsingExpression = downUsingExpression,
                dataRisk = MigrationOverlayDataRisk.USER_ASSERTED_SAFE,
                conversionReversibility = reversibility,
                expressionSource = expressionSource,
                reviewedByUser = true,
            ),
        ),
        createdAt = "2026-05-13T10:15:30Z",
        createdByVersion = "d-migrate-test",
    ).withComputedHash()
