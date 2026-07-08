package dev.dmigrate.format.parquet

import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.NeutralType
import org.apache.parquet.example.data.Group
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Liest einen Spaltenwert aus einer Parquet-[Group] zurueck in das
 * neutrale Wertemodell, das auch der `JdbcChunkSequence` (Export-
 * Pfad) erzeugt. Liefert `null` fuer leere/Repetition-0-Felder,
 * sonst das passende Java-Objekt:
 *
 * | NeutralType | Java-Typ |
 * | --- | --- |
 * | BooleanType | Boolean |
 * | SmallInt / Integer / Identifier | Int |
 * | BigInteger | Long |
 * | Float (SINGLE/DOUBLE) | Float / Double |
 * | Decimal | BigDecimal |
 * | Text / Char / Email / Xml / Enum | String |
 * | Binary / Geometry | ByteArray |
 * | Date | LocalDate |
 * | Time | LocalTime |
 * | DateTime | Instant |
 * | Uuid | UUID |
 * | Json | String |
 * | Array | String (Roundtrip-Form aus dem Writer) |
 *
 * Symmetrisch zu [ParquetGroupValueWriter].
 */
internal object ParquetGroupValueReader {

    fun readColumn(group: Group, fieldIndex: Int, neutralType: NeutralType): Any? {
        if (group.getFieldRepetitionCount(fieldIndex) == 0) return null
        return when (neutralType) {
            is NeutralType.BooleanType -> group.getBoolean(fieldIndex, 0)
            is NeutralType.SmallInt, is NeutralType.Integer, is NeutralType.Identifier ->
                group.getInteger(fieldIndex, 0)
            is NeutralType.BigInteger -> group.getLong(fieldIndex, 0)
            is NeutralType.Float -> when (neutralType.floatPrecision) {
                FloatPrecision.SINGLE -> group.getFloat(fieldIndex, 0)
                FloatPrecision.DOUBLE -> group.getDouble(fieldIndex, 0)
            }
            is NeutralType.Decimal -> readDecimal(group, fieldIndex, neutralType.precision, neutralType.scale)
            is NeutralType.Text, is NeutralType.Email, is NeutralType.Char,
            is NeutralType.Xml, is NeutralType.Enum, is NeutralType.Json,
            is NeutralType.Array, is NeutralType.FullText ->
                group.getString(fieldIndex, 0)
            is NeutralType.Binary, is NeutralType.Geometry ->
                group.getBinary(fieldIndex, 0).bytes
            is NeutralType.Date -> LocalDate.ofEpochDay(group.getInteger(fieldIndex, 0).toLong())
            is NeutralType.Time -> {
                val micros = group.getInteger(fieldIndex, 0).toLong()
                LocalTime.ofNanoOfDay(micros * NANOS_PER_MICRO)
            }
            is NeutralType.DateTime -> {
                val micros = group.getLong(fieldIndex, 0)
                Instant.ofEpochSecond(micros / MICROS_PER_SECOND, (micros % MICROS_PER_SECOND) * NANOS_PER_MICRO)
            }
            is NeutralType.Uuid -> {
                val bytes = group.getBinary(fieldIndex, 0).bytes
                val buffer = ByteBuffer.wrap(bytes)
                UUID(buffer.long, buffer.long)
            }
        }
    }

    private fun readDecimal(group: Group, fieldIndex: Int, precision: Int, scale: Int): BigDecimal {
        val unscaled: BigInteger = when {
            precision <= 9 -> BigInteger.valueOf(group.getInteger(fieldIndex, 0).toLong())
            precision <= 18 -> BigInteger.valueOf(group.getLong(fieldIndex, 0))
            else -> BigInteger(group.getBinary(fieldIndex, 0).bytes)
        }
        return BigDecimal(unscaled, scale)
    }

    private const val NANOS_PER_MICRO = 1_000L
    private const val MICROS_PER_SECOND = 1_000_000L
}
