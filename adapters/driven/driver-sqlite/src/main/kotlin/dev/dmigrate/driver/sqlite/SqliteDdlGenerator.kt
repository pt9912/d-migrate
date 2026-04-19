package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*

class SqliteDdlGenerator : AbstractDdlGenerator(SqliteTypeMapper()) {

    override val dialect = DatabaseDialect.SQLITE

    private val routineHelper = SqliteRoutineDdlHelper(::quoteIdentifier)

    // -- Quoting -----------------------------------------------

    override fun quoteIdentifier(name: String): String = SqlIdentifiers.quoteIdentifier(name, dialect)

    // -- Custom types (ENUM, COMPOSITE, DOMAIN) ----------------

    override fun generateCustomTypes(types: Map<String, CustomTypeDefinition>): List<DdlStatement> {
        // SQLite has no CREATE TYPE. ENUMs are handled inline with CHECK constraints.
        // Composite types are not supported. Domain types use base type + inline CHECK.
        val statements = mutableListOf<DdlStatement>()
        for ((name, typeDef) in types) {
            when (typeDef.kind) {
                CustomTypeKind.ENUM -> {
                    // No standalone CREATE TYPE for ENUM in SQLite; handled inline at column level.
                    // Emit a comment so the user knows it was intentionally skipped.
                    statements += DdlStatement(
                        "-- Enum type ${quoteIdentifier(name)} is handled inline via CHECK constraints",
                        listOf(
                            TransformationNote(
                                type = NoteType.INFO,
                                code = "I001",
                                objectName = name,
                                message = "Enum type '$name' mapped to inline TEXT + CHECK constraint in SQLite."
                            )
                        )
                    )
                }
                CustomTypeKind.COMPOSITE -> {
                    // NOT SUPPORTED
                    statements += DdlStatement(
                        "-- Composite type ${quoteIdentifier(name)} is not supported in SQLite",
                        listOf(
                            TransformationNote(
                                type = NoteType.ACTION_REQUIRED,
                                code = "E054",
                                objectName = name,
                                message = "Composite type '$name' is not supported in SQLite.",
                                hint = "Flatten composite fields into individual table columns or use JSON."
                            )
                        )
                    )
                }
                CustomTypeKind.DOMAIN -> {
                    // Domain types are not natively supported, but we map base type + inline CHECK
                    // at the column level. Emit an informational comment here.
                    statements += DdlStatement(
                        "-- Domain type ${quoteIdentifier(name)} is mapped to its base type with inline CHECK in SQLite",
                        listOf(
                            TransformationNote(
                                type = NoteType.INFO,
                                code = "I001",
                                objectName = name,
                                message = "Domain type '$name' mapped to base type with inline CHECK constraint in SQLite."
                            )
                        )
                    )
                }
            }
        }
        return statements
    }

    // -- Sequences ---------------------------------------------

    override fun generateSequences(
        sequences: Map<String, SequenceDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        // SQLite does not support sequences. Skip each one with E056.
        val statements = mutableListOf<DdlStatement>()
        for ((name, _) in sequences) {
            skipped += SkippedObject("sequence", name, "Sequences are not supported in SQLite")
            statements += DdlStatement(
                "-- Sequence ${quoteIdentifier(name)} is not supported in SQLite",
                listOf(
                    TransformationNote(
                        type = NoteType.ACTION_REQUIRED,
                        code = "E056",
                        objectName = name,
                        message = "Sequence '$name' is not supported in SQLite.",
                        hint = "Use INTEGER PRIMARY KEY AUTOINCREMENT or application-level sequencing."
                    )
                )
            )
        }
        return statements
    }

    override fun canGenerateSpatial(profile: SpatialProfile): Boolean =
        profile == SpatialProfile.SPATIALITE

    // -- Tables ------------------------------------------------

