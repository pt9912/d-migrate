package dev.dmigrate.format.parquet

import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import org.apache.parquet.schema.LogicalTypeAnnotation
import org.apache.parquet.schema.MessageType
import org.apache.parquet.schema.PrimitiveType
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import org.apache.parquet.schema.Type.Repetition
import org.apache.parquet.schema.Types

/**
 * Konvertiert ein [ChunkSchema] in einen Parquet-[MessageType]
 * gemaess der AP2 §8 Mapping-Tabelle
 * (`docs/planning/done-archive/parquet-schema-source.md`).
 *
 * Bewusst pure Funktion ohne Seiteneffekt. Decimal-Physik
 * richtet sich nach Precision (INT32 bis 9, INT64 bis 18,
 * sonst `FIXED_LEN_BYTE_ARRAY`); Temporal-Einheit ist MICROS
 * (AP2 §8). Nicht aufgefuehrte `NeutralType`-Varianten
 * (`Geometry`, `Enum`, `Array`) sind in S3 minimal als BINARY
 * gemappt — semantische Details
 * (`geometryType`/`srid`/`elementType`) liegen in
 * Bundle-Manifest (AP7) / Footer-KV (AP11).
 */
internal object ChunkSchemaToParquetMessageType {

    fun convert(schema: ChunkSchema): MessageType =
        MessageType(schema.table, schema.columns.map(::convertColumn))

    private fun convertColumn(column: ChunkColumnSchema): PrimitiveType {
        val repetition = if (column.nullable) Repetition.OPTIONAL else Repetition.REQUIRED
        return when (val t = column.neutralType) {
            is NeutralType.BooleanType ->
                PrimitiveType(repetition, PrimitiveTypeName.BOOLEAN, column.name)

            is NeutralType.SmallInt -> Types.primitive(PrimitiveTypeName.INT32, repetition)
                .`as`(LogicalTypeAnnotation.intType(BITS_16, /* isSigned = */ true))
                .named(column.name)

            is NeutralType.Integer -> Types.primitive(PrimitiveTypeName.INT32, repetition)
                .`as`(LogicalTypeAnnotation.intType(BITS_32, /* isSigned = */ true))
                .named(column.name)

            is NeutralType.Identifier -> Types.primitive(PrimitiveTypeName.INT32, repetition)
                .`as`(LogicalTypeAnnotation.intType(BITS_32, /* isSigned = */ true))
                .named(column.name)

            is NeutralType.BigInteger -> Types.primitive(PrimitiveTypeName.INT64, repetition)
                .`as`(LogicalTypeAnnotation.intType(BITS_64, /* isSigned = */ true))
                .named(column.name)

            is NeutralType.Float -> {
                val primitive = when (t.floatPrecision) {
                    FloatPrecision.SINGLE -> PrimitiveTypeName.FLOAT
                    FloatPrecision.DOUBLE -> PrimitiveTypeName.DOUBLE
                }
                PrimitiveType(repetition, primitive, column.name)
            }

            is NeutralType.Decimal -> decimalType(t.precision, t.scale, column.name, repetition)

            is NeutralType.Text, is NeutralType.Email -> Types.primitive(PrimitiveTypeName.BINARY, repetition)
                .`as`(LogicalTypeAnnotation.stringType())
                .named(column.name)

            is NeutralType.Char -> Types.primitive(PrimitiveTypeName.BINARY, repetition)
                .`as`(LogicalTypeAnnotation.stringType())
                .named(column.name)

            is NeutralType.Binary -> Types.primitive(PrimitiveTypeName.BINARY, repetition)
                .named(column.name)

            is NeutralType.Date -> Types.primitive(PrimitiveTypeName.INT32, repetition)
                .`as`(LogicalTypeAnnotation.dateType())
                .named(column.name)

            is NeutralType.Time -> Types.primitive(PrimitiveTypeName.INT32, repetition)
                .`as`(LogicalTypeAnnotation.timeType(/* isAdjustedToUTC = */ false, MICROS))
                .named(column.name)

            is NeutralType.DateTime -> Types.primitive(PrimitiveTypeName.INT64, repetition)
                .`as`(LogicalTypeAnnotation.timestampType(t.timezone, MICROS))
                .named(column.name)

            is NeutralType.Uuid -> Types.primitive(PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY, repetition)
                .length(UUID_BYTES)
                .`as`(LogicalTypeAnnotation.uuidType())
                .named(column.name)

            is NeutralType.Json -> Types.primitive(PrimitiveTypeName.BINARY, repetition)
                .`as`(LogicalTypeAnnotation.jsonType())
                .named(column.name)

            // Xml: in Parquet ohne eigenen Logical-Type. Per AP2 §8 als
            // STRING(UTF-8) abgelegt; XML-Detail bleibt im Manifest.
            is NeutralType.Xml -> Types.primitive(PrimitiveTypeName.BINARY, repetition)
                .`as`(LogicalTypeAnnotation.stringType())
                .named(column.name)

            // Enum: AP2 §8 nennt ENUM mit STRING-Fallback. Wir nehmen
            // ENUM, der ENUM-Annotation-Pfad ist parquet-java-nativ
            // vorhanden; Werte-Liste/refType reisen im Manifest mit.
            is NeutralType.Enum -> Types.primitive(PrimitiveTypeName.BINARY, repetition)
                .`as`(LogicalTypeAnnotation.enumType())
                .named(column.name)

            // Geometry: AP2 §8 nennt WKB im BINARY ohne Logical-Type;
            // geometryType/srid liegen im Manifest (Hauptplan §6) bzw.
            // Footer-KV (AP11). Hier nur BINARY ohne Annotation.
            is NeutralType.Geometry -> Types.primitive(PrimitiveTypeName.BINARY, repetition)
                .named(column.name)

            // Array: AP2 §8 nennt `group LIST<element>`. parquet-java
            // hat dafuer ein dedicates Three-Level-List-Pattern, das
            // sich nicht als `PrimitiveType` ausdruecken laesst — fuer
            // S3 Cut A wird Array auf BINARY mit STRING-Annotation
            // reduziert (serialisiert als JSON-Array-String), bis der
            // AP3-Followup (Mapping-Tabellen-Erweiterung) das volle
            // LIST-Pattern liefert. elementType reist im Manifest.
            is NeutralType.Array -> Types.primitive(PrimitiveTypeName.BINARY, repetition)
                .`as`(LogicalTypeAnnotation.stringType())
                .named(column.name)
        }
    }

