package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferentialAction
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.ManualActionRequired
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote

/**
 * Spalten- und Constraint-Rendering fuer Oracle-DDL, aus [OracleDdlGenerator]
 * ausgelagert. Anders als T-SQL kennt Oracle **keine benannten DEFAULT-
 * Constraints** -- DEFAULT ist eine reine Spalteneigenschaft
 * (`col NUMBER DEFAULT 1`), wie bei PostgreSQL. UNIQUE/CHECK/PK/FK werden
 * dagegen benannt gerendert (`uq_`/`ck_<table>_<column>`), damit ein
 * spaeterer Diff-Pfad sie adressieren kann.
 *
 * UNIQUE/PRIMARY KEY auf CLOB/BLOB-Spalten ist in Oracle nicht erzeugbar
 * (ORA-02329) -- E057 statt ungueltigem DDL, analog MSSQLs LOB-Schluessel-
 * Regel. Anders als MSSQL zaehlt NULL in Oracle-UNIQUE-Constraints NICHT als
 * Wert (wie PostgreSQL/MySQL/SQLite) -- kein Analogon zu MSSQLs W138 noetig.
 */
internal class OracleColumnConstraintHelper(
    private val quoteIdentifier: (String) -> String,
    private val typeMapper: OracleTypeMapper,
) {

    fun generateColumnSql(
        tableName: String,
        colName: String,
        col: ColumnDefinition,
        schema: SchemaDefinition,
        notes: MutableList<TransformationNote>,
    ): String {
        val type = col.type
        val ctx = ColumnContext(tableName, colName, col, notes)
        val generation = col.generation
        return when {
            generation is ColumnGeneration.Identity && supportsIdentity(type) ->
                identityColumn(ctx, generation.mode)
            type is NeutralType.Identifier && type.autoIncrement ->
                identityColumn(ctx, IdentityMode.ALWAYS)
            type is NeutralType.Enum -> enumColumn(ctx, type, schema)
            else -> {
                if (generation is ColumnGeneration.Identity) {
                    notes += identityDroppedNote(
                        tableName, colName,
                        "Identity generation on column '$colName' was dropped: Oracle supports IDENTITY only on " +
                            "NUMBER columns, not on ${typeMapper.toSql(type)}.",
                    )
                }
                plainColumn(ctx)
            }
        }
    }

    private class ColumnContext(
        val tableName: String,
        val colName: String,
        val col: ColumnDefinition,
        val notes: MutableList<TransformationNote>,
    )

    // ── Identity ─────────────────────────────────

    private fun supportsIdentity(type: NeutralType): Boolean = when (type) {
        is NeutralType.Integer, is NeutralType.SmallInt, is NeutralType.BigInteger -> true
        is NeutralType.Decimal -> type.scale == 0
        else -> false
    }

    private fun identityColumn(ctx: ColumnContext, mode: IdentityMode): String {
        val modeSql = when (mode) {
            IdentityMode.ALWAYS -> "ALWAYS"
            IdentityMode.BY_DEFAULT -> "BY DEFAULT"
        }
        val parts = mutableListOf(quoteIdentifier(ctx.colName), typeMapper.toSql(ctx.col.type))
        parts += "GENERATED $modeSql AS IDENTITY"
        if (ctx.col.unique) parts += uniqueClause(ctx.tableName, ctx.colName)
        if (ctx.col.default != null) {
            ctx.notes += identityDroppedNote(
                ctx.tableName, ctx.colName,
                "Default on identity column '${ctx.colName}' was dropped: Oracle allows no DEFAULT on an " +
                    "IDENTITY column.",
            )
        }
        return parts.joinToString(" ")
    }

    private fun identityDroppedNote(tableName: String, colName: String, message: String) = TransformationNote(
        type = NoteType.WARNING,
        code = "W151",
        objectName = "$tableName.$colName",
        message = message,
        hint = "Adjust the identity generation or drop the conflicting property in the schema.",
    )

    // ── Enum ─────────────────────────────────────

    /**
     * Enum -> `VARCHAR2(<laengster Wert>)` + benannter CHECK: Oracle kennt
     * keinen Enum-Typ (auch kein `CREATE TYPE ... AS ENUM` wie PostgreSQL).
     * Ein `refType` verweist entweder auf einen benannten Enum (Werte
     * aufloesen) oder eine PostgreSQL-`DOMAIN` (fuer Oracle noch nicht
     * gebaute Basistyp-Aufloesung -- E053, Spalte bleibt als CLOB nutzbar).
     */
    private fun enumColumn(ctx: ColumnContext, type: NeutralType.Enum, schema: SchemaDefinition): String {
        val refType = type.refType
        if (refType != null) {
            val customType = schema.customTypes[refType]
            if (customType?.kind == CustomTypeKind.DOMAIN) {
                ctx.notes += ManualActionRequired(
                    code = "E053", objectType = "domain", objectName = "${ctx.tableName}.${ctx.colName}",
                    reason = "Domain '$refType' has no Oracle base-type resolution yet; column '${ctx.colName}' " +
                        "was rendered as CLOB.",
                    hint = "Declare the column with a neutral type directly, or adjust the value manually.",
                ).toNote()
                val parts = mutableListOf(quoteIdentifier(ctx.colName), "CLOB")
                parts += nullabilityDefaultUnique(ctx, lob = true)
                return parts.joinToString(" ")
            }
            (customType?.values ?: type.values)?.let { return boundedEnumColumn(ctx, it) }
        }
        val values = type.values ?: return plainColumn(ctx)
        return boundedEnumColumn(ctx, values)
    }

    private fun boundedEnumColumn(ctx: ColumnContext, values: List<String>): String {
        val parts = mutableListOf(quoteIdentifier(ctx.colName), "VARCHAR2(${OracleTypeMapper.enumWidth(values)})")
        parts += nullabilityDefaultUnique(ctx, lob = false)
        parts += enumCheckClause(ctx.tableName, ctx.colName, values)
        return parts.joinToString(" ")
    }

    /**
     * Die benannte CHECK-Klausel einer wertebasierten Enum-Spalte. Einzige
     * Quelle fuer den Generate-Pfad (inline im `CREATE TABLE`) und den
     * Diff-Pfad (`OracleDiffCustomTypeOps`, das sie beim Fan-out eines
     * geaenderten Custom Types einzeln droppen und neu anlegen muss) --
     * berechneten beide den Namen getrennt, droppte der Fan-out einen
     * Constraint, den es unter diesem Namen nicht gibt.
     */
    fun enumCheckClause(tableName: String, colName: String, values: List<String>): String {
        // `toDefaultSql` ignoriert den Typ fuer String-Literale; ihn hier
        // durchzureichen taeuschte eine Abhaengigkeit vor, die es nicht gibt.
        val allowed = values.joinToString(", ") { typeMapper.toDefaultSql(DefaultValue.StringLiteral(it), NeutralType.Text()) }
        return "CONSTRAINT ${quoteIdentifier(enumCheckName(tableName, colName))} " +
            "CHECK (${quoteIdentifier(colName)} IN ($allowed))"
    }

    // ── Plain columns ────────────────────────────

    private fun plainColumn(ctx: ColumnContext): String {
        val type = ctx.col.type
        val parts = mutableListOf(quoteIdentifier(ctx.colName), typeMapper.toSql(type))
        ctx.notes += typeNotes(ctx.tableName, ctx.colName, type)
        parts += nullabilityDefaultUnique(ctx, typeMapper.isLargeObject(type))
        return parts.joinToString(" ")
    }

    private fun typeNotes(tableName: String, colName: String, type: NeutralType): List<TransformationNote> {
        val objectName = "$tableName.$colName"
        val notes = mutableListOf<TransformationNote>()
        if (typeMapper.isWidenedToClob(type)) {
            val limit = if (type is NeutralType.Char) OracleTypeMapper.MAX_CHAR_LENGTH else OracleTypeMapper.MAX_VARCHAR2_LENGTH
            notes += TransformationNote(
                type = NoteType.WARNING, code = "W145", objectName = objectName,
                message = "Declared length of column '$colName' exceeds $limit bytes; rendered as CLOB " +
                    "(the length bound is not preserved in Oracle).",
                hint = "Reduce the length to fit VARCHAR2/CHAR, or accept the unbounded CLOB column.",
            )
        }
        if (type is NeutralType.Time) {
            notes += TransformationNote(
                type = NoteType.WARNING, code = "W146", objectName = objectName,
                message = "Time column '$colName' is rendered as VARCHAR2(8) ('HH24:MI:SS' text): Oracle has no " +
                    "native time-only column type.",
                hint = "Add a CHECK constraint to validate the format if strict typing is required.",
            )
        }
        if (type is NeutralType.Date) {
            notes += TransformationNote(
                type = NoteType.INFO, code = "W147", objectName = objectName,
                message = "Date column '$colName' is rendered as Oracle DATE, which always carries a time " +
                    "component (midnight); a subsequent reverse read yields datetime, not date.",
            )
        }
        if (type is NeutralType.Array) {
            notes += TransformationNote(
                type = NoteType.WARNING, code = "W149", objectName = objectName,
                message = "Array column '$colName' is rendered as JSON: Oracle has no native array column type.",
                hint = "Values are stored as a JSON array; adjust application code that expects a native array.",
            )
        }
        if (type is NeutralType.FullText) {
            notes += TransformationNote(
                type = NoteType.WARNING, code = "W132", objectName = objectName,
                message = "Full-text column '$colName' degraded to CLOB; Oracle has no full-text vector column type.",
                hint = "To restore full-text search, create an Oracle Text index over the source text column(s) manually.",
            )
        }
        if (typeMapper.isPrecisionClamped(type)) {
            notes += TransformationNote(
                type = NoteType.WARNING, code = "W148", objectName = objectName,
                message = "NUMBER precision of column '$colName' exceeds 38 and was clamped to 38 (Oracle maximum).",
                hint = "Verify the value range still fits, or use a wider textual representation.",
            )
        }
        return notes
    }

    private fun nullabilityDefaultUnique(ctx: ColumnContext, lob: Boolean): List<String> {
        val parts = mutableListOf<String>()
        if (ctx.col.required) parts += "NOT NULL"
        ctx.col.default?.let { default ->
            if (default is DefaultValue.FunctionCall && default.name.lowercase() == "gen_uuid") {
                ctx.notes += TransformationNote(
                    type = NoteType.INFO, code = "W150", objectName = "${ctx.tableName}.${ctx.colName}",
                    message = "Default 'gen_uuid' on column '${ctx.colName}' is rendered as RAWTOHEX(SYS_GUID()): " +
                        "32 hex characters without dashes, unlike the standard UUID text format.",
                    hint = "Wrap with REGEXP_REPLACE to insert dashes if the standard UUID format is required.",
                )
            }
            parts += "DEFAULT ${typeMapper.toDefaultSql(default, ctx.col.type)}"
        }
        if (ctx.col.unique) {
            if (lob) {
                ctx.notes += lobKeyNote(ctx.tableName, "uq_${ctx.tableName}_${ctx.colName}", "UNIQUE", listOf(ctx.colName))
            } else {
                parts += uniqueClause(ctx.tableName, ctx.colName)
            }
        }
        return parts
    }

    private fun uniqueClause(tableName: String, colName: String): String =
        "CONSTRAINT ${quoteIdentifier("uq_${tableName}_$colName")} UNIQUE"

    /** E057: UNIQUE/PRIMARY KEY auf LOB-Spalten ist in Oracle nicht erzeugbar (ORA-02329). */
    fun lobKeyNote(tableName: String, constraintName: String, kind: String, columns: List<String>): TransformationNote =
        ManualActionRequired(
            code = "E057", objectType = "constraint", objectName = constraintName,
            reason = "$kind constraint '$constraintName' on table '$tableName' was skipped: column(s) " +
                "'${columns.joinToString(", ")}' are large-object types (CLOB/BLOB) which Oracle does not allow " +
                "as key columns (ORA-02329).",
            hint = "Bound the column (e.g. max_length <= 4000) so it becomes key-eligible, or enforce uniqueness manually.",
        ).toNote()

    // ── Foreign keys / table constraints ─────────

    /**
     * Oracle kennt fuer `ON DELETE` nur `CASCADE` und `SET NULL`; `RESTRICT`/
     * `NO_ACTION` entsprechen dem Oracle-Default (keine Klausel = Loeschung
     * der referenzierten Zeile schlaegt fehl, solange Kinder existieren) und
     * werden deshalb ohne Notiz weggelassen. `SET_DEFAULT` hat kein Oracle-
     * Aequivalent und wird mit W153 ausgewiesen.
     */
    fun buildForeignKeyClause(
        constraintName: String,
        fromColumns: List<String>,
        toTable: String,
        toColumns: List<String>,
        onDelete: ReferentialAction?,
        notes: MutableList<TransformationNote>,
    ): String {
        val fromCols = fromColumns.joinToString(", ") { quoteIdentifier(it) }
        val toCols = toColumns.joinToString(", ") { quoteIdentifier(it) }
        return buildString {
            append("CONSTRAINT ${quoteIdentifier(constraintName)} FOREIGN KEY ($fromCols) ")
            append("REFERENCES ${quoteIdentifier(toTable)} ($toCols)")
            // Oracle kennt kein ON UPDATE fuer Fremdschluessel (wie beim Reverse).
            when (onDelete) {
                ReferentialAction.CASCADE -> append(" ON DELETE CASCADE")
                ReferentialAction.SET_NULL -> append(" ON DELETE SET NULL")
                ReferentialAction.SET_DEFAULT -> notes += TransformationNote(
                    type = NoteType.WARNING, code = "W153", objectName = constraintName,
                    message = "ON DELETE SET DEFAULT on foreign key '$constraintName' was dropped: Oracle has " +
                        "no equivalent referential action.",
                    hint = "Enforce the default value with an AFTER DELETE trigger if required.",
                )
                ReferentialAction.RESTRICT, ReferentialAction.NO_ACTION, null -> Unit
            }
        }
    }

    fun generateConstraintClause(
        tableName: String,
        constraint: ConstraintDefinition,
        lobColumns: Set<String>,
        notes: MutableList<TransformationNote>,
    ): String? = when (constraint.type) {
        ConstraintType.CHECK ->
            "CONSTRAINT ${quoteIdentifier(constraint.name)} CHECK (${constraint.expression})"
        ConstraintType.UNIQUE -> {
            val columns = constraint.columns.orEmpty()
            val lob = columns.filter { it in lobColumns }
            if (lob.isNotEmpty()) {
                notes += lobKeyNote(tableName, constraint.name, "UNIQUE", lob)
                null
            } else {
                "CONSTRAINT ${quoteIdentifier(constraint.name)} UNIQUE (${columns.joinToString(", ") { quoteIdentifier(it) }})"
            }
        }
        ConstraintType.EXCLUDE -> {
            notes += ManualActionRequired(
                code = "E054", objectType = "constraint", objectName = constraint.name,
                reason = "EXCLUDE constraint '${constraint.name}' is not supported in Oracle.",
                hint = "Enforce the exclusion with a trigger or application-level validation instead.",
            ).toNote()
            null
        }
        ConstraintType.FOREIGN_KEY -> {
            val ref = constraint.references!!
            buildForeignKeyClause(constraint.name, constraint.columns.orEmpty(), ref.table, ref.columns, ref.onDelete, notes)
        }
    }

    companion object {
        /** Namensschema des Enum-CHECKs -- siehe [enumCheckClause]. */
        fun enumCheckName(tableName: String, colName: String): String = "ck_${tableName}_$colName"
    }
}
