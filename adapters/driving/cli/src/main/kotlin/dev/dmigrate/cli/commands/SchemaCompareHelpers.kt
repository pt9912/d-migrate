package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.*
import dev.dmigrate.core.model.*

internal object SchemaCompareHelpers {

    // ── Canonical string representations ──────────────────────────

    fun neutralTypeToString(type: NeutralType): String = when (type) {
        is NeutralType.Identifier -> if (type.autoIncrement) "identifier(auto)" else "identifier"
        is NeutralType.Text -> if (type.maxLength != null) "text(${type.maxLength})" else "text"
        is NeutralType.Char -> "char(${type.length})"
        is NeutralType.Integer -> "integer"
        is NeutralType.SmallInt -> "smallint"
        is NeutralType.BigInteger -> "biginteger"
        is NeutralType.Float -> "float(${type.floatPrecision.name.lowercase()})"
        is NeutralType.Decimal -> "decimal(${type.precision},${type.scale})"
        is NeutralType.BooleanType -> "boolean"
        is NeutralType.DateTime -> if (type.timezone) "datetime(tz)" else "datetime"
        is NeutralType.Date -> "date"
        is NeutralType.Time -> "time"
        is NeutralType.Uuid -> "uuid"
        is NeutralType.Json -> "json"
        is NeutralType.Xml -> "xml"
        is NeutralType.Binary -> "binary"
        is NeutralType.Email -> "email"
        is NeutralType.Enum -> when {
            type.refType != null -> "enum(ref:${type.refType})"
            type.values != null -> "enum(${type.values!!.joinToString(",")})"
            else -> "enum"
        }
        is NeutralType.Array -> "array(${type.elementType})"
        is NeutralType.Geometry -> {
            val gt = type.geometryType.schemaName
            val srid = type.srid
            if (srid != null) "geometry($gt,$srid)" else "geometry($gt)"
        }
    }

    fun defaultValueToString(dv: DefaultValue?): String? = when (dv) {
        null -> null
        is DefaultValue.StringLiteral -> "\"${dv.value}\""
        is DefaultValue.NumberLiteral -> dv.value.toString()
        is DefaultValue.BooleanLiteral -> dv.value.toString()
        is DefaultValue.FunctionCall -> "${dv.name}()"
    }

    fun indexSignature(idx: IndexDefinition): String = buildString {
        if (idx.name != null) append(idx.name) else append(idx.columns.joinToString(","))
        append(" [${idx.type.name.lowercase()}")
        if (idx.unique) append(",unique")
        append("]")
    }

    fun constraintSignature(c: ConstraintDefinition): String = buildString {
        append("${c.name} (${c.type.name.lowercase()}")
        val cols = c.columns
        if (!cols.isNullOrEmpty()) append(" on [${cols.joinToString(",")}]")
        val refs = c.references
        if (refs != null) append(" -> ${refs.table}[${refs.columns.joinToString(",")}]")
        append(")")
    }

    fun referenceToString(ref: ReferenceDefinition?): String? {
        if (ref == null) return null
        return buildString {
            append("${ref.table}.${ref.column}")
            val parts = mutableListOf<String>()
            ref.onDelete?.let { parts += "onDelete=${it.name.lowercase()}" }
            ref.onUpdate?.let { parts += "onUpdate=${it.name.lowercase()}" }
            if (parts.isNotEmpty()) append(" (${parts.joinToString(", ")})")
        }
    }

    // ── Projection: SchemaDiff → DiffView ─────────────────────────

    fun projectDiff(diff: SchemaDiff): DiffView {
        val metadata = diff.schemaMetadata
        return DiffView(
            schemaMetadata = if (metadata != null) MetadataChangeView(
                name = metadata.name?.let { StringChange(it.before, it.after) },
                version = metadata.version?.let { StringChange(it.before, it.after) },
            ) else null,
            customTypesAdded = diff.customTypesAdded.map {
                CustomTypeSummaryView(it.name, it.definition.kind.name.lowercase(), customTypeDetail(it.definition))
            },
            customTypesRemoved = diff.customTypesRemoved.map {
                CustomTypeSummaryView(it.name, it.definition.kind.name.lowercase(), customTypeDetail(it.definition))
            },
            customTypesChanged = diff.customTypesChanged.map {
                CustomTypeChangeView(
                    it.name,
                    (it.kind?.after ?: it.kind?.before)?.name?.lowercase() ?: "unknown",
                    customTypeChanges(it),
                )
            },
            tablesAdded = diff.tablesAdded.map { TableSummaryView(it.name, it.definition.columns.size) },
            tablesRemoved = diff.tablesRemoved.map { TableSummaryView(it.name, it.definition.columns.size) },
            tablesChanged = diff.tablesChanged.map { projectTableDiff(it) },
            viewsAdded = diff.viewsAdded.map { ViewSummaryView(it.name, it.definition.materialized) },
            viewsRemoved = diff.viewsRemoved.map { ViewSummaryView(it.name, it.definition.materialized) },
            viewsChanged = diff.viewsChanged.map { projectViewDiff(it) },
            sequencesAdded = diff.sequencesAdded.map { it.name },
            sequencesRemoved = diff.sequencesRemoved.map { it.name },
            sequencesChanged = diff.sequencesChanged.map { it.name },
            functionsAdded = diff.functionsAdded.map { it.name },
            functionsRemoved = diff.functionsRemoved.map { it.name },
            functionsChanged = diff.functionsChanged.map { it.name },
            proceduresAdded = diff.proceduresAdded.map { it.name },
            proceduresRemoved = diff.proceduresRemoved.map { it.name },
            proceduresChanged = diff.proceduresChanged.map { it.name },
            triggersAdded = diff.triggersAdded.map { it.name },
            triggersRemoved = diff.triggersRemoved.map { it.name },
            triggersChanged = diff.triggersChanged.map { it.name },
        )
    }

