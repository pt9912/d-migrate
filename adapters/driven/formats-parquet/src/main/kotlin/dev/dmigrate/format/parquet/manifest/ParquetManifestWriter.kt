package dev.dmigrate.format.parquet.manifest

import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.common.FlowStyle
import org.snakeyaml.engine.v2.common.NonPrintableStyle
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.format.DateTimeFormatter

/**
 * Schreibt `manifest.yaml` aus einem [ParquetBundleManifest]
 * in einen [OutputStream] (S3b Cut A, AP7 §10.1).
 *
 * Konventionen:
 *
 * - YAML serialisiert ueber `snakeyaml-engine` 2.7 (siehe
 *   parquet-libraries.md §3.2 Vorentscheidung) mit
 *   BLOCK-Flow und konsistenter Key-Reihenfolge.
 * - Top-Level-Key-Reihenfolge entspricht AP7 §5.1
 *   (`formatVersion`, `producer`, `producerVersion`,
 *   `exportedAt`, `schemaSource`, `tables`).
 * - Pro Tabelle: `table`, `file`, `rowCount`, `sha256`,
 *   `columns` (in dieser Reihenfolge); optionale Felder
 *   werden weggelassen, wenn `null`.
 * - SHA-256 als 64-stelliger Lowercase-Hex-String ohne
 *   `sha256:`-Praefix (AP7 §7.3).
 * - `exportedAt` als UTC ISO-8601 mit `Z`-Suffix
 *   (`Instant.toString()`-Default).
 */
internal class ParquetManifestWriter {

    fun write(manifest: ParquetBundleManifest, output: OutputStream) {
        OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
            writeToWriter(manifest, writer)
        }
    }

    private fun writeToWriter(manifest: ParquetBundleManifest, writer: Writer) {
        val dump = Dump(
            DumpSettings.builder()
                .setDefaultFlowStyle(FlowStyle.BLOCK)
                .setIndent(YAML_INDENT)
                .setSplitLines(false)
                .setNonPrintableStyle(NonPrintableStyle.ESCAPE)
                .build(),
        )
        writer.write(dump.dumpToString(toYamlNode(manifest)))
    }

    private fun toYamlNode(manifest: ParquetBundleManifest): Map<String, Any?> = linkedMapOf(
        "formatVersion" to manifest.formatVersion,
        "producer" to manifest.producer,
        "producerVersion" to manifest.producerVersion,
        "exportedAt" to DateTimeFormatter.ISO_INSTANT.format(manifest.exportedAt),
        "schemaSource" to manifest.schemaSource.yamlValue,
        "tables" to manifest.tables.map(::tableToYamlNode),
    )

    private fun tableToYamlNode(table: ManifestTable): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        put("table", table.table)
        put("file", table.file)
        table.rowCount?.let { put("rowCount", it) }
        table.sha256?.let { put("sha256", it) }
        put("columns", table.columns.map(::columnToYamlNode))
    }

    private fun columnToYamlNode(column: ManifestColumn): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        put("name", column.name)
        put("nullable", column.nullable)
        column.sqlTypeName?.let { put("sqlTypeName", it) }
        column.jdbcType?.let { put("jdbcType", it) }
        column.precision?.let { put("precision", it) }
        column.scale?.let { put("scale", it) }
        column.timezone?.let { put("timezone", it) }
        column.neutralType?.let { put("neutralType", neutralTypeToYamlNode(it)) }
    }

    private fun neutralTypeToYamlNode(neutralType: ManifestNeutralType): Map<String, Any> {
        val result = linkedMapOf<String, Any>("kind" to neutralType.kind)
        neutralType.attributes.forEach { (key, value) -> result[key] = value }
        return result
    }

    private companion object {
        private const val YAML_INDENT = 2
    }
}

/**
 * Berechnet den SHA-256-Digest einer Parquet-Datei als
 * 64-stelligen Lowercase-Hex-String (AP7 §7.2/§7.3). Liest die
 * Datei genau einmal und streamt direkt in den `MessageDigest`.
 */
internal object Sha256DigestCalculator {

    private const val BUFFER_SIZE = 8 * 1024

    fun compute(file: java.nio.file.Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        java.nio.file.Files.newInputStream(file).use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }
}
