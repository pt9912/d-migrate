package dev.dmigrate.server.core.upload.bundle

/**
 * Follow-up AP 2 — versionierte Bundle-Format-Identitäten.
 *
 * LF-010 / LF-013 / LN-009 / LN-011: "`bundleFormat` ist ein versionierter Wert, z. B.
 * `seed-bundle.v1.zip` oder `seed-bundle.v1.tar`; freie Strings werden
 * nicht akzeptiert." Diese Konstanten sind die einzigen erlaubten Werte.
 *
 * v1 deckt explizit nur `.zip` ab — TAR ist als zukünftige Erweiterung
 * vorgesehen, aber nicht Teil dieses APs (Scope-Carve-out, LF-010 / LF-013 / LN-009 / LN-011
 * "Nicht-Ziele"). Die `ALL`-Map dient als Wire-Whitelist für die
 * Validierung in `artifact_upload_init` und `data_import_start`.
 */
object BundleFormat {

    const val SEED_BUNDLE_V1_ZIP: String = "seed-bundle.v1.zip"

    /**
     * Wire-Whitelist akzeptierter `bundleFormat`-Werte. Caller, die
     * einen anderen Wert übergeben, erhalten `VALIDATION_ERROR(bundleFormat)`.
     */
    val ALL: Set<String> = setOf(SEED_BUNDLE_V1_ZIP)

    /**
     * Manifest-Pfad-Konstante für v1. In v1 trägt das Manifest immer
     * den Pfad `manifest.json` im Bundle-Root.
     */
    const val MANIFEST_PATH_V1: String = "manifest.json"

    /**
     * Manifest-Format-Tag, das der [BundleManifest.version]-Wert
     * tragen muss, damit der Manifest als v1 akzeptiert wird.
     */
    const val MANIFEST_VERSION_V1: String = "v1"
}

/**
 * Follow-up AP 2 — Sicherheitsgrenzen für die Bundle-Extraktion.
 *
 * LF-010 / LF-013 / LN-009 / LN-011 Tests: "absolute Pfade, Traversal, Symlink, doppelte Entries,
 * zu viele Entries, zu grosse entpackte Daten und unbekannte Entry-
 * Typen liefern VALIDATION_ERROR." Diese Grenzen werden vom
 * Bundle-Extractor enforced; Überschreitungen liefern stabile
 * `VALIDATION_ERROR`-Details ohne lokale Pfade (LF-010 / LF-013 / LN-009 / LN-011 wortlaut).
 *
 * Defaults sind bewusst konservativ — Operator kann sie pro Deployment
 * über [DataRunnerDependencies] hochsetzen, sollte aber nie über
 * 1 GiB / 100 MiB pro Entry / 1000 Entries hinausgehen ohne
 * Quota-/Disk-Audit.
 */
data class BundleSecurityLimits(
    /** Maximale Anzahl Datei-Entries (inkl. Manifest). */
    val maxEntryCount: Int = DEFAULT_MAX_ENTRY_COUNT,
    /** Maximale entpackte Gesamtbytes über alle Entries. */
    val maxTotalUncompressedBytes: Long = DEFAULT_MAX_TOTAL_UNCOMPRESSED_BYTES,
    /** Maximale entpackte Bytes pro Entry. */
    val maxEntryUncompressedBytes: Long = DEFAULT_MAX_ENTRY_UNCOMPRESSED_BYTES,
    /** Maximale Manifest-Bytes (Sub-Limit innerhalb der Entry-Bytes). */
    val maxManifestBytes: Long = DEFAULT_MAX_MANIFEST_BYTES,
    /**
     * Maximales Kompressionsverhältnis (entpackt / komprimiert) pro
     * Entry. Schützt gegen Zip-Bomb-Angriffe, bei denen kleine
     * Archive zu vielfacher Größe expandieren. `0` deaktiviert die
     * Prüfung — nur in Tests verwenden.
     */
    val maxCompressionRatio: Int = DEFAULT_MAX_COMPRESSION_RATIO,
) {
    init {
        require(maxEntryCount > 0) { "maxEntryCount must be positive" }
        require(maxTotalUncompressedBytes > 0) { "maxTotalUncompressedBytes must be positive" }
        require(maxEntryUncompressedBytes > 0) { "maxEntryUncompressedBytes must be positive" }
        require(maxManifestBytes > 0) { "maxManifestBytes must be positive" }
        require(maxCompressionRatio >= 0) { "maxCompressionRatio must not be negative" }
    }

    companion object {
        const val DEFAULT_MAX_ENTRY_COUNT: Int = 100
        const val DEFAULT_MAX_TOTAL_UNCOMPRESSED_BYTES: Long = 1L * 1024 * 1024 * 1024 // 1 GiB
        const val DEFAULT_MAX_ENTRY_UNCOMPRESSED_BYTES: Long = 100L * 1024 * 1024 // 100 MiB
        const val DEFAULT_MAX_MANIFEST_BYTES: Long = 1L * 1024 * 1024 // 1 MiB
        const val DEFAULT_MAX_COMPRESSION_RATIO: Int = 100
    }
}