    private fun projectTableDiff(t: TableDiff): TableChangeView = TableChangeView(
        name = t.name,
        columnsAdded = t.columnsAdded.entries.sortedBy { it.key }.map { (n, c) -> ColumnSummaryView(n, neutralTypeToString(c.type)) },
        columnsRemoved = t.columnsRemoved.keys.sorted(),
        columnsChanged = t.columnsChanged.map { c ->
            ColumnChangeView(
                name = c.name,
                type = c.type?.let { StringChange(neutralTypeToString(it.before), neutralTypeToString(it.after)) },
                required = c.required?.let { StringChange(it.before.toString(), it.after.toString()) },
                default = c.default?.let { NullableStringChange(defaultValueToString(it.before), defaultValueToString(it.after)) },
                unique = c.unique?.let { StringChange(it.before.toString(), it.after.toString()) },
                references = c.references?.let { NullableStringChange(referenceToString(it.before), referenceToString(it.after)) },
            )
        },
        primaryKey = t.primaryKey?.let { StringListChange(it.before, it.after) },
        indicesAdded = t.indicesAdded.map { indexSignature(it) },
        indicesRemoved = t.indicesRemoved.map { indexSignature(it) },
        indicesChanged = t.indicesChanged.map { StringChange(indexSignature(it.before), indexSignature(it.after)) },
        constraintsAdded = t.constraintsAdded.map { constraintSignature(it) },
        constraintsRemoved = t.constraintsRemoved.map { constraintSignature(it) },
        constraintsChanged = t.constraintsChanged.map { StringChange(constraintSignature(it.before), constraintSignature(it.after)) },
    )

    private fun projectViewDiff(v: ViewDiff): ViewChangeView = ViewChangeView(
        name = v.name,
        materialized = v.materialized?.let { StringChange(it.before.toString(), it.after.toString()) },
        refresh = v.refresh?.let { NullableStringChange(it.before, it.after) },
        queryChanged = v.query != null,
        sourceDialect = v.sourceDialect?.let { NullableStringChange(it.before, it.after) },
    )

    // ── Renderers (delegated) ────────────────────────────────────

    fun renderPlain(doc: SchemaCompareDocument): String = CompareRendererPlain.render(doc)
    fun renderJson(doc: SchemaCompareDocument): String = CompareRendererJson.render(doc)
    fun renderYaml(doc: SchemaCompareDocument): String = CompareRendererYaml.render(doc)

    // ── Escaping (shared across renderers) ───────────────────────

    internal fun esc(s: String) = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    // ── Custom Type Helpers ─────────────────────────────────────

    private fun customTypeDetail(def: CustomTypeDefinition): String = when (def.kind) {
        CustomTypeKind.ENUM -> def.values?.joinToString(", ") ?: ""
        CustomTypeKind.DOMAIN -> "base: ${def.baseType ?: "?"}"
        CustomTypeKind.COMPOSITE -> "${def.fields?.size ?: 0} fields"
    }

    private fun customTypeChanges(diff: CustomTypeDiff): List<String> {
        val changes = mutableListOf<String>()
        diff.kind?.let { changes += "kind: ${it.before} -> ${it.after}" }
        diff.values?.let { changes += "values: [${it.before.joinToString(", ")}] -> [${it.after.joinToString(", ")}]" }
        diff.baseType?.let { changes += "baseType: ${it.before} -> ${it.after}" }
        diff.precision?.let { changes += "precision: ${it.before} -> ${it.after}" }
        diff.scale?.let { changes += "scale: ${it.before} -> ${it.after}" }
        diff.check?.let { changes += "check: changed" }
        diff.description?.let { changes += "description: changed" }
        diff.fields?.let { changes += "fields: changed" }
        return changes
    }
}
