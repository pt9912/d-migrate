package dev.dmigrate.core.validation

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition

/**
 * 0.9.7 SQLite-Sequence Phase B.2 step 1: dialect-agnostic semantic
 * validation of [SequenceDefinition] internals.
 *
 * Rules per `docs/planning/open/sqlite-sequence-emulation-plan.md` §3.6:
 *
 * - `increment` must not be `0` — an increment-zero sequence either
 *   repeats forever or loops in the trigger body; rejecting here
 *   makes sure neither the SQLite helper-table trigger nor the
 *   MySQL emulation routine is asked to handle that case.
 * - `increment` must not be `Long.MIN_VALUE` — `-Long.MIN_VALUE` is
 *   not representable in two's-complement Long arithmetic, and the
 *   overflow-safe `isIncrementInRange` check below has to negate
 *   the increment internally.
 * - `min_value ≤ max_value` — basic range consistency.
 * - `min_value ≤ start ≤ max_value` — the starting point has to sit
 *   inside the declared range.
 * - `|increment| ≤ max_value − min_value` — guarantees that the
 *   trigger overflow-guard formulas (`max - increment` /
 *   `min - increment`) stay within `Long`. Implemented overflow-safe
 *   via [isIncrementInRange], i.e. **without** ever computing the
 *   raw `max − min` subtraction (which itself overflows at the
 *   extremes).
 *
 * All violations report as `E125` so the user gets a stable code; the
 * message identifies which sub-rule fired. A single sequence may
 * emit multiple `E125` errors when it violates several rules at once
 * (e.g. `increment = 0` and `min > max`).
 *
 * `min_value = null` / `max_value = null` are user-level "unbounded"
 * markers; per §3.6 they substitute `Long.MIN_VALUE` / `Long.MAX_VALUE`
 * for the validation, which propagates correctly through
 * [isIncrementInRange].
 */
internal object SchemaSequenceValidationRules {

    fun validate(schema: SchemaDefinition): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        for ((name, sequence) in schema.sequences) {
            val path = "sequences.$name"
            validateIncrement(path, sequence, errors)
            validateRange(path, sequence, errors)
            validateIncrementFitsRange(path, sequence, errors)
        }
        return ValidationResult(errors, emptyList())
    }

    private fun validateIncrement(
        path: String,
        sequence: SequenceDefinition,
        errors: MutableList<ValidationError>,
    ) {
        if (sequence.increment == 0L) {
            errors += ValidationError(
                "E125",
                "sequence increment must not be 0 (an increment of zero would never advance " +
                    "and is rejected before DDL generation)",
                "$path.increment",
            )
        }
        if (sequence.increment == Long.MIN_VALUE) {
            errors += ValidationError(
                "E125",
                "sequence increment must not be Long.MIN_VALUE (${Long.MIN_VALUE}); " +
                    "its absolute value is not representable as Long",
                "$path.increment",
            )
        }
    }

    private fun validateRange(
        path: String,
        sequence: SequenceDefinition,
        errors: MutableList<ValidationError>,
    ) {
        val min = sequence.minValue ?: Long.MIN_VALUE
        val max = sequence.maxValue ?: Long.MAX_VALUE
        if (min > max) {
            errors += ValidationError(
                "E125",
                "sequence range is empty: min_value ($min) must not exceed max_value ($max)",
                path,
            )
            return
        }
        if (sequence.start < min || sequence.start > max) {
            errors += ValidationError(
                "E125",
                "sequence start (${sequence.start}) is outside the declared range [$min, $max]",
                "$path.start",
            )
        }
    }

    private fun validateIncrementFitsRange(
        path: String,
        sequence: SequenceDefinition,
        errors: MutableList<ValidationError>,
    ) {
        // Skip when the increment itself is already invalid — the
        // increment-zero / Long.MIN_VALUE error already fired and
        // running the range check would either short-circuit on the
        // explicit Long.MIN_VALUE guard or produce a duplicate diagnostic.
        if (sequence.increment == 0L || sequence.increment == Long.MIN_VALUE) return
        val min = sequence.minValue ?: Long.MIN_VALUE
        val max = sequence.maxValue ?: Long.MAX_VALUE
        // Skip range-inconsistent sequences — the dedicated range
        // diagnostic above already explains the failure.
        if (min > max) return
        if (!isIncrementInRange(sequence.increment, min, max)) {
            errors += ValidationError(
                "E125",
                "absolute value of sequence increment (${sequence.increment}) exceeds " +
                    "the range [$min, $max]; pick a smaller |increment| or widen the bounds",
                "$path.increment",
            )
        }
    }

    /**
     * Overflow-safe equivalent of `abs(inc) <= max - min`, never
     * computing the raw `max - min` subtraction (which overflows at
     * the `Long.MIN_VALUE / Long.MAX_VALUE` extremes). See
     * `docs/planning/open/sqlite-sequence-emulation-plan.md` §3.6 for
     * the derivation. Assumes `inc != 0` and `inc != Long.MIN_VALUE`
     * (the caller checks both before invoking) and `min <= max`.
     */
    internal fun isIncrementInRange(inc: Long, min: Long, max: Long): Boolean {
        if (inc == Long.MIN_VALUE) return false
        return if (inc > 0) {
            // Need: inc <= max - min ⟺ min <= max - inc.
            // Guard against `max - inc` underflow first.
            if (max < Long.MIN_VALUE + inc) false else min <= max - inc
        } else {
            // inc < 0, inc > Long.MIN_VALUE.
            // Need: |inc| <= max - min ⟺ max >= min - inc.
            // Guard against `min - inc` overflow first.
            if (min > Long.MAX_VALUE + inc) false else max >= min - inc
        }
    }
}
