package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferentialAction
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.ManualActionRequired
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote

/**
 * Spalten- und Constraint-Rendering für T-SQL, aus [MssqlDdlGenerator]
 * ausgelagert. Alle Spalten-Constraints (DEFAULT, UNIQUE, CHECK) werden
 * **benannt** gerendert (`df_`/`uq_`/`ck_<table>_<column>`): SQL Server
 * vergibt sonst Zufallsnamen (`DF__orders__3213E83F`), die ein Diff-Pfad
 * nicht deterministisch adressieren könnte (Regeln: `spec/ddl-generation-rules.md`).
 *
 * LOB-Spalten (`NVARCHAR(MAX)`, `VARBINARY(MAX)`, `XML`) sind in SQL Server
 * keine zulässigen Schlüsselspalten — UNIQUE/PRIMARY KEY darauf werden mit
 * E057 ausgewiesen statt als ungültiges DDL emittiert; die LOB-Menge liefert
 * [MssqlColumnTypeResolver.lobColumns] (geteilt mit dem Index-Pfad).
 */
internal class MssqlColumnConstraintHelper(
    private val quoteIdentifier: (String) -> String,
    private val typeMapper: MssqlTypeMapper,
    private val typeResolver: MssqlColumnTypeResolver,
    private val referentialActionSql: (ReferentialAction) -> String,
) {

    fun generateColumnSql(
        tableName: String,
        colName: String,
        col: ColumnDefinition,
        table: TableDefinition,
        schema: SchemaDefinition,
        notes: MutableList<TransformationNote>,
    ): String = renderColumn(tableName, colName, col, table, schema, notes).let { rendering ->
        (listOf(rendering.declaration) + rendering.objects.map { inlineClause(it) }).joinToString(" ")
    }

    /**
     * Spaltendeklaration und die benannten Objekte der Spalte **getrennt**.
     *
     * Der Generate-Pfad setzt beides sofort zusammen ([generateColumnSql]);
     * der Tabellen-Neubau des Diff-Pfads kann das nicht. SQL Server fuehrt
     * Constraints schema-global: solange die alte Tabelle existiert, sind
     * `df_`/`uq_`/`ck_<tabelle>_<spalte>` vergeben, und die Neubau-Tabelle
     * scheiterte an Msg 2714. Sie legt die Spalte deshalb nackt an und holt
     * die Objekte nach dem Umbenennen unter ihren endgueltigen Namen nach
     * ([alterStatement]).
     *
     * Beide Formen entstehen aus DERSELBEN Liste. Die Entscheidung, welche
     * Objekte eine Spalte hat — kein UNIQUE auf LOB (E057), CHECK nur bei
     * Enum und Domain, kein DEFAULT auf IDENTITY — faellt damit an genau
     * einer Stelle statt an zweien, die auseinanderlaufen koennen.
     */
    fun renderColumn(
        tableName: String,
        colName: String,
        col: ColumnDefinition,
        table: TableDefinition,
        schema: SchemaDefinition,
        notes: MutableList<TransformationNote>,
    ): ColumnRendering {
        val type = col.type
        val generation = col.generation
        val ctx = ColumnContext(tableName, colName, col, table, notes)
        val declaration = when {
            generation is ColumnGeneration.Identity && supportsIdentity(type) ->
                identityColumn(ctx, typeMapper.toSql(type), generation.mode)
            type is NeutralType.Identifier && type.autoIncrement -> identityColumn(ctx, "INT", IdentityMode.ALWAYS)
            type is NeutralType.Enum -> enumColumn(ctx, type, schema)
            type is NeutralType.Geometry -> geometryColumn(ctx, type)
            else -> {
                if (generation is ColumnGeneration.Identity) {
                    notes += identityNote(
                        tableName, colName,
                        "Identity generation on column '$colName' was dropped: SQL Server supports IDENTITY only on " +
                            "integer and scale-0 DECIMAL types, not on ${typeMapper.toSql(type)}.",
                    )
                }
                plainColumn(ctx)
            }
        }
        return ColumnRendering(declaration, ctx.objects.toList(), ctx.sqlType)
    }

    /**
     * Die Deklaration ohne benannte Objekte, die Objekte, die zu ihr gehoeren,
     * und der reine SQL-Typ.
     *
     * [sqlType] ist nicht redundant zu `MssqlTypeMapper.toSql`: der Mapper
     * kennt nur den neutralen Typ, der Helfer die SPALTE. Ein Enum wird beim
     * Mapper zu `NVARCHAR(MAX)`, hier zu `NVARCHAR(<laengster Wert>)` — und
     * nur die zweite Form ist schluesselfaehig und deckt sich mit dem, was
     * `schema generate` schreibt. `null`, wenn der Zweig keinen Typ bestimmt.
     */
    data class ColumnRendering(
        val declaration: String,
        val objects: List<MssqlColumnObject>,
        val sqlType: String? = null,
    )

    private fun inlineClause(obj: MssqlColumnObject): String {
        val head = "CONSTRAINT ${quoteIdentifier(obj.name)}"
        return when (obj.kind) {
            MssqlColumnObject.Kind.DEFAULT -> "$head DEFAULT ${obj.body}"
            MssqlColumnObject.Kind.UNIQUE -> "$head UNIQUE"
            MssqlColumnObject.Kind.CHECK -> "$head CHECK (${obj.body})"
        }
    }

    /**
     * Dasselbe Objekt als eigenstaendiges Statement — die Form, die der
     * Tabellen-Neubau nach dem Umbenennen braucht. Der DEFAULT nennt seine
     * Spalte im `FOR`, das UNIQUE in der Spaltenliste; der CHECK traegt sie
     * bereits in seinem Ausdruck.
     */
    fun alterStatement(tableName: String, colName: String, obj: MssqlColumnObject): String {
        val head = "ALTER TABLE ${quoteIdentifier(tableName)} ADD CONSTRAINT ${quoteIdentifier(obj.name)}"
        return when (obj.kind) {
            MssqlColumnObject.Kind.DEFAULT -> "$head DEFAULT ${obj.body} FOR ${quoteIdentifier(colName)};"
            MssqlColumnObject.Kind.UNIQUE -> "$head UNIQUE (${quoteIdentifier(colName)});"
            MssqlColumnObject.Kind.CHECK -> "$head CHECK (${obj.body});"
        }
    }

    private class ColumnContext(
        val tableName: String,
        val colName: String,
        val col: ColumnDefinition,
        val table: TableDefinition,
        val notes: MutableList<TransformationNote>,
    ) {
        /** Die benannten Objekte, die die Spalten-Zweige unterwegs einsammeln. */
        val objects = mutableListOf<MssqlColumnObject>()

        /** Der reine SQL-Typ, den der jeweilige Zweig bestimmt hat. */
        var sqlType: String? = null
    }

    // ── Identity ─────────────────────────────────


    private fun identityColumn(ctx: ColumnContext, baseType: String, mode: IdentityMode): String {
        ctx.sqlType = baseType
        val parts = mutableListOf(quoteIdentifier(ctx.colName), "$baseType IDENTITY(1,1)", "NOT NULL")
        if (ctx.col.unique) ctx.objects += uniqueObject(ctx)
        if (mode == IdentityMode.BY_DEFAULT) {
            ctx.notes += identityNote(
                ctx.tableName, ctx.colName,
                "Identity column '${ctx.colName}' (GENERATED BY DEFAULT) is rendered as IDENTITY(1,1): " +
                    "SQL Server rejects explicit values unless SET IDENTITY_INSERT is ON.",
            )
        }
        if (ctx.col.default != null) {
            ctx.notes += identityNote(
                ctx.tableName, ctx.colName,
                "Default on identity column '${ctx.colName}' was dropped: SQL Server allows no DEFAULT on IDENTITY columns.",
            )
        }
        return parts.joinToString(" ")
    }

    private fun identityNote(tableName: String, colName: String, message: String) = TransformationNote(
        type = NoteType.WARNING,
        code = "W140",
        objectName = "$tableName.$colName",
        message = message,
        hint = "Load data with SET IDENTITY_INSERT ON, or adjust the identity generation in the schema.",
    )

    // ── Enum / Domain ────────────────────────────

    private fun enumColumn(ctx: ColumnContext, type: NeutralType.Enum, schema: SchemaDefinition): String {
        val refType = type.refType
        if (refType != null) {
            val customType = schema.customTypes[refType]
            if (customType != null && customType.kind == CustomTypeKind.DOMAIN) {
                return domainColumn(ctx, refType, customType)
            }
            customType?.values?.let { return boundedEnumColumn(ctx, it) }
        }
        type.values?.let { return boundedEnumColumn(ctx, it) }
        return plainColumn(ctx)
    }

    /**
     * Enum → `NVARCHAR(<längster Wert>)` + benannter CHECK: T-SQL kennt keinen
     * Enum-Typ; die Breite ist auf den Wertevorrat begrenzt (wie MySQL-`ENUM`),
     * damit die Spalte index- und schlüsselfähig bleibt (NVARCHAR(MAX) wäre es nicht).
     */
    private fun boundedEnumColumn(ctx: ColumnContext, values: List<String>): String {
        val width = MssqlTypeMapper.enumWidth(values)
        ctx.sqlType = typeMapper.unicodeText(width)
        val parts = mutableListOf(quoteIdentifier(ctx.colName), typeMapper.unicodeText(width))
        parts += nullabilityAndObjects(ctx, lob = false)
        val allowed = values.joinToString(", ") { typeMapper.toDefaultSql(DefaultValue.StringLiteral(it), ctx.col.type) }
        ctx.objects += MssqlColumnObject(
            MssqlColumnObject.Kind.CHECK,
            checkName(ctx),
            "${quoteIdentifier(ctx.colName)} IN ($allowed)",
        )
        return parts.joinToString(" ")
    }

    private fun domainColumn(ctx: ColumnContext, domainName: String, customType: CustomTypeDefinition): String {
        val baseType = customType.baseType ?: "text"
        val neutral = typeResolver.resolveDomainBaseType(baseType, customType.precision, customType.scale)
        val sqlType = if (neutral != null) {
            typeMapper.toSql(neutral)
        } else {
            // Kein Roh-Durchreichen fremder Typnamen: sichtbar als E053, Spalte bleibt nutzbar.
            ctx.notes += ManualActionRequired(
                code = "E053", objectType = "domain", objectName = "${ctx.tableName}.${ctx.colName}",
                reason = "Domain '$domainName' base type '$baseType' has no T-SQL mapping; column " +
                    "'${ctx.colName}' was rendered as NVARCHAR(MAX).",
                hint = "Declare the domain base type as a neutral type, or adjust the column type manually.",
            ).toNote()
            "NVARCHAR(MAX)"
        }
        ctx.sqlType = sqlType
        val parts = mutableListOf(quoteIdentifier(ctx.colName), sqlType)
        val lob = neutral?.let { typeMapper.isLargeObject(it) } ?: true
        parts += nullabilityAndObjects(ctx, lob)
        customType.check?.let { check ->
            // PostgreSQL-Domain-CHECKs adressieren den Wert als `VALUE`; in T-SQL
            // steht dort die Spalte selbst (String-Literale bleiben unangetastet).
            val expression = typeResolver.substituteValueToken(check, quoteIdentifier(ctx.colName))
            ctx.objects += MssqlColumnObject(MssqlColumnObject.Kind.CHECK, checkName(ctx), expression)
        }
        return parts.joinToString(" ")
    }

    // ── Geometry ─────────────────────────────────

    private fun geometryColumn(ctx: ColumnContext, type: NeutralType.Geometry): String {
        val sqlType = typeMapper.spatialTypeSql(type)
        ctx.sqlType = sqlType
        val parts = mutableListOf(quoteIdentifier(ctx.colName), sqlType)
        if (ctx.col.required) parts += "NOT NULL"
        // Bei `geography` ist 4326 der SQL-Server-Default-SRID der Werte — nur
        // ein abweichender SRID oder ein Subtyp ist dann nicht spaltenseitig erzwungen.
        val sridUnenforced = type.srid != null &&
            !(sqlType == "geography" && type.srid == MssqlTypeMapper.GEOGRAPHY_DEFAULT_SRID)
        if (sridUnenforced || type.geometryType != GeometryType.GEOMETRY) {
            ctx.notes += TransformationNote(
                type = NoteType.WARNING,
                code = "W120",
                objectName = "${ctx.tableName}.${ctx.colName}",
                message = "Geometry column '${ctx.colName}' is rendered as `$sqlType`: SQL Server carries the subtype " +
                    "('${type.geometryType}') and the SRID (${type.srid ?: "none"}) per value, not per column.",
                hint = "Enforce the subtype/SRID with a CHECK constraint (e.g. STGeometryType()/STSrid) if required.",
            )
        }
        return parts.joinToString(" ")
    }

    // ── Plain columns ────────────────────────────

    private fun plainColumn(ctx: ColumnContext): String {
        val type = ctx.col.type
        ctx.sqlType = typeMapper.toSql(type)
        val parts = mutableListOf(quoteIdentifier(ctx.colName), typeMapper.toSql(type))
        ctx.notes += typeNotes(ctx.tableName, ctx.colName, type)
        parts += nullabilityAndObjects(ctx, typeMapper.isLargeObject(type))
        return parts.joinToString(" ")
    }

    private fun typeNotes(tableName: String, colName: String, type: NeutralType): List<TransformationNote> {
        val objectName = "$tableName.$colName"
        val notes = mutableListOf<TransformationNote>()
        if (typeMapper.isWidenedToMax(type)) {
            notes += TransformationNote(
                type = NoteType.WARNING, code = "W136", objectName = objectName,
                message = "Declared length of column '$colName' exceeds 4000 characters; rendered as NVARCHAR(MAX) " +
                    "(the length bound is not preserved in SQL Server).",
                hint = "Reduce the length to 4000 or accept the unbounded NVARCHAR(MAX) column.",
            )
        }
        if (type is NeutralType.Json || type is NeutralType.Array) {
            val kind = if (type is NeutralType.Json) "JSON" else "Array"
            notes += TransformationNote(
                type = NoteType.WARNING, code = "W137", objectName = objectName,
                message = "$kind column '$colName' is rendered as NVARCHAR(MAX): SQL Server has no native " +
                    "$kind column type; a reverse read yields plain text.",
                hint = "Add CHECK (ISJSON(${quoteIdentifier(colName)}) = 1) if the content must stay valid JSON.",
            )
        }
        if (type is NeutralType.FullText) {
            notes += TransformationNote(
                type = NoteType.WARNING, code = "W132", objectName = objectName,
                message = "Full-text column '$colName' degraded to NVARCHAR(MAX); SQL Server has no full-text " +
                    "vector column type.",
                hint = "To restore full-text search, create a full-text catalog and index over the source text " +
                    "column(s) manually.",
            )
        }
        if (typeMapper.isPrecisionClamped(type)) {
            notes += TransformationNote(
                type = NoteType.WARNING, code = "W139", objectName = objectName,
                message = "DECIMAL precision of column '$colName' exceeds 38 and was clamped to 38 " +
                    "(SQL Server maximum).",
                hint = "Verify the value range still fits, or use a wider textual representation.",
            )
        }
        return notes
    }

    /**
     * `NOT NULL` gehört in die Deklaration und bleibt dort; DEFAULT und UNIQUE
     * sind benannte Objekte und wandern nach [ColumnContext.objects] — W138 bei
     * NULL-fähiger Spalte und als E057-Skip auf LOB-Spalten (kein Schlüssel möglich).
     */
    private fun nullabilityAndObjects(ctx: ColumnContext, lob: Boolean): List<String> {
        val parts = mutableListOf<String>()
        if (ctx.col.required) parts += "NOT NULL"
        ctx.col.default?.let {
            ctx.objects += MssqlColumnObject(
                MssqlColumnObject.Kind.DEFAULT,
                MssqlConstraintNames.default(ctx.tableName, ctx.colName),
                typeMapper.toDefaultSql(it, ctx.col.type),
            )
        }
        if (ctx.col.unique) {
            if (lob) {
                ctx.notes += lobKeyNote(
                    ctx.tableName,
                    MssqlConstraintNames.unique(ctx.tableName, ctx.colName),
                    "UNIQUE",
                    listOf(ctx.colName),
                )
            } else {
                ctx.objects += uniqueObject(ctx)
                if (isNullable(ctx.table, ctx.colName)) {
                    ctx.notes += nullableUniqueNote(ctx.tableName, ctx.colName, ctx.colName)
                }
            }
        }
        return parts
    }

    private fun uniqueObject(ctx: ColumnContext) = MssqlColumnObject(
        MssqlColumnObject.Kind.UNIQUE,
        MssqlConstraintNames.unique(ctx.tableName, ctx.colName),
        body = "",
    )

    private fun checkName(ctx: ColumnContext): String = MssqlConstraintNames.check(ctx.tableName, ctx.colName)

    /**
     * W138: SQL Server zählt NULL in UNIQUE-Constraints als Wert — höchstens
     * eine NULL-Zeile, anders als PostgreSQL/MySQL/SQLite (beliebig viele NULLs).
     */
    private fun nullableUniqueNote(tableName: String, objectName: String, columns: String) = TransformationNote(
        type = NoteType.WARNING,
        code = "W138",
        objectName = "$tableName.$objectName",
        message = "UNIQUE on nullable column(s) '$columns': SQL Server treats NULL as a value in unique " +
            "constraints, so at most one NULL row is allowed (PostgreSQL/MySQL/SQLite allow many).",
        hint = "Use a filtered unique index (WHERE column IS NOT NULL) if multiple NULL rows must coexist.",
    )

    companion object {
        /**
         * Ob T-SQL auf diesem Typ ueberhaupt IDENTITY kennt. Steht im Companion,
         * weil nicht nur der Generate-Pfad die Frage stellt: der Diff-Pfad
         * entscheidet daran, ob eine Typaenderung einen Tabellen-Neubau braucht
         * ([MssqlRebuildPlanner]) — und eine zweite Kopie dieser Liste waere
         * genau die Abweichung, die erst gegen eine echte Datenbank auffiele.
         */
        fun supportsIdentity(type: NeutralType): Boolean = when (type) {
            is NeutralType.Integer, is NeutralType.BigInteger, is NeutralType.SmallInt -> true
            // T-SQL IDENTITY auf DECIMAL nur mit Skala 0 (so liefert es auch der Reverse).
            is NeutralType.Decimal -> type.scale == 0
            else -> false
        }

        /**
         * Ob diese Spalte in SQL Server als IDENTITY landet — aus dem Typ
         * (`identifier` mit `auto_increment`) oder aus `generation`, sofern der
         * Typ es traegt. Beide Wege fuehren zu derselben Spalte, und beide
         * schliessen `ALTER COLUMN` aus (Msg 156).
         */
        fun isIdentity(type: NeutralType, col: ColumnDefinition?): Boolean =
            (type as? NeutralType.Identifier)?.autoIncrement == true ||
                (col?.generation is ColumnGeneration.Identity && supportsIdentity(type))
    }

    /** E057: UNIQUE/PRIMARY KEY auf LOB-Spalten ist in SQL Server nicht erzeugbar. */
    fun lobKeyNote(tableName: String, constraintName: String, kind: String, columns: List<String>): TransformationNote =
        ManualActionRequired(
            code = "E057", objectType = "constraint", objectName = constraintName,
            reason = "$kind constraint '$constraintName' on table '$tableName' was skipped: column(s) " +
                "'${columns.joinToString(", ")}' are large-object types (NVARCHAR(MAX)/VARBINARY(MAX)/XML) " +
                "which SQL Server does not allow as key columns.",
            hint = "Bound the column (e.g. max_length <= 4000) so it becomes key-eligible, or enforce uniqueness manually.",
        ).toNote()

    // ── Foreign keys / table constraints ─────────

    /** Ein zu rendernder Fremdschlüssel (Spalten-Referenz, Constraint, zirkulär oder aufgeschoben). */
    data class ForeignKeySpec(
        val constraintName: String,
        val fromTable: String,
        val fromColumns: List<String>,
        val toTable: String,
        val toColumns: List<String>,
        val onDelete: ReferentialAction?,
        val onUpdate: ReferentialAction?,
    )

    /**
     * FK-Klausel; kaskadierende Aktionen, die [MssqlCascadePathGuard] als
     * Zyklus/Mehrfachpfad erkannt hat, werden zu `NO ACTION` und als E057
     * in [notes] ausgewiesen.
     */
    fun buildForeignKeyClause(
        guard: MssqlCascadePathGuard,
        fk: ForeignKeySpec,
        notes: MutableList<TransformationNote>,
    ): String {
        val neutralise = guard.mustNeutralise(fk.constraintName) &&
            (MssqlCascadePathGuard.isCascading(fk.onDelete) || MssqlCascadePathGuard.isCascading(fk.onUpdate))
        if (neutralise) {
            notes += ManualActionRequired(
                code = "E057", objectType = "constraint", objectName = fk.constraintName,
                reason = "Cascading referential action of foreign key '${fk.constraintName}' " +
                    "('${fk.fromTable}' → '${fk.toTable}') was rendered as NO ACTION: SQL Server rejects " +
                    "cascade cycles and multiple cascade paths (error 1785).",
                hint = "Implement the cascade with an AFTER DELETE/UPDATE trigger, or restructure the cascade paths.",
            ).toNote()
        }
        fun action(action: ReferentialAction?): String? = when {
            action == null -> null
            neutralise && MssqlCascadePathGuard.isCascading(action) -> "NO ACTION"
            else -> referentialActionSql(action)
        }
        val fromCols = fk.fromColumns.joinToString(", ") { quoteIdentifier(it) }
        val toCols = fk.toColumns.joinToString(", ") { quoteIdentifier(it) }
        return buildString {
            append("CONSTRAINT ${quoteIdentifier(fk.constraintName)} FOREIGN KEY ($fromCols) ")
            append("REFERENCES ${quoteIdentifier(fk.toTable)} ($toCols)")
            action(fk.onDelete)?.let { append(" ON DELETE $it") }
            action(fk.onUpdate)?.let { append(" ON UPDATE $it") }
        }
    }

    fun generateConstraintClause(
        guard: MssqlCascadePathGuard,
        tableName: String,
        table: TableDefinition,
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
                val nullable = columns.filter { isNullable(table, it) }
                if (nullable.isNotEmpty()) {
                    notes += nullableUniqueNote(tableName, constraint.name, nullable.joinToString(", "))
                }
                "CONSTRAINT ${quoteIdentifier(constraint.name)} UNIQUE (${columns.joinToString(", ") { quoteIdentifier(it) }})"
            }
        }
        ConstraintType.EXCLUDE -> {
            notes += ManualActionRequired(
                code = "E054", objectType = "constraint", objectName = constraint.name,
                reason = "EXCLUDE constraint '${constraint.name}' is not supported in SQL Server.",
                hint = "Enforce the exclusion with a trigger or application-level validation instead.",
            ).toNote()
            null
        }
        ConstraintType.FOREIGN_KEY -> {
            val ref = constraint.references!!
            buildForeignKeyClause(
                guard,
                ForeignKeySpec(
                    constraint.name, tableName, constraint.columns.orEmpty(), ref.table, ref.columns,
                    ref.onDelete, ref.onUpdate,
                ),
                notes,
            )
        }
    }

    private fun isNullable(table: TableDefinition, column: String): Boolean {
        val definition = table.columns[column] ?: return false
        if (column in table.primaryKey) return false
        if (definition.generation is ColumnGeneration.Identity) return false
        val type = definition.type
        if (type is NeutralType.Identifier && type.autoIncrement) return false
        return !definition.required
    }
}
