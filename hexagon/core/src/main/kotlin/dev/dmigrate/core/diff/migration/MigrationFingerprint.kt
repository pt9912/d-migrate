package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.core.util.sha256Hex

/**
 * Canonical fingerprint of a [SchemaDefinition] for the migration
 * pipeline. Used by:
 *
 * - migration reports (`currentFingerprint`, `desiredFingerprint`),
 * - the SQL metadata block in `--rollback-output` artefacts
 *   (`postUpFingerprint`, `allowedPostUpFingerprints`),
 * - the post-`--execute` compare and `schema rollback` drift checks.
 *
 * Algorithm: `schema-fingerprint-v1`. Steps:
 *
 * 1. Apply [ReverseMarkerNormalizer] so reverse-generated provenance
 *    cannot affect the fingerprint.
 * 2. Build a deterministic string projection of the schema. Maps
 *    are emitted in lexicographic key order; lists keep their
 *    declared order (PK column lists, parameter lists, etc.) — those
 *    are semantic.
 * 3. SHA-256 over the UTF-8 bytes of the projection, lowercase hex.
 *
 * The output format includes the [ALGORITHM] string as a leading
 * line so future projection changes ship as a new algorithm name
 * (e.g. `schema-fingerprint-v2`) rather than as a silent invariant
 * shift.
 *
 * Property:  the projection itself is exposed as [project] for
 * Diagnose-Pfade (e.g. golden-file tests, debug reports). Production
 * callers should use [compute].
 */
object MigrationFingerprint {

    /** Algorithm identifier folded into every projection. Bump on contract change. */
    const val ALGORITHM: String = "schema-fingerprint-v1"

    /** Field-/key separator inside the canonical projection. ASCII Unit Separator (0x1F). */
    private const val SEP: Char = '\u001F'

    /** SHA-256 hex of the canonical projection. */
    fun compute(schema: SchemaDefinition): String =
        sha256Hex(project(schema))

    /** Returns the canonical projection string. Public for diagnostics. */
    fun project(schema: SchemaDefinition): String {
        val normalized = ReverseMarkerNormalizer.normalize(schema)
        val sb = StringBuilder()
        sb.append("algorithm=").append(ALGORITHM).append('\n')
        sb.append("name=").append(normalized.name).append('\n')
        sb.append("version=").append(normalized.version).append('\n')
        sb.append("description=").append(normalized.description ?: "").append('\n')
        sb.append("encoding=").append(normalized.encoding).append('\n')
        sb.append("locale=").append(normalized.locale ?: "").append('\n')
        appendCustomTypes(sb, normalized.customTypes)
        appendTables(sb, normalized.tables)
        appendViews(sb, normalized.views)
        appendSequences(sb, normalized.sequences)
        appendFunctions(sb, normalized.functions)
        appendProcedures(sb, normalized.procedures)
        appendTriggers(sb, normalized.triggers)
        return sb.toString()
    }

    // ── Custom types ────────────────────────────────────────────────

    private fun appendCustomTypes(sb: StringBuilder, types: Map<String, CustomTypeDefinition>) {
        sb.append("custom_types[").append(types.size).append("]\n")
        for ((name, def) in types.entries.sortedBy { it.key }) {
            sb.append("custom_type=").append(name)
                .append(SEP).append("kind=").append(def.kind.name)
                .append(SEP).append("base=").append(def.baseType ?: "")
                .append(SEP).append("values=").append(def.values?.joinToString(",") ?: "")
                .append(SEP).append("precision=").append(def.precision ?: "")
                .append(SEP).append("scale=").append(def.scale ?: "")
                .append(SEP).append("check=").append(def.check ?: "")
                .append('\n')
        }
    }

    // ── Tables ──────────────────────────────────────────────────────

