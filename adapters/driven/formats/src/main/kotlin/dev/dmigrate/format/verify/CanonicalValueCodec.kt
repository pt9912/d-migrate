package dev.dmigrate.format.verify

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.verify.ValueCanonicalizationException
import dev.dmigrate.verify.ValueCanonicalizer
import java.nio.charset.StandardCharsets
import java.sql.Array as SqlArray
import java.sql.Blob
import java.sql.Clob
import java.util.UUID

/**
 * LN-009 / ADR 0030: dialekt-neutrale Wert-Kanonik für `data transfer --verify`.
 *
 * Dispatcht auf den (ziel-projizierten) [NeutralType] und coerct den Rohwert in
 * dessen kanonische Domäne, sodass semantisch gleiche Werte über Dialektgrenzen
 * identische Bytes liefern. Der Aufrufer ([ValueCanonicalizer]) garantiert
 * Nicht-Null-Werte; NULL trennt das Framing strukturell.
 *
 * Vertrag der kanonischen Formen: siehe ADR 0030 (Tabelle D4). Projektions-
 * bewusst — flattening-äquivalente Werte kollidieren (Boolean unter `Integer` →
 * `"1"`/`"0"`; UUID unter `Text` → Lowercase-Hyphen-String).
 *
 * Numerik/Temporal sind in [CanonicalNumeric]/[CanonicalTemporal] ausgelagert,
 * JSON/Geometry in [CanonicalJson]/[CanonicalGeometry].
 */
