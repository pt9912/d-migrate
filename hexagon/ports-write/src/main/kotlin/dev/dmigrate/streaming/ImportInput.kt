package dev.dmigrate.streaming

import dev.dmigrate.format.data.ChunkSchema
import java.io.InputStream
import java.nio.file.Path

/**
 * Import-Quelle für den [StreamingImporter][dev.dmigrate.streaming.StreamingImporter].
 *
 * LF-010 / LF-013: Die CLI löst `--source` in eine dieser Varianten auf. Der
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
     * die Default-Reihenfolge explizit und wird u.a. für den
     * Schema-/Topo-Sort-Pfad befüllt.
     */
    data class Directory(
        val path: Path,
        val tableFilter: List<String>? = null,
        val tableOrder: List<String>? = null,
    ) : ImportInput()

    /**
     * AP9 §4.1: Bereits aufgeloestes Bundle (Multi-Table-/Directory-
     * Import mit verpflichtendem Bundle-Manifest, vgl. Parquet Cut A
     * Umbrella §3 S5a). Bewusst Parquet-frei im Vertrag: der Port spricht
     * nur "Bundle", der Adapter befuellt das mit format-spezifischer
     * Information.
     *
     * - [bundleRoot]: Bundle-Wurzelverzeichnis (Pfad zur
     *   `manifest.yaml`-tragenden Directory). Wird vom
     *   `ImportPreflightValidator` als `inputPath`-Wert benutzt.
     * - [tables]: effektive, vom Resolver (AP8 §4.4) aufgeloeste
     *   Reihenfolge nach Filter/Order-Auswertung; Streaming-Layer
     *   iteriert linear darueber.
     * - [resumeFingerprint]: Pflicht, weil Bundle-Importe ohne
     *   Fingerprint nicht resumable waeren (AP8 §8.1). Wird beim
     *   Initial-Lauf bedingungslos persistiert.
     */
    data class ResolvedBundle(
        val bundleRoot: Path,
        val tables: List<ResolvedBundleTableBinding>,
        val resumeFingerprint: BundleResumeFingerprint,
    ) : ImportInput()

    /**
     * AP11 §6.2 / AP12 §5.1: bereits aufgeloester Single-File-Import
     * (Parquet mit Footer-KV `d-migrate.manifest` oder Footer-Fallback,
     * vgl. Parquet Cut A Umbrella §3 S5b). Bewusst Parquet-frei im
     * Vertrag — der CLI-Resolver (S6) baut das DTO nach dem
     * `ParquetSingleFilePreflight.phase1/phase2`-Lauf.
     *
     * - [table]: vom Preflight aufgeloester Tabellenname (AP11 §5.5
     *   Precedence: CLI `--table` vor Footer-KV).
     * - [path]: absolute, normalisierte Datei-Path.
     * - [schema]: bereits aufgeloestes `ChunkSchema` (entweder aus dem
     *   Footer-KV oder aus dem Phase-2-Target-JDBC-Fallback).
     * - [contentSha256]: SHA-256 ueber den vollstaendigen Datei-Bytestrom
     *   fuer Resume-Konsistenz (AP11 §6.4); `null` wenn der Initial-Lauf
     *   ohne Resume-Aktivierung lief.
     */
    data class ResolvedSingleFile(
        val table: String,
        val path: Path,
        val schema: ChunkSchema,
        val contentSha256: String? = null,
    ) : ImportInput()
}

/**
 * AP9 §4.1: Pfad + Schema pro Tabelle in einem
 * [ImportInput.ResolvedBundle]. Bewusst kein InputStream-Vertrag —
 * Bundle-Reader sind seekbar (`parquet-libraries.md` §7.1).
 *
 * [expectedSha256] ist optionaler Manifest-`tables[].sha256`-Wert
 * fuer die Live-Integritaetspruefung im AP7-Preflight. `null` bedeutet:
 * Producer hat keinen Hash geschrieben, Live-Pruefung wird im
 * Normal-Import geskippt. Beim `--resume`-Lauf ist der Hash dagegen
 * Pflicht — der `ImportCheckpointManager` wirft sonst
 * `BUNDLE_RESUME_REQUIRES_FILE_HASHES`.
 */
data class ResolvedBundleTableBinding(
    val table: String,
    val path: Path,
    val schema: ChunkSchema,
    val expectedSha256: String? = null,
)

/**
 * AP9 §4.1: Fingerprint fuer den Checkpoint-Vergleich beim `--resume`.
 * Wird vom CLI-Resolver aus dem aktuellen Bundle-Manifest gebaut und
 * beim Initial-Lauf in `BundleCheckpointSpecifics` persistiert. Beim
 * `--resume` vergleicht der `ImportCheckpointManager` die persistierten
 * Werte gegen die frisch berechneten.
 *
 * `fileSha256ByTable` ist bewusst NICHT Teil des Fingerprints — die
 * Per-Tabelle-Hashes leben im `manifest.yaml` und sind implizit durch
 * [manifestSha256] abgedeckt (AP8 §8.2).
 */
data class BundleResumeFingerprint(
    val manifestSha256: String,
    val formatVersion: String,
    val producerVersion: String,
    val tableOrder: List<String>,
)
