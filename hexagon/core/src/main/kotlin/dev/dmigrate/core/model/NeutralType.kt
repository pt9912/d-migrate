package dev.dmigrate.core.model

sealed class NeutralType {
    data class Identifier(val autoIncrement: Boolean = false) : NeutralType()
    data class Text(val maxLength: Int? = null) : NeutralType()
    data class Char(val length: Int) : NeutralType()
    data object Integer : NeutralType()
    data object SmallInt : NeutralType()
    data object BigInteger : NeutralType()
    data class Float(val floatPrecision: FloatPrecision = FloatPrecision.DOUBLE) : NeutralType()
    data class Decimal(val precision: Int, val scale: Int) : NeutralType()
    data object BooleanType : NeutralType()
    data class DateTime(val timezone: Boolean = false) : NeutralType()
    data object Date : NeutralType()
    data object Time : NeutralType()
    data object Uuid : NeutralType()
    data object Json : NeutralType()
    data object Xml : NeutralType()
    data object Binary : NeutralType()
    data object Email : NeutralType() {
        const val MAX_LENGTH = 254
    }
    data class Enum(val values: List<String>? = null, val refType: String? = null) : NeutralType()
    data class Array(val elementType: String) : NeutralType()
    data class Geometry(
        val geometryType: GeometryType = GeometryType.GEOMETRY,
        val srid: Int? = null,
    ) : NeutralType()
}

enum class FloatPrecision { SINGLE, DOUBLE }
