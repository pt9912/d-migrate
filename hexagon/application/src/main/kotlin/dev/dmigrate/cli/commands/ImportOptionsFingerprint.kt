package dev.dmigrate.cli.commands

import dev.dmigrate.core.util.sha256Hex

/**
 * LF-010 / LF-013 / LN-009 / LN-012: deterministischer SHA-256-Fingerprint
 * ueber die resume-relevanten
 * Importoptionen.
 *
 * Der Fingerprint wird in
 * [dev.dmigrate.streaming.checkpoint.CheckpointManifest.optionsFingerprint]
 * gespeichert und vom [DataImportRunner]-Preflight gegen den aktuellen
 * Request verglichen. Ein Mismatch fuehrt zu Exit 3.
 *
 * **Bewusst festgezogen**:
 * - Reihenfolge der Felder im Hash-Input ist als Import-Resume-Vertrag
 *   eingefroren; neue inkompatible Optionen erfordern ein `schemaVersion`-Bump
 *   (`CheckpointManifest.CURRENT_SCHEMA_VERSION`).
 * - Die Tabellenliste geht **in Reihenfolge** in den Hash ein, weil
 *   Tabellenliste und effektive Importreihenfolge Kompatibilitaetskriterien sind.
 * - `null`-Werte werden als eigener Marker (`<null>`) kodiert, damit
 *   `encoding = null` (auto-detect) und `encoding = "null"` nicht
 *   dieselbe Signatur erzeugen.
 * - Directory-Topologie wird ueber die stabile `table -> inputFile`-
 *   Bindung in den Fingerprint aufgenommen.
 */
object ImportOptionsFingerprint {

    // Eine Presence-Byte-Kodierung trennt sauber zwischen Abwesenheit
    // (`null`) und einem Wert, der zufaellig denselben Text wie ein
    // Sentinel-String haette.
    private const val ABSENT = "0"
    private const val PRESENT = "1"
    private const val FIELD_SEPARATOR = "\u001F"   // ASCII Unit Separator
    private const val LIST_SEPARATOR = "\u001E"    // ASCII Record Separator

    /**
     * Berechnet den SHA-256-Fingerprint fuer den gegebenen Input.
     *
     * @return 64-stelliger Hex-String in Kleinbuchstaben.
     */
    fun compute(input: Input): String = sha256Hex(canonicalForm(input))

    /**
     * Eingangsdaten fuer den Fingerprint. Wird vom [DataImportRunner]
     * aus dem `DataImportRequest` zusammengestellt, nachdem Target und
     * Source aufgeloest sind.
     */
    data class Input(
        /** `"json"`/`"yaml"`/`"csv"`/`"parquet"` — lowercase (S6 Cut A: parquet ergaenzt). */
        val format: String,
        /** CLI-`--encoding`; `null` = Auto-Detect via BOM. */
        val encoding: String?,
        val csvNoHeader: Boolean,
        val csvNullString: String,
        /** CLI-`--on-error`: `"abort"`/`"skip"`/`"log"`. */
        val onError: String,
        /** CLI-`--on-conflict`: `"abort"`/`"skip"`/`"update"`. */
        val onConflict: String,
        /** CLI-`--trigger-mode`: `"fire"`/`"disable"`/`"strict"`. */
        val triggerMode: String,
        val truncate: Boolean,
        val disableFkChecks: Boolean,
        val reseedSequences: Boolean,
        val chunkSize: Int,
        /**
         * Effektive Tabellenliste in Verarbeitungsreihenfolge. Fuer
         * Stdin- und SingleFile-Imports enthaelt sie den einen
         * Zielnamen; fuer Directory-Imports ohne `--tables` bleibt sie
         * leer, bis der Directory-Scan sie gefuellt hat.
         */
        val tables: List<String>,
        /** `"stdin"`/`"single-file"`/`"directory"`. */
        val inputTopology: String,
        /**
         * Kanonischer Eingabepfad:
         * - `"<stdin>"` fuer Stdin
         * - absoluter + normalisierter Dateipfad fuer SingleFile
         * - absoluter + normalisierter Verzeichnispfad fuer Directory
         */
        val inputPath: String,
        /** `"POSTGRESQL"`/`"MYSQL"`/`"SQLITE"`. */
        val targetDialect: String,
        /**
         * Aufgeloeste Ziel-URL (nach `targetResolver` und `urlParser`).
         * Passwoerter sind in diesem String nicht mehr enthalten (der
         * URL-Parser hat sie in `ConnectionConfig.password` verschoben).
         */
        val targetUrl: String,
        /**
         * LF-010 / LF-013 / LN-009: stabile `table -> relativer Dateiname`-Zuordnung fuer
         * Directory-Importe. Eine geaenderte Dateimenge, ein
         * umbenanntes File oder eine geaenderte Reihenfolge (durch
         * `--schema`-Topo-Sort) veraendert damit den Hash und loest
         * Exit 3 aus.
         *
         * Fuer Stdin- und SingleFile-Importe bleibt die Map leer und
         * der Fingerprint ist bytegleich zum Basis-Import-Resume-Vertrag.
         */
        val inputFilesByTable: Map<String, String> = emptyMap(),
    )

    private fun canonicalForm(input: Input): String = buildString {
        appendField("format", input.format)
        appendOptionalField("encoding", input.encoding)
        appendField("csvNoHeader", input.csvNoHeader.toString())
        appendField("csvNullString", input.csvNullString)
        appendField("onError", input.onError)
        appendField("onConflict", input.onConflict)
        appendField("triggerMode", input.triggerMode)
        appendField("truncate", input.truncate.toString())
        appendField("disableFkChecks", input.disableFkChecks.toString())
        appendField("reseedSequences", input.reseedSequences.toString())
        appendField("chunkSize", input.chunkSize.toString())
        appendField("tables", input.tables.joinToString(separator = LIST_SEPARATOR))
        appendField("inputTopology", input.inputTopology)
        appendField("inputPath", input.inputPath)
        appendField("targetDialect", input.targetDialect)
        appendField("targetUrl", input.targetUrl)
        // LF-010 / LF-013 / LN-009: `table -> file`-Signatur fuer Directory-
        // Importe. Reihenfolge folgt `tables`, um bei Umsortierungen
        // stabil zu bleiben. Wenn die Map leer ist (Stdin/SingleFile
        // oder Basis-Callsite), wird **nichts** angehaengt — der
        // Hash bleibt bytegleich zum Basis-Import-Resume-Vertrag.
        if (input.inputFilesByTable.isNotEmpty()) {
            val fileSignature = input.tables.joinToString(separator = LIST_SEPARATOR) { table ->
                val file = input.inputFilesByTable[table] ?: ""
                "$table:$file"
            }
            appendField("inputFilesByTable", fileSignature)
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
