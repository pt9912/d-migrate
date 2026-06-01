package dev.dmigrate.core.model

data class SequenceDefinition(
    val description: String? = null,
    val start: Long = 1,
    val increment: Long = 1,
    val minValue: Long? = null,
    val maxValue: Long? = null,
    val cycle: Boolean = false,
    val cache: Int? = null,
    /**
     * 0.9.7 preserve-current-value Sub-Slice A: opt-in to runtime-state
     * preservation across migrations. When `true` and the target
     * dialect's `SequenceCapability.supportsAtomicPreserve` is set, the
     * planner emits an `AlterSequenceCurrentValue` follow-up so a
     * freshly-created or altered sequence resumes at the live
     * `last_value` / `next_value` instead of jumping back to `start`.
     * The dialect-specific probe adapter
     * (`{Postgres,Mysql,Sqlite}SequenceCurrentValueProbe`) reads that
     * runtime value inside the atomic-preserve lock at execute time.
     * Default `false` keeps the pre-0.9.7 declarative-only behaviour
     * for all existing schemas.
     */
    val preserveCurrentValue: Boolean = false,
)
