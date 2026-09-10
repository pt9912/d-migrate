package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayBinding
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.RawTextProvenanceFields
import dev.dmigrate.core.diff.migration.overlay.RawTextProvenanceOverlayEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Die Herkunft beantwortet genau eine Frage: hat der **Autor** den Text seit
 * dem letzten Anwenden geaendert? Verglichen werden dabei zwei Autorentexte —
 * die Katalogform steht im Dokument daneben, geht hier aber nicht ein.
 */
class OverlayRawTextAuthorshipTest : FunSpec({

    fun entry(
        id: String = "constraint:orders.chk_age:expression",
        objectType: String = "constraint",
        path: List<String> = listOf("orders", "chk_age"),
        field: String = RawTextProvenanceFields.CHECK_EXPRESSION,
        keyPosition: Int? = null,
        applied: String = "age >= 18",
    ) = RawTextProvenanceOverlayEntry(
        id = id, objectType = objectType, objectPath = path, field = field, keyPosition = keyPosition,
        appliedAuthorText = applied, observedCatalogText = "((age >= 18))",
    )

    fun documents(
        vararg entries: RawTextProvenanceOverlayEntry,
        kind: String = MigrationOverlayKinds.RAW_TEXT_PROVENANCE,
    ) =
        listOf(
            MigrationOverlayDocument(
                source = "overlays/provenance.json",
                overlay = MigrationOverlay(
                    overlayKind = kind,
                    binding = MigrationOverlayBinding.Representation("schema-fp"),
                    dialect = "postgresql",
                    entries = entries.toList(),
                    createdAt = "2026-09-08T10:00:00Z",
                    createdByVersion = "d-migrate-test",
                ),
            ),
        )

    test("derselbe Autorentext heisst: der Autor hat nichts geaendert") {
        val authorship = OverlayRawTextAuthorship.of(documents(entry()))!!

        authorship.authorChanged(
            "constraint", listOf("orders", "chk_age"), RawTextProvenanceFields.CHECK_EXPRESSION, null, "age >= 18",
        ) shouldBe false
    }

    test("ein anderer Autorentext heisst: doch") {
        val authorship = OverlayRawTextAuthorship.of(documents(entry()))!!

        authorship.authorChanged(
            "constraint", listOf("orders", "chk_age"), RawTextProvenanceFields.CHECK_EXPRESSION, null, "age >= 21",
        ) shouldBe true
    }

    test("die Katalogform geht nicht ein — sonst stuende sie wieder gegen den Dateitext") {
        val authorship = OverlayRawTextAuthorship.of(documents(entry()))!!

        // Der Katalogtext des Eintrags ist `((age >= 18))`. Wuerde er
        // verglichen, kaeme hier `false` heraus.
        authorship.authorChanged(
            "constraint", listOf("orders", "chk_age"), RawTextProvenanceFields.CHECK_EXPRESSION, null, "((age >= 18))",
        ) shouldBe true
    }

    test("ohne passenden Eintrag gibt es keine Auskunft, keine stille Zusage") {
        val authorship = OverlayRawTextAuthorship.of(documents(entry()))!!

        authorship.authorChanged(
            "view", listOf("v"), RawTextProvenanceFields.VIEW_QUERY, null, "SELECT 1",
        ).shouldBeNull()
    }

    test("die Stellung gehoert zum Schluessel — ein anderer Schluessel ist ein anderer Eintrag") {
        val authorship = OverlayRawTextAuthorship.of(
            documents(
                entry(
                    id = "index:orders.i:key-expression:1", objectType = "index", path = listOf("orders", "i"),
                    field = RawTextProvenanceFields.INDEX_KEY_EXPRESSION, keyPosition = 1, applied = "upper(nm)",
                ),
            ),
        )!!

        authorship.authorChanged(
            "index", listOf("orders", "i"), RawTextProvenanceFields.INDEX_KEY_EXPRESSION, 1, "upper(nm)",
        ) shouldBe false
        authorship.authorChanged(
            "index", listOf("orders", "i"), RawTextProvenanceFields.INDEX_KEY_EXPRESSION, 2, "upper(nm)",
        ).shouldBeNull()
    }

    test("ohne Herkunfts-Dokument gibt es gar keine Auskunft") {
        OverlayRawTextAuthorship.of(emptyList()).shouldBeNull()
        OverlayRawTextAuthorship.of(documents(kind = MigrationOverlayKinds.RENAME_MAPPING)).shouldBeNull()
    }
})
