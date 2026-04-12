package dev.dmigrate.core.data

/**
 * Signalisiert, dass die Input-Daten des Importers strukturell nicht zum
 * Zielschema passen — z.B. ein Header-Name fehlt im Target, eine JSON-/
 * YAML-Row bringt zusätzliche Felder mit, oder eine headerlose CSV-Row
 * hat eine falsche Feldanzahl.
 *
 * Lebt bewusst in `hexagon:core` (statt in `adapters:driven:driver-common`),
 * weil sowohl `adapters:driven:formats` (Reader) als auch
 * `adapters:driven:streaming` (Importer) und die Writer-Schicht dieselbe
 * fachliche Ausnahme werfen und fangen — ohne dass `formats` eine
 * Modul-Kante zu `driver-common`
 * bekommt (siehe implementation-plan-0.4.0.md §3.1.1 letzter Absatz,
 * §3.8).
 *
 * Der `StreamingImporter` mapped diese Exception auf den `--on-error`-
 * Pfad (§6.4 / §6.5): bei `abort` → Exit 3, bei `skip|log` → Chunk-
 * Granularität.
 */
class ImportSchemaMismatchException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
