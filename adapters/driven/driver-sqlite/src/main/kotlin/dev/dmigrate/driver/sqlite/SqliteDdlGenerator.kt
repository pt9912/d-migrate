package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*

class SqliteDdlGenerator : AbstractDdlGenerator(SqliteTypeMapper()) {

    override val dialect = DatabaseDialect.SQLITE

    private val routineHelper = SqliteRoutineDdlHelper(::quoteIdentifier)
    private val capabilitySupport = SqliteCapabilityDdlSupport(::quoteIdentifier)
    private val columnConstraintHelper = SqliteColumnConstraintHelper(
        ::quoteIdentifier, typeMapper, ::columnSql, ::referentialActionSql
    )
    private val tableSupport = SqliteTableDdlSupport(::quoteIdentifier, columnConstraintHelper)

    override fun quoteIdentifier(name: String): String = SqlIdentifiers.quoteIdentifier(name, dialect)

    override fun generateCustomTypes(types: Map<String, CustomTypeDefinition>): List<DdlStatement> =
        capabilitySupport.generateCustomTypes(types)

    override fun generateSequences(
        sequences: Map<String, SequenceDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> = capabilitySupport.generateSequences(sequences, skipped)

    override fun canGenerateSpatial(profile: SpatialProfile): Boolean =
        profile == SpatialProfile.SPATIALITE

    override fun generateTable(
        name: String,
        table: TableDefinition,
        schema: SchemaDefinition,
        deferredFks: Set<Pair<String, String>>,
        options: DdlGenerationOptions,
    ): List<DdlStatement> =
        tableSupport.generateTable(name, table, schema, deferredFks, options)

    override fun generateIndices(tableName: String, table: TableDefinition): List<DdlStatement> =
        tableSupport.generateIndices(tableName, table)

    override fun handleCircularReferences(
        edges: List<CircularFkEdge>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> = capabilitySupport.handleCircularReferences(edges, skipped)

    override fun generateViews(
        views: Map<String, ViewDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> = routineHelper.generateViews(views, skipped)

    override fun generateFunctions(
        functions: Map<String, FunctionDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> = routineHelper.generateFunctions(functions, skipped)

    override fun generateProcedures(
        procedures: Map<String, ProcedureDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> = routineHelper.generateProcedures(procedures, skipped)

    override fun generateTriggers(
        triggers: Map<String, TriggerDefinition>,
        tables: Map<String, TableDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> = routineHelper.generateTriggers(triggers, skipped)

    override fun invertStatement(stmt: DdlStatement): DdlStatement? =
        capabilitySupport.invertStatement(stmt) ?: super.invertStatement(stmt)
}
