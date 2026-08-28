package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferenceDefinition

/*
 * Die Wert-Projektionen des Fingerabdrucks: Typ, Default, Referenz, Generierung.
 *
 * Sie stehen neben dem Anhaenge-Code statt in ihm, weil sie eine andere Art
 * Funktion sind -- reine Wert-zu-Text-Abbildungen ohne StringBuilder und ohne
 * Kenntnis der Satzstruktur. Dieselbe Datei traegt deshalb keine Zeile, die
 * weiss, wie ein Fingerabdruck aufgebaut ist.
 *
 * Jede Aenderung hier verschiebt den Fingerabdruck aller Schemata und braucht
 * einen Versionsstempel-Bump in `MigrationFingerprint.ALGORITHM`.
 */

internal fun neutralType(t: NeutralType): String = when (t) {
    is NeutralType.Identifier -> if (t.autoIncrement) "identifier(auto)" else "identifier"
    is NeutralType.Text -> if (t.maxLength != null) "text(${t.maxLength})" else "text"
    is NeutralType.Char -> "char(${t.length})"
    is NeutralType.Float -> "float(${t.floatPrecision.name.lowercase()})"
    is NeutralType.Decimal -> "decimal(${t.precision},${t.scale})"
    is NeutralType.DateTime -> if (t.timezone) "datetime(tz)" else "datetime"
    is NeutralType.Enum -> enumType(t)
    is NeutralType.Array -> "array(${t.elementType})"
    is NeutralType.Geometry -> geometryType(t)
    else -> simpleNeutralType(t)
}

internal fun simpleNeutralType(t: NeutralType): String = when (t) {
    NeutralType.Integer -> "integer"
    NeutralType.SmallInt -> "smallint"
    NeutralType.BigInteger -> "biginteger"
    NeutralType.BooleanType -> "boolean"
    NeutralType.Date -> "date"
    NeutralType.Time -> "time"
    NeutralType.Uuid -> "uuid"
    NeutralType.Json -> "json"
    NeutralType.Xml -> "xml"
    NeutralType.Binary -> "binary"
    NeutralType.Email -> "email"
    NeutralType.FullText -> "fulltext"
    else -> error("simpleNeutralType called for non-simple variant: $t")
}

internal fun enumType(t: NeutralType.Enum): String = when {
    t.refType != null -> "enum(ref:${t.refType})"
    t.values != null -> "enum(${joinValues(t.values!!)})"
    else -> "enum"
}

/**
 * Werte kollisionsfrei aneinanderreihen. Ein blosses Komma reicht nicht:
 * die Werte sind beliebige Nutzer-Strings, und `["a,b"]` haette sonst
 * denselben projizierten Text wie `["a", "b"]` — zwei verschiedene Schemata
 * mit demselben Fingerprint.
 */
internal fun joinValues(values: List<String>): String =
    values.joinToString(",") { it.replace("\\", "\\\\").replace(",", "\\,") }

internal fun geometryType(t: NeutralType.Geometry): String {
    val gt = t.geometryType.schemaName
    return if (t.srid != null) "geometry($gt,${t.srid})" else "geometry($gt)"
}

internal fun defaultValue(dv: DefaultValue?): String = when (dv) {
    null -> ""
    is DefaultValue.StringLiteral -> "str:${dv.value}"
    is DefaultValue.NumberLiteral -> "num:${dv.value}"
    is DefaultValue.BooleanLiteral -> "bool:${dv.value}"
    is DefaultValue.FunctionCall -> "fn:${dv.name}"
    is DefaultValue.SequenceNextVal -> "seq:${dv.sequenceName}"
}

internal fun reference(ref: ReferenceDefinition?): String {
    if (ref == null) return ""
    val parts = mutableListOf("table=${ref.table}", "column=${ref.column}")
    ref.onDelete?.let { parts += "onDelete=${it.name}" }
    ref.onUpdate?.let { parts += "onUpdate=${it.name}" }
    return parts.joinToString(",")
}

internal fun generation(gen: ColumnGeneration?): String = when (gen) {
    null -> ""
    is ColumnGeneration.Identity -> buildString {
        append("identity:mode=${gen.mode.name}")
        gen.sequenceName?.let { append(",sequence=$it") }
        if (gen.legacySerialSyntax) append(",legacy_serial=true")
    }
}
