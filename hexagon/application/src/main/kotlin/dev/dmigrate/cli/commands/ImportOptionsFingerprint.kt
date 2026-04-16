package dev.dmigrate.cli.commands

import java.security.MessageDigest

/**
 * 0.9.0 Phase D.1 (`docs/ImpPlan-0.9.0-D.md` §4.3 / §5.1):
 * deterministischer SHA-256-Fingerprint ueber die resume-relevanten
 * Importoptionen.
 *
 * Der Fingerprint wird in
 * [dev.dmigrate.streaming.checkpoint.CheckpointManifest.optionsFingerprint]
 * gespeichert und vom [DataImportRunner]-Preflight gegen den aktuellen
 * Request verglichen. Ein Mismatch fuehrt zu Exit 3 (§4.8).
 *
 * **Bewusst festgezogen**:
 * - Reihenfolge der Felder im Hash-Input ist in D.1 eingefroren; neue
 *   Optionen in 0.9.x-Folgereleases erfordern ein `schemaVersion`-Bump
 *   (`CheckpointManifest.CURRENT_SCHEMA_VERSION`).
 * - Die Tabellenliste geht **in Reihenfolge** in den Hash ein; der Plan
 *   §4.3 verlangt „Tabellenliste und effektive Importreihenfolge" als
 *   Kompatibilitaetskriterium.
 * - `null`-Werte werden als eigener Marker (`<null>`) kodiert, damit
 *   `encoding = null` (auto-detect) und `encoding = "null"` nicht
 *   dieselbe Signatur erzeugen.
 * - D.1 fasst die Directory-Topologie nur grob (Basis-Pfad + optionale
 *   `--tables`); die stabile `table -> inputFile`-Bindung liefert D.4
 *   (§4.5) und extend dann den Fingerprint entsprechend.
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
    fun compute(input: Input): String {
        val canonical = canonicalForm(input)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
    }

    /**
     * Eingangsdaten fuer den Fingerprint. Wird vom [DataImportRunner]
     * aus dem `DataImportRequest` zusammengestellt, nachdem Target und
     * Source aufgeloest sind.
     */
    data class Input(
        /** `"json"`/`"yaml"`/`"csv"` — lowercase. */
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
         * in D.1 leer (wird in D.4 aus dem Directory-Scan gefuellt).
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
         * 0.9.0 Phase D.4 (`docs/ImpPlan-0.9.0-D.md` §4.5 / §5.4):
         * stabile `table -> relativer Dateiname`-Zuordnung fuer
         * Directory-Importe. Eine geaenderte Dateimenge, ein
         * umbenanntes File oder eine geaenderte Reihenfolge (durch
         * `--schema`-Topo-Sort) veraendert damit den Hash und loest
         * Exit 3 aus.
         *
         * Fuer Stdin- und SingleFile-Importe bleibt die Map leer und
         * der Fingerprint ist bytegleich zum Phase-D.1-Vertrag.
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
        // 0.9.0 Phase D.4: `table -> file`-Signatur fuer Directory-
        // Importe. Reihenfolge folgt `tables`, um bei Umsortierungen
        // stabil zu bleiben. Wenn die Map leer ist (Stdin/SingleFile
        // oder Phase-D.1-Callsite), wird **nichts** angehaengt — der
        // Hash bleibt bytegleich zum Phase-D.1-Vertrag.
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
