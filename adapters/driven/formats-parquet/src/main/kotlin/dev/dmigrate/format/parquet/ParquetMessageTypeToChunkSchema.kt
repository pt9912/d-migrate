package dev.dmigrate.format.parquet

import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.NeutralType
import org.apache.parquet.schema.LogicalTypeAnnotation
import org.apache.parquet.schema.PrimitiveType
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName

/**
 * Umkehrung von [ChunkSchemaToParquetMessageType]: leitet aus dem
 * Parquet-Footer einen [NeutralType] je Spalte ab.
 *
 * **Der Vertrag ist bewusst schwaecher als "urspruenglichen Typ
 * rekonstruieren", und er muss es sein:** die Vorwaertsrichtung ist
 * nicht injektiv. `Text`, `Email`, `Char`, `Xml`, `FullText` und
 * `Array` landen allesamt auf `BINARY` + `stringType`; `Integer` und
 * `Identifier` auf demselben `INT32` + `intType(32)`; `Binary` und
 * `Geometry` auf annotationsfreiem `BINARY`. Aus dem Footer allein
 * ist die Unterscheidung nicht wiederherstellbar — die semantischen
 * Details reisen im Manifest (Bundle-`manifest.yaml` bzw. Footer-KV).
 *
 * Was hier zaehlt, ist ausschliesslich: **der abgeleitete Typ muss in
 * [ParquetGroupValueReader.readColumn] denselben Zugriff ausloesen wie
 * die physische Spalte.** Eine Spalte, die als `INT32` im File liegt,
 * muss auf einen Typ abgebildet werden, der `getInteger` zieht — ob
 * das `Integer` oder `Identifier` heisst, ist fuer das Lesen egal.
 * [readShape] macht genau dieses Kriterium pruefbar.
 *
 * Bei Mehrdeutigkeit wird die allgemeinste Variante gewaehlt (`Text`
 * statt `Char`, `Integer` statt `Identifier`, `Binary` statt
 * `Geometry`) — sie traegt keine Zusatzzusagen, die der Footer nicht
 * deckt.
 */
internal object ParquetMessageTypeToChunkSchema {

    /**
     * Physische Zugriffsform, auf die
     * [ParquetGroupValueReader.readColumn] verzweigt. Absichtlich
     * grobkoernig: zwei Typen mit derselben Form sind fuer das Lesen
     * austauschbar.
     */
    enum class ReadShape { BOOLEAN, INT32, INT64, FLOAT, DOUBLE, BINARY }

    fun neutralTypeOf(field: PrimitiveType): NeutralType {
        val annotation = field.logicalTypeAnnotation
        return when (field.primitiveTypeName) {
            PrimitiveTypeName.BOOLEAN -> NeutralType.BooleanType
            PrimitiveTypeName.INT32 -> int32Type(annotation)
            PrimitiveTypeName.INT64 -> int64Type(annotation)
            PrimitiveTypeName.FLOAT -> NeutralType.Float(FloatPrecision.SINGLE)
            PrimitiveTypeName.DOUBLE -> NeutralType.Float(FloatPrecision.DOUBLE)
            PrimitiveTypeName.BINARY -> binaryType(annotation)
            PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY -> fixedType(annotation)
            // INT96 erzeugt dieser Writer nie (Legacy-Timestamp). Als
            // Byte-Folge lesen statt raten.
            PrimitiveTypeName.INT96 -> NeutralType.Binary
            else -> NeutralType.Binary
        }
    }

    /**
     * Lese-Zugriffsform eines [NeutralType] — spiegelt die Verzweigung
     * in [ParquetGroupValueReader.readColumn] Zweig fuer Zweig. Wird
     * beides geaendert, muss beides zusammen geaendert werden.
     */
    fun readShape(type: NeutralType): ReadShape = when (type) {
        is NeutralType.BooleanType -> ReadShape.BOOLEAN
        is NeutralType.SmallInt, is NeutralType.Integer, is NeutralType.Identifier,
        is NeutralType.Date, is NeutralType.Time,
        -> ReadShape.INT32
        is NeutralType.BigInteger, is NeutralType.DateTime -> ReadShape.INT64
        is NeutralType.Float -> floatShape(type)
        is NeutralType.Decimal -> decimalShape(type)
        else -> ReadShape.BINARY
    }

