package dev.dmigrate.driver.mysql

import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import dev.dmigrate.driver.metadata.SchemaReaderUtils
import java.sql.Connection

class MysqlSchemaReader(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : SchemaReader {

    private val sequenceSupport = MysqlSequenceSupport()
    private val routineReader = MysqlRoutineReader()

    override fun read(pool: ConnectionPool, options: SchemaReadOptions): SchemaReadResult {
        val notes = mutableListOf<SchemaReadNote>()
        val skipped = mutableListOf<SkippedObject>()

        pool.borrow().use { conn ->
            val session = jdbcFactory(conn)
            val database = currentDatabase(conn)
            val lctn = lowerCaseTableNames(conn)

            val metaDb = normalizeMysqlMetadataIdentifier(database, lctn)
            val scope = ReverseScope(catalogName = metaDb, schemaName = metaDb)

            val tables = readTables(session, metaDb, lctn, notes)
            val views = if (options.includeViews) routineReader.readViews(session, metaDb) else emptyMap()
            val functions = if (options.includeFunctions) routineReader.readFunctions(session, metaDb, notes) else emptyMap()
            val procedures = if (options.includeProcedures) routineReader.readProcedures(session, metaDb, notes) else emptyMap()
            val triggers = if (options.includeTriggers) routineReader.readTriggers(session, metaDb) else emptyMap()

            val supportSnapshot = sequenceSupport.scanSequenceSupport(session, metaDb, scope)

            val d2Result = sequenceSupport.materializeSupportSequences(supportSnapshot)

            val d3Result = sequenceSupport.materializeSequenceDefaults(
                supportSnapshot, d2Result.sequences, tables,
            )

            val filteredTables = sequenceSupport.filterSupportTable(d3Result.enrichedTables, supportSnapshot)
            val filteredFunctions = sequenceSupport.filterSupportRoutines(functions, supportSnapshot)
            val filteredTriggers = d3Result.filteredTriggers(triggers)
            notes += d2Result.notes
            notes += d3Result.notes

            val schemaDef = SchemaDefinition(
                name = ReverseScopeCodec.mysqlName(database),
                version = ReverseScopeCodec.REVERSE_VERSION,
                tables = filteredTables,
                views = views,
                functions = filteredFunctions,
                procedures = procedures,
                triggers = filteredTriggers,
                sequences = d2Result.sequences,
            )

            return SchemaReadResult(schema = schemaDef, notes = notes, skippedObjects = skipped)
        }
    }

    internal fun scanSequenceSupport(
        session: JdbcOperations,
        database: String,
        scope: ReverseScope,
    ): MysqlSequenceSupportSnapshot = sequenceSupport.scanSequenceSupport(session, database, scope)

    internal fun materializeSupportSequences(
        snapshot: MysqlSequenceSupportSnapshot,
    ): MysqlSequenceSupport.D2Result = sequenceSupport.materializeSupportSequences(snapshot)

    internal fun materializeSequenceDefaults(
        snapshot: MysqlSequenceSupportSnapshot,
        materializedSequences: Map<String, SequenceDefinition>,
        tables: Map<String, TableDefinition>,
    ): MysqlSequenceSupport.D3Result = sequenceSupport.materializeSequenceDefaults(snapshot, materializedSequences, tables)

    private fun readTables(
        session: JdbcOperations,
        database: String,
        lctn: Int,
        notes: MutableList<SchemaReadNote>,
    ): Map<String, TableDefinition> {
        val tableRefs = MysqlMetadataQueries.listTableRefs(session, database)
        val result = LinkedHashMap<String, TableDefinition>()
        for (ref in tableRefs) {
            val metaTable = normalizeMysqlMetadataIdentifier(ref.name, lctn)
            result[ref.name] = readTable(session, database, metaTable, ref.name, notes)
        }
        return result
    }

    private fun readTable(
        session: JdbcOperations,
        database: String,
        metaTable: String,
        displayName: String,
        notes: MutableList<SchemaReadNote>,
    ): TableDefinition {
        val colRows = MysqlMetadataQueries.listColumns(session, database, metaTable)
        val pkColumns = MysqlMetadataQueries.listPrimaryKeyColumns(session, database, metaTable)
        val fks = MysqlMetadataQueries.listForeignKeys(session, database, metaTable)
        val allIndices = MysqlMetadataQueries.listIndices(session, database, metaTable)
        val checks = MysqlMetadataQueries.listCheckConstraints(session, database, metaTable)
        val engine = MysqlMetadataQueries.listTableEngine(session, database, metaTable)

        val fkConstraintNames = fks.map { it.name }.toSet()
        val fkColumnLists = fks.map { it.columns }
        val indices = mutableListOf<dev.dmigrate.driver.metadata.IndexProjection>()
        for (idx in allIndices) {
            if (idx.name in fkConstraintNames) {
                continue
            }
            if (fkColumnLists.any { it == idx.columns }) {
                notes += SchemaReadNote(
                    severity = SchemaReadSeverity.INFO,
                    code = "R330",
                    objectName = "$displayName.${idx.name}",
                    message = "Index columns match FK — may be an auto-generated support index",
                    hint = "Review if this index was explicitly created or is FK-backing",
                )
            }
            indices += idx
        }

        val singleColFks = SchemaReaderUtils.liftSingleColumnFks(fks)
        val singleColUnique = SchemaReaderUtils.singleColumnUniqueFromIndices(indices)

        val columns = LinkedHashMap<String, ColumnDefinition>()
        for (row in colRows) {
            val colName = row["column_name"] as String
            val isPkCol = colName in pkColumns
            val extra = (row["extra"] as? String) ?: ""
            val isAutoIncrement = extra.contains("auto_increment", ignoreCase = true)
            val mapping = MysqlTypeMapping.mapColumn(MysqlTypeMapping.ColumnInput(
                dataType = row["data_type"] as String,
                columnType = (row["column_type"] as? String) ?: (row["data_type"] as String),
                isAutoIncrement = isAutoIncrement,
                charMaxLen = (row["character_maximum_length"] as? Number)?.toInt(),
                numPrecision = (row["numeric_precision"] as? Number)?.toInt(),
                numScale = (row["numeric_scale"] as? Number)?.toInt(),
                tableName = displayName,
                colName = colName,
            ))
            if (mapping.note != null) notes += mapping.note
            val neutralType = mapping.type

            val required = if (isPkCol) false else (row["is_nullable"] as String) == "NO"
            val unique = if (isPkCol) false else colName in singleColUnique

            val references = singleColFks[colName]

            val defaultVal = if (isAutoIncrement) null
            else MysqlTypeMapping.parseDefault(row["column_default"] as? String, neutralType)

            columns[colName] = ColumnDefinition(
                type = neutralType,
                required = required,
                unique = unique,
                default = defaultVal,
                references = references,
            )
        }

        val constraints = mutableListOf<ConstraintDefinition>()
        constraints += SchemaReaderUtils.buildMultiColumnFkConstraints(fks)
        constraints += SchemaReaderUtils.buildMultiColumnUniqueFromIndices(indices)
        constraints += SchemaReaderUtils.buildCheckConstraints(checks)

        val indexDefs = indices.filter { !it.isUnique || it.columns.size == 1 }
            .filter { !(it.isUnique && it.columns.size == 1) }
            .map { idx ->
                IndexDefinition(
                    name = idx.name,
                    columns = idx.columns,
                    type = when (idx.type?.uppercase()) {
                        "HASH" -> IndexType.HASH
                        else -> IndexType.BTREE
                    },
                    unique = idx.isUnique,
                )
            }

        val metadata = engine?.let { TableMetadata(engine = it) }

        return TableDefinition(
            columns = columns,
            primaryKey = pkColumns,
            indices = indexDefs,
            constraints = constraints,
            metadata = metadata,
        )
    }
}
