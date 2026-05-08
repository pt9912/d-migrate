package dev.dmigrate.core.validation

import java.time.Duration

/**
 * Asserts that [duration] is strictly positive (non-null, non-zero,
 * non-negative). Used by config carriers that hold lifetime/timeout
 * values where zero or negative is meaningless.
 */
fun requirePositive(duration: Duration, label: String) {
    require(!duration.isNegative && !duration.isZero) {
        "$label must be > 0, got $duration"
    }
}
