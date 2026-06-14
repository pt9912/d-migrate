package dev.dmigrate.format.parquet.manifest

import dev.dmigrate.format.data.SchemaOrigin
import java.time.Instant

/**
 * Datenklassen-Vertrag fuer `manifest.yaml` in einem Parquet-
 * Bundle (AP7 §5.1/§5.2,
 * `docs/planning/done/parquet-manifest-format.md`).
 *
 * Producer-Seite (S3b): wird vom [ParquetManifestWriter] aus
 * dem Export-Lauf befuellt und serialisiert. Reader-Seite
 * (S5a) liest dieselbe Struktur via `ParquetManifestReader`
 * zurueck.
 *
 * Format-Versionierung per AP7 §8.1: `1.0` ist Startversion;
 * MAJOR-Bump signalisiert inkompatible Aenderungen.
 */
internal data class ParquetBundleManifest(
    val formatVersion: String,
    val producer: String,
    val producerVersion: String,
    val exportedAt: Instant,
    val schemaSource: ManifestSchemaSource,
    val tables: List<ManifestTable>,
) {
    companion object {
        const val CURRENT_FORMAT_VERSION: String = "1.0"
        const val PRODUCER_LITERAL: String = "d-migrate"
    }
}

internal data class ManifestTable(
    val table: String,
    val file: String,
    val rowCount: Long?,
    val sha256: String?,
    val columns: List<ManifestColumn>,
)

internal data class ManifestColumn(
    val name: String,
    val nullable: Boolean,
    val neutralType: ManifestNeutralType?,
    val sqlTypeName: String?,
    val jdbcType: Int?,
    val precision: Int?,
    val scale: Int?,
    val timezone: String?,
)

/** AP7 §5.4 — `kind`-diskriminierte YAML-Repraesentation der NeutralType-Hierarchie. */
internal data class ManifestNeutralType(
    val kind: String,
    val attributes: Map<String, Any> = emptyMap(),
)

/**
 * AP7 §5.3 — drei Provenance-Werte; spiegelt
 * `dev.dmigrate.format.data.SchemaOrigin` direkt (mit
 * `MERGED` faellt auf `jdbc-metadata`, weil AP7 nur drei
 * Werte kennt — die `MERGED`-Information lebt heute nicht im
 * Manifest).
 */
internal enum class ManifestSchemaSource(val yamlValue: String) {
    SCHEMA_READER("schema-reader"),
    JDBC_METADATA("jdbc-metadata"),
    MANIFEST_FALLBACK("manifest-fallback");

    companion object {
        fun fromSchemaOrigin(origin: SchemaOrigin): ManifestSchemaSource = when (origin) {
            SchemaOrigin.SCHEMA_READER -> SCHEMA_READER
            SchemaOrigin.JDBC_METADATA -> JDBC_METADATA
            SchemaOrigin.MERGED -> JDBC_METADATA
            SchemaOrigin.MANIFEST_FALLBACK -> MANIFEST_FALLBACK
        }
    }
}
