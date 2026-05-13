package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayConversionReversibility
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDataRisk
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.OverlayText
import dev.dmigrate.core.diff.migration.overlay.RenameMappingOverlayEntry
import dev.dmigrate.core.diff.migration.overlay.UsingExpressionOverlayEntry
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class MigrationOverlayPreflightTest : FunSpec({

    test("valid rename overlay satisfies F0 preflight without requiring a feature consumer") {
        val plan = planWith(
            overlay = renameOverlay().withComputedHash(),
            source = "overlays/rename.json",
        )

        val result = MigrationOverlayPreflight.validate(plan, DatabaseDialect.POSTGRESQL)

        result.hasBlockers shouldBe false
        result.reportItems shouldBe emptyList()
    }

    test("unsigned rename overlay blocks before render") {
        val plan = planWith(
            overlay = renameOverlay(),
            source = "overlays/rename.json",
        )

        val result = MigrationOverlayPreflight.validate(plan, DatabaseDialect.POSTGRESQL)
        val failure = MigrationOverlayPreflight.buildFailureResult(plan, result)

        result.hasBlockers shouldBe true
        result.reportItems.map { it.diagnosticCode }.shouldContain("OVERLAY_HASH_MISSING")
        failure.isBlocked shouldBe true
        failure.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        failure.statements shouldBe emptyList()
    }

    test("overlay load failures block through the F0 preflight report") {
        val result = MigrationOverlayPreflight.validate(
            planWithoutOverlays(),
            DatabaseDialect.POSTGRESQL,
            loadFailures = listOf(
                MigrationOverlayLoadFailure(
                    source = "overlays/bad.json",
                    diagnosticCode = "OVERLAY_UNKNOWN_ENTRY_KIND",
                ),
            ),
        )

        result.hasBlockers shouldBe true
        result.reportItems.single().source shouldBe "overlays/bad.json"
        result.reportItems.single().entryId shouldBe null
        result.reportItems.single().overlayHash shouldBe "<unavailable>"
        result.reportItems.single().diagnosticCode shouldBe "OVERLAY_UNKNOWN_ENTRY_KIND"
    }

    test("overlay diagnostics expose source entry hash and code without secret expression values") {
        val secret = "prod_secret_cast_expression"
        val overlay = usingOverlay(
            overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
            upUsingExpression = OverlayText(secret, secret = true),
        ).withComputedHash()
        val plan = planWith(overlay, "overlays/secret.json")

        val result = MigrationOverlayPreflight.validate(plan, DatabaseDialect.POSTGRESQL)

        result.reportItems.single().source shouldBe "overlays/secret.json"
        result.reportItems.single().entryId shouldBe "use-email"
        result.reportItems.single().diagnosticCode shouldBe "OVERLAY_ENTRY_KIND_MISMATCH"
        result.toString().contains(secret) shouldBe false
    }
})

private fun planWith(overlay: MigrationOverlay, source: String): DiffResult =
    DiffResult(
        current = DiffEndpoint(schemaName = "App", fingerprint = "src-fp"),
        desired = DiffEndpoint(schemaName = "App", fingerprint = "dst-fp"),
        schemaDiff = SchemaDiff(),
        operations = emptyList(),
        migrationOverlays = listOf(MigrationOverlayDocument(source = source, overlay = overlay)),
    )

private fun planWithoutOverlays(): DiffResult =
    DiffResult(
        current = DiffEndpoint(schemaName = "App", fingerprint = "src-fp"),
        desired = DiffEndpoint(schemaName = "App", fingerprint = "dst-fp"),
        schemaDiff = SchemaDiff(),
        operations = emptyList(),
    )

private fun renameOverlay(): MigrationOverlay =
    MigrationOverlay(
        overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
        sourceFingerprint = "src-fp",
        targetFingerprint = "dst-fp",
        dialect = "postgresql",
        entries = listOf(
            RenameMappingOverlayEntry(
                id = "rename-users",
                objectType = "table",
                fromName = "app_user",
                toName = "users",
            ),
        ),
        createdAt = "2026-05-12T10:15:30Z",
        createdByVersion = "d-migrate-test",
    )

private fun usingOverlay(
    overlayKind: String = MigrationOverlayKinds.USING_EXPRESSION,
    upUsingExpression: OverlayText = OverlayText("\"email\"::TEXT"),
): MigrationOverlay =
    MigrationOverlay(
        overlayKind = overlayKind,
        sourceFingerprint = "src-fp",
        targetFingerprint = "dst-fp",
        dialect = "postgresql",
        entries = listOf(
            UsingExpressionOverlayEntry(
                id = "use-email",
                table = "users",
                column = "email",
                sourceType = "VARCHAR(255)",
                targetType = "TEXT",
                upUsingExpression = upUsingExpression,
                dataRisk = MigrationOverlayDataRisk.USER_ASSERTED_SAFE,
                conversionReversibility = MigrationOverlayConversionReversibility.AUTOMATIC,
                expressionSource = "user",
                reviewedByUser = true,
            ),
        ),
        createdAt = "2026-05-12T10:15:30Z",
        createdByVersion = "d-migrate-test",
    )
