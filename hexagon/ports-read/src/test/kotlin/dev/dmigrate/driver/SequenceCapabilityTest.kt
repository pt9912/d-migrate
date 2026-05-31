package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * 0.9.7 Cross-Dialect-Sequencing Sub-Slice A: pins
 * [SequenceCapabilityDefaults] per dialect. The defaults are the
 * lowest-precedence layer Sub-Slice B will read; locking them here
 * keeps the renderer-side validation contract honest if a later
 * tranche tries to silently flip a flag without going through the
 * plan-doc (`docs/planning/done/ImpPlan-0.9.7-cross-dialect-sequencing.md` §5.2).
 */
class SequenceCapabilityTest : FunSpec({

    test("PostgreSQL default: full support, cache has runtime preallocation (no W114)") {
        SequenceCapabilityDefaults.forDialect(DatabaseDialect.POSTGRESQL) shouldBe SequenceCapability(
            supportsNamedSequences = true,
            supportsStart = true,
            supportsMinMaxValue = true,
            supportsCycle = true,
            supportsCache = true,
            emitsCachePreallocationWarning = false,
            supportsCurrentValuePreserve = true,
            supportsOwnedBy = true,
        )
    }

    test("MySQL default: helper-table emulation, cache_size is metadata only (W114)") {
        SequenceCapabilityDefaults.forDialect(DatabaseDialect.MYSQL) shouldBe SequenceCapability(
            supportsNamedSequences = true,
            supportsStart = true,
            supportsMinMaxValue = true,
            supportsCycle = true,
            supportsCache = true,
            emitsCachePreallocationWarning = true,
            supportsCurrentValuePreserve = true,
            supportsOwnedBy = false,
        )
    }

    test("SQLite default: helper-table, preserveCurrentValue=true (0.9.7 follow-up: probe+stage+renderer)") {
        SequenceCapabilityDefaults.forDialect(DatabaseDialect.SQLITE) shouldBe SequenceCapability(
            supportsNamedSequences = true,
            supportsStart = true,
            supportsMinMaxValue = true,
            supportsCycle = true,
            supportsCache = true,
            emitsCachePreallocationWarning = true,
            supportsCurrentValuePreserve = true,
            supportsOwnedBy = false,
        )
    }

    test("Atomic-Preserve Phase A: all dialects default to non-atomic (capability fields documented, executor not wired yet)") {
        // sequence-preserve-atomic-lock-plan.md Phase A: the
        // capability matrix is committed (supportsAtomicPreserve,
        // supportsAtomicPreserveAllInPlan,
        // transactionalProtectedSequenceOperations) but every dialect
        // still routes through the two-transaction fallback. Phase B
        // adds the executor implementations that flip these flags per
        // dialect — when that lands, this test breaks deliberately so
        // the Phase-B commit cannot ship without an explicit capability
        // update.
        DatabaseDialect.values().forEach { dialect ->
            val capability = SequenceCapabilityDefaults.forDialect(dialect)
            capability.supportsAtomicPreserve shouldBe false
            capability.supportsAtomicPreserveAllInPlan shouldBe false
            capability.transactionalProtectedSequenceOperations shouldBe emptySet<String>()
        }
    }
})
