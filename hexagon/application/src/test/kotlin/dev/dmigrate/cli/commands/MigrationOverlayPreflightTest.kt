package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayConversionReversibility
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDataRisk
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDiagnostic
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDiagnostics
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayReportItem
import dev.dmigrate.core.diff.migration.overlay.OverlayText
import dev.dmigrate.core.diff.migration.overlay.RenameMappingOverlayEntry
import dev.dmigrate.core.diff.migration.overlay.UsingExpressionOverlayEntry
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class MigrationOverlayPreflightTest : FunSpec({

    test("valid rename overlay satisfies F0 preflight and surfaces OVERLAY_ACCEPTED provenance") {
        val plan = planWith(
            overlay = renameOverlay().withComputedHash(),
            source = "overlays/rename.json",
        )

        val result = MigrationOverlayPreflight.validate(plan, DatabaseDialect.POSTGRESQL)

        result.hasBlockers shouldBe false
        // F.4 cli-inline-overlay §3.4: a valid entry emits an
        // INFO-severity OVERLAY_ACCEPTED provenance row so report
        // consumers can attribute it back to source/entryId.
        result.reportItems.single().diagnosticCode shouldBe MigrationOverlayDiagnostics.OVERLAY_ACCEPTED
        result.reportItems.single().severity shouldBe MigrationOverlayDiagnostic.Severity.INFO
        result.reportItems.single().entryId shouldBe "rename-users"
        // Provenance rows must not become Failure-Diagnostics.
        result.diagnostics shouldBe emptyList()
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
        // F.4 cli-inline-overlay review fix: when the document is
        // rejected at doc-level, no OVERLAY_ACCEPTED INFO row is
        // emitted for its entries — they would not be applied
        // anyway, and the provenance row would read as misleading
        // success next to the HASH_MISSING blocker.
        val items = result.reportItems
        items.single().diagnosticCode shouldBe MigrationOverlayDiagnostics.HASH_MISSING
        items.single().entryId shouldBe null
        items.any { it.diagnosticCode == MigrationOverlayDiagnostics.OVERLAY_ACCEPTED } shouldBe false
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

    test("F.4 rename-mapping-invalid-enum: stale fingerprint emits RENAME_MAPPING_INVALID, not MANUAL_ACTION_REQUIRED") {
        // Stale fingerprint produces both OVERLAY_STALE_SOURCE_FINGERPRINT
        // (generic doc-level) and OVERLAY_RENAME_MAPPING_STALE_FINGERPRINT
        // (rename-bound) — the latter is the structured trigger for the
        // new reason, so the result must list TWO blockers and pin the
        // primary to RENAME_MAPPING_INVALID.
        val overlay = renameOverlay().copy(sourceFingerprint = "drifted").withComputedHash()
        val plan = planWith(overlay, "overlays/rename.json")

        val result = MigrationOverlayPreflight.validate(plan, DatabaseDialect.POSTGRESQL)
        val failure = MigrationOverlayPreflight.buildFailureResult(plan, result)

        result.reportItems.map { it.diagnosticCode }
            .shouldContain(MigrationOverlayDiagnostics.RENAME_MAPPING_STALE_FINGERPRINT)
        failure.isBlocked shouldBe true
        failure.primaryBlockedReason shouldBe MigrationBlockedReason.RENAME_MAPPING_INVALID
        failure.blockers.map { it.reason } shouldContainExactly listOf(
            MigrationBlockedReason.RENAME_MAPPING_INVALID,
            MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
        )
        // The rename-bound diagnostic lives under RENAME_MAPPING_INVALID,
        // the generic doc-level stale-source diagnostic under
        // MANUAL_ACTION_REQUIRED — the classifier MUST NOT lump them.
        failure.blockers
            .single { it.reason == MigrationBlockedReason.RENAME_MAPPING_INVALID }
            .diagnostics.map { it.code }
            .shouldContain(MigrationOverlayDiagnostics.RENAME_MAPPING_STALE_FINGERPRINT)
        failure.blockers
            .single { it.reason == MigrationBlockedReason.MANUAL_ACTION_REQUIRED }
            .diagnostics.map { it.code }
            .shouldContain(MigrationOverlayDiagnostics.STALE_SOURCE_FINGERPRINT)
    }

    test("F.4 rename-mapping-invalid-enum: ambiguous mapping emits RENAME_MAPPING_INVALID") {
        // Two rename entries with the same source name but different
        // targets — the validator emits OVERLAY_RENAME_MAPPING_AMBIGUOUS
        // per entry; classifier groups them all under
        // RENAME_MAPPING_INVALID.
        val overlay = renameOverlay().copy(
            entries = listOf(
                RenameMappingOverlayEntry(
                    id = "rename-a", objectType = "table",
                    fromName = "app_user", toName = "users",
                ),
                RenameMappingOverlayEntry(
                    id = "rename-b", objectType = "table",
                    fromName = "app_user", toName = "members",
                ),
            ),
        ).withComputedHash()
        val plan = planWith(overlay, "overlays/rename.json")

        val result = MigrationOverlayPreflight.validate(plan, DatabaseDialect.POSTGRESQL)
        val failure = MigrationOverlayPreflight.buildFailureResult(plan, result)

        failure.primaryBlockedReason shouldBe MigrationBlockedReason.RENAME_MAPPING_INVALID
        failure.blockers.single().reason shouldBe MigrationBlockedReason.RENAME_MAPPING_INVALID
        failure.blockers.single().diagnostics.map { it.code }.toSet()
            .shouldContain(MigrationOverlayDiagnostics.RENAME_MAPPING_AMBIGUOUS)
    }

    test("F.4 rename-mapping-invalid-enum: duplicate mapping emits RENAME_MAPPING_INVALID") {
        val overlay = renameOverlay().copy(
            entries = listOf(
                RenameMappingOverlayEntry(
                    id = "rename-a", objectType = "table",
                    fromName = "app_user", toName = "users",
                ),
                RenameMappingOverlayEntry(
                    id = "rename-b", objectType = "table",
                    fromName = "app_user", toName = "users",
                ),
            ),
        ).withComputedHash()
        val plan = planWith(overlay, "overlays/rename.json")

        val failure = MigrationOverlayPreflight.buildFailureResult(
            plan, MigrationOverlayPreflight.validate(plan, DatabaseDialect.POSTGRESQL),
        )

        failure.primaryBlockedReason shouldBe MigrationBlockedReason.RENAME_MAPPING_INVALID
        failure.blockers.single().diagnostics.map { it.code }
            .shouldContain(MigrationOverlayDiagnostics.RENAME_MAPPING_DUPLICATE)
    }

    test("F.4 rename-mapping-invalid-enum: chain mapping emits RENAME_MAPPING_INVALID") {
        val overlay = renameOverlay().copy(
            entries = listOf(
                RenameMappingOverlayEntry(
                    id = "rename-a", objectType = "table",
                    fromName = "old_users", toName = "app_user",
                ),
                RenameMappingOverlayEntry(
                    id = "rename-b", objectType = "table",
                    fromName = "app_user", toName = "users",
                ),
            ),
        ).withComputedHash()
        val plan = planWith(overlay, "overlays/rename.json")

        val failure = MigrationOverlayPreflight.buildFailureResult(
            plan, MigrationOverlayPreflight.validate(plan, DatabaseDialect.POSTGRESQL),
        )

        failure.primaryBlockedReason shouldBe MigrationBlockedReason.RENAME_MAPPING_INVALID
        failure.blockers.single().diagnostics.map { it.code }
            .shouldContain(MigrationOverlayDiagnostics.RENAME_MAPPING_CHAIN_UNSUPPORTED)
    }

    test("F.4 rename-mapping-invalid-enum: case-conflict mapping emits RENAME_MAPPING_INVALID") {
        val overlay = renameOverlay().copy(
            entries = listOf(
                RenameMappingOverlayEntry(
                    id = "rename-a", objectType = "table",
                    fromName = "app_user", toName = "users",
                ),
                RenameMappingOverlayEntry(
                    id = "rename-b", objectType = "table",
                    fromName = "App_User", toName = "USERS",
                ),
            ),
        ).withComputedHash()
        val plan = planWith(overlay, "overlays/rename.json")

        val failure = MigrationOverlayPreflight.buildFailureResult(
            plan, MigrationOverlayPreflight.validate(plan, DatabaseDialect.POSTGRESQL),
        )

        failure.primaryBlockedReason shouldBe MigrationBlockedReason.RENAME_MAPPING_INVALID
        failure.blockers.single().diagnostics.map { it.code }.toSet()
            .shouldContain(MigrationOverlayDiagnostics.RENAME_MAPPING_CASE_CONFLICT)
    }

    test("F.4 rename-mapping-invalid-enum: objectType outside {table,column} whitelist blocks with RENAME_MAPPING_INVALID") {
        // Pre-Plan-Gate enforces the current rename objectType
        // whitelist. Every objectType the later
        // View-/Trigger-/Routine-Rename slice will own
        // (`view`, `trigger`, `function`, `procedure`, `sequence`,
        // `materialized_view`) blocks the rename overlay today so a
        // half-finished build cannot silently emit a Drop+Add for an
        // object class it does not know how to rename.
        val unsupportedObjectTypes = listOf(
            "view", "trigger", "function", "procedure", "sequence", "materialized_view",
        )
        unsupportedObjectTypes.forEach { objectType ->
            val overlay = renameOverlay().copy(
                entries = listOf(
                    RenameMappingOverlayEntry(
                        id = "rename-$objectType", objectType = objectType,
                        fromName = "old_$objectType", toName = "new_$objectType",
                    ),
                ),
            ).withComputedHash()
            val plan = planWith(overlay, "overlays/rename.json")

            val result = MigrationOverlayPreflight.validate(plan, DatabaseDialect.POSTGRESQL)
            val failure = MigrationOverlayPreflight.buildFailureResult(plan, result)

            // The blocker is OVERLAY_UNKNOWN_ENTRY_KIND tagged with
            // the rename-mapping kind + the offending objectType.
            val unknownEntry = result.reportItems.single {
                it.diagnosticCode == MigrationOverlayDiagnostics.UNKNOWN_ENTRY_KIND
            }
            unknownEntry.entryKind shouldBe MigrationOverlayKinds.RENAME_MAPPING
            unknownEntry.renameObjectType shouldBe objectType
            failure.primaryBlockedReason shouldBe MigrationBlockedReason.RENAME_MAPPING_INVALID
        }
    }

    test("F.4 rename-mapping-invalid-enum: generic UNKNOWN_ENTRY_KIND load failure stays MANUAL_ACTION_REQUIRED") {
        // A load-failure diagnostic that happens to use
        // OVERLAY_UNKNOWN_ENTRY_KIND but is not tagged with the
        // rename-mapping kind MUST NOT be lifted into the new reason —
        // the classifier reads structured fields, not the bare code.
        val result = MigrationOverlayPreflight.validate(
            planWithoutOverlays(),
            DatabaseDialect.POSTGRESQL,
            loadFailures = listOf(
                MigrationOverlayLoadFailure(
                    source = "overlays/bad.json",
                    diagnosticCode = MigrationOverlayDiagnostics.UNKNOWN_ENTRY_KIND,
                ),
            ),
        )
        val failure = MigrationOverlayPreflight.buildFailureResult(planWithoutOverlays(), result)

        result.reportItems.single().entryKind shouldBe null
        result.reportItems.single().renameObjectType shouldBe null
        failure.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        failure.blockers.single().reason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
    }

    test("F.4 rename-mapping-invalid-enum: pre-plan API surfaces the whitelist via parameter") {
        // The planless entry point accepts the whitelist as an explicit
        // parameter so the caller — and tests like this — can pin the
        // current set. Default is `{table, column}`; the test passes a
        // custom expanded set and verifies the validator now accepts
        // `view` without blocking. This is the contract the later
        // View-Rename slice will rely on.
        val overlay = renameOverlay().copy(
            entries = listOf(
                RenameMappingOverlayEntry(
                    id = "rename-view", objectType = "view",
                    fromName = "v_old", toName = "v_new",
                ),
            ),
        ).withComputedHash()
        val result = MigrationOverlayPreflight.validateBeforePlan(
            documents = listOf(MigrationOverlayDocument(source = "overlays/rename.json", overlay = overlay)),
            sourceFingerprint = "src-fp",
            targetFingerprint = "dst-fp",
            dialect = "postgresql",
            supportedRenameObjectTypes = setOf("table", "column", "view"),
        )

        result.hasBlockers shouldBe false
    }

    test("F.4 rename-mapping-invalid-enum: synthetic OBJECT_RENAME_UNSUPPORTED blocker classifies as RENAME_MAPPING_INVALID") {
        // §4.3 last classifier row: the forward-looking renderer
        // diagnostic from the upcoming View-/Trigger-/Routine-Rename
        // slice MUST map to RENAME_MAPPING_INVALID even though no
        // production code path emits it today. We wire a synthetic
        // preflight result directly into buildFailureResult so the
        // classifier table stays honest until the renderer that
        // produces the code lands. Without this test a future
        // refactor could silently drop the entry.
        val item = MigrationOverlayReportItem(
            source = "overlays/rename.json",
            entryId = "rename-x",
            overlayHash = "<computed>",
            diagnosticCode = "OBJECT_RENAME_UNSUPPORTED",
            severity = MigrationOverlayDiagnostic.Severity.BLOCKER,
            entryKind = MigrationOverlayKinds.RENAME_MAPPING,
            renameObjectType = "view",
        )
        val diagnostic = DiffDiagnostic(
            code = "OBJECT_RENAME_UNSUPPORTED",
            message = "Forward-looking renderer diagnostic",
            severity = DiffDiagnostic.Severity.BLOCKER,
        )
        val synthetic = MigrationOverlayPreflightResult(
            reportItems = listOf(item),
            diagnostics = listOf(diagnostic),
        )

        val failure = MigrationOverlayPreflight.buildFailureResult(planWithoutOverlays(), synthetic)

        failure.primaryBlockedReason shouldBe MigrationBlockedReason.RENAME_MAPPING_INVALID
        failure.blockers.single().reason shouldBe MigrationBlockedReason.RENAME_MAPPING_INVALID
    }

    test("F.4 rename-mapping-invalid-enum: case-folded rename objectType passes the whitelist") {
        // Operator typing 'TABLE' or 'Column' must not trip the
        // whitelist — the validator already case-folds source/target
        // rename keys, and the whitelist comparison now matches.
        listOf("TABLE", "Table", "COLUMN", "Column").forEach { typed ->
            val overlay = renameOverlay().copy(
                entries = listOf(
                    RenameMappingOverlayEntry(
                        id = "rename-$typed", objectType = typed,
                        fromName = "old_name", toName = "new_name",
                    ),
                ),
            ).withComputedHash()
            val result = MigrationOverlayPreflight.validateBeforePlan(
                documents = listOf(MigrationOverlayDocument(source = "overlays/rename.json", overlay = overlay)),
                sourceFingerprint = "src-fp",
                targetFingerprint = "dst-fp",
                dialect = "postgresql",
            )

            result.reportItems.none { it.diagnosticCode == MigrationOverlayDiagnostics.UNKNOWN_ENTRY_KIND } shouldBe true
        }
    }

    test("F.4 rename-mapping-invalid-enum: planless gate validates rename overlays without a DiffResult") {
        // §3 demands an entry point that takes explicit fingerprints
        // and document list and never touches DiffResult.* — the gate
        // can therefore run before DiffPlanner.plan(...). This test
        // pins that contract: only documents + fingerprints + dialect
        // + (optional) whitelist + load failures, and a usable
        // hasBlockers signal.
        val overlay = renameOverlay().copy(
            entries = listOf(
                RenameMappingOverlayEntry(
                    id = "rename-a", objectType = "view",
                    fromName = "v_old", toName = "v_new",
                ),
            ),
        ).withComputedHash()
        val pre = MigrationOverlayPreflight.validateBeforePlan(
            documents = listOf(MigrationOverlayDocument(source = "overlays/rename.json", overlay = overlay)),
            sourceFingerprint = "src-fp",
            targetFingerprint = "dst-fp",
            dialect = "postgresql",
        )
        pre.hasBlockers shouldBe true
        pre.reportItems.single { it.diagnosticCode == MigrationOverlayDiagnostics.UNKNOWN_ENTRY_KIND }
            .renameObjectType shouldBe "view"
    }

    test("F.4 cli-inline-overlay: cross-document duplicate emits RENAME_MAPPING_INVALID with both source/entryId pairs") {
        // File overlay + inline overlay both describe the exact same
        // rename — the per-doc validator wouldn't catch this because
        // each doc looks individually valid. The cross-doc gate must
        // block before plan().
        val fileOverlay = renameOverlay().copy(
            entries = listOf(
                RenameMappingOverlayEntry(
                    id = "file-entry",
                    objectType = "table",
                    fromName = "app_user",
                    toName = "users",
                ),
            ),
        ).withComputedHash()
        val inlineOverlay = renameOverlay().copy(
            entries = listOf(
                RenameMappingOverlayEntry(
                    id = "rename-table-0",
                    objectType = "table",
                    fromName = "app_user",
                    toName = "users",
                ),
            ),
        ).withComputedHash()

        val result = MigrationOverlayPreflight.validateBeforePlan(
            documents = listOf(
                MigrationOverlayDocument(source = "overlays/file.json", overlay = fileOverlay),
                MigrationOverlayDocument(source = "cli-inline", overlay = inlineOverlay),
            ),
            sourceFingerprint = "src-fp",
            targetFingerprint = "dst-fp",
            dialect = "postgresql",
        )

        result.hasBlockers shouldBe true
        val dups = result.reportItems.filter {
            it.diagnosticCode == MigrationOverlayDiagnostics.RENAME_MAPPING_DUPLICATE
        }
        // One item per side of the conflict.
        dups.map { it.source }.toSet() shouldBe setOf("overlays/file.json", "cli-inline")
        dups.map { it.entryId }.toSet() shouldBe setOf("file-entry", "rename-table-0")
        // Classifier folds these into the new reason.
        val failure = MigrationOverlayPreflight.buildFailureResult(planWithoutOverlays(), result)
        failure.primaryBlockedReason shouldBe MigrationBlockedReason.RENAME_MAPPING_INVALID
    }

    test("F.4 cli-inline-overlay: cross-document ambiguous source emits RENAME_MAPPING_INVALID") {
        // Two overlays claim the same source name but disagree on
        // the target — RENAME_MAPPING_AMBIGUOUS across docs.
        val fileOverlay = renameOverlay().copy(
            entries = listOf(
                RenameMappingOverlayEntry(
                    id = "file-entry", objectType = "table",
                    fromName = "app_user", toName = "users",
                ),
            ),
        ).withComputedHash()
        val inlineOverlay = renameOverlay().copy(
            entries = listOf(
                RenameMappingOverlayEntry(
                    id = "rename-table-0", objectType = "table",
                    fromName = "app_user", toName = "members",
                ),
            ),
        ).withComputedHash()

        val result = MigrationOverlayPreflight.validateBeforePlan(
            documents = listOf(
                MigrationOverlayDocument(source = "overlays/file.json", overlay = fileOverlay),
                MigrationOverlayDocument(source = "cli-inline", overlay = inlineOverlay),
            ),
            sourceFingerprint = "src-fp",
            targetFingerprint = "dst-fp",
            dialect = "postgresql",
        )
        val ambiguous = result.reportItems.filter {
            it.diagnosticCode == MigrationOverlayDiagnostics.RENAME_MAPPING_AMBIGUOUS
        }
        ambiguous.map { it.source }.toSet() shouldBe setOf("overlays/file.json", "cli-inline")
        val failure = MigrationOverlayPreflight.buildFailureResult(planWithoutOverlays(), result)
        failure.primaryBlockedReason shouldBe MigrationBlockedReason.RENAME_MAPPING_INVALID
    }

    test("F.4 cli-inline-overlay: same rename across docs WITH same target produces no AMBIGUOUS, only DUPLICATE") {
        // Defensive: an exact duplicate should NOT also fire as
        // ambiguous (operator would see two findings for one fault).
        val fileOverlay = renameOverlay().withComputedHash()
        val inlineOverlay = renameOverlay().withComputedHash()
        val result = MigrationOverlayPreflight.validateBeforePlan(
            documents = listOf(
                MigrationOverlayDocument(source = "overlays/file.json", overlay = fileOverlay),
                MigrationOverlayDocument(source = "cli-inline", overlay = inlineOverlay),
            ),
            sourceFingerprint = "src-fp",
            targetFingerprint = "dst-fp",
            dialect = "postgresql",
        )
        val crossDoc = result.reportItems.filter {
            it.diagnosticCode == MigrationOverlayDiagnostics.RENAME_MAPPING_DUPLICATE ||
                it.diagnosticCode == MigrationOverlayDiagnostics.RENAME_MAPPING_AMBIGUOUS
        }
        crossDoc.map { it.diagnosticCode }.toSet() shouldBe setOf(
            MigrationOverlayDiagnostics.RENAME_MAPPING_DUPLICATE,
        )
    }

    test("F.4 cli-inline-overlay review fix: same source path passed twice still fires DUPLICATE") {
        // Two distinct MigrationOverlayDocument instances with the
        // same source string (e.g. operator passes
        // `--migration-overlay foo.json --migration-overlay foo.json`)
        // are still two distinct documents and must trigger a
        // cross-doc DUPLICATE finding. The gate keys on docIndex
        // (list position) instead of source string to catch this.
        val overlay = renameOverlay().withComputedHash()
        val result = MigrationOverlayPreflight.validateBeforePlan(
            documents = listOf(
                MigrationOverlayDocument(source = "overlays/file.json", overlay = overlay),
                MigrationOverlayDocument(source = "overlays/file.json", overlay = overlay),
            ),
            sourceFingerprint = "src-fp",
            targetFingerprint = "dst-fp",
            dialect = "postgresql",
        )
        result.reportItems.filter {
            it.diagnosticCode == MigrationOverlayDiagnostics.RENAME_MAPPING_DUPLICATE
        }.size shouldBe 2
    }

    test("F.4 cli-inline-overlay: single document with rename mapping does NOT produce cross-doc findings") {
        // Smoke: cross-doc gate must only fire when >=2 distinct
        // sources are present.
        val overlay = renameOverlay().withComputedHash()
        val result = MigrationOverlayPreflight.validateBeforePlan(
            documents = listOf(MigrationOverlayDocument(source = "overlays/single.json", overlay = overlay)),
            sourceFingerprint = "src-fp",
            targetFingerprint = "dst-fp",
            dialect = "postgresql",
        )
        result.reportItems.none {
            it.diagnosticCode == MigrationOverlayDiagnostics.RENAME_MAPPING_DUPLICATE ||
                it.diagnosticCode == MigrationOverlayDiagnostics.RENAME_MAPPING_AMBIGUOUS
        } shouldBe true
        // Pin §3.4: valid entry surfaces as INFO OVERLAY_ACCEPTED.
        result.reportItems.single { it.diagnosticCode == MigrationOverlayDiagnostics.OVERLAY_ACCEPTED }
            .source shouldBe "overlays/single.json"
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
