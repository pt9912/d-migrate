package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.AggregateDefinition
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.TransformationNote

/** Geteilte Bausteine der MSSQL-Generator-Specs. */
internal object MssqlDdlTestSupport {

    fun schema(
        tables: Map<String, TableDefinition> = emptyMap(),
        customTypes: Map<String, CustomTypeDefinition> = emptyMap(),
        sequences: Map<String, SequenceDefinition> = emptyMap(),
        views: Map<String, ViewDefinition> = emptyMap(),
        functions: Map<String, FunctionDefinition> = emptyMap(),
        procedures: Map<String, ProcedureDefinition> = emptyMap(),
        triggers: Map<String, TriggerDefinition> = emptyMap(),
        aggregates: Map<String, AggregateDefinition> = emptyMap(),
    ) = SchemaDefinition(
        name = "test_schema",
        version = "1.0",
        tables = tables,
        customTypes = customTypes,
        sequences = sequences,
        views = views,
        functions = functions,
        procedures = procedures,
        triggers = triggers,
        aggregates = aggregates,
    )

    fun col(
        type: NeutralType,
        required: Boolean = false,
        unique: Boolean = false,
        default: DefaultValue? = null,
        references: ReferenceDefinition? = null,
        generation: ColumnGeneration? = null,
    ) = ColumnDefinition(
        type = type, required = required, unique = unique, default = default,
        references = references, generation = generation,
    )

    fun idTable(vararg extra: Pair<String, ColumnDefinition>): TableDefinition = TableDefinition(
        columns = linkedMapOf("id" to col(NeutralType.Identifier(autoIncrement = true))) + extra.toMap(),
        primaryKey = listOf("id"),
    )

    fun DdlResult.codes(): List<String> = notes.map { it.code }

    fun DdlResult.notesWithCode(code: String): List<TransformationNote> = notes.filter { it.code == code }
}
