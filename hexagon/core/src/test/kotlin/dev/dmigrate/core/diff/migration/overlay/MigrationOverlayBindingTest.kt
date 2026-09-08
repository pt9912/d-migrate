package dev.dmigrate.core.diff.migration.overlay

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainText
import io.kotest.matchers.string.shouldNotContain as shouldNotContainText

/**
 * Woran ein Overlay gebunden ist — und dass die Bindung nicht umgedeutet wird.
 *
 * Ein Uebergangs-Dokument sagt etwas ueber zwei Zustaende, ein
 * Darstellungs-Dokument ueber die Schreibweise **eines** Schemas. Beide in
 * dieselben zwei Felder zu pressen ist nicht bloss unschoen: der Validator
 * prueft `sourceFingerprint` gegen den IST-Zustand, ein Darstellungs-Dokument
 * mit dem SOLL-Abdruck in beiden Feldern kaeme also nur durch, wenn es nichts
 * zu migrieren gibt.
 */
class MigrationOverlayBindingTest : FunSpec({

    /** Eine erfundene Art, die keine Bindungsart verlangt — die Kinder kommen spaeter. */
    val representationKind = "partition-mapping"

    fun renameEntry() = RenameMappingOverlayEntry(
        id = "r1", objectType = "table", fromName = "kunde", toName = "customer",
    )

    fun overlay(
        kind: String,
        binding: MigrationOverlayBinding,
        formatVersion: String? = null,
    ): MigrationOverlay {
        val base = MigrationOverlay(
            overlayKind = kind,
            binding = binding,
            dialect = "postgresql",
            entries = if (kind == MigrationOverlayKinds.RENAME_MAPPING) listOf(renameEntry()) else emptyList(),
            createdAt = "2026-09-08T10:00:00Z",
            createdByVersion = "d-migrate-test",
        )
        return (formatVersion?.let { base.copy(formatVersion = it) } ?: base).withComputedHash()
    }

    fun validate(
        doc: MigrationOverlay,
        representationFingerprint: String? = "schema-fp",
    ) = MigrationOverlayValidator.validate(
        overlay = doc,
        context = MigrationOverlayValidationContext(
            expectedSourceFingerprint = "src-fp",
            expectedTargetFingerprint = "dst-fp",
            expectedDialect = "postgresql",
            expectedRepresentationFingerprint = representationFingerprint,
            supportedOverlayKinds = setOf(
                MigrationOverlayKinds.USING_EXPRESSION,
                MigrationOverlayKinds.RENAME_MAPPING,
                representationKind,
            ),
        ),
        source = "overlays/probe.json",
    ).diagnostics.map { it.code }

    // ── Formatversion folgt der Bindung ──────────────────────────

    test("a transition document stays v1 — it uses nothing v2 has to offer") {
        // Die Version pauschal zu heben braeche das Lesen durch aeltere
        // Staende, ohne dass sich am Dokument etwas geaendert haette.
        overlay(
            MigrationOverlayKinds.RENAME_MAPPING,
            MigrationOverlayBinding.Transition("src-fp", "dst-fp"),
        ).formatVersion shouldBe MigrationOverlay.FORMAT_VERSION_V1
    }

    test("a representation document declares v2 — that is the version that knows the binding") {
        overlay(
            representationKind,
            MigrationOverlayBinding.Representation("schema-fp"),
        ).formatVersion shouldBe MigrationOverlay.FORMAT_VERSION_V2
    }

    // ── Draht-Form ───────────────────────────────────────────────

    test("a transition binding stays flat on the wire, a representation brings its own field") {
        val transition = MigrationOverlayCanonicalJson.encodeUnsigned(
            overlay(MigrationOverlayKinds.RENAME_MAPPING, MigrationOverlayBinding.Transition("src-fp", "dst-fp")),
        )
        withClue(transition) {
            transition shouldContainText "\"sourceFingerprint\": \"src-fp\""
            transition shouldContainText "\"targetFingerprint\": \"dst-fp\""
            transition shouldNotContainText "schemaFingerprint"
        }

        val representation = MigrationOverlayCanonicalJson.encodeUnsigned(
            overlay(representationKind, MigrationOverlayBinding.Representation("schema-fp")),
        )
        withClue(representation) {
            representation shouldContainText "\"schemaFingerprint\": \"schema-fp\""
            representation shouldNotContainText "sourceFingerprint"
            representation shouldNotContainText "targetFingerprint"
        }
    }

    // ── Die Bindungsart gehoert zur Overlay-Art ──────────────────

    test("a kind that describes a transition rejects a representation binding, it does not reinterpret it") {
        for (kind in listOf(MigrationOverlayKinds.RENAME_MAPPING, MigrationOverlayKinds.USING_EXPRESSION)) {
            withClue(kind) {
                validate(overlay(kind, MigrationOverlayBinding.Representation("schema-fp"))) shouldContain
                    MigrationOverlayDiagnostics.BINDING_MISMATCH
            }
        }
    }

    test("the mismatch silences the fingerprint findings — they would compare different things") {
        val codes = validate(
            overlay(MigrationOverlayKinds.RENAME_MAPPING, MigrationOverlayBinding.Representation("schema-fp")),
        )
        codes shouldNotContain MigrationOverlayDiagnostics.STALE_SOURCE_FINGERPRINT
        codes shouldNotContain MigrationOverlayDiagnostics.STALE_SCHEMA_FINGERPRINT
        codes shouldNotContain MigrationOverlayDiagnostics.RENAME_MAPPING_STALE_FINGERPRINT
    }

    // ── Abdruck-Pruefung je Bindung ──────────────────────────────

    test("a representation document binds to the schema it describes") {
        validate(overlay(representationKind, MigrationOverlayBinding.Representation("schema-fp")))
            .shouldNotContainAnyBlockerCode()

        validate(overlay(representationKind, MigrationOverlayBinding.Representation("other-fp"))) shouldContain
            MigrationOverlayDiagnostics.STALE_SCHEMA_FINGERPRINT
    }

    test("a caller that cannot check a representation rejects it instead of guessing") {
        validate(
            overlay(representationKind, MigrationOverlayBinding.Representation("schema-fp")),
            representationFingerprint = null,
        ) shouldContain MigrationOverlayDiagnostics.REPRESENTATION_NOT_APPLICABLE
    }

    test("a representation binding in a v1 document is a contradiction") {
        // Diese Formatversion kennt die Bindung nicht, kann sie also auch nicht
        // meinen.
        validate(
            overlay(
                representationKind,
                MigrationOverlayBinding.Representation("schema-fp"),
                formatVersion = MigrationOverlay.FORMAT_VERSION_V1,
            ),
        ) shouldContain MigrationOverlayDiagnostics.BINDING_NOT_IN_FORMAT_VERSION
    }

    test("a transition document still checks both fingerprints") {
        validate(
            overlay(MigrationOverlayKinds.RENAME_MAPPING, MigrationOverlayBinding.Transition("drifted", "dst-fp")),
        ) shouldContain MigrationOverlayDiagnostics.STALE_SOURCE_FINGERPRINT
        validate(
            overlay(MigrationOverlayKinds.RENAME_MAPPING, MigrationOverlayBinding.Transition("src-fp", "drifted")),
        ) shouldContain MigrationOverlayDiagnostics.STALE_TARGET_FINGERPRINT
    }
})

private fun List<String>.shouldNotContainAnyBlockerCode() {
    filterNot { it == MigrationOverlayDiagnostics.OVERLAY_ACCEPTED } shouldBe emptyList()
}