class CanonicalValueCodec(
    private val json: CanonicalJson = CanonicalJson(),
    private val geometry: CanonicalGeometry = CanonicalGeometry(),
) : ValueCanonicalizer {

    override fun canonicalize(value: Any, type: NeutralType): ByteArray = when (type) {
        is NeutralType.Text -> utf8(asString(value))
        NeutralType.Xml -> utf8(asString(value))
        NeutralType.Email -> utf8(asString(value))
        is NeutralType.Enum -> utf8(asString(value))
        NeutralType.FullText -> utf8(asString(value))
        is NeutralType.Char -> utf8(asString(value).trimEnd(' '))
        NeutralType.Uuid -> utf8(canonUuid(value))
        NeutralType.Integer, NeutralType.SmallInt, NeutralType.BigInteger -> utf8(CanonicalNumeric.integral(value))
        is NeutralType.Identifier -> utf8(CanonicalNumeric.integral(value))
        is NeutralType.Decimal -> utf8(CanonicalNumeric.decimal(value))
        is NeutralType.Float -> utf8(CanonicalNumeric.float(value))
        NeutralType.BooleanType -> utf8(if (CanonicalNumeric.boolean(value)) "1" else "0")
        NeutralType.Date -> utf8(CanonicalTemporal.date(value))
        NeutralType.Time -> utf8(CanonicalTemporal.time(value))
        is NeutralType.DateTime -> utf8(if (type.timezone) CanonicalTemporal.instantUtc(value) else CanonicalTemporal.localDateTime(value))
        NeutralType.Json -> json.canonicalize(asString(value))
        is NeutralType.Array -> canonArray(value, type.elementType)
        NeutralType.Binary -> asBytes(value)
        is NeutralType.Geometry -> geometry.canonicalize(value)
    }

    private fun asString(value: Any): String = when (value) {
        is String -> value
        is Clob -> readClob(value)
        // PGobject (jsonb/xml/uuid/…) trägt seine Textform in getValue().
        else -> pgObjectValue(value) ?: value.toString()
    }

    private fun canonUuid(value: Any): String = when (value) {
        is UUID -> value.toString()
        // Textform (Ziel hat uuid→text abgeflacht) auf kanonische Lowercase-Form bringen.
        else -> runCatching { UUID.fromString(asString(value)).toString() }.getOrElse { asString(value).lowercase() }
    }

    private fun asBytes(value: Any): ByteArray = when (value) {
        is ByteArray -> value
        is Blob -> readBlob(value)
        else -> throw cannot(value, "Binary")
    }

    /** Rekursiv: jedes Element kanonisieren, längen-gerahmt + Count-präfixiert. */
    private fun canonArray(value: Any, elementType: String): ByteArray {
        val elements: List<Any?> = when (value) {
            is SqlArray -> sqlArrayToList(value)
            is List<*> -> value
            is Array<*> -> value.toList()
            else -> throw cannot(value, "Array")
        }
        val elemType = elementTypeToNeutral(elementType)
        val out = FramedBytes()
        out.putVarInt(elements.size.toLong())
        for (element in elements) {
            if (element == null) {
                out.putByte(0)
            } else {
                out.putByte(1)
                out.putFramed(canonicalize(element, elemType))
            }
        }
        return out.toByteArray()
    }

    private fun sqlArrayToList(array: SqlArray): List<Any?> = try {
        when (val raw = array.array) {
            is Array<*> -> raw.toList()
            is IntArray -> raw.toList()
            is LongArray -> raw.toList()
            is ShortArray -> raw.toList()
            is FloatArray -> raw.toList()
            is DoubleArray -> raw.toList()
            is BooleanArray -> raw.toList()
            is ByteArray -> raw.toList()
            else -> throw cannot(array, "Array")
        }
    } catch (t: Throwable) {
        throw ValueCanonicalizationException("java.sql.Array nicht aufzählbar: ${t.message}", t)
    }

    private fun utf8(s: String): ByteArray = s.toByteArray(StandardCharsets.UTF_8)

    private fun pgObjectValue(value: Any): String? =
        if (value.javaClass.name == "org.postgresql.util.PGobject") invokeNoArgString(value, "getValue") else null

    private fun invokeNoArgString(target: Any, method: String): String? = try {
        target.javaClass.getMethod(method).invoke(target) as? String
    } catch (_: Throwable) {
        null
    }

    private fun readBlob(blob: Blob): ByteArray {
        val length = blob.length()
        return if (length == 0L) ByteArray(0) else blob.getBytes(1, length.toInt())
    }

    private fun readClob(clob: Clob): String {
        val length = clob.length()
        return if (length == 0L) "" else clob.getSubString(1, length.toInt())
    }

    private fun cannot(value: Any, expected: String): ValueCanonicalizationException =
        ValueCanonicalizationException("Wert der Klasse ${value.javaClass.name} nicht als $expected kanonisierbar")

    companion object {
        /**
         * Bildet den `elementType`-String eines Array-Neutraltyps auf einen
         * [NeutralType] für die rekursive Element-Kanonik ab. Unbekannt → `Text`.
         */
        fun elementTypeToNeutral(elementType: String): NeutralType =
            when (elementType.trim().lowercase().substringBefore('(')) {
                "smallint", "int2" -> NeutralType.SmallInt
                "integer", "int", "int4" -> NeutralType.Integer
                "bigint", "int8" -> NeutralType.BigInteger
                "boolean", "bool" -> NeutralType.BooleanType
                "numeric", "decimal" -> NeutralType.Decimal(38, 0)
                "real", "double", "double precision", "float", "float8", "float4" -> NeutralType.Float()
                "uuid" -> NeutralType.Uuid
                "json", "jsonb" -> NeutralType.Json
                "date" -> NeutralType.Date
                "time" -> NeutralType.Time
                "timestamp" -> NeutralType.DateTime(timezone = false)
                "timestamptz", "timestamp with time zone" -> NeutralType.DateTime(timezone = true)
                "bytea", "binary", "blob" -> NeutralType.Binary
                else -> NeutralType.Text()
            }
    }
}

/** Kleiner Byte-Builder mit VarInt-Längenrahmung (unsigned LEB128). */
internal class FramedBytes {
    private val buffer = java.io.ByteArrayOutputStream()

    fun putByte(b: Int) {
        buffer.write(b)
    }

    fun putVarInt(valueIn: Long) {
        var value = valueIn
        while (true) {
            val b = (value and 0x7F).toInt()
            value = value ushr 7
            if (value == 0L) {
                buffer.write(b)
                return
            }
            buffer.write(b or 0x80)
        }
    }

    fun putFramed(bytes: ByteArray) {
        putVarInt(bytes.size.toLong())
        buffer.write(bytes)
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()
}
