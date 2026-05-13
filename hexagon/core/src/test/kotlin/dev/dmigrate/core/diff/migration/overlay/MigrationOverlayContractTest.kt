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
                  "sourceType": "TEXT",
                  "targetType": "TEXT",
                  "upUsingExpression": {
                    "value": "COALESCE(profile->>'email', '')",
                    "secret": false
                  },
                  "dataRisk": "NO_DATA_LOSS_EXPECTED",
                  "reversibility": "AUTOMATIC",
                  "expressionSource": "user",
                  "reviewedByUser": true,
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

    test("F.4 canonical rename overlay binds structure fingerprints") {
        val overlay = unsignedRenameOverlay(
            entry = renameEntry(
                fromStructureFingerprint = "from-struct-fp",
                toStructureFingerprint = "to-struct-fp",
            ),
        )
        val expectedUnsigned = """
            {
              "formatVersion": "migration-overlay.v1",
              "overlayKind": "rename-mapping",
              "sourceFingerprint": "src-fp",
              "targetFingerprint": "dst-fp",
              "dialect": "postgresql",
              "entries": [
                {
                  "kind": "rename-mapping",
                  "id": "rename-users",
                  "objectType": "table",
                  "fromName": "app_user",
                  "toName": "users",
                  "fromStructureFingerprint": "from-struct-fp",
                  "toStructureFingerprint": "to-struct-fp",
                  "requiredFeatures": []
                }
              ],
              "createdAt": "2026-05-12T10:15:30Z",
              "createdByVersion": "d-migrate-test"
            }
        """.trimIndent() + "\n"

        MigrationOverlayCanonicalJson.encodeUnsigned(overlay) shouldBe expectedUnsigned
        MigrationOverlayCanonicalJson.computeHash(overlay) shouldBe sha256Hex(expectedUnsigned)
    }

    test("F.4 rename overlay rejects stale fingerprints with rename-specific diagnostics") {
        val overlay = unsignedRenameOverlay(
            sourceFingerprint = "old-src",
            targetFingerprint = "old-dst",
        ).withComputedHash()

        val result = MigrationOverlayValidator.validate(overlay, validationContext(), "overlays/rename.json")

        result.diagnostics.map { it.code }.shouldContain(MigrationOverlayDiagnostics.STALE_SOURCE_FINGERPRINT)
        result.diagnostics.map { it.code }.shouldContain(MigrationOverlayDiagnostics.STALE_TARGET_FINGERPRINT)
        result.diagnostics.map { it.code }.shouldContain(MigrationOverlayDiagnostics.RENAME_MAPPING_STALE_FINGERPRINT)
    }

    test("F.4 rename overlay rejects ambiguous mappings and chains") {
        val ambiguous = unsignedRenameOverlay(
            entries = listOf(
                renameEntry(id = "rename-a", fromName = "app_user", toName = "users"),
                renameEntry(id = "rename-b", fromName = "APP_USER", toName = "accounts"),
            ),
        ).withComputedHash()
        val chain = unsignedRenameOverlay(
            entries = listOf(
                renameEntry(id = "rename-users", fromName = "users_old", toName = "users"),
                renameEntry(id = "rename-accounts", fromName = "users", toName = "accounts"),
            ),
        ).withComputedHash()

        val ambiguousResult = MigrationOverlayValidator.validate(ambiguous, validationContext(), "overlays/ambiguous.json")
        val chainResult = MigrationOverlayValidator.validate(chain, validationContext(), "overlays/chain.json")

        ambiguousResult.diagnostics.map { it.code }.shouldContain(MigrationOverlayDiagnostics.RENAME_MAPPING_AMBIGUOUS)
        ambiguousResult.diagnostics.map { it.code }.shouldContain(MigrationOverlayDiagnostics.RENAME_MAPPING_CASE_CONFLICT)
        chainResult.diagnostics.map { it.code }.shouldContain(MigrationOverlayDiagnostics.RENAME_MAPPING_CHAIN_UNSUPPORTED)
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

    test("F.0 decorative producer metadata is accepted but secret-bearing metadata blocks") {
        val decorative = unsignedUsingOverlay(
            producerMetadata = mapOf("producer.note" to "human readable"),
        ).withComputedHash()
        val secretBearing = unsignedUsingOverlay(
            producerMetadata = mapOf("producer.note" to "jdbc:postgresql://db.example/prod?password=prod-secret"),
        ).withComputedHash()

        MigrationOverlayValidator.validate(decorative, validationContext(), "overlays/decorative.json")
            .hasBlockers shouldBe false
        MigrationOverlayValidator.validate(secretBearing, validationContext(), "overlays/secret-metadata.json")
            .diagnostics.map { it.code }
            .shouldContain(MigrationOverlayDiagnostics.SECRET_BEARING_PRODUCER_METADATA)
    }

    test("F.0 secret-bearing overlay values block compatibility without leaking values") {
        val secret = "prod-secret-password"
        val overlay = unsignedUsingOverlay(
            entries = listOf(
                usingEntry(
                    upUsingExpression = OverlayText(secret, secret = true),
                    downUsingExpression = OverlayText(secret, secret = true),
                ),
            ),
        ).withComputedHash()

        val result = MigrationOverlayValidator.validate(overlay, validationContext(), "overlays/secret.json")
        val report = MigrationOverlayReport.fromValidation(result)

        result.diagnostics.map { it.code }.shouldContain(MigrationOverlayDiagnostics.SECRET_BEARING_FIELD)
        result.diagnostics.joinToString("\n") { it.message }.contains(secret) shouldBe false
        report.toString().contains(secret) shouldBe false
    }

    test("programmatic entries still require their typed contract fields") {
        val using = unsignedUsingOverlay(
            entries = listOf(
                usingEntry(
                    column = "",
                    upUsingExpression = OverlayText(""),
                ),
            ),
        ).withComputedHash()
        val rename = unsignedRenameOverlay(
            entry = renameEntry(
                objectType = "",
                toName = "",
            ),
        ).withComputedHash()

        val usingResult = MigrationOverlayValidator.validate(using, validationContext(), "overlays/using.json")
        val renameResult = MigrationOverlayValidator.validate(rename, validationContext(), "overlays/rename.json")

        usingResult.diagnostics.map { it.message }.shouldContain("column is required")
        usingResult.diagnostics.map { it.message }.shouldContain("upUsingExpression.value is required")
        renameResult.diagnostics.map { it.message }.shouldContain("objectType is required")
        renameResult.diagnostics.map { it.message }.shouldContain("toName is required")
    }

    test("report exposes source entry id hash and diagnostic code without secret values") {
        val secret = "prod-secret-password"
        val overlay = unsignedUsingOverlay(
            overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
            entries = listOf(
                usingEntry(
                    upUsingExpression = OverlayText(secret, secret = true),
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

private fun unsignedRenameOverlay(
    sourceFingerprint: String = "src-fp",
    targetFingerprint: String = "dst-fp",
    entry: RenameMappingOverlayEntry = renameEntry(),
    entries: List<RenameMappingOverlayEntry> = listOf(entry),
): MigrationOverlay =
    unsignedUsingOverlay(
        overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
        sourceFingerprint = sourceFingerprint,
        targetFingerprint = targetFingerprint,
        entries = entries,
    )

private fun usingEntry(
    id: String = "use-email",
    column: String = "email",
    sourceType: String = "TEXT",
    targetType: String = "TEXT",
    upUsingExpression: OverlayText = OverlayText("COALESCE(profile->>'email', '')"),
    downUsingExpression: OverlayText? = null,
    dataRisk: MigrationOverlayDataRisk = MigrationOverlayDataRisk.NO_DATA_LOSS_EXPECTED,
    conversionReversibility: MigrationOverlayConversionReversibility =
        MigrationOverlayConversionReversibility.AUTOMATIC,
    expressionSource: String = "user",
    reviewedByUser: Boolean = true,
): UsingExpressionOverlayEntry =
    UsingExpressionOverlayEntry(
        id = id,
        table = "users",
        column = column,
        sourceType = sourceType,
        targetType = targetType,
        upUsingExpression = upUsingExpression,
        downUsingExpression = downUsingExpression,
        dataRisk = dataRisk,
        conversionReversibility = conversionReversibility,
        expressionSource = expressionSource,
        reviewedByUser = reviewedByUser,
    )

private fun renameEntry(
    id: String = "rename-users",
    objectType: String = "table",
    fromName: String = "app_user",
    toName: String = "users",
    fromStructureFingerprint: String? = null,
    toStructureFingerprint: String? = null,
): RenameMappingOverlayEntry =
    RenameMappingOverlayEntry(
        id = id,
        objectType = objectType,
        fromName = fromName,
        toName = toName,
        fromStructureFingerprint = fromStructureFingerprint,
        toStructureFingerprint = toStructureFingerprint,
    )
