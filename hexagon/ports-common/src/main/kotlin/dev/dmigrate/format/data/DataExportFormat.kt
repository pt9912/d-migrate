package dev.dmigrate.format.data

/**
 * Unterstützte Datenexport-Formate (LF-009).
 *
 * Wird vom CLI über `--format json|yaml|csv` als Pflicht-Argument
 * eingegeben und vom [DataChunkWriterFactory] in den passenden
 * [DataChunkWriter] aufgelöst.
 */
enum class DataExportFormat(
    val cliName: String,
    val fileExtensions: List<String>,
    /**
     * Review-Finding F1: Wenn `true`, braucht das Format zufaelligen
     * Read-Zugriff auf die Quelle (Footer-Seek, Row-Group-Sprung) und
     * kann nicht aus Stdin gelesen werden — CLI-Preflight lehnt
     * `--source -` mit `--format <this>` ab. Aktuell nur Parquet, aber
     * Arrow IPC, ORC oder gemultiplexte Container koennen ohne neue
     * CLI-Branch folgen.
     */
    val requiresSeekableInput: Boolean = false,
    /**
     * Symmetrische Capability fuer den Export-Pfad: Wenn `true`, kann
     * das Format nicht nach Stdout geschrieben werden (Footer-Schreib-
     * Seek noetig). CLI-Preflight lehnt das Fehlen von `--output` ab.
     */
    val requiresSeekableOutput: Boolean = false,
) {
    JSON("json", listOf("json")),
    YAML("yaml", listOf("yaml", "yml")),
    CSV("csv", listOf("csv")),
    PARQUET(
        cliName = "parquet",
        fileExtensions = listOf("parquet"),
        requiresSeekableInput = true,
        requiresSeekableOutput = true,
    );

    companion object {
        /** @throws IllegalArgumentException wenn der Name unbekannt ist. */
        fun fromCli(name: String): DataExportFormat = entries.firstOrNull { it.cliName == name.lowercase() }
            ?: throw IllegalArgumentException(
                "Unknown export format '$name'. Supported: ${entries.joinToString(", ") { it.cliName }}"
            )
    }
}
