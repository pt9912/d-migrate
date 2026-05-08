package dev.dmigrate.mcp.upload.bundle

import dev.dmigrate.server.core.upload.bundle.BundleFormat
import dev.dmigrate.server.core.upload.bundle.BundleManifest
import dev.dmigrate.server.core.upload.bundle.BundleSecurityLimits
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Follow-up AP 2 — Bundle-Extractor für `seed-bundle.v1.zip`.
 *
 * Plan §4 Sicherheitsanforderungen, die hier durchgesetzt werden:
 *
 * - **Pfad-Hygiene**: absolute Pfade, `..`-Segmente und Backslashes
 *   werden abgewiesen, bevor der Eintrag gelesen wird.
 * - **Symlinks/Hardlinks**: ZIP-Format unterstützt Symlinks via Unix-
 *   Modus-Bit; der Extractor liest jeden Entry als Datei und ignoriert
 *   den Modus, sodass Symlinks niemals als solche im Temp-Dir landen.
 * - **Device-/Special-Files**: ZIP-Spec hat keine Notion davon; alle
 *   Entries werden als reguläre Dateien geschrieben.
 * - **Doppelte Pfade**: case-insensitiv abgelehnt — sonst kollidieren
 *   `Users.csv` und `users.csv` auf einem case-insensitiven FS.
 * - **Bomb-Schutz**: harte Obergrenzen für Entry-Anzahl, entpackte
 *   Gesamtbytes, Einzeldatei-Bytes und Kompressionsverhältnis. Beim
 *   Überschreiten wird der Entry abgebrochen — kein Cleanup nötig,
 *   weil das Temp-Dir vom Aufrufer im finally-Block gelöscht wird
 *   (Plan §4 "Cleanup im finally-Pfad").
 * - **Manifest-Pflicht**: das Bundle muss `manifest.json` im Root
 *   enthalten; sonst `MissingManifest`. Der Manifest-Inhalt muss alle
 *   referenzierten Dateien als Entries vorfinden.
 *
 * Pfad-Sicherheit: Vor jedem `Files.copy` prüft der Extractor, dass
 * der **absolute** Zielpfad mit dem absoluten Bundle-Root als Prefix
 * beginnt. Das fängt CVE-2018-1002200-artige Angriffe ab, bei denen
 * ein präparierter ZIP `..`-Sequenzen im Entry-Namen verbirgt, die das
 * `BundleManifestEntry`-Init-Block-Filter umgehen würden (z. B. `..`
 * via UTF-8-Mehrbyte-Codes oder NUL-Pfade).
 */
