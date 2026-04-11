package dev.dmigrate.streaming

import java.io.InputStream
import java.nio.file.Path

/**
 * Import-Quelle für den [StreamingImporter][dev.dmigrate.streaming.StreamingImporter].
 *
 * Die CLI löst `--source` erst in Phase E in eine dieser Varianten auf. Der
 * Streaming-Layer arbeitet bewusst nur noch gegen diese bereits aufgelöste
 * Form und kennt keinen rohen Source-String.
 */
sealed class ImportInput {

    /**
     * Eine Tabelle aus einem bereits gelieferten [InputStream].
     *
     * Die explizite Stream-Ownership vermeidet implizite `System.in`-Zugriffe
     * im Streaming-Layer und hält den Pfad in Tests direkt injizierbar.
     */
    data class Stdin(
        val table: String,
        val input: InputStream,
    ) : ImportInput()

    /**
     * Eine Tabelle aus genau einer Datei.
     */
    data class SingleFile(
        val table: String,
        val path: Path,
    ) : ImportInput()

    /**
     * Mehrere Tabellen aus einem Verzeichnis.
     *
     * `tableFilter` begrenzt die Kandidatenmenge. `tableOrder` überschreibt
     * die Default-Reihenfolge explizit und wird in Phase E u.a. für den
     * Schema-/Topo-Sort-Pfad befüllt.
     */
    data class Directory(
        val path: Path,
        val tableFilter: List<String>? = null,
        val tableOrder: List<String>? = null,
    ) : ImportInput()
}
