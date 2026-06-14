package dev.dmigrate.format.parquet.manifest

import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.SchemaOrigin
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.InputStream
import java.io.Reader
import java.time.Instant

/**
 * Parser fuer `manifest.yaml` / Footer-KV-YAML
 * (AP7 §5, AP11 §5.2). Konvertiert UTF-8-YAML in
 * [ParquetBundleManifest] und ermoeglicht den Bundle-Reader
 * (S5a) und den Single-File-Reader (S4) auf demselben
 * Parser-Pfad.
 *
 * Strenge Validierung:
 * - Pflichtfelder pro AP7 §5.2 sind vorhanden;
 *   `MANIFEST_FIELD_MISSING` bei Lucken.
 * - `tables` ist eine Sequenz; jede Tabelle hat
 *   `table`/`columns` (Bundle: zusaetzlich `file`).
 * - `schemaSource` ist eines der drei AP7 §5.3-Werte.
 *
 * Anchors/Tags werden bewusst verboten (AP7 §9.1 Punkt 2).
 */
internal class ParquetManifestReader(
    private val context: Context = Context.SINGLE_FILE,
) {

    enum class Context { BUNDLE, SINGLE_FILE }

    fun read(input: InputStream): ParquetBundleManifest =
        readYaml(yamlLoad().loadFromInputStream(input))

    fun read(input: Reader): ParquetBundleManifest =
        readYaml(yamlLoad().loadFromReader(input))

    fun read(text: String): ParquetBundleManifest =
        readYaml(yamlLoad().loadFromString(text))

    private fun yamlLoad(): Load = Load(
        LoadSettings.builder()
            .setAllowDuplicateKeys(false)
            .setMaxAliasesForCollections(0)
            .build(),
    )

    private fun readYaml(raw: Any?): ParquetBundleManifest {
        val root = raw as? Map<*, *> ?: throw ParquetManifestParseException(
            "MANIFEST_FIELD_INVALID: manifest root is not a mapping (got ${raw?.javaClass?.simpleName ?: "null"})",
        )
        val formatVersion = requireString(root, "formatVersion")
        val producer = requireString(root, "producer")
        val producerVersion = requireString(root, "producerVersion")
        val exportedAt = Instant.parse(requireString(root, "exportedAt"))
        val schemaSource = parseSchemaSource(requireString(root, "schemaSource"))
        val tables = (root["tables"] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.map(::readTable)
            ?: throw ParquetManifestParseException("MANIFEST_FIELD_MISSING: tables")
        if (tables.isEmpty()) {
            throw ParquetManifestParseException("MANIFEST_FIELD_INVALID: tables must not be empty")
        }
        return ParquetBundleManifest(
            formatVersion = formatVersion,
            producer = producer,
            producerVersion = producerVersion,
            exportedAt = exportedAt,
            schemaSource = schemaSource,
            tables = tables,
        )
    }

    private fun readTable(node: Map<*, *>): ManifestTable {
        val table = requireString(node, "table")
        val file = when (context) {
            Context.BUNDLE -> requireString(node, "file")
            Context.SINGLE_FILE -> (node["file"] as? String).orEmpty()
        }
        val rowCount = (node["rowCount"] as? Number)?.toLong()
        val sha256 = node["sha256"] as? String
        val columns = (node["columns"] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.map(::readColumn)
            ?: throw ParquetManifestParseException("MANIFEST_FIELD_MISSING: tables[].columns")
        return ManifestTable(
            table = table,
            file = file,
            rowCount = rowCount,
            sha256 = sha256,
            columns = columns,
        )
    }

    private fun readColumn(node: Map<*, *>): ManifestColumn {
        val name = requireString(node, "name")
        val nullable = (node["nullable"] as? Boolean)
            ?: throw ParquetManifestParseException("MANIFEST_FIELD_MISSING: tables[].columns[].nullable for '$name'")
        val neutralTypeNode = node["neutralType"] as? Map<*, *>
        val neutralType = neutralTypeNode?.let(::parseManifestNeutralType)
        return ManifestColumn(
            name = name,
            nullable = nullable,
            neutralType = neutralType,
            sqlTypeName = node["sqlTypeName"] as? String,
            jdbcType = (node["jdbcType"] as? Number)?.toInt(),
            precision = (node["precision"] as? Number)?.toInt(),
            scale = (node["scale"] as? Number)?.toInt(),
            timezone = node["timezone"] as? String,
        )
    }

    private fun parseManifestNeutralType(node: Map<*, *>): ManifestNeutralType {
        val kind = requireString(node, "kind")
        val attributes = node.entries
            .filter { it.key != "kind" }
            .associate { (k, v) -> k.toString() to (v ?: "") }
        return ManifestNeutralType(kind = kind, attributes = attributes)
    }

    private fun parseSchemaSource(raw: String): ManifestSchemaSource =
        ManifestSchemaSource.entries.firstOrNull { it.yamlValue == raw }
            ?: throw ParquetManifestParseException(
                "MANIFEST_FIELD_INVALID: schemaSource='$raw' is not one of " +
                    ManifestSchemaSource.entries.joinToString { it.yamlValue },
            )

    private fun requireString(node: Map<*, *>, key: String): String {
        val value = node[key]
            ?: throw ParquetManifestParseException("MANIFEST_FIELD_MISSING: $key")
        return value.toString()
    }
}

/**
 * Wirft der [ParquetManifestReader] bei Parse-/Validierungs-
 * Fehlern. Die `message`-Zeile beginnt mit dem AP7 §9.2-
 * Fehlercode (`MANIFEST_*`), damit CLI-Wiring den Code per
 * `substringBefore(":")` mappen kann.
 */
class ParquetManifestParseException(message: String) : RuntimeException(message)

/**
 * Konvertiert eine [ManifestNeutralType]-Map zurueck in einen
 * konkreten [NeutralType]. Erlaubt Reader-Seite (S4 +
 * Bundle-Reader in S5a) die Sealed-Hierarchie aus dem
 * `kind`-Diskriminator zu rekonstruieren.
 */
internal object ManifestNeutralTypeToCore {

    fun convert(manifest: ManifestNeutralType): NeutralType =
        convertSimple(manifest)
            ?: convertNumeric(manifest)
            ?: convertTextual(manifest)
            ?: convertStructured(manifest)
            ?: throw ParquetManifestParseException(
                "MANIFEST_FIELD_INVALID: unknown NeutralType kind '${manifest.kind}'",
            )

    private fun convertSimple(manifest: ManifestNeutralType): NeutralType? = when (manifest.kind) {
        "Boolean" -> NeutralType.BooleanType
        "Date" -> NeutralType.Date
        "Time" -> NeutralType.Time
        "DateTime" -> NeutralType.DateTime(
            timezone = manifest.attributes["timezone"] != null,
        )
        "Uuid" -> NeutralType.Uuid
        "Json" -> NeutralType.Json
        "Xml" -> NeutralType.Xml
        "Email" -> NeutralType.Email
        "Binary" -> NeutralType.Binary
        else -> null
    }

    private fun convertNumeric(manifest: ManifestNeutralType): NeutralType? = when (manifest.kind) {
        "SmallInt" -> NeutralType.SmallInt
        "Integer" -> NeutralType.Integer
        "Identifier" -> NeutralType.Identifier(
            autoIncrement = manifest.attributes["autoIncrement"] as? Boolean ?: false,
        )
        "BigInteger" -> NeutralType.BigInteger
        "Float" -> NeutralType.Float(
            floatPrecision = when (manifest.attributes["precision"] as? String) {
                "SINGLE" -> FloatPrecision.SINGLE
                else -> FloatPrecision.DOUBLE
            },
        )
        "Decimal" -> NeutralType.Decimal(
            precision = (manifest.attributes["precision"] as? Number)?.toInt()
                ?: throw ParquetManifestParseException("MANIFEST_FIELD_MISSING: Decimal.precision"),
            scale = (manifest.attributes["scale"] as? Number)?.toInt()
                ?: throw ParquetManifestParseException("MANIFEST_FIELD_MISSING: Decimal.scale"),
        )
        else -> null
    }

    private fun convertTextual(manifest: ManifestNeutralType): NeutralType? = when (manifest.kind) {
        "Text" -> NeutralType.Text(
            maxLength = (manifest.attributes["maxLength"] as? Number)?.toInt(),
        )
        "Char" -> NeutralType.Char(
            length = (manifest.attributes["length"] as? Number)?.toInt()
                ?: throw ParquetManifestParseException("MANIFEST_FIELD_MISSING: Char.length"),
        )
        else -> null
    }

    private fun convertStructured(manifest: ManifestNeutralType): NeutralType? = when (manifest.kind) {
        "Enum" -> NeutralType.Enum(
            values = (manifest.attributes["values"] as? List<*>)?.map { it.toString() },
            refType = manifest.attributes["refType"] as? String,
        )
        "Array" -> {
            val element = (manifest.attributes["element"] as? Map<*, *>)
                ?.let { it["kind"] as? String }
                ?: "unknown"
            NeutralType.Array(elementType = element)
        }
        "Geometry" -> NeutralType.Geometry(
            geometryType = GeometryType.of(manifest.attributes["geometryType"] as? String),
            srid = (manifest.attributes["srid"] as? Number)?.toInt(),
        )
        else -> null
    }
}

/**
 * Konvertiert eine [ManifestTable] zurueck in ein
 * [ChunkSchema]. [origin] kommt aus
 * [ManifestSchemaSource]-Cross-Mapping in
 * `ParquetSingleFilePreflight` / Bundle-Reader.
 */
internal fun ManifestTable.toChunkSchema(origin: SchemaOrigin): ChunkSchema =
    ChunkSchema(
        table = table,
        origin = origin,
        columns = columns.map { col ->
            ChunkColumnSchema(
                name = col.name,
                nullable = col.nullable,
                neutralType = col.neutralType?.let { ManifestNeutralTypeToCore.convert(it) }
                    ?: throw ParquetManifestParseException(
                        "MANIFEST_FIELD_MISSING: column '${col.name}' has no neutralType",
                    ),
            )
        },
    )
