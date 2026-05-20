package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*

internal class MysqlSequenceDdlSupport(
    private val quoteIdentifier: (String) -> String,
) {
    private var currentOptions: DdlGenerationOptions = DdlGenerationOptions()
    private var currentSchema: SchemaDefinition? = null
    private var supportObjectsBlocked = false
    private val pendingSupportTriggers = mutableListOf<MysqlSequenceTriggerSpec>()
    private val pendingSequenceNotes = mutableListOf<TransformationNote>()

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
            pendingSupportTriggers += MysqlSequenceTriggerSpec(
                tableName, columnName, seqDefault.sequenceName,
            )
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

        statements += DdlStatement(MysqlSequenceEmulationTemplates.supportTableSql(quoteIdentifier))
        for ((name, sequence) in sequences) {
            statements += DdlStatement(
                MysqlSequenceEmulationTemplates.sequenceSeedSql(name, sequence, quoteIdentifier),
            )
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

        statements += DdlStatement(MysqlSequenceEmulationTemplates.nextvalRoutineSql(quoteIdentifier))
        statements += DdlStatement(MysqlSequenceEmulationTemplates.setvalRoutineSql(quoteIdentifier))
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
            statements += DdlStatement(
                MysqlSequenceEmulationTemplates.sequenceTriggerSql(spec, triggerName, quoteIdentifier),
            )
        }
        return statements
    }

}
