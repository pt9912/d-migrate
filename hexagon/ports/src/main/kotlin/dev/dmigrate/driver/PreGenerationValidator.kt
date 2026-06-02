package dev.dmigrate.driver

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationError

/**
 * Driver-supplied hook that runs **after** the dialect-agnostic
 * [dev.dmigrate.core.validation.SchemaValidator] has accepted the
 * neutral schema and **before** the per-dialect
 * [DdlGenerator.generate] is invoked.
 *
 * Lets a dialect register validation rules that only make sense for a
 * specific emulation/mode combination (e.g. SQLite `helper_table`
 * emulation rejecting `PRIMARY KEY` columns with
 * `DefaultValue.SequenceNextVal` per
 * `docs/planning/in-progress/sqlite-sequence-emulation-plan.md` §3.4) without
 * leaking that rule into the dialect-agnostic
 * [dev.dmigrate.core.validation.SchemaValidator]. PostgreSQL or MySQL
 * targets can keep using the same neutral schema without tripping the
 * SQLite-specific gate.
 *
 * Drivers that have no mode-specific gates inherit [NoOp].
 */
interface PreGenerationValidator {

    /**
     * Inspects [schema] in the context of [options] (which carries the
     * dialect-specific mode flags via `DdlDialectContext`) and returns
     * the per-violation [ValidationError] list. An empty list means
     * the schema is cleared for DDL generation.
     */
    fun validate(
        schema: SchemaDefinition,
        options: DdlGenerationOptions,
    ): List<ValidationError>

    /** Default for drivers that have no mode-specific pre-generation gate. */
    object NoOp : PreGenerationValidator {
        override fun validate(
            schema: SchemaDefinition,
            options: DdlGenerationOptions,
        ): List<ValidationError> = emptyList()
    }
}
