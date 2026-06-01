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

    // Atomic-Preserve Phase C.4 (2026-06-01): the per-dialect
    // defaults flipped `supportsAtomicPreserve` to `true` for
    // PG/MySQL/SQLite once the executor + dispatcher wiring landed.
    // `supportsAtomicPreserveAllInPlan` stays `false` until Phase D
    // ships the cross-plan deadlock proof. The protected-operation
    // allowlist mirrors today's Stage candidates (CreateSequence /
    // AlterSequence / RenameSequence). The per-dialect tests below
    // pin every field explicitly so a future drift in any flag has
    // to come through this test.
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
            supportsAtomicPreserveAllInPlan = false,
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
            supportsAtomicPreserveAllInPlan = false,
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
            supportsAtomicPreserveAllInPlan = false,
            transactionalProtectedSequenceOperations = atomicPreserveAllowlist,
        )
    }

    test("Atomic-Preserve Phase C.4: every dialect supports atomic preserve with the kind allowlist") {
        // sequence-preserve-atomic-lock-plan.md Phase C.4: capability
        // flag flip + populated allowlist landed once
        // SchemaMigrateWiring instantiates the executor dispatcher.
        // Stage (C.1) still reads neither flag nor allowlist — the
        // master-grün invariant means the heutige Probe-in-Stage path
        // continues until C.1 lands. `supportsAtomicPreserveAllInPlan`
        // stays `false` until Phase D's deadlock proof flips it.
        DatabaseDialect.values().forEach { dialect ->
            val capability = SequenceCapabilityDefaults.forDialect(dialect)
            capability.supportsAtomicPreserve shouldBe true
            capability.supportsAtomicPreserveAllInPlan shouldBe false
            capability.transactionalProtectedSequenceOperations shouldBe atomicPreserveAllowlist
        }
    }
})
