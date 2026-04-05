package dev.dmigrate.core.model

data class SchemaDefinition(
    val schemaFormat: String = "1.0",
    val name: String,
    val version: String,
    val description: String? = null,
    val encoding: String = "utf-8",
    val locale: String? = null,
    val customTypes: Map<String, CustomTypeDefinition> = emptyMap(),
    val tables: Map<String, TableDefinition> = emptyMap(),
    val procedures: Map<String, ProcedureDefinition> = emptyMap(),
    val functions: Map<String, FunctionDefinition> = emptyMap(),
    val views: Map<String, ViewDefinition> = emptyMap(),
    val triggers: Map<String, TriggerDefinition> = emptyMap(),
    val sequences: Map<String, SequenceDefinition> = emptyMap()
)
