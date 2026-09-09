package dev.dmigrate.driver

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * 0.9.7 Cross-Dialect-Sequencing Sub-Slice A: pins
 * [SequenceCapabilityDefaults] per dialect. The defaults are the
 * lowest-precedence layer Sub-Slice B will read; locking them here
 * keeps the renderer-side validation contract honest if a later
 * tranche tries to silently flip a flag without going through the
 * plan-doc (`docs/planning/done-archive/ImpPlan-0.9.7-cross-dialect-sequencing.md` §5.2).
 */
class SequenceCapabilityTest : FunSpec({

    // Atomic-Preserve Phase D (2026-06-01): the per-dialect defaults
    // now carry `preserveWindowIsolation = ATOMIC` (flipped in C.4 once
    // the executor + dispatcher wiring landed) and
    // `preserveAllCandidatesInOneWindow = true` (flipped in D after
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
            preserveWindowIsolation = PreserveWindowIsolation.ATOMIC,
            preserveAllCandidatesInOneWindow = true,
            protectedSequenceOperations = atomicPreserveAllowlist,
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
            preserveWindowIsolation = PreserveWindowIsolation.ATOMIC,
            preserveAllCandidatesInOneWindow = true,
            protectedSequenceOperations = atomicPreserveAllowlist,
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
            preserveWindowIsolation = PreserveWindowIsolation.ATOMIC,
            preserveAllCandidatesInOneWindow = true,
            protectedSequenceOperations = atomicPreserveAllowlist,
        )
    }

    test("every dialect guards its preserve window — and says which guarantee it gives") {
        // Vier Dialekte koennen das Fenster zuruecknehmen, Oracle nicht: dort
        // committet jedes DDL implizit, und der Restore IST DDL. Die
        // Unterscheidung steht im Modell, damit niemand aus `guardsWindow`
        // auf Ruecknahmesicherheit schliesst.
        DatabaseDialect.values().forEach { dialect ->
            val capability = SequenceCapabilityDefaults.forDialect(dialect)
            val expected = if (dialect == DatabaseDialect.ORACLE) {
                PreserveWindowIsolation.SERIALIZED
            } else {
                PreserveWindowIsolation.ATOMIC
            }
            withClue(dialect.name) {
                capability.preserveWindowIsolation shouldBe expected
                capability.preserveAllCandidatesInOneWindow shouldBe true
                capability.protectedSequenceOperations shouldBe atomicPreserveAllowlist
            }
        }
    }
})