    override fun generateTable(
        name: String,
        table: TableDefinition,
        schema: SchemaDefinition,
        deferredFks: Set<Pair<String, String>>,
        options: DdlGenerationOptions,
    ): List<DdlStatement> {
        val statements = mutableListOf<DdlStatement>()
        val notes = mutableListOf<TransformationNote>()
        val columnLines = mutableListOf<String>()

        // Separate geometry columns for SpatiaLite two-step strategy
        val geometryCols = table.columns.filter { it.value.type is NeutralType.Geometry }
        val normalCols = table.columns.filter { it.value.type !is NeutralType.Geometry }

        // For 0.5.5: block table if geometry columns have unsupported metadata
        if (geometryCols.isNotEmpty() && options.spatialProfile == SpatialProfile.SPATIALITE) {
            val geoColNames = geometryCols.keys
            for ((colName, col) in geometryCols) {
                if (col.required || col.unique || col.default != null || col.references != null ||
                    colName in table.primaryKey) {
                    return blockTableForSpatialMetadata(name, colName,
                        "required/unique/default/references/PK")
                }
            }
            // Check table-level constraints referencing geometry columns
            for (constraint in table.constraints) {
                val cols = constraint.columns.orEmpty()
                if (cols.any { it in geoColNames }) {
                    return blockTableForSpatialMetadata(name, cols.first { it in geoColNames },
                        "table-level constraint '${constraint.name}'")
                }
            }
            // Check indices referencing geometry columns
            for (idx in table.indices) {
                if (idx.columns.any { it in geoColNames }) {
                    return blockTableForSpatialMetadata(name, idx.columns.first { it in geoColNames },
                        "index on geometry column")
                }
            }
        }

        // Track whether the only PK column is an Identifier (AUTOINCREMENT already includes PK)
        val identifierPkColumns = table.primaryKey.filter { pkCol ->
            val col = table.columns[pkCol]
            col != null && col.type is NeutralType.Identifier
        }
        val skipPrimaryKeyClause = table.primaryKey.size == 1
            && identifierPkColumns.size == 1

        // Columns (exclude geometry for SpatiaLite)
        val effectiveCols = if (geometryCols.isNotEmpty() && options.spatialProfile == SpatialProfile.SPATIALITE)
            normalCols else table.columns
        for ((colName, col) in effectiveCols) {
            columnLines += generateColumnSql(colName, col, schema, name, notes, deferredFks)
        }

        // Explicit constraints (CHECK, UNIQUE, EXCLUDE, FOREIGN_KEY)
        for (constraint in table.constraints) {
            columnLines += generateConstraintClause(constraint, notes)
        }

        // Primary key (only if not already covered by AUTOINCREMENT)
        if (table.primaryKey.isNotEmpty() && !skipPrimaryKeyClause) {
            val pkCols = table.primaryKey.joinToString(", ") { quoteIdentifier(it) }
            columnLines += "PRIMARY KEY ($pkCols)"
        }

        // Build CREATE TABLE
        val tableSql = buildString {
            append("CREATE TABLE ${quoteIdentifier(name)} (\n")
            append(columnLines.joinToString(",\n") { "    $it" })
            append("\n)")

            // Partitioning: NOT SUPPORTED
            if (table.partitioning != null) {
                notes += TransformationNote(
                        type = NoteType.ACTION_REQUIRED,
                        code = "E055",
                        objectName = name,
                        message = "Table partitioning is not supported in SQLite for table '$name'.",
                        hint = "Partition data at the application level or use separate tables."
                )
            }

            append(";")
        }
        statements += DdlStatement(tableSql, notes)

        // SpatiaLite: AddGeometryColumn() after CREATE TABLE
        if (geometryCols.isNotEmpty() && options.spatialProfile == SpatialProfile.SPATIALITE) {
            for ((colName, col) in geometryCols) {
                val geo = col.type as NeutralType.Geometry
                val geoType = geo.geometryType.schemaName.uppercase()
                val srid = geo.srid ?: 0
                val quotedTable = name.replace("'", "''")
                val quotedCol = colName.replace("'", "''")
                statements += DdlStatement(
                    "SELECT AddGeometryColumn('$quotedTable', '$quotedCol', $srid, '$geoType', 'XY');")
            }
        }

        return statements
    }

