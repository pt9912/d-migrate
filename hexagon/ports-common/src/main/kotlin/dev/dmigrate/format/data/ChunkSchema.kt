package dev.dmigrate.format.data

import dev.dmigrate.core.model.NeutralType

/**
 * JDBC-neutrales Tabellenschema fuer den Parquet-Export.
 *
 * Wird vom `StreamingExporter` vor dem ersten Chunk pro
 * Tabelle erzeugt und an den [DataChunkWriter] uebergeben
 * (Migration `begin(table, columns)` -> `begin(table, schema)`
 * passiert in S0b dieses Umbrellas; S0 liefert nur die
 * Typanlage). JSON/YAML/CSV-Writer lesen aus [columns] nur
 * Name und Nullability; Parquet konsumiert zusaetzlich
 * [ChunkColumnSchema.neutralType] fuer das `MessageType`-
 * Mapping (AP2 §8).
 *
 * Bewusst in `hexagon:ports-common` (nicht `ports-write`),
 * damit der Importpfad dasselbe Schemaobjekt referenzieren
 * kann — Parquet-Bundle-Manifest liefert `ChunkSchema` an
 * den Import (AP2 §6.5).
 */
data class ChunkSchema(
    val table: String,
    val columns: List<ChunkColumnSchema>,
    val origin: SchemaOrigin,
)

/**
 * Spaltenmetadaten fuer [ChunkSchema] (AP2 §6.1).
 *
 * [neutralType] traegt Decimal-Precision/Scale,
 * DateTime-Timezone, Geometry/SRID und alle ambivalenten
 * Varianten und ist deshalb als alleinige Typinformation
 * im Schema ausreichend (AP2 §4.4).
 */
data class ChunkColumnSchema(
    val name: String,
    val nullable: Boolean,
    val neutralType: NeutralType,
)

/**
 * Provenance der [ChunkSchema] (AP2 §6.1; AP9 §5 hat
 * `MANIFEST_FALLBACK` ergaenzt).
 *
 * - [JDBC_METADATA]: aus `ResultSetMetaData` der Exportquery.
 * - [SCHEMA_READER]: aus der quellseitigen
 *   `SchemaReader`-Implementierung.
 * - [MERGED]: kombiniert aus mehreren Quellen
 *   (typischerweise JDBC-Metadaten als Primaerquelle plus
 *   `SchemaReader` als Ergaenzung fuer ambivalente Typen).
 * - [MANIFEST_FALLBACK]: best-effort-Schema aus dem
 *   Parquet-Bundle-Manifest (`schemaSource =
 *   "manifest-fallback"`, AP8 §6.2 + AP9 §5). Semantisch
 *   verschieden von [MERGED] — markiert Manifest-Typen
 *   ohne Reader-/JDBC-Provenance.
 */
enum class SchemaOrigin {
    JDBC_METADATA,
    SCHEMA_READER,
    MERGED,
    MANIFEST_FALLBACK,
}