    private fun appendTables(sb: StringBuilder, tables: Map<String, TableDefinition>) {
        sb.append("tables[").append(tables.size).append("]\n")
        for ((name, table) in tables.entries.sortedBy { it.key }) {
            sb.append("table=").append(name).append('\n')
            sb.append("  columns[").append(table.columns.size).append("]\n")
            for ((colName, col) in table.columns.entries.sortedBy { it.key }) {
                sb.append("    column=").append(colName)
                    .append(SEP).append("type=").append(neutralType(col.type))
                    .append(SEP).append("required=").append(col.required)
                    .append(SEP).append("unique=").append(col.unique)
                    .append(SEP).append("default=").append(defaultValue(col.default))
                    .append(SEP).append("references=").append(reference(col.references))
                    .append(SEP).append("generation=").append(generation(col.generation))
                    .append('\n')
            }
            sb.append("  primary_key=").append(table.primaryKey.joinToString(",")).append('\n')
            sb.append("  indices[").append(table.indices.size).append("]\n")
            for (idx in table.indices.sortedWith(indexOrder)) {
                sb.append("    index=").append(idx.name ?: "")
                    .append(SEP).append("columns=").append(idx.columns.joinToString(","))
                    .append(SEP).append("type=").append(idx.type.name)
                    .append(SEP).append("unique=").append(idx.unique)
                    .append(SEP).append("where=").append(idx.where ?: "")
                    .append('\n')
            }
            sb.append("  constraints[").append(table.constraints.size).append("]\n")
            for (c in table.constraints.sortedBy { it.name }) {
                sb.append("    constraint=").append(c.name)
                    .append(SEP).append("type=").append(c.type.name)
                    .append(SEP).append("columns=").append(c.columns?.joinToString(",") ?: "")
                    .append(SEP).append("expr=").append(c.expression ?: "")
                    .append(SEP).append("ref=")
                    .append(c.references?.let { "${it.table}[${it.columns.joinToString(",")}]" } ?: "")
                    .append('\n')
            }
        }
    }

    private val indexOrder = compareBy<IndexDefinition> { it.name ?: "" }
        .thenBy { it.columns.joinToString(",") }

    // ── Views ───────────────────────────────────────────────────────

    private fun appendViews(sb: StringBuilder, views: Map<String, ViewDefinition>) {
        sb.append("views[").append(views.size).append("]\n")
        for ((name, view) in views.entries.sortedBy { it.key }) {
            sb.append("view=").append(name)
                .append(SEP).append("materialized=").append(view.materialized)
                .append(SEP).append("refresh=").append(view.refresh ?: "")
                .append(SEP).append("query=").append(view.query ?: "")
                .append(SEP).append("source_dialect=").append(view.sourceDialect ?: "")
                .append('\n')
        }
    }

    // ── Sequences ───────────────────────────────────────────────────

    private fun appendSequences(sb: StringBuilder, seqs: Map<String, SequenceDefinition>) {
        sb.append("sequences[").append(seqs.size).append("]\n")
        for ((name, seq) in seqs.entries.sortedBy { it.key }) {
            sb.append("sequence=").append(name)
                .append(SEP).append("start=").append(seq.start)
                .append(SEP).append("increment=").append(seq.increment)
                .append(SEP).append("min=").append(seq.minValue ?: "")
                .append(SEP).append("max=").append(seq.maxValue ?: "")
                .append(SEP).append("cycle=").append(seq.cycle)
                .append(SEP).append("cache=").append(seq.cache ?: "")
                .append('\n')
        }
    }

    // ── Routines / triggers ────────────────────────────────────────

    private fun appendFunctions(sb: StringBuilder, fns: Map<String, FunctionDefinition>) {
        sb.append("functions[").append(fns.size).append("]\n")
        for ((name, fn) in fns.entries.sortedBy { it.key }) {
            sb.append("function=").append(name)
                .append(SEP).append("language=").append(fn.language ?: "")
                .append(SEP).append("returns=").append(fn.returns?.type ?: "")
                .append(SEP).append("body=").append(fn.body ?: "")
                .append(SEP).append("source_dialect=").append(fn.sourceDialect ?: "")
                .append('\n')
        }
    }

    private fun appendProcedures(sb: StringBuilder, procs: Map<String, ProcedureDefinition>) {
        sb.append("procedures[").append(procs.size).append("]\n")
        for ((name, proc) in procs.entries.sortedBy { it.key }) {
            sb.append("procedure=").append(name)
                .append(SEP).append("language=").append(proc.language ?: "")
                .append(SEP).append("body=").append(proc.body ?: "")
                .append(SEP).append("source_dialect=").append(proc.sourceDialect ?: "")
                .append('\n')
        }
    }

    private fun appendTriggers(sb: StringBuilder, trs: Map<String, TriggerDefinition>) {
        sb.append("triggers[").append(trs.size).append("]\n")
        for ((name, trg) in trs.entries.sortedBy { it.key }) {
            sb.append("trigger=").append(name)
                .append(SEP).append("table=").append(trg.table)
                .append(SEP).append("event=").append(trg.event.name)
                .append(SEP).append("timing=").append(trg.timing.name)
                .append(SEP).append("for_each=").append(trg.forEach.name)
                .append(SEP).append("condition=").append(trg.condition ?: "")
                .append(SEP).append("body=").append(trg.body ?: "")
                .append(SEP).append("source_dialect=").append(trg.sourceDialect ?: "")
                .append('\n')
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

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
