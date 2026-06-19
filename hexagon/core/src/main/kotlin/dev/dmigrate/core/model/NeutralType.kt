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

    /**
     * ADR 0015: PostgreSQL full-text search vector (`tsvector`). A first-class
     * neutral type — abstracted in the model, NOT a passed-through dialect type
     * string. Parameterless on purpose: the `tsvector` column carries no
     * modifiers; the text-search configuration belongs to the populating
     * trigger/function, not the column type. PostgreSQL round-trips it as
     * `tsvector` (and its GiST index survives); other dialects degrade it to
     * text with a note.
     */
    data object FullText : NeutralType()
}

enum class FloatPrecision { SINGLE, DOUBLE }