    private fun floatShape(type: NeutralType.Float): ReadShape = when (type.floatPrecision) {
        FloatPrecision.SINGLE -> ReadShape.FLOAT
        FloatPrecision.DOUBLE -> ReadShape.DOUBLE
    }

    /** Decimal-Physik haengt an der Precision (siehe readDecimal). */
    private fun decimalShape(type: NeutralType.Decimal): ReadShape = when {
        type.precision <= DECIMAL_INT32_MAX -> ReadShape.INT32
        type.precision <= DECIMAL_INT64_MAX -> ReadShape.INT64
        else -> ReadShape.BINARY
    }

    fun readShapeOf(field: PrimitiveType): ReadShape = when (field.primitiveTypeName) {
        PrimitiveTypeName.BOOLEAN -> ReadShape.BOOLEAN
        PrimitiveTypeName.INT32 -> ReadShape.INT32
        PrimitiveTypeName.INT64 -> ReadShape.INT64
        PrimitiveTypeName.FLOAT -> ReadShape.FLOAT
        PrimitiveTypeName.DOUBLE -> ReadShape.DOUBLE
        else -> ReadShape.BINARY
    }

    private fun int32Type(annotation: LogicalTypeAnnotation?): NeutralType = when {
        annotation is LogicalTypeAnnotation.DateLogicalTypeAnnotation -> NeutralType.Date
        annotation is LogicalTypeAnnotation.TimeLogicalTypeAnnotation -> NeutralType.Time
        annotation is LogicalTypeAnnotation.DecimalLogicalTypeAnnotation ->
            NeutralType.Decimal(annotation.precision, annotation.scale)
        annotation is LogicalTypeAnnotation.IntLogicalTypeAnnotation && annotation.bitWidth <= BITS_16 ->
            NeutralType.SmallInt
        // Auch der annotationsfreie Fall landet hier: INT32 ohne
        // Logical-Type ist ein vorzeichenbehafteter 32-Bit-Integer.
        else -> NeutralType.Integer
    }

    private fun int64Type(annotation: LogicalTypeAnnotation?): NeutralType = when (annotation) {
        is LogicalTypeAnnotation.TimestampLogicalTypeAnnotation ->
            NeutralType.DateTime(timezone = annotation.isAdjustedToUTC)
        is LogicalTypeAnnotation.DecimalLogicalTypeAnnotation ->
            NeutralType.Decimal(annotation.precision, annotation.scale)
        else -> NeutralType.BigInteger
    }

    private fun binaryType(annotation: LogicalTypeAnnotation?): NeutralType = when (annotation) {
        is LogicalTypeAnnotation.JsonLogicalTypeAnnotation -> NeutralType.Json
        // Enum-Werte liegen im Manifest; fuers Lesen ist Enum ohnehin
        // ein String (readColumn behandelt beide gleich).
        is LogicalTypeAnnotation.StringLogicalTypeAnnotation,
        is LogicalTypeAnnotation.EnumLogicalTypeAnnotation,
        -> NeutralType.Text()
        // Ohne Annotation: rohe Bytes. Geometry traegt dieselbe
        // Physik, unterscheidet sich aber nur im Manifest.
        else -> NeutralType.Binary
    }

    private fun fixedType(annotation: LogicalTypeAnnotation?): NeutralType = when (annotation) {
        is LogicalTypeAnnotation.UUIDLogicalTypeAnnotation -> NeutralType.Uuid
        is LogicalTypeAnnotation.DecimalLogicalTypeAnnotation ->
            NeutralType.Decimal(annotation.precision, annotation.scale)
        else -> NeutralType.Binary
    }

    private const val BITS_16 = 16
    private const val DECIMAL_INT32_MAX = 9
    private const val DECIMAL_INT64_MAX = 18
}
