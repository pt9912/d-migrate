package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.RawTextAuthorship
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.RawTextProvenance
import dev.dmigrate.core.diff.migration.overlay.RawTextProvenanceOverlayEntry

/**
 * Die Herkunft aus den mitgegebenen Overlay-Dokumenten.
 *
 * Gefragt wird nur der **Autorentext**, der zuletzt angewandt wurde — die
 * Katalogform steht zwar daneben, geht aber in diese Antwort nicht ein: ob der
 * Autor etwas geaendert hat, ist eine Aussage ueber zwei Autorentexte.
 *
 * Ohne passenden Eintrag `null`: keine Herkunft, also konservativ planen. Ein
 * Eintrag, den niemand nachtraegt, macht daraus keine stille Zusage.
 */
internal object OverlayRawTextAuthorship {

    fun of(documents: List<MigrationOverlayDocument>): RawTextAuthorship? {
        val byId = documents
            .asSequence()
            .filter { it.overlay.overlayKind == MigrationOverlayKinds.RAW_TEXT_PROVENANCE }
            .flatMap { it.overlay.entries.asSequence() }
            .filterIsInstance<RawTextProvenanceOverlayEntry>()
            // Das zuletzt genannte Dokument gewinnt — dieselbe Regel, nach der
            // mehrfach angegebene Overlays ueberhaupt zusammengefuehrt werden.
            .associateBy { it.id }
        if (byId.isEmpty()) return null
        return RawTextAuthorship { objectType, objectPath, field, keyPosition, authoredNow ->
            val entry = byId[RawTextProvenance.entryId(objectType, objectPath, field, keyPosition)]
                ?: return@RawTextAuthorship null
            entry.appliedAuthorText != authoredNow.orEmpty()
        }
    }
}
