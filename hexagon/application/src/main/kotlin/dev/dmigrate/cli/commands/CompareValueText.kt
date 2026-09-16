package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.TableMetadata
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.canonicalOrder

/**
 * Ein verglichener Wert als Text — so, wie `schema compare` ihn in der CLI
 * und in den `details` der MCP-Funde nennt (`spec/mcp-server.md`).
 *
 * Skalare stehen, wie das Schema-Dokument sie schreibt: Text und Zahlen
 * unveraendert, Aufzaehlungswerte klein (`after`, `definer`, `enum`,
 * `instead_of`), Listen als `[a, b]` — Trigger-Ereignisse in der Reihenfolge
 * des Dokuments. Strukturierte Werte einer Spalte stehen in der Kurzform des
 * Vergleichs (`text(254)`, `identity(mode=by_default)`,
 * `orders.id (on_delete=cascade)`). Nie die Kotlin-Darstellung eines Objekts.
 */
object CompareValueText {

    /** [value] als Text — `null`, wenn es keinen Wert gibt. */
    fun of(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is Boolean, is Number -> value.toString()
        is Enum<*> -> value.name.lowercase()
        is NeutralType -> type(value)
        is DefaultValue -> default(value)
        is ReferenceDefinition -> reference(value)
        is ColumnGeneration -> generation(value)
        is TableMetadata -> metadata(value)
        is Collection<*> -> list(value)
        else -> value.toString()
    }

    /**
     * Ein Spaltentyp in der Kurzform des Vergleichs. Die Typen mit Angaben
     * stehen hier, die ohne in [simpleType] — so bleibt jede Verzweigung
     * unter der Komplexitaetsgrenze.
     */
    fun type(type: NeutralType): String = when (type) {
        is NeutralType.Identifier -> if (type.autoIncrement) "identifier(auto)" else "identifier"
        is NeutralType.Text -> if (type.maxLength != null) "text(${type.maxLength})" else "text"
        is NeutralType.Char -> "char(${type.length})"
        is NeutralType.Float -> "float(${type.floatPrecision.name.lowercase()})"
        is NeutralType.Decimal -> "decimal(${type.precision},${type.scale})"
        is NeutralType.DateTime -> if (type.timezone) "datetime(tz)" else "datetime"
        is NeutralType.Enum -> enumType(type)
        is NeutralType.Array -> "array(${type.elementType})"
        is NeutralType.Geometry -> geometryType(type)
        else -> simpleType(type)
    }

    private fun simpleType(type: NeutralType): String = when (type) {
        is NeutralType.Integer -> "integer"
        is NeutralType.SmallInt -> "smallint"
        is NeutralType.BigInteger -> "biginteger"
        is NeutralType.BooleanType -> "boolean"
        is NeutralType.Date -> "date"
        is NeutralType.Time -> "time"
        is NeutralType.Uuid -> "uuid"
        is NeutralType.Json -> "json"
        is NeutralType.Xml -> "xml"
        is NeutralType.Binary -> "binary"
        is NeutralType.Email -> "email"
        is NeutralType.FullText -> "fulltext"
        else -> error("simpleType called for a parametric NeutralType: $type")
    }

    private fun enumType(type: NeutralType.Enum): String {
        type.refType?.let { return "enum(ref:$it)" }
        return type.values?.let { "enum(${it.joinToString(",")})" } ?: "enum"
    }

    private fun geometryType(type: NeutralType.Geometry): String {
        val name = type.geometryType.schemaName
        return if (type.srid != null) "geometry($name,${type.srid})" else "geometry($name)"
    }

    fun default(value: DefaultValue): String = when (value) {
        is DefaultValue.StringLiteral -> "\"${value.value}\""
        is DefaultValue.NumberLiteral -> value.value.toString()
        is DefaultValue.BooleanLiteral -> value.value.toString()
        is DefaultValue.FunctionCall -> "${value.name}()"
        is DefaultValue.SequenceNextVal -> "sequence_nextval(${value.sequenceName})"
    }

    /** `orders.id (on_delete=cascade, on_update=restrict)` — die Aktionen unter ihrem Dokument-Schluessel. */
    fun reference(value: ReferenceDefinition): String = buildString {
        append("${value.table}.${value.column}")
        val actions = listOfNotNull(
            value.onDelete?.let { "on_delete=${it.name.lowercase()}" },
            value.onUpdate?.let { "on_update=${it.name.lowercase()}" },
        )
        if (actions.isNotEmpty()) append(" (${actions.joinToString(", ")})")
    }

    fun generation(value: ColumnGeneration): String = when (value) {
        is ColumnGeneration.Identity -> buildString {
            append("identity(mode=${value.mode.name.lowercase()}")
            value.sequenceName?.let { append(",sequence=$it") }
            if (value.legacySerialSyntax) append(",legacy_serial_syntax=true")
            append(")")
        }
        is ColumnGeneration.Computed -> "computed(${value.expression}${if (value.stored) ", stored" else ""})"
    }

    /** `engine=InnoDB, without_rowid=true` — leer, wenn keine Angabe gesetzt ist. */
    fun metadata(value: TableMetadata): String = listOfNotNull(
        value.engine?.let { "engine=$it" },
        "without_rowid=true".takeIf { value.withoutRowid },
    ).joinToString(", ")

    private fun list(values: Collection<*>): String {
        val ordered = if (values.isNotEmpty() && values.all { it is TriggerEvent }) {
            values.filterIsInstance<TriggerEvent>().canonicalOrder()
        } else {
            values
        }
        return ordered.joinToString(", ", prefix = "[", postfix = "]") { of(it) ?: "null" }
    }
}
