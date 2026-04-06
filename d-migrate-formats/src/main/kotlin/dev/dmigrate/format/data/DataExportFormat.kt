package dev.dmigrate.format.data

/**
 * Unterstützte Datenexport-Formate (Plan §6.15).
 *
 * Wird vom CLI über `--format json|yaml|csv` als Pflicht-Argument
 * eingegeben und vom [DataChunkWriterFactory] in den passenden
 * [DataChunkWriter] aufgelöst.
 */
enum class DataExportFormat(val cliName: String) {
    JSON("json"),
    YAML("yaml"),
    CSV("csv");

    companion object {
        /** @throws IllegalArgumentException wenn der Name unbekannt ist. */
        fun fromCli(name: String): DataExportFormat = entries.firstOrNull { it.cliName == name.lowercase() }
            ?: throw IllegalArgumentException(
                "Unknown export format '$name'. Supported: ${entries.joinToString(", ") { it.cliName }}"
            )
    }
}
