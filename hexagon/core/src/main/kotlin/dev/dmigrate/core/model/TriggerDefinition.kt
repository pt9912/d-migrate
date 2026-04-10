package dev.dmigrate.core.model

data class TriggerDefinition(
    val description: String? = null,
    val table: String,
    val event: TriggerEvent,
    val timing: TriggerTiming,
    val forEach: TriggerForEach = TriggerForEach.ROW,
    val condition: String? = null,
    val body: String? = null,
    val dependencies: DependencyInfo? = null,
    val sourceDialect: String? = null
)

enum class TriggerEvent { INSERT, UPDATE, DELETE }
enum class TriggerTiming { BEFORE, AFTER, INSTEAD_OF }
enum class TriggerForEach { ROW, STATEMENT }
