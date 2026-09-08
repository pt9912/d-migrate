package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDiagnostics
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.format.overlay.MigrationOverlayJsonCodec
import dev.dmigrate.format.overlay.MigrationOverlayJsonDecodeException
import java.nio.file.Path
import kotlin.io.path.inputStream

/**
 * Liest Overlay-Dateien von der Platte — eine Stelle fuer alle Befehle, die
 * `--migration-overlay` kennen.
 *
 * Ein Lesefehler wird **nicht** geworfen, sondern als
 * [MigrationOverlayLoadFailure] gefuehrt: ein unlesbares Overlay ist ein
 * Befund des Laufs wie jeder andere und gehoert in denselben Bericht wie ein
 * abgelehntes.
 */
internal object MigrationOverlayFileLoader {

    data class Loaded(
        val documents: List<MigrationOverlayDocument>,
        val failures: List<MigrationOverlayLoadFailure>,
    )

    fun loadAll(paths: List<Path>): Loaded {
        if (paths.isEmpty()) return Loaded(emptyList(), emptyList())
        val codec = MigrationOverlayJsonCodec()
        val documents = mutableListOf<MigrationOverlayDocument>()
        val failures = mutableListOf<MigrationOverlayLoadFailure>()
        for (path in paths) {
            try {
                path.inputStream().use { input ->
                    documents += MigrationOverlayDocument(source = path.toString(), overlay = codec.read(input))
                }
            } catch (e: MigrationOverlayJsonDecodeException) {
                failures += MigrationOverlayLoadFailure(source = path.toString(), diagnosticCode = e.code)
            } catch (_: Exception) {
                failures += MigrationOverlayLoadFailure(
                    source = path.toString(),
                    diagnosticCode = MigrationOverlayDiagnostics.FIELD_TYPE_MISMATCH,
                )
            }
        }
        return Loaded(documents, failures)
    }

    /** Nur die lesbaren Dokumente — fuer Befehle, die Lesefehler selbst melden. */
    fun load(paths: List<Path>): List<MigrationOverlayDocument> = loadAll(paths).documents
}
