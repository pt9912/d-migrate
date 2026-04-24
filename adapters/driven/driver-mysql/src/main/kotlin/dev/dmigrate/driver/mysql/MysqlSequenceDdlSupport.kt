package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*

internal class MysqlSequenceDdlSupport(
    private val quoteIdentifier: (String) -> String,
) {
    private var currentOptions: DdlGenerationOptions = DdlGenerationOptions()
    private var currentSchema: SchemaDefinition? = null
    private var supportObjectsBlocked = false
    private val pendingSupportTriggers = mutableListOf<SupportTriggerSpec>()
    private val pendingSequenceNotes = mutableListOf<TransformationNote>()

    private data class SupportTriggerSpec(
        val tableName: String,
        val columnName: String,
        val sequenceName: String,
    )

    private val isHelperTable: Boolean
        get() = currentOptions.mysqlNamedSequenceMode == MysqlNamedSequenceMode.HELPER_TABLE

    fun beginRun(schema: SchemaDefinition, options: DdlGenerationOptions) {
        currentOptions = options
        currentSchema = schema
        supportObjectsBlocked = false
        pendingSupportTriggers.clear()
        pendingSequenceNotes.clear()
    }

    fun finalizeResult(result: DdlResult): DdlResult {
        val schema = currentSchema
        if (!isHelperTable || schema?.sequences?.isNotEmpty() != true) return result

        val warning = TransformationNote(
            type = NoteType.WARNING,
            code = "W117",
            objectName = MysqlSequenceNaming.SUPPORT_TABLE,
            message = "Sequence values in MySQL helper-table mode are transaction-bound; " +
                "rollback retracts increments (unlike native PostgreSQL sequences).",
        )
        return DdlResult(result.statements, result.skippedObjects, result.globalNotes + warning)
    }

    fun resolveSequenceDefault(
        tableName: String,
        columnName: String,
        seqDefault: DefaultValue.SequenceNextVal,
    ): String? {
        if (isHelperTable) {
            pendingSupportTriggers += SupportTriggerSpec(tableName, columnName, seqDefault.sequenceName)
            pendingSequenceNotes += TransformationNote(
                type = NoteType.WARNING,
                code = "W115",
                objectName = "$tableName.$columnName",
                message = "SequenceNextVal on '$columnName' uses lossy MySQL trigger semantics; " +
                    "explicit NULL is treated like an omitted value.",
            )
            return null
        }

        pendingSequenceNotes += TransformationNote(
            type = NoteType.ACTION_REQUIRED,
            code = "E056",
            objectName = "$tableName.$columnName",
            message = "Sequence-based default on '$columnName' requires " +
                "--mysql-named-sequences helper_table to generate support objects.",
            hint = "Add --mysql-named-sequences helper_table to enable sequence emulation.",
        )
        return null
    }

    fun drainPendingNotes(): List<TransformationNote> =
        pendingSequenceNotes.toList().also { pendingSequenceNotes.clear() }

    fun generateSequences(
        sequences: Map<String, SequenceDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> {
        if (!isHelperTable) {
            return sequences.map { (name, _) ->
                val action = ManualActionRequired(
                    code = "E056",
                    objectType = "sequence",
                    objectName = name,
                    reason = "Sequence '$name' is not supported in MySQL without helper_table mode.",
                    hint = "Add --mysql-named-sequences helper_table to enable sequence emulation.",
                )
                skipped += action.toSkipped()
                DdlStatement("", listOf(action.toNote()))
            }
        }

        val statements = mutableListOf<DdlStatement>()
        val notes = mutableListOf<TransformationNote>()
        val schema = currentSchema
        if (schema != null && MysqlSequenceNaming.SUPPORT_TABLE in (schema.tables?.keys ?: emptySet())) {
            val action = ManualActionRequired(
                code = "E124",
                objectType = "table",
                objectName = MysqlSequenceNaming.SUPPORT_TABLE,
                reason = "Support object name collision: '${MysqlSequenceNaming.SUPPORT_TABLE}' " +
                    "already exists in the neutral schema.",
                hint = "Rename the existing table or use --mysql-named-sequences action_required.",
            )
            skipped += action.toSkipped()
            statements += DdlStatement("", listOf(action.toNote()))
            supportObjectsBlocked = true
            return statements
        }

        statements += DdlStatement(buildSupportTableSql())
        for ((name, sequence) in sequences) {
            statements += DdlStatement(buildSequenceSeedSql(name, sequence))
            if (sequence.cache != null) {
                notes += TransformationNote(
                    type = NoteType.WARNING,
                    code = "W114",
                    objectName = name,
                    message = "Sequence '$name' has cache=${sequence.cache} but MySQL helper-table mode " +
                        "does not emulate preallocation; cache value is stored as metadata only.",
                )
            }
        }

        if (notes.isNotEmpty()) statements += DdlStatement("", notes)
        return statements
    }

    fun generateSupportFunctions(
        functions: Map<String, FunctionDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> {
        if (!isHelperTable || supportObjectsBlocked) return emptyList()

        val statements = mutableListOf<DdlStatement>()
        for (routineName in listOf(MysqlSequenceNaming.NEXTVAL_ROUTINE, MysqlSequenceNaming.SETVAL_ROUTINE)) {
            if (routineName in functions.keys) {
                val action = ManualActionRequired(
                    code = "E124",
                    objectType = "function",
                    objectName = routineName,
                    reason = "Support object name collision: '$routineName' already exists in the neutral schema.",
                    hint = "Rename the existing function or use --mysql-named-sequences action_required.",
                )
                skipped += action.toSkipped()
                statements += DdlStatement("", listOf(action.toNote()))
                supportObjectsBlocked = true
                return statements
            }
        }

        statements += DdlStatement(buildNextvalRoutineSql())
        statements += DdlStatement(buildSetvalRoutineSql())
        return statements
    }

    fun generateSupportTriggers(
        triggers: Map<String, TriggerDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> {
        if (!isHelperTable || supportObjectsBlocked) return emptyList()

        val statements = mutableListOf<DdlStatement>()
        for (spec in pendingSupportTriggers) {
            val triggerName = MysqlSequenceNaming.triggerName(spec.tableName, spec.columnName)
            if (triggerName in triggers) {
                val action = ManualActionRequired(
                    code = "E124",
                    objectType = "trigger",
                    objectName = triggerName,
                    reason = "Support object name collision: '$triggerName' already exists in the neutral schema.",
                    hint = "Rename the existing trigger or use --mysql-named-sequences action_required.",
                )
                skipped += action.toSkipped()
                statements += DdlStatement("", listOf(action.toNote()))
            }
        }

        for (spec in pendingSupportTriggers) {
            val triggerName = MysqlSequenceNaming.triggerName(spec.tableName, spec.columnName)
            if (triggerName in triggers) continue
            statements += DdlStatement(buildSupportTriggerSql(spec, triggerName))
        }
        return statements
    }

    private fun buildSupportTableSql(): String = buildString {
        appendLine("CREATE TABLE ${quoteIdentifier(MysqlSequenceNaming.SUPPORT_TABLE)} (")
        appendLine("    ${quoteIdentifier("managed_by")} VARCHAR(32) NOT NULL,")
        appendLine("    ${quoteIdentifier("format_version")} VARCHAR(32) NOT NULL,")
        appendLine("    ${quoteIdentifier("name")} VARCHAR(255) NOT NULL,")
        appendLine("    ${quoteIdentifier("next_value")} BIGINT NOT NULL,")
        appendLine("    ${quoteIdentifier("increment_by")} BIGINT NOT NULL,")
        appendLine("    ${quoteIdentifier("min_value")} BIGINT NULL,")
        appendLine("    ${quoteIdentifier("max_value")} BIGINT NULL,")
        appendLine("    ${quoteIdentifier("cycle_enabled")} TINYINT(1) NOT NULL,")
        appendLine("    ${quoteIdentifier("cache_size")} INT NULL,")
        appendLine("    PRIMARY KEY (${quoteIdentifier("name")})")
        append(") ENGINE=InnoDB;")
    }

    private fun buildSequenceSeedSql(name: String, sequence: SequenceDefinition): String {
        val start = sequence.start ?: 1L
        val increment = sequence.increment ?: 1L
        val minValue = sequence.minValue?.toString() ?: "NULL"
        val maxValue = sequence.maxValue?.toString() ?: "NULL"
        val cycle = if (sequence.cycle == true) 1 else 0
        val cache = sequence.cache?.toString() ?: "NULL"
        val nameLiteral = MysqlSequenceSqlCodec.quoteStringLiteral(name)
        return "INSERT INTO ${quoteIdentifier(MysqlSequenceNaming.SUPPORT_TABLE)} " +
            "(${quoteIdentifier("managed_by")}, ${quoteIdentifier("format_version")}, ${quoteIdentifier("name")}, " +
            "${quoteIdentifier("next_value")}, ${quoteIdentifier("increment_by")}, ${quoteIdentifier("min_value")}, " +
            "${quoteIdentifier("max_value")}, ${quoteIdentifier("cycle_enabled")}, " +
            "${quoteIdentifier("cache_size")}) VALUES " +
            "('d-migrate', 'mysql-sequence-v1', $nameLiteral, $start, $increment, " +
            "$minValue, $maxValue, $cycle, $cache);"
    }

    private fun buildNextvalRoutineSql(): String = buildString {
        appendLine("DELIMITER //")
        appendLine("CREATE FUNCTION ${quoteIdentifier(MysqlSequenceNaming.NEXTVAL_ROUTINE)}(seq_name VARCHAR(255))")
        appendLine("RETURNS BIGINT")
        appendLine("DETERMINISTIC")
        appendLine("MODIFIES SQL DATA")
        appendLine("BEGIN")
        appendLine("    /* d-migrate:mysql-sequence-v1 object=nextval */")
        appendLine("    DECLARE val BIGINT;")
        appendLine(
            "    UPDATE ${quoteIdentifier(MysqlSequenceNaming.SUPPORT_TABLE)} " +
                "SET ${quoteIdentifier("next_value")} = ${quoteIdentifier("next_value")} + " +
                "${quoteIdentifier("increment_by")} " +
                "WHERE ${quoteIdentifier("name")} = seq_name;"
        )
        appendLine(
            "    SELECT ${quoteIdentifier("next_value")} - ${quoteIdentifier("increment_by")} INTO val " +
                "FROM ${quoteIdentifier(MysqlSequenceNaming.SUPPORT_TABLE)} " +
                "WHERE ${quoteIdentifier("name")} = seq_name;"
        )
        appendLine("    RETURN val;")
        appendLine("END //")
        append("DELIMITER ;")
    }

    private fun buildSetvalRoutineSql(): String = buildString {
        appendLine("DELIMITER //")
        appendLine(
            "CREATE FUNCTION ${quoteIdentifier(MysqlSequenceNaming.SETVAL_ROUTINE)}" +
                "(seq_name VARCHAR(255), new_value BIGINT)"
        )
        appendLine("RETURNS BIGINT")
        appendLine("DETERMINISTIC")
        appendLine("MODIFIES SQL DATA")
        appendLine("BEGIN")
        appendLine("    /* d-migrate:mysql-sequence-v1 object=setval */")
        appendLine(
            "    UPDATE ${quoteIdentifier(MysqlSequenceNaming.SUPPORT_TABLE)} " +
                "SET ${quoteIdentifier("next_value")} = new_value WHERE ${quoteIdentifier("name")} = seq_name;"
        )
        appendLine("    RETURN new_value;")
        appendLine("END //")
        append("DELIMITER ;")
    }

    private fun buildSupportTriggerSql(spec: SupportTriggerSpec, triggerName: String): String = buildString {
        val sequenceLiteral = MysqlSequenceSqlCodec.quoteStringLiteral(spec.sequenceName)
        appendLine("DELIMITER //")
        appendLine("CREATE TRIGGER ${quoteIdentifier(triggerName)}")
        appendLine("    BEFORE INSERT ON ${quoteIdentifier(spec.tableName)}")
        appendLine("    FOR EACH ROW")
        appendLine("BEGIN")
        appendLine(
            "    /* d-migrate:mysql-sequence-v1 object=sequence-trigger " +
                "sequence=${MysqlSequenceSqlCodec.markerValue(spec.sequenceName)} " +
                "table=${MysqlSequenceSqlCodec.markerValue(spec.tableName)} " +
                "column=${MysqlSequenceSqlCodec.markerValue(spec.columnName)} */"
        )
        appendLine("    IF NEW.${quoteIdentifier(spec.columnName)} IS NULL THEN")
        appendLine(
            "        SET NEW.${quoteIdentifier(spec.columnName)} = " +
                "${quoteIdentifier(MysqlSequenceNaming.NEXTVAL_ROUTINE)}($sequenceLiteral);"
        )
        appendLine("    END IF;")
        appendLine("END //")
        append("DELIMITER ;")
    }

}
