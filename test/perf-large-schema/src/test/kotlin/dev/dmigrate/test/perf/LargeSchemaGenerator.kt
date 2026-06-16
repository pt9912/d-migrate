package dev.dmigrate.test.perf

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DependencyInfo
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.core.model.ViewDefinition

/**
 * Deterministic synthetic [SchemaDefinition] generator for the
 * Phase D large-schema scale tests.
 *
 * Plan-Doc: `docs/planning/done-archive/quality-coverage-expansion-plan.md`
 * §5.4.
 *
 * The generator produces a **mixed** schema: tables, sequences,
 * views, one shared audit function, and triggers that reference
 * the function plus a host table. Per the plan-doc the standard
 * generator must cover all four object classes (not just tables)
 * so the scale-test exercises every Phase A-G renderer slice the
 * pipeline orchestrates today.
 *
 * Determinism: the [seed] parameter is woven into every generated
 * name so a given (scale, seed) produces a byte-identical schema
 * across runs and JVMs.
 */
internal object LargeSchemaGenerator {

    private const val SHARED_FUNCTION_NAME = "fn_perf_audit"

    /**
     * Produce a synthetic mixed schema with [tables] tables,
     * [sequences] sequences, [views] views, [triggers] triggers,
     * plus the single shared [SHARED_FUNCTION_NAME] function the
     * triggers reference (so the PostgreSQL renderer's
     * `EXECUTE FUNCTION <ref>` body emission picks up a real
     * dependency rather than a hanging name).
     *
     * Trigger and view counts may exceed [tables] — they wrap
     * via modulo so the references stay valid even when the
     * scale dimensions differ.
     */
    fun mixedSchema(
        tables: Int,
        sequences: Int,
        views: Int,
        triggers: Int,
        seed: String,
    ): SchemaDefinition {
        require(tables > 0) { "tables must be > 0, was $tables" }
        require(sequences >= 0) { "sequences must be >= 0, was $sequences" }
        require(views >= 0) { "views must be >= 0, was $views" }
        require(triggers >= 0) { "triggers must be >= 0, was $triggers" }
        require(seed.isNotBlank()) { "seed must not be blank" }

        val tableMap = (1..tables).associate { i ->
            "t_${seed}_$i" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "payload" to ColumnDefinition(NeutralType.Text(), required = true),
                ),
                primaryKey = listOf("id"),
            )
        }
        val tableNames = tableMap.keys.toList()

        val sequenceMap = (1..sequences).associate { i ->
            "seq_${seed}_$i" to SequenceDefinition(start = 1L)
        }

        val viewMap = (1..views).associate { i ->
            val ref = tableNames[(i - 1) % tableNames.size]
            "v_${seed}_$i" to ViewDefinition(
                materialized = false,
                query = "SELECT id, payload FROM $ref",
                dependencies = DependencyInfo(tables = listOf(ref)),
                sourceDialect = "postgresql",
            )
        }

        val functionMap = if (triggers == 0) emptyMap() else mapOf(
            SHARED_FUNCTION_NAME to FunctionDefinition(
                returns = ReturnType(type = "trigger"),
                language = "plpgsql",
                body = "BEGIN RETURN NEW; END;",
                sourceDialect = "postgresql",
            ),
        )

        val triggerMap = (1..triggers).associate { i ->
            val ref = tableNames[(i - 1) % tableNames.size]
            "trg_${seed}_$i" to TriggerDefinition(
                table = ref,
                event = TriggerEvent.INSERT,
                timing = TriggerTiming.BEFORE,
                body = "$SHARED_FUNCTION_NAME()",
                dependencies = DependencyInfo(
                    tables = listOf(ref),
                    functions = listOf(SHARED_FUNCTION_NAME),
                ),
                sourceDialect = "postgresql",
            )
        }

        return SchemaDefinition(
            name = "perf-${seed}",
            version = "1.0.0",
            tables = tableMap,
            sequences = sequenceMap,
            views = viewMap,
            functions = functionMap,
            triggers = triggerMap,
        )
    }
}