class BundleExtractor(
    private val limits: BundleSecurityLimits = BundleSecurityLimits(),
) {

    /**
     * Extrahiert das Bundle aus [archive] in [destination].
     *
     * Vertrag:
     * - [destination] muss existieren und leer sein. Aufrufer sind für
     *   Cleanup zuständig (deleteIfExists im finally-Block).
     * - Liefert deterministisch [BundleExtractionOutcome]; wirft nur
     *   bei IOException auf dem Stream selbst (z. B. Disk Full).
     */
    fun extract(
        archive: InputStream,
        destination: Path,
    ): BundleExtractionOutcome {
        require(Files.isDirectory(destination)) { "destination must be an existing directory" }
        val state = ExtractionState(destination.toAbsolutePath().normalize())
        val streamOutcome = try {
            consumeZipStream(archive, state)
        } catch (_: IOException) {
            return BundleExtractionOutcome.Invalid("bundle archive is not a valid ZIP")
        }
        if (streamOutcome != null) return streamOutcome
        return finalizeFromState(state)
    }

    private fun consumeZipStream(
        archive: InputStream,
        state: ExtractionState,
    ): BundleExtractionOutcome? {
        ZipInputStream(archive).use { zis ->
            while (true) {
                val entry: ZipEntry = zis.nextEntry ?: break
                try {
                    if (entry.isDirectory) continue
                    val outcome = handleEntry(zis, entry, state)
                    if (outcome != null) return outcome
                } finally {
                    zis.closeEntry()
                }
            }
        }
        return null
    }

    private fun handleEntry(
        zis: ZipInputStream,
        entry: ZipEntry,
        state: ExtractionState,
    ): BundleExtractionOutcome? {
        state.entryCount += 1
        if (state.entryCount > limits.maxEntryCount) {
            return BundleExtractionOutcome.Invalid(
                "bundle exceeds maxEntryCount=${limits.maxEntryCount}",
            )
        }
        val entryName = entry.name
        pathHygieneError(entryName)?.let { reason ->
            return BundleExtractionOutcome.Invalid("bundle entry rejected: $reason")
        }
        if (!state.seenPathsLowercase.add(entryName.lowercase())) {
            return BundleExtractionOutcome.Invalid(
                "bundle contains duplicate entry path (case-insensitive)",
            )
        }
        val entryDestination = state.absDestination.resolve(entryName).normalize()
        if (!entryDestination.startsWith(state.absDestination)) {
            return BundleExtractionOutcome.Invalid(
                "bundle entry escapes destination (path traversal)",
            )
        }
        val limited = LimitingInputStream(zis, maxBytes = limits.maxEntryUncompressedBytes)
        return if (entryName == BundleFormat.MANIFEST_PATH_V1) {
            captureManifest(limited, zis, entry, state)
        } else {
            extractDataEntry(limited, entry, entryName, entryDestination, state)
        }
    }

    private fun captureManifest(
        limited: LimitingInputStream,
        zis: ZipInputStream,
        entry: ZipEntry,
        state: ExtractionState,
    ): BundleExtractionOutcome? {
        val manifestRaw = limited.readNBytesSafely(limits.maxManifestBytes.toInt())
            ?: return manifestTooLarge()
        // Wenn die Stream noch Bytes hat, ist das Manifest grösser als das
        // Sub-Limit. Read N bytes truncates; signal an explicit overflow.
        if (zis.read() != -1) return manifestTooLarge()
        state.manifestBytes = manifestRaw
        state.totalUncompressedBytes += manifestRaw.size.toLong()
        if (state.totalUncompressedBytes > limits.maxTotalUncompressedBytes) {
            return totalBytesExceeded()
        }
        return ratioCheck(entry, state.totalUncompressedBytes)
    }

    private fun extractDataEntry(
        limited: LimitingInputStream,
        entry: ZipEntry,
        entryName: String,
        entryDestination: Path,
        state: ExtractionState,
    ): BundleExtractionOutcome? {
        Files.createDirectories(entryDestination.parent)
        Files.copy(limited, entryDestination, StandardCopyOption.REPLACE_EXISTING)
        val written = limited.totalBytesRead
        if (limited.exceeded) {
            return BundleExtractionOutcome.Invalid(
                "entry '$entryName' exceeds maxEntryUncompressedBytes=" +
                    "${limits.maxEntryUncompressedBytes}",
            )
        }
        state.totalUncompressedBytes += written
        if (state.totalUncompressedBytes > limits.maxTotalUncompressedBytes) {
            return totalBytesExceeded()
        }
        ratioCheck(entry, written)?.let { return it }
        state.extractedFiles[entryName] = entryDestination
        return null
    }

    private fun finalizeFromState(state: ExtractionState): BundleExtractionOutcome {
        val rawManifest = state.manifestBytes ?: return BundleExtractionOutcome.MissingManifest
        val parsed = when (val outcome =
            BundleManifestParser.parse(rawManifest, limits.maxManifestBytes)) {
            is BundleManifestParseOutcome.Invalid -> return BundleExtractionOutcome.Invalid(outcome.reason)
            is BundleManifestParseOutcome.Valid -> outcome.manifest
        }
        val missingPaths = parsed.tables.map { it.path }.filterNot { state.extractedFiles.containsKey(it) }
        if (missingPaths.isNotEmpty()) {
            return BundleExtractionOutcome.Invalid(
                "manifest references unknown entries: ${missingPaths.joinToString(",")}",
            )
        }
        return BundleExtractionOutcome.Valid(
            manifest = parsed,
            manifestFingerprint = BundleManifestParser.fingerprint(rawManifest),
            extractedFiles = state.extractedFiles.toMap(),
            totalUncompressedBytes = state.totalUncompressedBytes,
        )
    }

    private fun manifestTooLarge(): BundleExtractionOutcome.Invalid =
        BundleExtractionOutcome.Invalid(
            "manifest exceeds size limit (${limits.maxManifestBytes} bytes)",
        )

    private fun totalBytesExceeded(): BundleExtractionOutcome.Invalid =
        BundleExtractionOutcome.Invalid(
            "bundle exceeds maxTotalUncompressedBytes=${limits.maxTotalUncompressedBytes}",
        )

    private class ExtractionState(val absDestination: Path) {
        var manifestBytes: ByteArray? = null
        val extractedFiles: MutableMap<String, Path> = mutableMapOf()
        val seenPathsLowercase: MutableSet<String> = mutableSetOf()
        var entryCount: Int = 0
        var totalUncompressedBytes: Long = 0L
    }

    private fun ratioCheck(entry: ZipEntry, written: Long): BundleExtractionOutcome.Invalid? {
        if (limits.maxCompressionRatio == 0) return null
        val compressed = entry.compressedSize
        if (compressed <= 0L) return null
        val ratio = written / compressed
        if (ratio > limits.maxCompressionRatio) {
            return BundleExtractionOutcome.Invalid(
                "entry '${entry.name}' exceeds maxCompressionRatio=${limits.maxCompressionRatio}",
            )
        }
        return null
    }

    private fun pathHygieneError(path: String): String? = when {
        path.isBlank() -> "blank entry name"
        path.startsWith("/") -> "absolute path"
        path.contains("\\") -> "backslash"
        path.contains("..") -> "'..' segment"
        path.contains(' ') -> "null byte"
        else -> null
    }
}