    private fun generateColumnSql(
        colName: String,
        col: ColumnDefinition,
        schema: SchemaDefinition,
        tableName: String,
        notes: MutableList<TransformationNote>,
        deferredFks: Set<Pair<String, String>> = emptySet()
    ): String {
        val type = col.type

        // Identifier type: TypeMapper returns "INTEGER PRIMARY KEY AUTOINCREMENT" (already includes PK)
        if (type is NeutralType.Identifier && type.autoIncrement) {
            val parts = mutableListOf<String>()
            parts += quoteIdentifier(colName)
            parts += typeMapper.toSql(type)
            // NOT NULL is implicit for INTEGER PRIMARY KEY in SQLite
            if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
            if (col.unique) parts += "UNIQUE"
            return parts.joinToString(" ")
        }

        // Enum with ref_type: resolve from custom types and inline CHECK
        if (type is NeutralType.Enum && type.refType != null) {
            val customType = schema.customTypes[type.refType]
            val parts = mutableListOf<String>()
            parts += quoteIdentifier(colName)
            parts += "TEXT"
            if (col.required) parts += "NOT NULL"
            if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
            if (col.unique) parts += "UNIQUE"
            // If the custom type has enum values, add inline CHECK
            if (customType != null && customType.kind == CustomTypeKind.ENUM && customType.values != null) {
                val allowed = customType.values!!.joinToString(", ") { "'${it.replace("'", "''")}'" }
                parts += "CHECK (${quoteIdentifier(colName)} IN ($allowed))"
            }
            // Inline reference if present
            if (col.references != null && (tableName to colName) !in deferredFks) {
                parts += inlineForeignKey(col.references!!)
            }
            return parts.joinToString(" ")
        }

        // Enum with inline values: TEXT + CHECK
        if (type is NeutralType.Enum && type.values != null) {
            val parts = mutableListOf<String>()
            parts += quoteIdentifier(colName)
            parts += "TEXT"
            if (col.required) parts += "NOT NULL"
            if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
            if (col.unique) parts += "UNIQUE"
            val allowed = type.values!!.joinToString(", ") { "'${it.replace("'", "''")}'" }
            parts += "CHECK (${quoteIdentifier(colName)} IN ($allowed))"
            // Inline reference if present
            if (col.references != null && (tableName to colName) !in deferredFks) {
                parts += inlineForeignKey(col.references!!)
            }
            return parts.joinToString(" ")
        }

        // Decimal type: warn about precision loss
        if (type is NeutralType.Decimal) {
            notes += TransformationNote(
                type = NoteType.WARNING,
                code = "W200",
                objectName = "$tableName.$colName",
                message = "Decimal(${type.precision},${type.scale}) mapped to REAL in SQLite. Precision may be lost.",
                hint = "Store as TEXT if exact decimal precision is required."
            )
        }

        // Default path: use base columnSql and then append inline FK if present
        val baseSql = columnSql(colName, col, schema)
        return if (col.references != null && (tableName to colName) !in deferredFks) {
            "$baseSql ${inlineForeignKey(col.references!!)}"
        } else {
            baseSql
        }
    }

    private fun inlineForeignKey(ref: ReferenceDefinition): String {
        val sql = buildString {
            append("REFERENCES ${quoteIdentifier(ref.table)}(${quoteIdentifier(ref.column)})")
            if (ref.onDelete != null) append(" ON DELETE ${referentialActionSql(ref.onDelete!!)}")
            if (ref.onUpdate != null) append(" ON UPDATE ${referentialActionSql(ref.onUpdate!!)}")
        }
        return sql
    }

    private fun generateConstraintClause(
        constraint: ConstraintDefinition,
        notes: MutableList<TransformationNote>
    ): String {
        return when (constraint.type) {
            ConstraintType.CHECK -> {
                "CONSTRAINT ${quoteIdentifier(constraint.name)} CHECK (${constraint.expression})"
            }
            ConstraintType.UNIQUE -> {
                val cols = constraint.columns?.joinToString(", ") { quoteIdentifier(it) } ?: ""
                "CONSTRAINT ${quoteIdentifier(constraint.name)} UNIQUE ($cols)"
            }
            ConstraintType.EXCLUDE -> {
                // EXCLUDE constraints are not supported in SQLite
                notes += TransformationNote(
                    type = NoteType.ACTION_REQUIRED,
                    code = "E054",
                    objectName = constraint.name,
                    message = "EXCLUDE constraint '${constraint.name}' is not supported in SQLite.",
                    hint = "Enforce exclusion logic at the application level or use triggers."
                )
                "-- EXCLUDE constraint ${quoteIdentifier(constraint.name)} is not supported in SQLite"
            }
            ConstraintType.FOREIGN_KEY -> {
                val ref = constraint.references!!
                val fromCols = constraint.columns?.joinToString(", ") { quoteIdentifier(it) } ?: ""
                val toCols = ref.columns.joinToString(", ") { quoteIdentifier(it) }
                buildString {
                    append("CONSTRAINT ${quoteIdentifier(constraint.name)} FOREIGN KEY ($fromCols) REFERENCES ${quoteIdentifier(ref.table)} ($toCols)")
                    if (ref.onDelete != null) append(" ON DELETE ${referentialActionSql(ref.onDelete!!)}")
                    if (ref.onUpdate != null) append(" ON UPDATE ${referentialActionSql(ref.onUpdate!!)}")
                }
            }
        }
    }

    // -- Indices -----------------------------------------------

    override fun generateIndices(tableName: String, table: TableDefinition): List<DdlStatement> {
        return table.indices.mapNotNull { index -> generateIndex(tableName, index) }
    }

    private fun generateIndex(tableName: String, index: IndexDefinition): DdlStatement? {
        val indexName = index.name ?: "idx_${tableName}_${index.columns.joinToString("_")}"

        // Only BTREE is supported in SQLite (it is the default and only index type)
        if (index.type != IndexType.BTREE) {
            return DdlStatement(
                "-- Index ${quoteIdentifier(indexName)} skipped: ${index.type.name} index type is not supported in SQLite",
                listOf(
                    TransformationNote(
                        type = NoteType.WARNING,
                        code = "W102",
                        objectName = indexName,
                        message = "${index.type.name} index '${indexName}' on table '$tableName' is not supported in SQLite. Only BTREE is available.",
                        hint = "The index has been skipped. If needed, create a standard BTREE index instead."
                    )
                )
            )
        }

        val cols = index.columns.joinToString(", ") { quoteIdentifier(it) }
        val sql = buildString {
            append("CREATE ")
            if (index.unique) append("UNIQUE ")
            append("INDEX ${quoteIdentifier(indexName)} ON ${quoteIdentifier(tableName)} ($cols);")
        }
        return DdlStatement(sql)
    }

    // -- Circular FK references --------------------------------

    override fun handleCircularReferences(
        edges: List<CircularFkEdge>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        // SQLite cannot do ALTER TABLE ADD CONSTRAINT, so circular FKs are not possible.
        // Add each circular edge as a SkippedObject and return empty statements.
        val statements = mutableListOf<DdlStatement>()
        for (edge in edges) {
            val constraintName = "fk_${edge.fromTable}_${edge.fromColumn}"
            skipped += SkippedObject(
                "foreign_key",
                constraintName,
                "Circular foreign key from '${edge.fromTable}.${edge.fromColumn}' to '${edge.toTable}.${edge.toColumn}' cannot be added in SQLite (no ALTER TABLE ADD CONSTRAINT)"
            )
            statements += DdlStatement(
                "-- Circular FK ${quoteIdentifier(constraintName)} skipped: SQLite cannot ALTER TABLE ADD CONSTRAINT",
                listOf(
                    TransformationNote(
                        type = NoteType.ACTION_REQUIRED,
                        code = "E019",
                        objectName = constraintName,
                        message = "Circular foreign key from '${edge.fromTable}.${edge.fromColumn}' to '${edge.toTable}.${edge.toColumn}' cannot be created in SQLite.",
                        hint = "SQLite does not support ALTER TABLE ADD CONSTRAINT. Enforce referential integrity at the application level."
                    )
                )
            )
        }
        return statements
    }

    // -- Views -------------------------------------------------

    override fun generateViews(
        views: Map<String, ViewDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> = routineHelper.generateViews(views, skipped)

    // -- Functions ---------------------------------------------

    override fun generateFunctions(
        functions: Map<String, FunctionDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> = routineHelper.generateFunctions(functions, skipped)

    // -- Procedures --------------------------------------------

    override fun generateProcedures(
        procedures: Map<String, ProcedureDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> = routineHelper.generateProcedures(procedures, skipped)

    // -- Triggers ----------------------------------------------

    override fun generateTriggers(
        triggers: Map<String, TriggerDefinition>,
        tables: Map<String, TableDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> = routineHelper.generateTriggers(triggers, tables, skipped)

    // -- Rollback inversion overrides --------------------------

    override fun invertStatement(stmt: DdlStatement): DdlStatement? {
        val sql = stmt.sql.trim()
        // Handle "CREATE VIEW IF NOT EXISTS" which the base class does not cover
        if (sql.startsWith("CREATE VIEW IF NOT EXISTS", ignoreCase = true)) {
            val afterKeyword = sql.substring("CREATE VIEW IF NOT EXISTS".length).trimStart()
            val name = afterKeyword.split(Regex("[\\s(]"), limit = 2).first()
            return DdlStatement("DROP VIEW IF EXISTS $name;")
        }
        // Handle SpatiaLite AddGeometryColumn → DiscardGeometryColumn
        if (sql.startsWith("SELECT AddGeometryColumn(", ignoreCase = true)) {
            val argsStart = sql.indexOf('(') + 1
            val argsEnd = sql.lastIndexOf(')')
            if (argsStart > 0 && argsEnd > argsStart) {
                val args = sql.substring(argsStart, argsEnd).split(',').map { it.trim() }
                if (args.size >= 2) {
                    return DdlStatement("SELECT DiscardGeometryColumn(${args[0]}, ${args[1]});")
                }
            }
        }
        return super.invertStatement(stmt)
    }

    private fun blockTableForSpatialMetadata(table: String, column: String, reason: String): List<DdlStatement> =
        listOf(DdlStatement("", notes = listOf(TransformationNote(
            type = NoteType.ACTION_REQUIRED, code = "E052", objectName = table,
            message = "Geometry column '$column' has unsupported metadata ($reason) for SpatiaLite",
            hint = "Remove metadata from geometry column or use a different dialect",
            blocksTable = true,
        ))))
}
