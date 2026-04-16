package dev.dmigrate.cli.commands

import java.security.MessageDigest

/**
 * 0.9.0 Phase C.1 (`docs/ImpPlan-0.9.0-C1.md` §4.2 / §5.1):
 * deterministischer SHA-256-Fingerprint ueber die resume-relevanten
 * Exportoptionen.
 *
 * Der Fingerprint wird in [dev.dmigrate.streaming.checkpoint.CheckpointManifest.optionsFingerprint]
 * gespeichert und vom [DataExportRunner]-Preflight gegen den aktuellen
 * Request verglichen. Ein Mismatch fuehrt zu Exit 3 (§4.5 Phase A).
 *
 * **Bewusst festgezogen**:
 * - Reihenfolge der Felder im Hash-Input ist in C.1 eingefroren; neue
 *   Optionen in 0.9.x-Folgereleases erfordern ein `schemaVersion`-Bump
 *   (`CheckpointManifest.CURRENT_SCHEMA_VERSION`).
 * - Die Tabellenliste geht **in Reihenfolge** in den Hash ein
 *   (`--tables a,b` erzeugt einen anderen Hash als `--tables b,a`),
 *   weil der Ueberplan §4.3 „Tabellenmenge **und** Reihenfolge" als
 *   Kompatibilitaetskriterium verlangt.
 * - `null`-Werte werden als eigener Marker (`<null>`) kodiert, damit
 *   `filter = null` und `filter = "<null>"` nicht dieselbe Signatur
 *   erzeugen.
 */
object ExportOptionsFingerprint {

    // Eine Presence-Byte-Kodierung trennt sauber zwischen
    // Abwesenheit (null) und einem Wert, der zufaellig denselben Text
    // wie ein Sentinel-String haette. `ABSENT` und `PRESENT` stehen am
    // Anfang jedes optionalen Felds; feste Felder bekommen keinen
    // Presence-Marker.
    private const val ABSENT = "0"
    private const val PRESENT = "1"
    private const val FIELD_SEPARATOR = "\u001F"   // ASCII Unit Separator
    private const val LIST_SEPARATOR = "\u001E"    // ASCII Record Separator

    /**
     * Berechnet den SHA-256-Fingerprint fuer den gegebenen Input.
     *
     * @return 64-stelliger Hex-String in Kleinbuchstaben.
     */
    fun compute(input: Input): String {
        val canonical = canonicalForm(input)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
    }

    /**
     * Eingangsdaten fuer den Fingerprint. Wird vom [DataExportRunner]
     * aus dem `DataExportRequest` zusammengestellt, sobald die
     * effektive Tabellenliste aufgeloest ist.
     *
     * `tables` enthaelt die Tabellen **in der Reihenfolge**, in der sie
     * verarbeitet werden (nach `--tables`-Auflösung bzw. Auto-Discovery).
     *
     * `outputMode` ist eine kanonische Zeichenkette:
     * `"stdout"`, `"single-file"` oder `"file-per-table"`.
     */
    data class Input(
        val format: String,
        val encoding: String,
        val csvDelimiter: String,
        val csvBom: Boolean,
        val csvNoHeader: Boolean,
        val csvNullString: String,
        val filter: String?,
        val sinceColumn: String?,
        val since: String?,
        val tables: List<String>,
        val outputMode: String,
        /**
         * 0.9.0 Phase C.1 (`docs/ImpPlan-0.9.0-C1.md` §4.5): kanonischer
         * Zielpfad als String. Fuer `file-per-table` ist das das
         * Ausgabe-Verzeichnis; fuer `single-file` der Dateipfad; fuer
         * `stdout` der literale String `"<stdout>"`. Damit erkennt der
         * Preflight, wenn sich das Ausgabeziel zwischen Laeufen
         * geaendert hat.
         */
        val outputPath: String,
        /**
         * 0.9.0 Phase C.2 (`docs/ImpPlan-0.9.0-C2.md` §4.1): PK-
         * Spaltensignatur pro Tabelle in der Reihenfolge von `tables`.
         * Ein Wechsel des Primaerschluessels invalidiert den Resume-
         * Marker (C.2 stuetzt sich auf den PK als Tie-Breaker). Leere
         * PK-Listen sind erlaubt: die Tabelle hat dann keinen
         * Tie-Breaker und kann ohnehin nur C.1-granular resumen.
         *
         * Fuer Phase-C.1-Callsites, die noch keine PK-Kenntnis haben,
         * bleibt der Default `emptyMap()` — der Fingerprint bleibt dann
         * bytegleich zum Phase-C.1-Vertrag (Presence-Byte).
         */
        val primaryKeysByTable: Map<String, List<String>> = emptyMap(),
    )

    private fun canonicalForm(input: Input): String = buildString {
        appendField("format", input.format)
        appendField("encoding", input.encoding)
        appendField("csvDelimiter", input.csvDelimiter)
        appendField("csvBom", input.csvBom.toString())
        appendField("csvNoHeader", input.csvNoHeader.toString())
        appendField("csvNullString", input.csvNullString)
        appendOptionalField("filter", input.filter)
        appendOptionalField("sinceColumn", input.sinceColumn)
        appendOptionalField("since", input.since)
        appendField("tables", input.tables.joinToString(separator = LIST_SEPARATOR))
        appendField("outputMode", input.outputMode)
        appendField("outputPath", input.outputPath)
        // 0.9.0 Phase C.2: PK-Signatur; Reihenfolge folgt `tables`, um
        // gegen Umsortierungen stabil zu sein. Wenn `primaryKeysByTable`
        // leer ist (Phase-C.1-Callsite), wird **nichts** angehaengt —
        // der Hash bleibt bytegleich zum Phase-C.1-Vertrag.
        if (input.primaryKeysByTable.isNotEmpty()) {
            val pkSignature = input.tables.joinToString(separator = LIST_SEPARATOR) { table ->
                val pk = input.primaryKeysByTable[table].orEmpty()
                "$table:" + pk.joinToString(separator = ",")
            }
            appendField("primaryKeysByTable", pkSignature)
        }
    }

    private fun StringBuilder.appendField(name: String, value: String) {
        append(name)
        append('=')
        append(value)
        append(FIELD_SEPARATOR)
    }

    private fun StringBuilder.appendOptionalField(name: String, value: String?) {
        append(name)
        append('=')
        if (value == null) {
            append(ABSENT)
        } else {
            append(PRESENT)
            append(value)
        }
        append(FIELD_SEPARATOR)
    }
}