    private fun decimalType(precision: Int, scale: Int, name: String, repetition: Repetition): PrimitiveType {
        return when {
            precision <= DECIMAL_INT32_MAX -> Types.primitive(PrimitiveTypeName.INT32, repetition)
                .`as`(LogicalTypeAnnotation.decimalType(scale, precision))
                .named(name)
            precision <= DECIMAL_INT64_MAX -> Types.primitive(PrimitiveTypeName.INT64, repetition)
                .`as`(LogicalTypeAnnotation.decimalType(scale, precision))
                .named(name)
            else -> {
                // FIXED_LEN_BYTE_ARRAY mit Laenge gerundet auf ceil(precision * log2(10) / 8).
                val byteLength = ((precision * BITS_PER_DECIMAL_DIGIT_NUM + BITS_PER_DECIMAL_DIGIT_DEN - 1)
                    / BITS_PER_DECIMAL_DIGIT_DEN / BITS_PER_BYTE) + 1
                Types.primitive(PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY, repetition)
                    .length(byteLength)
                    .`as`(LogicalTypeAnnotation.decimalType(scale, precision))
                    .named(name)
            }
        }
    }

    private val MICROS = LogicalTypeAnnotation.TimeUnit.MICROS

    private const val BITS_16 = 16
    private const val BITS_32 = 32
    private const val BITS_64 = 64
    private const val BITS_PER_BYTE = 8
    private const val UUID_BYTES = 16
    private const val DECIMAL_INT32_MAX = 9
    private const val DECIMAL_INT64_MAX = 18

    // log2(10) ~= 3.322; als rationale Approximation 332/100.
    private const val BITS_PER_DECIMAL_DIGIT_NUM = 332
    private const val BITS_PER_DECIMAL_DIGIT_DEN = 100
}
