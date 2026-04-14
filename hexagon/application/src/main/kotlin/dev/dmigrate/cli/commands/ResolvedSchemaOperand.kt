package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SkippedObject

/**
 * Envelope for a resolved schema operand in compare or transfer
 * workflows involving database sources.
 *
 * Carries the neutral schema alongside its validation result and any
 * reverse-engineering notes or skipped objects, so that downstream
 * consumers (compare output, transfer preflight) can surface this
 * information without losing it.
 *
 * This type lives in the application layer, not in ports, because it
 * combines port-level types ([SchemaReadNote], [SkippedObject]) with
 * core-level types ([SchemaDefinition], [ValidationResult]).
 */
data class ResolvedSchemaOperand(
    val reference: String,
    val schema: SchemaDefinition,
    val validation: ValidationResult,
    val notes: List<SchemaReadNote> = emptyList(),
    val skippedObjects: List<SkippedObject> = emptyList(),
)
