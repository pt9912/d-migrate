package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*

class SqliteDdlGenerator : AbstractDdlGenerator(SqliteTypeMapper()) {

    override val dialect = DatabaseDialect.SQLITE

    private val sequenceSupport = SqliteSequenceDdlSupport()
    private val routineHelper = SqliteRoutineDdlHelper(::quoteIdentifier)
    private val capabilitySupport = SqliteCapabilityDdlSupport(::quoteIdentifier, sequenceSupport)
    private val columnConstraintHelper = SqliteColumnConstraintHelper(
        ::quoteIdentifier, typeMapper, ::columnSql, ::referentialActionSql, sequenceSupport,
    )
    private val tableSupport = SqliteTableDdlSupport(::quoteIdentifier, columnConstraintHelper, sequenceSupport)

    override fun generate(schema: SchemaDefinition, options: DdlGenerationOptions): DdlResult {
        sequenceSupport.beginRun(schema, options)
        return sequenceSupport.finalizeResult(super.generate(schema, options))
    }

    /**
     * 0.9.7 Phase F1: Plan §5.2 — Rollback runs a preflight before
     * any DROP touches `dmg_sequences`. If external objects reference
     * the helper table (E058) or the connection has ATTACHed
     * databases (E060), the preflight aborts the entire stream via
     * `SELECT RAISE(ABORT, …)`. Only emitted in `helper_table` mode
     * and only when the forward pass actually produced support
     * objects — otherwise there is nothing to drop and the preflight
     * would be no-op noise.
     */
    override fun generateRollback(schema: SchemaDefinition, options: DdlGenerationOptions): DdlResult {
        val downResult = super.generateRollback(schema, options)
        if (!sequenceSupport.helperTableModeActive()) return downResult
        if (!sequenceSupport.helperTableProducedSupportObjects(schema)) return downResult
        val preflight = SqliteSequenceEmulationTemplates.rollbackPreflightSqls()
            .map { DdlStatement(it) }
        return DdlResult(preflight + downResult.statements, downResult.skippedObjects)
    }

    override fun quoteIdentifier(name: String): String = SqlIdentifiers.quoteIdentifier(name, dialect)

    override fun resolveSequenceDefault(
        tableName: String,
        colName: String,
        col: ColumnDefinition,
        seqDefault: DefaultValue.SequenceNextVal,
    ): String? = sequenceSupport.resolveSequenceDefault(tableName, colName, seqDefault)

    override fun columnSql(tableName: String, colName: String, col: ColumnDefinition, schema: SchemaDefinition): String {
        if (!sequenceSupport.shouldSuppressNotNull(tableName, col)) {
            return super.columnSql(tableName, colName, col, schema)
        }
        // super.columnSql runs `resolveSequenceDefault` which registers
        // the column in `sequenceBackedColumns`; only after that
        // registration may `recordNotNullSuppressionNote` fire (its
        // require-guard insists on the registration).
        val rendered = super.columnSql(tableName, colName, col.copy(required = false), schema)
        sequenceSupport.recordNotNullSuppressionNote(tableName, colName)
        return rendered
    }

    override fun generateCustomTypes(types: Map<String, CustomTypeDefinition>): List<DdlStatement> =
        capabilitySupport.generateCustomTypes(types)

    override fun generateSequences(
        schema: SchemaDefinition,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> = capabilitySupport.generateSequences(schema.sequences, skipped)

    override fun canGenerateSpatial(profile: SpatialProfile): Boolean =
        profile == SpatialProfile.SPATIALITE

    override fun generateTable(
        name: String,
        table: TableDefinition,
        schema: SchemaDefinition,
        deferredFks: Set<Pair<String, String>>,
        deferredConstraints: Set<Pair<String, String>>,
        options: DdlGenerationOptions,
    ): List<DdlStatement> =
        tableSupport.generateTable(name, table, schema, deferredFks, deferredConstraints, options)

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
    ): List<DdlStatement> {
        val statements = mutableListOf<DdlStatement>()
        statements += sequenceSupport.generateSupportTriggers(triggers.keys, skipped)
        statements += routineHelper.generateTriggers(triggers, skipped)
        return statements
    }

    override fun invertStatement(stmt: DdlStatement): DdlStatement? =
        capabilitySupport.invertStatement(stmt) ?: super.invertStatement(stmt)
}
