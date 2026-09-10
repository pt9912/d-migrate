package dev.dmigrate.core.diff.migration.overlay

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * Das Herkunfts-Overlay traegt, was aus der Katalogform nicht rekonstruierbar
 * ist: welcher Autorentext zuletzt angewandt wurde. Ein Eintrag, den niemand
 * zuordnen kann, waere ein stiller Platzhalter — die Pruefung faengt das ab.
 */
class RawTextProvenanceOverlayTest : FunSpec({

    fun document(vararg entries: RawTextProvenanceOverlayEntry) = MigrationOverlay(
        overlayKind = MigrationOverlayKinds.RAW_TEXT_PROVENANCE,
        binding = MigrationOverlayBinding.Representation("schema-fp"),
        dialect = "postgresql",
        entries = entries.toList(),
        createdAt = "2026-09-08T10:00:00Z",
        createdByVersion = "d-migrate-test",
    ).withComputedHash()

    fun codes(doc: MigrationOverlay) = MigrationOverlayValidator.validate(
        overlay = doc,
        context = MigrationOverlayValidationContext(
            expectedSourceFingerprint = "src-fp",
            expectedTargetFingerprint = "dst-fp",
            expectedDialect = "postgresql",
            expectedRepresentationFingerprint = "schema-fp",
        ),
        source = "overlays/provenance.json",
    ).diagnostics.map { it.code }

    val checkEntry = RawTextProvenanceOverlayEntry(
        id = "chk", objectType = "constraint", objectPath = listOf("orders", "chk_status"),
        field = RawTextProvenanceFields.CHECK_EXPRESSION,
        appliedAuthorText = "status = 'A'", observedCatalogText = "((status = 'A'::text))",
    )

    test("die Herkunft beschreibt ein Schema, keinen Uebergang — also Darstellungs-Bindung") {
        MigrationOverlay.requiredBindingFor(MigrationOverlayKinds.RAW_TEXT_PROVENANCE) shouldBe
            MigrationOverlay.BindingKind.REPRESENTATION
    }

    test("ein vollstaendiger Eintrag geht durch") {
        codes(document(checkEntry)).shouldBeEmpty()
    }

    test("ein leerer Autorentext ist zulaessig — das Feld kann zuvor ungesetzt gewesen sein") {
        codes(document(checkEntry.copy(appliedAuthorText = "", observedCatalogText = ""))).shouldBeEmpty()
    }

    test("ein Ausdrucks-Schluessel ohne Stellung ist nicht zuzuordnen") {
        // Die Reihenfolge der Schluessel eines Index ist bedeutungstragend; der
        // Ausdruck allein sagt nicht, welcher gemeint ist.
        val entry = checkEntry.copy(
            objectType = "index", objectPath = listOf("t", "i"),
            field = RawTextProvenanceFields.INDEX_KEY_EXPRESSION,
        )

        codes(document(entry)) shouldContain MigrationOverlayDiagnostics.REQUIRED_FIELD_MISSING
    }

    test("mit Stellung geht derselbe Eintrag durch") {
        val entry = checkEntry.copy(
            objectType = "index", objectPath = listOf("t", "i"),
            field = RawTextProvenanceFields.INDEX_KEY_EXPRESSION, keyPosition = 1,
        )

        codes(document(entry)).shouldBeEmpty()
    }

    test("ein Feldname ausserhalb der vier rohen SQL-Felder wird abgelehnt") {
        codes(document(checkEntry.copy(field = "body"))) shouldContain
            MigrationOverlayDiagnostics.REQUIRED_FIELD_MISSING
    }

    test("ein leerer Objektpfad ebenso — ohne ihn ist der Eintrag nicht zuzuordnen") {
        codes(document(checkEntry.copy(objectPath = emptyList()))) shouldContain
            MigrationOverlayDiagnostics.REQUIRED_FIELD_MISSING
        codes(document(checkEntry.copy(objectPath = listOf("orders", " ")))) shouldContain
            MigrationOverlayDiagnostics.REQUIRED_FIELD_MISSING
    }
})