/**
 * Follow-up AP 2 — Outcome der Bundle-Extraktion.
 */
sealed interface BundleExtractionOutcome {

    data class Valid(
        val manifest: BundleManifest,
        val manifestFingerprint: String,
        val extractedFiles: Map<String, Path>,
        val totalUncompressedBytes: Long,
    ) : BundleExtractionOutcome

    data class Invalid(val reason: String) : BundleExtractionOutcome

    data object MissingManifest : BundleExtractionOutcome
}

/**
 * Limited InputStream: drosselt das Lesen auf [maxBytes] und markiert
 * [exceeded] = true, sobald das Limit überschritten würde. Wird
 * verwendet, um pro-Entry-Bombenschutz zu erzwingen, ohne den ZIP-
 * Stream selbst zu unterbrechen — der Caller bricht dann mit einem
 * stabilen Wire-Code ab.
 */
private class LimitingInputStream(
    private val delegate: InputStream,
    private val maxBytes: Long,
) : InputStream() {

    var totalBytesRead: Long = 0
        private set

    var exceeded: Boolean = false
        private set

    override fun read(): Int {
        if (exceeded) return -1
        val byte = delegate.read()
        if (byte == -1) return -1
        totalBytesRead += 1
        if (totalBytesRead > maxBytes) {
            exceeded = true
            return -1
        }
        return byte
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (exceeded) return -1
        val remaining = maxBytes - totalBytesRead
        if (remaining <= 0L) {
            exceeded = true
            return -1
        }
        val cap = if (len.toLong() > remaining) remaining.toInt() else len
        val read = delegate.read(b, off, cap)
        if (read == -1) return -1
        totalBytesRead += read
        if (totalBytesRead >= maxBytes) {
            // Probe one extra byte to detect overflow — if there is more
            // content, we mark exceeded so the caller stops.
            val extra = delegate.read()
            if (extra != -1) {
                exceeded = true
            }
        }
        return read
    }

    /**
     * Liest bis zu [n] Bytes; gibt `null` zurück, wenn die Quelle mehr
     * als [n] Bytes liefert.
     */
    fun readNBytesSafely(n: Int): ByteArray? {
        val buffer = delegate.readNBytes(n + 1)
        if (buffer.size > n) return null
        totalBytesRead += buffer.size
        return buffer
    }
}
