package dev.dmigrate.server.application.quota

import dev.dmigrate.core.validation.requirePositive
import java.time.Duration

/**
 * Passive Duration carrier for handler/runner timeouts. Phase A does
 * not enforce these — actual cancel-on-deadline lives in the handler
 * layer in later phases. Defaults are conservative; deployments can
 * override per operation.
 */
data class TimeoutBudget(
    val operation: String,
    val total: Duration,
) {
    init {
        requirePositive(total, "TimeoutBudget.total")
        require(operation.isNotBlank()) { "TimeoutBudget.operation must not be blank" }
    }
}
