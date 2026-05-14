package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayConversionReversibility
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDataRisk
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDiagnostics
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

    test("unsigned using-expression overlay blocks before render") {
        val plan = planWith(
            overlay = usingOverlay(),
            source = "overlays/using.json",
        )

        val result = MigrationOverlayPreflight.validate(plan, DatabaseDialect.POSTGRESQL)
        val failure = MigrationOverlayPreflight.buildFailureResult(plan, result)

        result.hasBlockers shouldBe true
        result.reportItems.single().source shouldBe "overlays/using.json"
        result.reportItems.single().entryId shouldBe null
        result.reportItems.single().diagnosticCode shouldBe "OVERLAY_HASH_MISSING"
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

    test("validateBeforePlan accepts a mixed-case dialect string thanks to Locale.ROOT") {
        // Caller didn't lowercase the dialect; the helper must do it
        // internally so a Turkish-locale JVM cannot turn `POSTGRESQL`
        // into `postgresqı` and trigger a spurious DIALECT_MISMATCH.
        val overlay = renameOverlay().withComputedHash()
        val result = MigrationOverlayPreflight.validateBeforePlan(
            documents = listOf(MigrationOverlayDocument(source = "overlays/rename.json", overlay = overlay)),
            sourceFingerprint = "src-fp",
            targetFingerprint = "dst-fp",
            dialect = "POSTGRESQL",
        )

        result.hasBlockers shouldBe false
    }

    test("validateBeforePlan surfaces load failures even when the document list is empty") {
        val result = MigrationOverlayPreflight.validateBeforePlan(
            documents = emptyList(),
            sourceFingerprint = "src-fp",
            targetFingerprint = "dst-fp",
            dialect = "postgresql",
            loadFailures = listOf(
                MigrationOverlayLoadFailure(
                    source = "overlays/missing.json",
                    diagnosticCode = "OVERLAY_FIELD_TYPE_MISMATCH",
                ),
            ),
        )

        result.hasBlockers shouldBe true
        result.reportItems.single().source shouldBe "overlays/missing.json"
        result.reportItems.single().diagnosticCode shouldBe "OVERLAY_FIELD_TYPE_MISMATCH"
        result.reportItems.single().overlayHash shouldBe "<unavailable>"
    }

    test("overlay diagnostics expose source entry hash and code without secret expression values") {
        val secret = "prod_secret_cast_expression"
        val overlay = usingOverlay(
            overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
            upUsingExpression = OverlayText(secret, secret = true),
        ).withComputedHash()
        val plan = planWith(overlay, "overlays/secret.json")

        val result = MigrationOverlayPreflight.validate(plan, DatabaseDialect.POSTGRESQL)
        val mismatch = result.reportItems.single {
            it.diagnosticCode == MigrationOverlayDiagnostics.ENTRY_KIND_MISMATCH
        }

        mismatch.source shouldBe "overlays/secret.json"
        mismatch.entryId shouldBe "use-email"
        result.reportItems.map { it.diagnosticCode }
            .shouldContain(MigrationOverlayDiagnostics.SECRET_BEARING_FIELD)
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
