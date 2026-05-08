package dev.dmigrate.server.core.upload.bundle

/**
 * Follow-up AP 2 (Bundle-/Mehrtabellen-Import) — Pflicht-Manifest-
 * Vertrag `seed-bundle.v1`.
 *
 * Manifest-v1-Form (JSON):
 *
 * ```json
 * {
 *   "version": "v1",
 *   "format": "csv",
 *   "tables": [
 *     { "name": "users",  "path": "users.csv" },
 *     { "name": "orders", "path": "orders.csv" }
 *   ]
 * }
 * ```
 *
 * LF-010 / LF-013 / LN-009 / LN-011-Vertragsentscheidungen, die hier durchgesetzt werden:
 *
 * - **Bundle-Level-Format** ist verbindlich; per-Table `format`-Override
 *   ist in v1 nicht erlaubt. Spätere Manifest-Versionen können das
 *   lockern, ohne v1-Importe zu brechen.
 * - **`schemaRef` ist nicht Manifest-Bestandteil**; der Caller liefert
 *   ihn, falls vorhanden, separat im `data_import_start`-Payload.
 * - **Pfade sind relativ zum Bundle-Root**, normalisiert (`/`-Separator),
 *   ohne führenden `/` und ohne `..`-Segmente. Der
 *   [BundleManifestParser]/[BundleManifestValidator] weist absolute
 *   Pfade, Traversal, doppelte Pfade und nicht-strings als
 *   `VALIDATION_ERROR` ab.
 *
 * Pure-Data: keine I/O, keine Parsing-Logik. Der Parser-Adapter sitzt
 * in `adapters/driving/mcp` und konsumiert die Manifest-Bytes; das
 * Ergebnis wird in dieses Modell deserialisiert.
 */
data class BundleManifest(
    val version: String,
    val format: String,
    val tables: List<BundleManifestEntry>,
) {
    init {
        require(version.isNotBlank()) { "manifest version must not be blank" }
        require(format.isNotBlank()) { "manifest format must not be blank" }
        require(tables.isNotEmpty()) { "manifest tables must not be empty" }
        val tableNames = tables.map { it.name }
        require(tableNames.size == tableNames.distinct().size) {
            "manifest tables contain duplicate table names"
        }
        val tablePaths = tables.map { it.path }
        require(tablePaths.size == tablePaths.distinct().size) {
            "manifest tables contain duplicate paths"
        }
    }
}

/**
 * Follow-up AP 2 — eine Tabellen-Bindung im
 * [BundleManifest]. Die Reihenfolge der Liste bestimmt **nicht** die
 * Import-Reihenfolge — der Runner zieht die finale Tabellenreihenfolge
 * aus dem `SchemaRefImportPreflightAdapter` (FK-Topologie). LF-010 / LF-013 / LN-009 / LN-011-
 * Wortlaut: "SchemaRefImportPreflightAdapter validiert Schema und
 * Tabellenreihenfolge, sobald ein `schemaRef` vorliegt."
 *
 * @param name normalisierter Tabellenname (lowercased, gleicher
 *   Vergleichsschlüssel wie `data_import_start.tables`).
 * @param path relativer Pfad innerhalb des Bundles, `/`-Separator,
 *   ohne führenden `/`, ohne `..`-Segment, ohne Backslash.
 */
data class BundleManifestEntry(
    val name: String,
    val path: String,
) {
    init {
        require(name.isNotBlank()) { "manifest entry name must not be blank" }
        require(path.isNotBlank()) { "manifest entry path must not be blank" }
        require(!path.startsWith("/")) { "manifest entry path must not be absolute" }
        require(!path.contains("\\")) { "manifest entry path must not contain backslash" }
        require(!path.contains("..")) { "manifest entry path must not contain '..' segments" }
    }
}
