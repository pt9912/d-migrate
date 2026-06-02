package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition

/**
 * Stable canonical-payload strings for [OperationIdFactory] inputs.
 * Mirrors the `MigrationFingerprint.append*` shape but at the
 * single-entity grain. Maps are emitted in lexicographic key order
 * so that two semantically equal inputs constructed via different
 * insertion orders produce the same operation ID across processes.
 *
 * Why this exists: Kotlin data-class `toString()` delegates to each
 * property's `toString()`. For `Map` properties iteration order is
 * the underlying map's iteration order — `LinkedHashMap` is stable,
 * `HashMap` is JVM/seed-dependent. The original first-cut planner
 * used `def.toString()` for ID payloads, which left Operation IDs
 * vulnerable to drift across loaders / JVM versions.
 *
 * The [SEP] character matches [CanonicalEncoding.SEP]. Bumping the
 * format requires invalidating Operation IDs in artefacts.
 */
internal object CanonicalPayload {

    private const val SEP: Char = CanonicalEncoding.SEP

    fun table(t: TableDefinition): String = buildString {
        append("cols[").append(t.columns.size).append(']')
        for ((name, col) in t.columns.entries.sortedBy { it.key }) {
            append(SEP).append(name).append('=').append(column(col))
        }
        append(SEP).append("pk=").append(t.primaryKey.joinToString(","))
        append(SEP).append("constraints[").append(t.constraints.size).append(']')
        for (c in t.constraints.sortedBy { it.name }) {
            append(SEP).append(constraint(c))
        }
        append(SEP).append("indices[").append(t.indices.size).append(']')
        for (i in t.indices.sortedWith(indexOrder)) {
            append(SEP).append(index(i))
        }
    }

    fun column(c: ColumnDefinition): String = buildString {
        append("type=").append(neutralType(c.type))
            .append(SEP).append("required=").append(c.required)
            .append(SEP).append("unique=").append(c.unique)
            .append(SEP).append("default=").append(defaultValue(c.default))
            .append(SEP).append("ref=").append(reference(c.references))
            .append(SEP).append("gen=").append(generation(c.generation))
    }

    fun constraint(c: ConstraintDefinition): String = buildString {
        append("constraint=").append(c.name)
            .append(SEP).append("type=").append(c.type.name)
            .append(SEP).append("columns=").append(c.columns?.joinToString(",") ?: "")
            .append(SEP).append("expr=").append(c.expression ?: "")
            .append(SEP).append("ref=")
            .append(c.references?.let { "${it.table}[${it.columns.joinToString(",")}]" } ?: "")
    }

    fun index(i: IndexDefinition): String = buildString {
        append("index=").append(i.name ?: "")
            .append(SEP).append("columns=").append(i.columns.joinToString(",") { it.name })
            .append(SEP).append("type=").append(i.type.name)
            .append(SEP).append("unique=").append(i.unique)
            .append(SEP).append("where=").append(i.where ?: "")
    }

    fun customType(t: CustomTypeDefinition): String = buildString {
        append("kind=").append(t.kind.name)
            .append(SEP).append("base=").append(t.baseType ?: "")
            .append(SEP).append("values=").append(t.values?.joinToString(",") ?: "")
            .append(SEP).append("precision=").append(t.precision ?: "")
            .append(SEP).append("scale=").append(t.scale ?: "")
            .append(SEP).append("check=").append(t.check ?: "")
        append(SEP).append("fields[").append(t.fields?.size ?: 0).append(']')
        t.fields?.entries?.sortedBy { it.key }?.forEach { (name, col) ->
            append(SEP).append(name).append('=').append(column(col))
        }
    }

    fun view(v: ViewDefinition): String = buildString {
        append("materialized=").append(v.materialized)
            .append(SEP).append("refresh=").append(v.refresh ?: "")
            .append(SEP).append("query=").append(v.query ?: "")
            .append(SEP).append("source_dialect=").append(v.sourceDialect ?: "")
        append(SEP).append("view_columns[").append(v.columns?.size ?: 0).append(']')
        v.columns?.forEach { col ->
            append(SEP).append(col.name).append(':').append(col.type ?: "")
        }
        val deps = v.dependencies
        if (deps != null) {
            append(SEP).append("deps_tables=").append(deps.tables.sorted().joinToString(","))
            append(SEP).append("deps_views=").append(deps.views.sorted().joinToString(","))
            append(SEP).append("deps_functions=").append(deps.functions.sorted().joinToString(","))
            append(SEP).append("deps_projection_complete=").append(deps.projectionComplete)
            append(SEP).append("deps_table_projection=").append(deps.tableProjectionStatus.name)
            append(SEP).append("deps_column_projection=").append(deps.columnProjectionStatus.name)
            append(SEP).append("deps_routine_projection=").append(deps.routineProjectionStatus.name)
            append(SEP).append("deps_columns[").append(deps.columns.size).append(']')
            for ((tableName, cols) in deps.columns.entries.sortedBy { it.key }) {
                append(SEP).append(tableName).append('=').append(cols.sorted().joinToString(","))
            }
        }
    }

    private val indexOrder = compareBy<IndexDefinition> { it.name ?: "" }
        .thenBy { it.columns.joinToString(",") { c -> c.name } }
        .thenBy { it.type.name }
        .thenBy { it.unique }
        .thenBy { it.where ?: "" }

    private fun neutralType(t: NeutralType): String = when (t) {
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

    private fun simpleNeutralType(t: NeutralType): String = when (t) {
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
        else -> error("simpleNeutralType called for non-simple variant: $t")
    }

    private fun enumType(t: NeutralType.Enum): String = when {
        t.refType != null -> "enum(ref:${t.refType})"
        t.values != null -> "enum(${t.values!!.joinToString(",")})"
        else -> "enum"
    }

    private fun geometryType(t: NeutralType.Geometry): String {
        val gt = t.geometryType.schemaName
        return if (t.srid != null) "geometry($gt,${t.srid})" else "geometry($gt)"
    }

    private fun defaultValue(dv: DefaultValue?): String = when (dv) {
        null -> ""
        is DefaultValue.StringLiteral -> "str:${dv.value}"
        is DefaultValue.NumberLiteral -> "num:${dv.value}"
        is DefaultValue.BooleanLiteral -> "bool:${dv.value}"
        is DefaultValue.FunctionCall -> "fn:${dv.name}"
        is DefaultValue.SequenceNextVal -> "seq:${dv.sequenceName}"
    }

    private fun reference(ref: ReferenceDefinition?): String {
        if (ref == null) return ""
        val parts = mutableListOf("table=${ref.table}", "column=${ref.column}")
        ref.onDelete?.let { parts += "onDelete=${it.name}" }
        ref.onUpdate?.let { parts += "onUpdate=${it.name}" }
        return parts.joinToString(",")
    }

    private fun generation(gen: ColumnGeneration?): String = when (gen) {
        null -> ""
        is ColumnGeneration.Identity -> buildString {
            append("identity:mode=${gen.mode.name}")
            gen.sequenceName?.let { append(",sequence=$it") }
            if (gen.legacySerialSyntax) append(",legacy_serial=true")
        }
    }
}
