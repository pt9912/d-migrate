package dev.dmigrate.core.diff.migration.overlay

import dev.dmigrate.core.util.sha256Hex
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class MigrationOverlayContractTest : FunSpec({

    test("canonical overlay JSON is stable and overlayHash is outside the signed payload") {
        val overlay = unsignedUsingOverlay()

        val expectedUnsigned = """
            {
              "formatVersion": "migration-overlay.v1",
              "overlayKind": "using-expression",
              "sourceFingerprint": "src-fp",
              "targetFingerprint": "dst-fp",
              "dialect": "postgresql",
              "entries": [
                {
                  "kind": "using-expression",
                  "id": "use-email",
                  "table": "users",
                  "column": "email",
                  "expression": {
                    "value": "COALESCE(profile->>'email', '')",
                    "secret": false
                  },
                  "requiredFeatures": []
                }
              ],
              "createdAt": "2026-05-12T10:15:30Z",
              "createdByVersion": "d-migrate-test"
            }
        """.trimIndent() + "\n"

        MigrationOverlayCanonicalJson.encodeUnsigned(overlay) shouldBe expectedUnsigned
        MigrationOverlayCanonicalJson.computeHash(overlay) shouldBe sha256Hex(expectedUnsigned)

        val signed = overlay.withComputedHash()
        MigrationOverlayCanonicalJson.encodeUnsigned(signed) shouldBe expectedUnsigned
    }

    test("entry order participates in the overlay hash") {
        val first = unsignedUsingOverlay(
            entries = listOf(
                usingEntry(id = "a", column = "a"),
                usingEntry(id = "b", column = "b"),
            ),
        )
        val second = unsignedUsingOverlay(entries = first.entries.reversed())

        (MigrationOverlayCanonicalJson.computeHash(first) == MigrationOverlayCanonicalJson.computeHash(second)) shouldBe false
    }

    test("rejects stale fingerprints dialect mismatch unknown version unknown kind and hash mismatch") {
        val overlay = unsignedUsingOverlay(
            formatVersion = "migration-overlay.v2",
            overlayKind = "not-a-kind",
            sourceFingerprint = "old-src",
            targetFingerprint = "old-dst",
            dialect = "mysql",
        ).copy(overlayHash = "bad-hash")

        val result = MigrationOverlayValidator.validate(
            overlay = overlay,
            context = validationContext(),
            source = "overlays/bad.json",
        )

        result.hasBlockers shouldBe true
        result.diagnostics.map { it.code }.shouldContain(MigrationOverlayDiagnostics.UNKNOWN_FORMAT_VERSION)
        result.diagnostics.map { it.code }.shouldContain(MigrationOverlayDiagnostics.UNKNOWN_OVERLAY_KIND)
        result.diagnostics.map { it.code }.shouldContain(MigrationOverlayDiagnostics.STALE_SOURCE_FINGERPRINT)
        result.diagnostics.map { it.code }.shouldContain(MigrationOverlayDiagnostics.STALE_TARGET_FINGERPRINT)
        result.diagnostics.map { it.code }.shouldContain(MigrationOverlayDiagnostics.DIALECT_MISMATCH)
        result.diagnostics.map { it.code }.shouldContain(MigrationOverlayDiagnostics.HASH_MISMATCH)
    }

    test("using and rename overlays both block without a valid F0 hash contract") {
        val using = unsignedUsingOverlay()
        val rename = unsignedRenameOverlay()

        listOf(using, rename).map { overlay ->
            MigrationOverlayValidator.validate(
                overlay = overlay,
                context = validationContext(),
                source = "overlays/${overlay.overlayKind}.json",
            ).diagnostics.single().code
        }.shouldContainExactly(
            MigrationOverlayDiagnostics.HASH_MISSING,
            MigrationOverlayDiagnostics.HASH_MISSING,
        )
    }

    test("entry type mismatches and reserved optional execution metadata are blockers") {
        val overlay = unsignedUsingOverlay(
            entries = listOf(renameEntry()),
            producerMetadata = mapOf("execution.retry" to "true"),
        ).withComputedHash()

        val result = MigrationOverlayValidator.validate(overlay, validationContext(), "overlays/mixed.json")

        result.diagnostics.map { it.code }.shouldContain(MigrationOverlayDiagnostics.ENTRY_KIND_MISMATCH)
        result.diagnostics.map { it.code }.shouldContain(MigrationOverlayDiagnostics.RESERVED_OPTIONAL_FIELD)
    }

    test("report exposes source entry id hash and diagnostic code without secret values") {
        val secret = "prod-secret-password"
        val overlay = unsignedUsingOverlay(
            overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
            entries = listOf(
                usingEntry(
                    expression = OverlayText(secret, secret = true),
                ),
            ),
        ).withComputedHash()

        val result = MigrationOverlayValidator.validate(overlay, validationContext(), "overlays/secret.json")
        val report = MigrationOverlayReport.fromValidation(result)

        report.single { it.diagnosticCode == MigrationOverlayDiagnostics.ENTRY_KIND_MISMATCH } shouldBe
            MigrationOverlayReportItem(
                source = "overlays/secret.json",
                entryId = "use-email",
                overlayHash = overlay.overlayHash!!,
                diagnosticCode = MigrationOverlayDiagnostics.ENTRY_KIND_MISMATCH,
                severity = MigrationOverlayDiagnostic.Severity.BLOCKER,
            )
        report.toString().contains(secret) shouldBe false
    }
})

private fun validationContext(): MigrationOverlayValidationContext =
    MigrationOverlayValidationContext(
        expectedSourceFingerprint = "src-fp",
        expectedTargetFingerprint = "dst-fp",
        expectedDialect = "postgresql",
    )

private fun unsignedUsingOverlay(
    formatVersion: String = MigrationOverlay.FORMAT_VERSION,
    overlayKind: String = MigrationOverlayKinds.USING_EXPRESSION,
    sourceFingerprint: String = "src-fp",
    targetFingerprint: String = "dst-fp",
    dialect: String = "postgresql",
    entries: List<MigrationOverlayEntry> = listOf(usingEntry()),
    producerMetadata: Map<String, String> = emptyMap(),
): MigrationOverlay =
    MigrationOverlay(
        formatVersion = formatVersion,
        overlayKind = overlayKind,
        sourceFingerprint = sourceFingerprint,
        targetFingerprint = targetFingerprint,
        dialect = dialect,
        entries = entries,
        createdAt = "2026-05-12T10:15:30Z",
        createdByVersion = "d-migrate-test",
        producerMetadata = producerMetadata,
    )

private fun unsignedRenameOverlay(): MigrationOverlay =
    unsignedUsingOverlay(
        overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
        entries = listOf(renameEntry()),
    )

private fun usingEntry(
    id: String = "use-email",
    column: String = "email",
    expression: OverlayText = OverlayText("COALESCE(profile->>'email', '')"),
): UsingExpressionOverlayEntry =
    UsingExpressionOverlayEntry(
        id = id,
        table = "users",
        column = column,
        expression = expression,
    )

private fun renameEntry(): RenameMappingOverlayEntry =
    RenameMappingOverlayEntry(
        id = "rename-users",
        objectType = "table",
        fromName = "app_user",
        toName = "users",
    )
