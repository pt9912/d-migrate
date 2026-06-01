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

    // Atomic-Preserve Phase D (2026-06-01): the per-dialect defaults
    // now carry `supportsAtomicPreserve = true` (flipped in C.4 once
    // the executor + dispatcher wiring landed) and
    // `supportsAtomicPreserveAllInPlan = true` (flipped in D after
    // the per-dialect Cross-Plan-Deadlock-Tests proved that the
    // name-sorted lock acquisition closes the diamond between
    // parallel runs). The protected-operation allowlist mirrors
    // today's Stage candidates (CreateSequence / AlterSequence /
    // RenameSequence). The per-dialect tests below pin every field
    // explicitly so a future drift in any flag has to come through
    // this test.
    val atomicPreserveAllowlist: Set<ProtectedOperationId> = setOf(
        ProtectedOperationId("CreateSequence"),
        ProtectedOperationId("AlterSequence"),
        ProtectedOperationId("RenameSequence"),
    )

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
            supportsAtomicPreserve = true,
            supportsAtomicPreserveAllInPlan = true,
            transactionalProtectedSequenceOperations = atomicPreserveAllowlist,
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
            supportsAtomicPreserve = true,
            supportsAtomicPreserveAllInPlan = true,
            transactionalProtectedSequenceOperations = atomicPreserveAllowlist,
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
            supportsAtomicPreserve = true,
            supportsAtomicPreserveAllInPlan = true,
            transactionalProtectedSequenceOperations = atomicPreserveAllowlist,
        )
    }

    test("Atomic-Preserve Phase D: every dialect supports atomic preserve incl. AllInPlan with the kind allowlist") {
        // sequence-preserve-atomic-lock-plan.md Phase D (2026-06-01):
        // both atomic-preserve capability flags are `true` for every
        // supported dialect after the per-dialect Cross-Plan-Deadlock
        // proofs landed (Postgres / MySQL / SQLite Cross-Plan-IT).
        // This test pins the matrix-shaped invariant so a future
        // dialect addition cannot silently land with the AllInPlan
        // flag still `false` (which would let multi-sequence plans
        // surface as `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED` instead
        // of getting the atomic path).
        DatabaseDialect.values().forEach { dialect ->
            val capability = SequenceCapabilityDefaults.forDialect(dialect)
            capability.supportsAtomicPreserve shouldBe true
            capability.supportsAtomicPreserveAllInPlan shouldBe true
            capability.transactionalProtectedSequenceOperations shouldBe atomicPreserveAllowlist
        }
    }
})
